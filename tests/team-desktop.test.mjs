import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import net from 'node:net';
import test from 'node:test';
import { DesktopLanSync } from '../desktop/lan-sync.mjs';
import { DesktopTeams, DesktopTeamProtocol, Admission, encodeInvite, readInvite, routeId } from '../desktop/team.mjs';
import { enrollViaRelay } from '../desktop/team-relay.mjs';
import { PeerWire } from '../desktop/peer-protocol.mjs';
import { startSyncServer } from '../sync-server/server.mjs';

function device() {
  let saved;
  const persist = data => { saved = data; };
  const first = new DesktopTeams(null, persist);
  return { first, reopen: () => new DesktopTeams(saved, persist) };
}

test('Windows team seat survives restart and nearby reconnect durably exchanges missing records', async () => {
  const root = device(), guest = device();
  const trip = root.first.create('小队骑行', ['电脑', '手机']);
  const peer = readInvite(encodeInvite(trip));
  const record = person => ({ id: randomUUID(), tripId: trip.id, at: Date.now(), kind: 'opening',
    data: { person, currency: 'CNY', amount: 150 } });
  const rootEvents = new Map([[0, record('m1')]]), peerEvents = new Map();
  const add = (events, received) => { for (const event of received) events.set(event.id, event); };
  const lan = new DesktopLanSync({ teams: root.first, discovery: false, host: '127.0.0.1',
    commit(_trip, events) { add(rootEvents, events); } });
  await lan.start();
  const connect = async directory => {
    lan.updateTeam(root.first.snapshot(trip, [...rootEvents.values()]));
    const socket = net.createConnection(lan.port, '127.0.0.1');
    socket.setTimeout(5000, () => socket.destroy());
    try {
      const snapshot = directory.snapshot(peer, [...peerEvents.values()]);
      const protocol = new DesktopTeamProtocol(new PeerWire(socket), snapshot, directory,
        (_trip, events) => add(peerEvents, events), () => true);
      return await protocol.exchange(routeId(snapshot, root.first.device));
    } finally { socket.destroy(); }
  };
  try {
    assert.equal((await connect(guest.first)).complete, true);
    const restored = guest.reopen();
    assert.equal(restored.ready(restored.snapshot(peer, [])), true);
    add(peerEvents, [record('m2')]);
    assert.equal((await connect(restored)).complete, true);
    assert.equal((await connect(restored)).complete, true);
    assert.deepEqual([...rootEvents.values()], [...peerEvents.values()]);
    await assert.rejects(connect(device().first), Admission);
  } finally { await lan.close(); }
});

test('desktop HTTPS enrollment and nearby seats share the same limit; relay stores no ledger', async () => {
  const relay = await startSyncServer({ port: 0, enrollmentOnly: true });
  try {
    const root = device().first, first = device().first, second = device().first;
    const trip = { ...root.create('跨网登记', ['甲', '乙']), syncUrl: 'https://relay.example.test' };
    const peer = readInvite(encodeInvite(trip));
    const request = (url, options) => fetch(relay.origin + new URL(url).pathname, options);
    const step = (directory, raw) => enrollViaRelay(directory.snapshot(raw, []), directory, () => true, request);
    await Promise.all([step(first, peer), step(second, peer)]);
    assert.equal(await step(root, trip), 'HTTPS 登记已就绪');
    const results = await Promise.allSettled([step(first, peer), step(second, peer)]);
    assert.equal(results.filter(result => result.status === 'fulfilled').length, 1);
    const denied = results.find(result => result.status === 'rejected');
    assert.ok(denied.reason instanceof Admission);
    assert.throws(() => root.enroll(root.snapshot(trip, []), { device: randomUUID(), key: device().first.key, member: 'm2' }), Admission);
    assert.equal((await fetch(relay.origin + '/sync', { method: 'POST', body: '{}' })).status, 404);
  } finally { await relay.close(); }
});
