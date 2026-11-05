import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, realpath, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { DesktopLanSync } from '../desktop/lan-sync.mjs';
import { startSyncServer } from '../sync-server/server.mjs';
import { client, trip, expense, state, join } from './support/web-client.mjs';

const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');

test('connection-bearing invitation configures only the joined travel', async () => {
  const t = { ...trip(), syncUrl: 'https://relay.example.test/' };
  const source = client(state([t]));
  const other = trip();
  const phone = client(state([other], [expense(other.id)]));
  const added = join(phone, source.api.inviteCode(t));
  assert.equal(added.syncUrl, 'https://relay.example.test');
  assert.equal(added.localPerson, 'b');
  assert.equal(phone.stored().trips.find(item => item.id === other.id).syncUrl, undefined);
  assert.equal(phone.stored().events.length, 1);
  const requests = [];
  phone.context.fetch = async (url, options) => {
    requests.push({ url, tripId: JSON.parse(options.body).tripId });
    return new Response('{"snapshots":[]}');
  };
  phone.context.navigator.onLine = true;
  await phone.api.syncNow();
  assert.equal(requests.find(item => item.tripId === t.id).url, 'https://relay.example.test/sync');
  assert.equal(requests.find(item => item.tripId === other.id).url, '/sync');
});

test('new connection code updates an existing travel while preserving identity and records', () => {
  const t = { ...trip('b'), syncUrl: 'https://old.example.test' };
  const entry = expense(t.id);
  const receiver = client(state([t], [entry]));
  const source = client();
  join(receiver, source.api.inviteCode({ ...t, localPerson: 'b', syncUrl: 'https://new.example.test' }), 'a');
  assert.equal(receiver.stored().trips.length, 1);
  assert.equal(receiver.stored().trips[0].localPerson, 'b');
  assert.equal(receiver.stored().trips[0].syncUrl, 'https://new.example.test');
  assert.deepEqual(receiver.stored().events, [entry]);
  const before = receiver.stored();
  join(receiver, source.api.inviteCode({ ...t, secret: Buffer.alloc(32, 11).toString('base64url'),
    syncUrl: 'https://unexpected.example.test' }));
  assert.deepEqual(receiver.stored(), before);
});

test('invitation rejects untrusted URL schemes, credentials and query payloads', () => {
  for (const syncUrl of ['http://127.0.0.1:8787', 'http://192.168.1.8:8787', 'file:///C:/ledger',
    'javascript:alert(1)', 'https://user:pass@example.test', 'https://example.test/?secret=x',
    'https://example.test/#key', 123, null]) {
    const receiver = client(state([]));
    join(receiver, encode({ ...trip(), inviterRole: 'a', syncUrl }));
    assert.equal(receiver.stored().trips.length, 0, 'accepted invalid URL ' + syncUrl);
  }
});

test('desktop invitation prepares the public connection and keeps hosted sync on localhost', async () => {
  const t = { ...trip(), hostedHere: true };
  const bridge = {
    getConnection: async () => ({ state: 'idle', url: '' }),
    connectPhone: async () => ({ state: 'ready', url: 'https://desktop.example.test' }),
    onConnectionChanged() {}, onRemoteChanged() {},
    exchangeLan: async () => ({ bundles: [], addresses: [] })
  };
  const desktop = client(state([t], [expense(t.id)]), { desktopBridge: bridge });
  desktop.context.fetch = async url => {
    assert.equal(url, '/sync'); return new Response('{"snapshots":[]}');
  };
  // Wait for the initial local-only synchronization before running another cycle.
  for (let i = 0; i < 20 && desktop.run('syncing'); i++) await delay(5);
  const hosted = await desktop.api.prepareInvite(desktop.api.getState().trips[0]);
  assert.equal(hosted.syncUrl, 'https://desktop.example.test');
  const receiver = client(state([]));
  assert.equal(join(receiver, desktop.api.inviteCode(hosted)).syncUrl, hosted.syncUrl);
  desktop.context.navigator.onLine = false;
  await desktop.api.syncNow();
});

test('embedded relay has a distinct identity and supports authenticated cross-origin requests', async () => {
  const parent = await realpath(os.tmpdir());
  const directory = await mkdtemp(path.join(parent, 'ride-desktop-relay-'));
  const relay = await startSyncServer({ port: 0, host: '127.0.0.1', dataDir: directory, instanceId: 'isolated-desktop' });
  try {
    const health = await (await fetch(relay.origin + '/health')).json();
    assert.equal(health.instanceId, 'isolated-desktop');
    const preflight = await fetch(relay.origin + '/sync', { method: 'OPTIONS', headers: {
      Origin: 'https://other-network.example.test', 'Access-Control-Request-Headers': 'X-Trip-Token' } });
    assert.equal(preflight.status, 200);
    assert.equal(preflight.headers.get('access-control-allow-origin'), '*');
  } finally {
    await relay.close();
    const target = await realpath(directory);
    assert.equal(path.dirname(target), parent);
    assert.ok(path.basename(target).startsWith('ride-desktop-relay-'));
    await rm(target, { recursive: true, force: true });
  }
});

test('desktop LAN exchanges Android-compatible frames without requiring internet access', async () => {
  const t = trip(), first = expense(t.id), second = { ...expense(t.id), data: {
    payer: 'b', currency: 'USD', amount: 500, shareA: 5000, mode: 'shared' } };
  const a = client(state([t], [first]));
  const b = client(state([{ ...t, localPerson: 'b' }], [second]));
  const left = new DesktopLanSync({ discovery: false, host: '127.0.0.1' });
  const right = new DesktopLanSync({ discovery: false, host: '127.0.0.1' });
  await left.start(); await right.start();
  try {
    const leftBundle = await a.api.encryptedBundle(t), rightBundle = await b.api.encryptedBundle(t);
    left.update({ revision: 'a', bundles: [leftBundle] });
    right.update({ revision: 'b', bundles: [rightBundle] });
    const received = Promise.all([once(left, 'incoming'), once(right, 'incoming')]);
    left.connectManual('127.0.0.1:' + right.port);
    await Promise.race([received, delay(3000).then(() => { throw new Error('LAN frames timed out'); })]);
    for (const bundle of left.update({ revision: 'a', bundles: [leftBundle] }).bundles)
      await a.api.mergeSyncedBundle(t, bundle);
    for (const bundle of right.update({ revision: 'b', bundles: [rightBundle] }).bundles)
      await b.api.mergeSyncedBundle(t, bundle);
    assert.equal(a.stored().events.length, 2); assert.equal(b.stored().events.length, 2);
    assert.deepEqual(a.stored().events.map(item => item.id).sort(), b.stored().events.map(item => item.id).sort());
    await a.api.mergeSyncedBundle(t, rightBundle);
    assert.equal(a.stored().events.length, 2);
  } finally { await left.close(); await right.close(); }
});

test('a desktop-initiated connection upgrades from v1 and sends only the new record', async () => {
  const travel = trip(), first = expense(travel.id), second = expense(travel.id);
  function replica(initial) {
    const book = state([travel], initial);
    const batches = [];
    const sync = new DesktopLanSync({ discovery: false, host: '127.0.0.1', commit: async (_trip, events) => {
      batches.push(events.map(event => event.id));
      for (const event of events) if (!book.events.some(current => current.id === event.id)) book.events.push(event);
    } });
    const refresh = async () => sync.update({ deviceId: book.deviceId, revision: book.events.map(event => event.id).join(':'),
      peerTrips: [{ id: travel.id, secret: travel.secret }], bundles: [await client(book).api.encryptedBundle(travel)] });
    return { book, batches, sync, refresh };
  }
  const left = replica([first]), right = replica([second]);
  await left.sync.start(); await right.sync.start();
  try {
    await left.refresh(); await right.refresh();
    const route = { host: '127.0.0.1', port: right.sync.port };
    await left.sync.exchange(route);
    assert.equal(route.modern, true, 'unknown address did not negotiate v2');
    assert.equal(left.book.events.length, 2);
    assert.equal(right.book.events.length, 2);
    left.batches.length = 0;
    right.batches.length = 0;
    const third = expense(travel.id);
    left.book.events.push(third);
    await left.refresh(); await right.refresh();
    await left.sync.exchange(route);
    assert.deepEqual(right.batches, [[third.id]]);
    assert.deepEqual(left.batches, []);
    assert.equal(left.sync.status, '同热点已同步 · 同伴已确认保存');
    await right.refresh();
    await left.sync.exchange(route);
    assert.deepEqual(right.batches, [[third.id]], 'matching roots resent saved records');
  } finally { await left.sync.close(); await right.sync.close(); }
});
