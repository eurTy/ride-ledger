import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import net from 'node:net';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { connectPage } from './support/cdp.mjs';
import { randomUUID } from 'node:crypto';
import { DesktopTeams, readInvite, encodeInvite } from '../desktop/team.mjs';

const require = createRequire(import.meta.url);
const project = fileURLToPath(new URL('../', import.meta.url));
const packaged = process.argv.includes('--packaged');
const { version } = JSON.parse(await readFile(path.join(project, 'package.json'), 'utf8'));
const executable = packaged ? path.join(project, 'build/desktop-release', `RideLedger-${version}-win-x64.exe`) : require('electron');
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
const report = { version, packaged, passed: [] };
try {
  const oldSnapshot = path.join(profile, 'relay', randomUUID() + '.json');
  await mkdir(path.dirname(oldSnapshot));
  await writeFile(oldSnapshot, '{"snapshots":{}}');
  await launch();
  await assert.rejects(readFile(oldSnapshot), { code: 'ENOENT' });
  assert.equal(await page.evaluate('document.title'), '旅行账本');
  await page.evaluate('document.querySelector("[data-action=new]").click(); document.getElementById("add-trip-member").click()');
  await submit('trip-form', { name: '桌面小队实测', personA: '电脑', member: '手机' });
  await waitFor('activeTrip()?.members.length === 2');
  await submit('quick-expense-form', { amount: '12.30' });
  await waitFor('state.events.length === 1');
  await page.evaluate('document.querySelector("[data-view=trips]").click(); document.querySelector("[data-action=invite]").click()');
  await waitFor('document.getElementById("invite-dialog").open');
  const invite = readInvite(await page.evaluate('document.getElementById("invite-code").value'));
  assert.deepEqual(invite.members, ['电脑', '手机']);
  assert.match(await page.evaluate('document.getElementById("team-invite-image").src'), /^data:image\/png;base64,/);
  report.passed.push('team creation, quick entry and QR invitation');
  await page.evaluate('document.getElementById("invite-dialog").close()');

  const oldId = randomUUID(), oldEvent = randomUUID();
  await page.evaluate(`(() => {
    const old = { id: '${oldId}', name: '旧双人测试', localPerson: 'a', personA: '甲', personB: '乙' };
    state.trips.push(old);
    state.events.push({ tripId: old.id, id: '${oldEvent}', at: 1, kind: 'opening', data: { person: 'a', amount: 100, currency: 'CNY' } });
    state.quickPrefs[old.id] = { currency: 'USD' };
    save();
  })()`);
  await page.send('Page.reload');
  await waitFor('typeof state !== "undefined" && state.trips.length === 1 && state.events.length === 1');
  assert.equal(await page.evaluate(`Object.hasOwn(JSON.parse(localStorage.getItem(STORAGE_KEY)).quickPrefs, '${oldId}')`), false);
  assert.equal(await page.evaluate('activeTrip().name'), '桌面小队实测');
  report.passed.push('legacy ledger and preferences removed, team records preserved');

  const inviter = new DesktopTeams(null, () => {});
  const code = encodeInvite(inviter.create('加入测试', ['对方', '我']));
  await page.evaluate(`(() => {
    document.querySelector('[data-view=trips]').click();
    document.querySelector('[data-action=join-team]').click();
    document.getElementById('team-invite-input').value = ${JSON.stringify(code)};
    document.getElementById('manual-team-invite').requestSubmit();
  })()`);
  await waitFor('document.getElementById("team-identity-dialog").open');
  await page.evaluate(`document.getElementById('team-join-member').value = 'm2'; document.getElementById('team-identity-form').requestSubmit();`);
  await waitFor('activeTrip()?.name === "加入测试" && document.getElementById("main").textContent.includes("首次登记")');
  report.passed.push('backup invitation selects member and waits for first enrollment');
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  for (const name of report.passed) console.log('DESKTOP_PASS ' + name);
} finally { await close(); }
