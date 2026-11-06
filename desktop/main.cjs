const { app, BrowserWindow, ipcMain, Menu, dialog, nativeImage, safeStorage } = require('electron');
const path = require('node:path');
const crypto = require('node:crypto');
const fs = require('node:fs');
const { pathToFileURL } = require('node:url');
const QRCode = require('qrcode');
const decodeQr = require('jsqr');

const userData = process.env.RIDE_LEDGER_USER_DATA || path.join(app.getPath('appData'), 'RideLedger');
app.setPath('userData', path.resolve(userData));
app.setName('旅行账本');
const ownsLock = app.requestSingleInstanceLock();
if (!ownsLock) app.quit();

let window, relay, publicRelay, tunnel, lan, timer, quitting = false;
const pendingPeerWrites = new Map();
const send = (channel, data) => { if (window && !window.isDestroyed()) window.webContents.send(channel, data); };
const authorize = event => {
  if (!window || event.sender !== window.webContents ||
      new URL(event.senderFrame.url).origin !== relay.origin) throw new Error('无效的应用请求');
};
const moduleUrl = file => pathToFileURL(path.join(__dirname, file)).href;

function savePeerBatch(trip, events) {
  if (!window || window.isDestroyed()) throw new Error('本机账本未打开');
  const requestId = crypto.randomUUID();
  return new Promise((resolve, reject) => {
    const finish = added => {
      clearTimeout(timeout);
      pendingPeerWrites.delete(requestId);
      if (Number.isInteger(added) && added >= 0 && added <= events.length) resolve(added);
      else reject(new Error('本机记录保存待重试'));
    };
    const timeout = setTimeout(() => finish(null), 10000);
    pendingPeerWrites.set(requestId, finish);
    try { send('lan:peer-batch', { requestId, tripId: trip.id, secret: trip.secret, team: trip.fingerprint, events }); }
    catch { finish(null); }
  });
}

app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
app.on('window-all-closed', () => app.quit());
app.on('before-quit', event => {
  if (quitting) return;
  event.preventDefault(); quitting = true; clearInterval(timer); tunnel?.stop();
  for (const finish of pendingPeerWrites.values()) finish(null);
  Promise.allSettled([lan?.close(), relay?.close(), publicRelay?.close()]).finally(() => app.quit());
});

if (ownsLock) app.whenReady().then(async () => {
  const { startSyncServer } = await import(moduleUrl('../sync-server/server.mjs'));
  const { PhoneTunnel } = await import(moduleUrl('phone-tunnel.mjs'));
  const { DesktopLanSync } = await import(moduleUrl('lan-sync.mjs'));
  const { Admission, DesktopTeams, readInvite, encodeInvite, validLegacySnapshotName } = await import(moduleUrl('team.mjs'));
  const { enrollViaRelay } = await import(moduleUrl('team-relay.mjs'));
  const port = Number(process.env.RIDE_LEDGER_PORT || 18787);
  const profile = app.getPath('userData');
  fs.mkdirSync(profile, { recursive: true });
  const ledgerDir = path.join(profile, 'relay');
  if (fs.existsSync(ledgerDir) && fs.realpathSync(ledgerDir) === path.join(fs.realpathSync(profile), 'relay')) {
    for (const entry of fs.readdirSync(ledgerDir, { withFileTypes: true })) {
      if (entry.isFile() && validLegacySnapshotName(entry.name)) fs.unlinkSync(path.join(ledgerDir, entry.name));
    }
  }
  if (!safeStorage.isEncryptionAvailable()) throw new Error('Windows 设备凭证存储不可用');
  const keyFile = path.join(profile, 'team-identity.bin');
  const saved = fs.existsSync(keyFile) ? safeStorage.decryptString(fs.readFileSync(keyFile)) : null;
  const teams = new DesktopTeams(saved, data => {
    const temp = keyFile + '.' + crypto.randomUUID() + '.tmp';
    fs.writeFileSync(temp, safeStorage.encryptString(data), { flag: 'wx', flush: true });
    fs.renameSync(temp, keyFile);
  });
  relay = await startSyncServer({ port, host: '127.0.0.1', legacySync: false });
  lan = new DesktopLanSync({ discovery: process.env.RIDE_LEDGER_DISABLE_DISCOVERY !== '1',
    host: process.env.RIDE_LEDGER_DISABLE_DISCOVERY === '1' ? '127.0.0.1' : '0.0.0.0', commit: savePeerBatch, teams });
  await lan.start();
  lan.on('incoming', () => send('remote:changed'));

  let enrollment = { trip: '', next: 0, status: '', busy: false };
  timer = setInterval(async () => {
    const job = enrollment;
    const trip = lan.teamTrip;
    if (!trip?.syncUrl || job.busy || Date.now() < job.next ||
        (trip.localPerson !== 'm1' && teams.ready(trip))) return;
    job.busy = true;
    let status;
    try { status = await enrollViaRelay(trip, teams, candidate => job === enrollment && lan.currentTeam(candidate)); }
    catch (error) { status = error instanceof Admission ? error.message : 'HTTPS 中转待重试'; }
    finally { job.busy = false; job.next = Date.now() + 3000; }
    if (job === enrollment && status !== job.status) { job.status = status; send('remote:changed'); }
  }, 1000);
  timer.unref();

  let openingRelay;
  ipcMain.handle('phone:connect', async event => {
    authorize(event);
    if (!openingRelay) openingRelay = (async () => {
      if (!tunnel) {
        const instanceId = crypto.randomUUID();
        publicRelay = await startSyncServer({ port: 0, host: '127.0.0.1', enrollmentOnly: true, instanceId });
        tunnel = new PhoneTunnel({ port: new URL(publicRelay.origin).port, userData: profile, instanceId });
      }
      return tunnel.start();
    })().finally(() => { openingRelay = null; });
    return openingRelay;
  });
  ipcMain.handle('team:update', (event, raw, events) => {
    authorize(event);
    const trip = raw ? teams.snapshot(raw, events) : null;
    const route = trip && trip.fingerprint + '\n' + trip.syncUrl + '\n' + trip.localPerson;
    if (route !== enrollment.trip) enrollment = { trip: route, next: Date.now() + (trip?.localPerson === 'm1' ? 0 : 5000), status: '', busy: false };
    const local = lan.updateTeam(trip);
    return local && { ...local, status: [local.status, enrollment.status].filter(Boolean).join(' · ') };
  });
  ipcMain.handle('team:create', (event, name, names) => { authorize(event); return teams.create(name, names); });
  ipcMain.handle('team:invite', async (event, trip) => {
    authorize(event);
    if (!teams.ready(teams.snapshot(trip, []))) throw new Error('请先完成首次登记');
    const code = encodeInvite(trip);
    return { code, image: await QRCode.toDataURL(code, { width: 360, margin: 4, errorCorrectionLevel: 'M' }) };
  });
  ipcMain.handle('team:read-invite', (event, code) => { authorize(event); return readInvite(code); });
  ipcMain.handle('team:read-image', async event => {
    authorize(event);
    const selection = await dialog.showOpenDialog(window, { title: '选择邀请二维码图片', properties: ['openFile'],
      filters: [{ name: '图片', extensions: ['png', 'jpg', 'jpeg'] }] });
    if (selection.canceled) return null;
    const file = selection.filePaths[0];
    if (fs.statSync(file).size > 12_000_000) throw new Error('二维码图片过大');
    let image = nativeImage.createFromPath(file);
    if (image.isEmpty()) throw new Error('无法读取图片');
    const original = image.getSize();
    if (Math.max(original.width, original.height) > 2048)
      image = image.resize(original.width > original.height ? { width: 2048 } : { height: 2048 });
    const { width, height } = image.getSize();
    const bytes = Uint8ClampedArray.from(image.toBitmap());
    for (let i = 0; i < bytes.length; i += 4) [bytes[i], bytes[i + 2]] = [bytes[i + 2], bytes[i]];
    const code = decodeQr(bytes, width, height)?.data;
    if (!code) throw new Error('图片里没有读到小队二维码');
    return readInvite(code);
  });
  ipcMain.handle('lan:peer-saved', (event, requestId, added) => {
    authorize(event);
    const finish = pendingPeerWrites.get(requestId);
    if (!finish) return;
    if (Number.isInteger(added) && added >= 0) event.sender.session.flushStorageData();
    finish(added);
  });

  Menu.setApplicationMenu(null);
  window = new BrowserWindow({ width: 1040, height: 840, minWidth: 390, minHeight: 650,
    title: '旅行账本', backgroundColor: '#f3f7f3', icon: path.join(__dirname, 'assets', 'icon.png'),
    show: process.env.RIDE_LEDGER_HEADLESS !== '1', autoHideMenuBar: true,
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), nodeIntegration: false,
      contextIsolation: true, sandbox: true, webSecurity: true } });
  window.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  window.webContents.on('will-navigate', (event, url) => {
    if (new URL(url).origin !== relay.origin) event.preventDefault();
  });
  window.webContents.session.setPermissionRequestHandler((_contents, permission, callback) =>
    callback(permission === 'clipboard-sanitized-write'));
  await window.loadURL(relay.origin);
}).catch(error => { dialog.showErrorBox('无法启动旅行账本', error.message); app.quit(); });
