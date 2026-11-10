import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import net from 'node:net';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import * as codec from './support/legacy-web-codec.mjs';
import { fixture, expectedSummary, invalidInvites } from './support/scenario.mjs';
import { DesktopLanSync } from '../desktop/lan-sync.mjs';
import { DesktopTeams, encodeInvite } from '../desktop/team.mjs';
import { randomUUID } from 'node:crypto';

const run = promisify(execFile);
const project = fileURLToPath(new URL('../', import.meta.url));
const sdk = process.env.ANDROID_SDK_ROOT || 'E:\\Android_Sdk';
const javaHome = process.env.JAVA_HOME || 'E:\\Android\\jbr';
const adb = path.join(sdk, 'platform-tools', 'adb.exe');
const serial = process.env.ANDROID_TEST_SERIAL || 'emulator-5554';
const adbRun = (...args) => run(adb, ['-s', serial, ...args], {
  windowsHide: true, maxBuffer: 2_000_000, timeout: 180000
});
const output = path.join(project, 'build', 'android-sync-tests');
const build = await run('powershell.exe', ['-NoProfile', '-File',
  path.join(project, 'tests', 'build-android-sync-tests.ps1'), '-Sdk', sdk, '-JavaHome', javaHome], {
  windowsHide: true, maxBuffer: 1_000_000, timeout: 60000
});
process.stdout.write(build.stdout);
const { t, events } = fixture();
const webBundle = codec.encryptedBundle(t, events);
const receivedFrames = [];
const servers = [];
const reversedPorts = [];
let desktopTeam;

try {
  const directory = new DesktopTeams(null, () => {});
  const team = directory.create('桌面与安卓小队', ['电脑', '手机']);
  const ledger = new Map();
  const opening = { id: randomUUID(), tripId: team.id, kind: 'opening', at: Date.now(),
    data: { person: 'm1', currency: 'CNY', amount: 7500 } };
  ledger.set(opening.id, opening);
  desktopTeam = new DesktopLanSync({ host: '127.0.0.1', discovery: false, teams: directory,
    commit(_trip, events) {
      let added = 0;
      for (const event of events) if (!ledger.has(event.id)) { ledger.set(event.id, event); added++; }
      desktopTeam.updateTeam(directory.snapshot(team, [...ledger.values()]));
      return added;
    } });
  await desktopTeam.start();
  desktopTeam.updateTeam(directory.snapshot(team, [...ledger.values()]));
  await mkdir(output, { recursive: true });
  const tcp = async mode => {
    const server = net.createServer(socket => {
      let received = Buffer.alloc(0), handled = false;
      socket.on('error', () => {});
      socket.on('data', async part => {
        received = Buffer.concat([received, part]);
        if (handled || received.length < 4) return;
        const size = received.readInt32BE(0);
        if (size < 1 || size > 12_000_000) { socket.destroy(); return; }
        if (received.length < size + 4) return;
        handled = true;
        try {
          receivedFrames.push(JSON.parse(received.subarray(4, size + 4).toString('utf8')));
          const bundles = mode === 'corrupt' ? [{ ...webBundle, data: 'AAAA' }, webBundle] : [webBundle];
          const bytes = Buffer.from(JSON.stringify({ version: 1, bundles }));
          const frame = Buffer.alloc(4 + bytes.length); frame.writeInt32BE(bytes.length); bytes.copy(frame, 4);
          if (mode === 'drop') { socket.end(frame.subarray(0, Math.floor(frame.length / 2))); return; }
          for (const [from, to] of [[0, 1], [1, 3], [3, 9], [9, frame.length]]) {
            socket.write(frame.subarray(from, to)); await delay(3);
          }
          socket.end();
        } catch { socket.destroy(); }
      });
    });
    servers.push(server);
    server.listen(0, '127.0.0.1'); await once(server, 'listening');
    return server.address().port;
  };
  const inviteA = codec.inviteCode(t);
  const valid = JSON.parse(Buffer.from(inviteA, 'base64url').toString('utf8'));
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const legacy = { ...valid }; delete legacy.inviterRole;
  const testHost = process.env.ANDROID_TEST_ADB_REVERSE === '1' ? '127.0.0.1' : '10.0.2.2';
  const lanPort = await tcp('normal'), lanDropPort = await tcp('drop'), lanCorruptPort = await tcp('corrupt');
  if (testHost === '127.0.0.1') {
    for (const port of [desktopTeam.port, lanPort, lanDropPort, lanCorruptPort]) {
      const endpoint = 'tcp:' + port;
      await adbRun('reverse', '--no-rebind', endpoint, endpoint);
      reversedPorts.push(endpoint);
    }
  }
  const data = {
    trip: t, events, expectedSummary, webBundle, inviteA,
    inviteB: codec.inviteCode({ ...t, localPerson: 'b' }),
    legacyInvite: encode(legacy), wrappedInvite: inviteA.match(/.{1,39}/g).join('\r\n'),
    wrongKeyInvite: encode({ ...valid, secret: Buffer.alloc(32, 19).toString('base64url') }),
    invalidInvites: invalidInvites(valid),
    testHost,
    desktopTeamInvite: encodeInvite(team), desktopTeamPort: desktopTeam.port,
    lanPort, lanDropPort, lanCorruptPort
  };
  await adbRun('install', '-r', path.join(output, 'ride-ledger-sync-tests.apk'));
  const { stdout } = await adbRun('shell', 'am', 'instrument', '-w', '-e', 'fixture', encode(data),
    'com.rideledger.synctest/com.rideledger.SyncTestInstrumentation');
  await mkdir(output, { recursive: true });
  await writeFile(path.join(output, 'instrumentation-result.txt'), stdout);
  const result = {};
  for (const line of stdout.split(/\r?\n/)) {
    const match = line.match(/^INSTRUMENTATION_RESULT: ([^=]+)=(.*)$/);
    if (match) result[match[1]] = match[2];
  }
  if (!result.report) throw new Error('Android instrumentation produced no report: ' + stdout);
  const report = JSON.parse(result.report);
  report.environment = { serial, sdk: (await adbRun('shell', 'getprop', 'ro.build.version.sdk')).stdout.trim(),
    release: (await adbRun('shell', 'getprop', 'ro.build.version.release')).stdout.trim() };
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  for (const name of report.passed || []) console.log('ANDROID_PASS ' + name);
  for (const name of report.failed || []) console.log('ANDROID_FAIL ' + name);
  if (result.androidInvite) {
    const invite = codec.parseInviteCode(result.androidInvite);
    assert.equal(invite.id, t.id); assert.equal(invite.inviterRole, 'b');
    assert.equal(invite.personA, t.personA); assert.equal(invite.personB, t.personB);
  }
  if (result.androidBundle) {
    const decoded = codec.decryptBundle(t, JSON.parse(result.androidBundle));
    const sort = values => JSON.parse(JSON.stringify(values)).sort((a, b) => a.id.localeCompare(b.id));
    assert.deepEqual(sort(decoded), sort(events));
    console.log('INTEROP_PASS Android invitation and AES-GCM records read by web client');
  }
  for (const message of receivedFrames) {
    assert.equal(message.version, 1);
    for (const bundle of message.bundles) assert.ok(Array.isArray(codec.decryptBundle(t, bundle)));
  }
  assert.ok(receivedFrames.length >= 5, 'native TCP scenarios did not run');
  assert.equal(ledger.size, 2, 'Android team expense did not arrive at the Windows transport');
  console.log('INTEROP_PASS Windows and Android team enrollment, nearby bidirectional records and deduplication');
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  assert.equal(report.failed.length, 0, 'Native sync checks failed; see build/android-sync-tests/report.json');
  if (Number(report.environment.sdk) >= 29) {
    const runId = randomUUID();
    const instrument = 'com.rideledger.synctest/com.rideledger.SyncTestInstrumentation';
    const crashArgs = phase => ['shell', 'am', 'instrument', '-w', '-e', 'journalCrash', phase, '-e', 'runId', runId, instrument];
    const stopped = await adbRun(...crashArgs('prepare'));
    assert.match(stopped.stdout, /shortMsg=Process crashed/i, 'isolated test process did not die before confirmation');
    const { stdout: recovery } = await adbRun(...crashArgs('recover'));
    const recovered = JSON.parse(recovery.match(/^INSTRUMENTATION_RESULT: report=(.*)$/m)?.[1] ?? 'null');
    assert.ok(recovered, 'crash recovery run produced no report');
    assert.deepEqual(recovered.failed, [], 'crash recovery did not preserve the journal and its cursor');
    for (const name of recovered.passed) {
      report.passed.push(name);
      console.log('ANDROID_PASS ' + name);
    }
    await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  }
  console.log('ANDROID_SYNC_OK checks=' + report.passed.length + ' tcp_frames=' + receivedFrames.length);
} finally {
  await adbRun('uninstall', 'com.rideledger.synctest').catch(() => {});
  for (const port of reversedPorts) await adbRun('reverse', '--remove', port).catch(() => {});
  for (const server of servers) await new Promise(resolve => server.close(resolve));
  await desktopTeam?.close();
}
