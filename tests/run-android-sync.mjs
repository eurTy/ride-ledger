import assert from 'node:assert/strict';
import { execFile } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, mkdtemp, readFile, unlink, rmdir, writeFile } from 'node:fs/promises';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';
import { client, state } from './support/web-client.mjs';
import { fixture, expectedSummary, invalidInvites } from './support/scenario.mjs';
import { startRelay } from './support/relay.mjs';

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
const relay = await startRelay();
const { t, events } = fixture();
const web = client(state([t], events));
const webBundle = await web.api.encryptedBundle(t);
const receivedFrames = [];
const servers = [];

try {
  await mkdir(output, { recursive: true });
  const tlsDir = await mkdtemp(path.join(output, 'tls-'));
  const p12 = path.join(tlsDir, 'relay.p12'), cer = path.join(tlsDir, 'relay.cer');
  const passphrase = 'isolated-android-test';
  const keytool = (...args) => run(path.join(javaHome, 'bin', 'keytool.exe'), args, { windowsHide: true });
  let pfx, certificate;
  try {
    await keytool('-genkeypair', '-alias', 'relay', '-keyalg', 'RSA', '-keysize', '2048', '-dname', 'CN=10.0.2.2',
      '-ext', 'SAN=IP:10.0.2.2', '-validity', '2', '-storetype', 'PKCS12', '-keystore', p12, '-storepass', passphrase);
    await keytool('-exportcert', '-alias', 'relay', '-keystore', p12, '-storepass', passphrase, '-file', cer);
    [pfx, certificate] = await Promise.all([readFile(p12), readFile(cer)]);
  } finally {
    await Promise.all([unlink(p12).catch(() => {}), unlink(cer).catch(() => {})]);
    await rmdir(tlsDir);
  }
  let dropFirstAnswer = true;
  const enrollment = https.createServer({ pfx, passphrase }, async (req, res) => {
    try {
      assert.equal(req.url, '/team-enrollment');
      const parts = [];
      for await (const part of req) parts.push(part);
      const input = Buffer.concat(parts);
      if (dropFirstAnswer && JSON.parse(input).answers?.length) {
        dropFirstAnswer = false;
        res.writeHead(503).end('{}');
        return;
      }
      const response = await fetch(relay.origin + req.url, {
        method: 'POST', body: input,
        headers: { 'Content-Type': 'application/json', 'X-Trip-Token': req.headers['x-trip-token'] }
      });
      res.writeHead(response.status, { 'Content-Type': 'application/json' });
      res.end(await response.text());
    } catch { res.writeHead(500).end('{}'); }
  });
  servers.push(enrollment);
  enrollment.listen(0, '127.0.0.1'); await once(enrollment, 'listening');

  const proxy = http.createServer(async (req, res) => {
    try {
      const parts = [];
      for await (const part of req) parts.push(part);
      if (req.url === '/503/sync') { res.writeHead(503); res.end('{}'); return; }
      const response = await fetch(relay.origin + '/sync', {
        method: 'POST', body: Buffer.concat(parts),
        headers: { 'Content-Type': 'application/json', 'X-Trip-Token': req.headers['x-trip-token'] }
      });
      const body = await response.json();
      if (req.url === '/lost-ack/sync') { req.socket.destroy(); return; }
      if (req.url === '/truncated/sync') { res.writeHead(200); res.end('{"snapshots":['); return; }
      if (req.url === '/corrupt/sync' && response.ok) body.snapshots.unshift({ ...body.snapshots[0], data: 'AAAA' });
      res.writeHead(response.status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    } catch (error) { if (!res.destroyed) { res.writeHead(500); res.end('{}'); } }
  });
  servers.push(proxy);
  proxy.listen(0, '127.0.0.1'); await once(proxy, 'listening');

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
  const inviteA = web.api.inviteCode(t);
  const valid = JSON.parse(Buffer.from(inviteA, 'base64url').toString('utf8'));
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const legacy = { ...valid }; delete legacy.inviterRole;
  const data = {
    trip: t, events, expectedSummary, webBundle, inviteA,
    inviteB: web.api.inviteCode({ ...t, localPerson: 'b' }),
    legacyInvite: encode(legacy), wrappedInvite: inviteA.match(/.{1,39}/g).join('\r\n'),
    wrongKeyInvite: encode({ ...valid, secret: Buffer.alloc(32, 19).toString('base64url') }),
    invalidInvites: invalidInvites(valid),
    endpoint: 'http://10.0.2.2:' + proxy.address().port,
    enrollmentEndpoint: 'https://10.0.2.2:' + enrollment.address().port,
    enrollmentCertificate: certificate.toString('base64url'),
    lanPort: await tcp('normal'), lanDropPort: await tcp('drop'), lanCorruptPort: await tcp('corrupt')
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
    const invite = web.api.parseInviteCode(result.androidInvite);
    assert.equal(invite.id, t.id); assert.equal(invite.inviterRole, 'b');
    assert.equal(invite.personA, t.personA); assert.equal(invite.personB, t.personB);
  }
  if (result.connectedInvite) {
    const connected = web.api.parseInviteCode(result.connectedInvite);
    assert.equal(connected.syncUrl, 'https://relay.example.test');
    assert.equal(connected.inviterRole, 'b');
    console.log('INTEROP_PASS Android connection-bearing invitation read by desktop/web client');
  }
  if (result.androidBundle) {
    const decoded = await web.api.decryptBundle(t, JSON.parse(result.androidBundle));
    const sort = values => JSON.parse(JSON.stringify(values)).sort((a, b) => a.id.localeCompare(b.id));
    assert.deepEqual(sort(decoded), sort(events));
    console.log('INTEROP_PASS Android invitation and AES-GCM records read by web client');
  }
  if (result.androidCloudBundle) {
    const decoded = await web.api.decryptBundle(t, JSON.parse(result.androidCloudBundle));
    assert.equal(new Set(decoded.map(event => event.id)).size, events.length + 2);
    for (const event of events) assert.deepEqual(JSON.parse(JSON.stringify(decoded.find(item => item.id === event.id))), event);
    console.log('INTEROP_PASS Native offline entries survive HTTP failures with original timestamps');
  }
  for (const message of receivedFrames) {
    assert.equal(message.version, 1);
    for (const bundle of message.bundles) assert.ok(Array.isArray(await web.api.decryptBundle(t, bundle)));
  }
  assert.ok(receivedFrames.length >= 5, 'native TCP scenarios did not run');
  await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
  assert.equal(report.failed.length, 0, 'Native sync checks failed; see build/android-sync-tests/report.json');
  console.log('ANDROID_SYNC_OK checks=' + report.passed.length + ' tcp_frames=' + receivedFrames.length);
} finally {
  await adbRun('uninstall', 'com.rideledger.synctest').catch(() => {});
  for (const server of servers) await new Promise(resolve => server.close(resolve));
  await relay.cleanup();
}
