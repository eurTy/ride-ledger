import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import net from 'node:net';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { connectPage } from './support/cdp.mjs';
import { client, state, join } from './support/web-client.mjs';

const run = promisify(execFile), require = createRequire(import.meta.url);
const project = fileURLToPath(new URL('../', import.meta.url));
const packaged = process.argv.includes('--packaged'), publicConnection = process.argv.includes('--public');
const android = process.argv.includes('--android');
const androidLan = process.argv.includes('--android-lan');
const upgrade = packaged && process.argv.includes('--upgrade');
const { version } = JSON.parse(await readFile(path.join(project, 'package.json'), 'utf8'));
const executable = packaged ? path.join(project, 'build/desktop-release', `RideLedger-${version}-win-x64.exe`) : require('electron');
const previousExecutable = path.join(project, 'build/desktop-release/RideLedger-0.1.4-win-x64.exe');
const output = path.join(project, 'build', 'desktop-flow-report');
await mkdir(output, { recursive: true });
const profile = await mkdtemp(path.join(project, 'build', 'desktop-flow-profile-'));
const reservation = net.createServer();
await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
const port = reservation.address().port;
await new Promise(resolve => reservation.close(resolve));
let child, page, consoleLog = '';

async function launch(binary = executable) {
  const environment = { ...process.env, RIDE_LEDGER_USER_DATA: profile, RIDE_LEDGER_PORT: String(port),
    RIDE_LEDGER_HEADLESS: '1', RIDE_LEDGER_DISABLE_DISCOVERY: '1' };
  delete environment.ELECTRON_RUN_AS_NODE;
  if (packaged) {
    delete environment.NODE_PATH;
    const pathKey = Object.keys(environment).find(key => key.toLowerCase() === 'path') || 'PATH';
    environment[pathKey] = (environment[pathKey] || '').split(path.delimiter).filter(entry => !/nodejs/i.test(entry)).join(path.delimiter);
  }
  consoleLog = '';
  child = spawn(binary, [...(packaged ? [] : [project]), '--remote-debugging-port=0'], {
    env: environment, cwd: packaged ? profile : project, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
  });
  child.stdout.on('data', part => { consoleLog += part; });
  child.stderr.on('data', part => { consoleLog += part; });
  child.on('error', error => { consoleLog += error.message; });
  for (let attempt = 0; attempt < 150; attempt++) {
    const match = consoleLog.match(/DevTools listening on ws:\/\/127\.0\.0\.1:(\d+)/);
    const activePort = match?.[1] || (await readFile(path.join(profile, 'DevToolsActivePort'), 'utf8').catch(() => '')).split(/\r?\n/)[0];
    if (activePort) {
      try {
        page = await connectPage(Number(activePort));
        if (await page.evaluate('Boolean(window.rideLedgerDesktop && document.getElementById("main").innerHTML)')) return;
        page.close();
      } catch { }
    }
    await delay(200);
  }
  throw new Error('Desktop launch did not complete: ' + consoleLog);
}
async function close() {
  if (page) { await page.send('Browser.close').catch(() => {}); page.close(); page = null; }
  await delay(600);
  if (child && child.exitCode === null) child.kill();
}
async function waitFor(expression, timeout = 10000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await page.evaluate(expression)) return;
    await delay(150);
  }
  throw new Error('Desktop condition timed out: ' + expression + '\n' +
    await page.evaluate('document.getElementById("toast").textContent'));
}
async function submit(id, fields) {
  await page.evaluate(`(() => {
    const form = document.getElementById(${JSON.stringify(id)});
    for (const [name, value] of Object.entries(${JSON.stringify(fields)})) {
      const input = form.elements.namedItem(name); input.value = value;
      if (input.dispatchEvent) input.dispatchEvent(new Event('change', { bubbles: true }));
    }
    form.requestSubmit();
  })()`);
}
const report = { version, packaged, publicConnection, android, androidLan, upgrade, passed: [] };
try {
  await launch(upgrade ? previousExecutable : executable);
  assert.equal(await page.evaluate('document.title'), '双人旅行账本');
  assert.equal(await page.evaluate('document.querySelector(".brand small").textContent'), '电脑客户端');
  await page.evaluate('document.querySelector("[data-action=new]").click()');
  await submit('trip-form', { name: '电脑手机互通示例', personA: '电脑用户', personB: '手机用户' });
  await waitFor('state.trips.length === 1');
  const tripId = await page.evaluate('activeTrip().id');

  await page.evaluate('document.querySelector("[data-view=review]").click(); document.querySelector("[data-action=opening]").click()');
  await submit('opening-form', { person: 'a', amount: '1000', currency: 'CNY' });
  await page.evaluate('document.querySelector("[data-view=quick]").click()');
  await submit('quick-expense-form', { amount: '12', currency: 'CNY', payer: 'a', mode: 'shared', note: '电脑录入饭费' });
  await page.evaluate('document.querySelector("[data-view=review]").click(); document.querySelector("[data-action=topup]").click()');
  await submit('topup-form', { person: 'b', amount: '50', currency: 'USD' });
  await page.evaluate('document.querySelector("[data-action=transfer]").click()');
  await submit('transfer-form', { from: 'b', amount: '3', currency: 'CNY' });
  await page.evaluate('document.querySelector("[data-action=exchange]").click()');
  await submit('exchange-form', { person: 'b', fromAmount: '10', fromCurrency: 'USD', toAmount: '9', toCurrency: 'EUR' });
  await page.evaluate('document.querySelector("[data-view=quick]").click()');
  await submit('quick-expense-form', { amount: '2', currency: 'EUR', payer: 'b', mode: 'personal', note: '个人饮料' });
  await waitFor('state.events.length === 6 && !syncing');
  assert.deepEqual(await page.evaluate(`(() => { const s=summary(activeTrip()); return {
    spent: String(s.spent.get('CNY')), cashA: String(s.cashA.get('CNY')),
    debt: String(s.debt.get('CNY')), cashUsdB: String(s.cashB.get('USD')),
    cashEurB: String(s.cashB.get('EUR')) }; })()`),
  { spent: '1200', cashA: '99100', debt: '300', cashUsdB: '4000', cashEurB: '700' });
  report.passed.push('native desktop UI: opening funds, shared/personal expense, top-up, repayment, exchange and totals');
  await close(); await launch();
  assert.equal(await page.evaluate('activeTrip().id'), tripId);
  assert.equal(await page.evaluate('state.events.length'), 6);
  assert.equal(await page.evaluate('currentView'), 'quick');
  report.passed.push('closing and reopening the executable preserves the ledger and selected travel');
  if (upgrade) report.passed.push('upgrading the v0.1.4 EXE to v0.1.5 preserves existing funds, records, identities and selected travel');
  if (packaged) report.passed.push('single-file EXE launches outside the development folder without Node.js on PATH');

  if (publicConnection) {
    await page.evaluate('document.querySelector("[data-view=trips]").click(); document.querySelector("[data-action=invite]").click()');
    await waitFor('document.getElementById("invite-dialog").open', 55000);
    const code = await page.evaluate('document.getElementById("invite-code").value');
    const phone = client(state([]));
    const joined = join(phone, code);
    assert.ok(joined.syncUrl.startsWith('https://'));
    report.publicUrl = joined.syncUrl;
    phone.context.fetch = fetch; phone.context.navigator.onLine = true;
    await phone.api.syncNow();
    assert.equal(phone.stored().events.length, 6);
    phone.context.navigator.onLine = false;
    phone.api.addEvent('expense', { payer: 'b', amount: 800, currency: 'CNY', shareA: 5000,
      mode: 'shared', note: 'phone offline record over public connection' });
    const restarted = client(phone.stored());
    restarted.context.fetch = fetch; restarted.context.navigator.onLine = true;
    await restarted.emit('online');
    await waitFor('state.events.length === 7');
    assert.equal(await page.evaluate('String(summary(activeTrip()).debt.get("CNY"))'), '-100');
    report.passed.push('public HTTPS: invitation auto-configures a separate client; offline entry survives restart and reaches desktop');

    if (android) {
      const sdk = process.env.ANDROID_SDK_ROOT || 'E:\\Android_Sdk';
      const adb = path.join(sdk, 'platform-tools', 'adb.exe');
      const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
      const adbRun = (...args) => run(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 90000, maxBuffer: 2_000_000 });
      await run('powershell.exe', ['-NoProfile', '-File', path.join(project, 'tests/build-android-sync-tests.ps1'),
        '-Sdk', sdk, '-JavaHome', process.env.JAVA_HOME || 'E:\\Android\\jbr'], { windowsHide: true, timeout: 60000 });
      await adbRun('install', '-r', path.join(project, 'build/android-sync-tests/ride-ledger-sync-tests.apk'));
      try {
        const { stdout } = await adbRun('shell', 'am', 'instrument', '-w', '-e', 'desktopInvite', code,
          '-e', 'expectedEvents', '7', 'com.rideledger.synctest/com.rideledger.SyncTestInstrumentation');
        await writeFile(path.join(output, 'android-public-result.txt'), stdout);
        const match = stdout.match(/INSTRUMENTATION_RESULT: report=(.*)/);
        if (!match) throw new Error(stdout);
        const native = JSON.parse(match[1]);
        report.androidChecks = native;
        assert.deepEqual(native.failed, [], 'Android public invitation checks failed');
        await waitFor('state.events.length === 8');
        assert.equal(await page.evaluate('String(summary(activeTrip()).debt.get("CNY"))'), '-450');
        report.passed.push('real Android code: joins from public invitation, downloads desktop entries, uploads offline entry, restarts without manual endpoint');
      } finally { await adbRun('uninstall', 'com.rideledger.synctest').catch(() => {}); }
    }
    await page.evaluate('document.getElementById("invite-dialog").close()');
  }
  if (androidLan) {
    await waitFor('desktopLan.addresses.length > 0 && !syncing');
    const code = await page.evaluate('inviteCode(activeTrip())');
    const expected = await page.evaluate('state.events.length');
    const tcpPort = Number((await page.evaluate('desktopLan.addresses[0]')).split(':').at(-1));
    await page.evaluate(`(() => {
      const original = Storage.prototype.setItem;
      window.desktopFailedSave = 0;
      Storage.prototype.setItem = function (key, value) {
        if (key === STORAGE_KEY && value.includes('Android peer /') && !window.desktopFailedSave) {
          window.desktopFailedSave++;
          throw new Error('Injected persistence failure');
        }
        return original.call(this, key, value);
      };
    })()`);
    const sdk = process.env.ANDROID_SDK_ROOT || 'E:\\Android_Sdk';
    const adb = path.join(sdk, 'platform-tools', 'adb.exe');
    const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
    const adbRun = (...args) => run(adb, ['-s', serial, ...args], { windowsHide: true, timeout: 180000, maxBuffer: 2_000_000 });
    await run('powershell.exe', ['-NoProfile', '-File', path.join(project, 'tests/build-android-sync-tests.ps1'),
      '-Sdk', sdk, '-JavaHome', process.env.JAVA_HOME || 'E:\\Android\\jbr'], { windowsHide: true, timeout: 60000 });
    await adbRun('install', '-r', path.join(project, 'build/android-sync-tests/ride-ledger-sync-tests.apk'));
    try {
      const { stdout } = await adbRun('shell', 'am', 'instrument', '-w', '-e', 'desktopPeerInvite', code,
        '-e', 'desktopPeerAddress', '10.0.2.2:' + tcpPort, '-e', 'expectedEvents', String(expected),
        'com.rideledger.synctest/com.rideledger.SyncTestInstrumentation');
      await writeFile(path.join(output, 'android-peer-result.txt'), stdout);
      const match = stdout.match(/INSTRUMENTATION_RESULT: report=(.*)/);
      if (!match) throw new Error(stdout);
      const native = JSON.parse(match[1]);
      report.androidPeerChecks = native;
      assert.deepEqual(native.failed, [], 'Android to desktop peer checks failed');
      assert.equal(await page.evaluate('window.desktopFailedSave'), 1, 'The local save failure was not exercised');
      await waitFor('state.events.length === ' + (expected + 2));
      assert.equal(await page.evaluate('desktopLan.status'), '同热点已同步 · 同伴已确认保存');
      await close(); await launch();
      assert.equal(await page.evaluate('state.events.length'), expected + 2);
      report.passed.push('Android 10 production protocol and desktop renderer exchange v2 deltas, reject a failed-save receipt, negotiate v1, deduplicate and survive reopening');
    } finally { await adbRun('uninstall', 'com.rideledger.synctest').catch(() => {}); }
  }
  await page.evaluate('document.querySelector("[data-view=trips]").click()');
  await writeFile(path.join(output, 'desktop.png'), await page.screenshot());
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  await writeFile(path.join(output, `report-${packaged ? 'exe' : 'source'}-${publicConnection ? 'public' : 'offline'}.json`),
    JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2));
} finally {
  await writeFile(path.join(output, 'electron.log'), consoleLog);
  await close();
}
