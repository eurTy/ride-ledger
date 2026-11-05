const { app, BrowserWindow, ipcMain, Menu, dialog } = require('electron');
const path = require('node:path');
const crypto = require('node:crypto');
const { pathToFileURL } = require('node:url');

const userData = process.env.RIDE_LEDGER_USER_DATA || path.join(app.getPath('appData'), 'RideLedger');
app.setPath('userData', path.resolve(userData));
app.setName('双人旅行账本');
const ownsLock = app.requestSingleInstanceLock();
if (!ownsLock) app.quit();

let window, relay, tunnel, lan, desktopDeviceId = '', quitting = false;
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
    try { send('lan:peer-batch', { requestId, tripId: trip.id, secret: trip.secret, events }); }
    catch { finish(null); }
  });
}

app.on('second-instance', () => { if (window) { if (window.isMinimized()) window.restore(); window.focus(); } });
app.on('window-all-closed', () => app.quit());
app.on('before-quit', event => {
  if (quitting) return;
  event.preventDefault(); quitting = true; tunnel?.stop();
  for (const finish of pendingPeerWrites.values()) finish(null);
  Promise.allSettled([lan?.close(), relay?.close()]).finally(() => app.quit());
});

if (ownsLock) app.whenReady().then(async () => {
  const { startSyncServer } = await import(moduleUrl('../sync-server/server.mjs'));
  const { PhoneTunnel } = await import(moduleUrl('phone-tunnel.mjs'));
  const { DesktopLanSync } = await import(moduleUrl('lan-sync.mjs'));
  const port = Number(process.env.RIDE_LEDGER_PORT || 18787);
  const instanceId = crypto.randomUUID();
  relay = await startSyncServer({ port, host: '127.0.0.1', instanceId,
    dataDir: path.join(app.getPath('userData'), 'relay'),
    onSnapshot: ({ deviceId }) => { if (deviceId !== desktopDeviceId) send('remote:changed'); } });
  tunnel = new PhoneTunnel({ port: new URL(relay.origin).port, userData: app.getPath('userData'), instanceId });
  tunnel.on('status', status => send('phone:status', status));
  lan = new DesktopLanSync({ discovery: process.env.RIDE_LEDGER_DISABLE_DISCOVERY !== '1',
    host: process.env.RIDE_LEDGER_DISABLE_DISCOVERY === '1' ? '127.0.0.1' : '0.0.0.0', commit: savePeerBatch });
  await lan.start();
  lan.on('incoming', () => send('remote:changed'));

  ipcMain.handle('phone:status', event => { authorize(event); return tunnel.status; });
  ipcMain.handle('phone:connect', event => { authorize(event); return tunnel.start(); });
  ipcMain.handle('phone:stop', event => { authorize(event); tunnel.stop(); return tunnel.status; });
  ipcMain.handle('lan:exchange', (event, snapshot) => {
    authorize(event);
    if (typeof snapshot.deviceId !== 'string') throw new Error('设备信息无效');
    desktopDeviceId = snapshot.deviceId;
    return lan.update(snapshot);
  });
  ipcMain.handle('lan:connect', (event, address) => { authorize(event); lan.connectManual(address); });
  ipcMain.handle('lan:peer-saved', (event, requestId, added) => {
    authorize(event);
    const finish = pendingPeerWrites.get(requestId);
    if (!finish) return;
    if (Number.isInteger(added) && added >= 0) event.sender.session.flushStorageData();
    finish(added);
  });

  Menu.setApplicationMenu(null);
  window = new BrowserWindow({ width: 1040, height: 840, minWidth: 390, minHeight: 650,
    title: '双人旅行账本', backgroundColor: '#f3f7f3', icon: path.join(__dirname, 'assets', 'icon.png'),
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
}).catch(error => { dialog.showErrorBox('无法启动双人旅行账本', error.message); app.quit(); });
