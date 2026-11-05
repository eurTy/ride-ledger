import test, { after } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID, randomBytes } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { trip, expense } from '../../tests/support/web-client.mjs';
import { replica, connect, partition, settle } from './legacy-network.mjs';
import { OperationLog, splitMinor, canonical, sealOperations, openOperations } from './operation-log.mjs';

const members = [1, 2, 3].map(value => '00000000-0000-4000-8000-' + String(value).padStart(12, '0'));
const money = amount => ({ payerId: members[0], currency: 'CNY', amount, shares: splitMinor(amount, members) });
const newLogs = (count = 3) => {
  const tripId = randomUUID(); return Array.from({ length: count }, () => new OperationLog({ tripId, members }));
};
const same = nodes => {
  const ids = nodes[0].browser.stored().events.map(value => value.id).sort();
  for (const node of nodes) assert.deepEqual(node.browser.stored().events.map(value => value.id).sort(), ids);
};
const observations = [];
after(async () => {
  if (!process.env.MESH_REPORT_DIRECTORY) return;
  await mkdir(process.env.MESH_REPORT_DIRECTORY, { recursive: true });
  await writeFile(path.join(process.env.MESH_REPORT_DIRECTORY, 'measurements.json'), JSON.stringify(observations, null, 2));
});

test('real TCP: three replicas converge over A-B-C with no A-C link or master ledger', async () => {
  const t = trip(), nodes = [];
  try {
    for (let i = 0; i < 3; i++) nodes.push(await replica(t, [expense(t.id)]));
    connect(nodes[0], nodes[1]); connect(nodes[1], nodes[2]);
    await settle(nodes, 3); same(nodes);
    assert.equal(nodes[0].lan.peers.has('probe-' + nodes[2].lan.port), false);
    observations.push({ scenario: 'real-loopback-chain', devices: 3, links: ['A-B','B-C'], uniqueEventsOnEach: 3,
      note: 'Existing two-person ledger on three device replicas. This does not make the product support three participants.' });
  } finally { await Promise.all(nodes.map(node => node.stop())); }
});

test('real TCP: a stored record reaches C through B after its original A has left', async () => {
  const t = trip(), nodes = [];
  try {
    nodes.push(await replica(t, [expense(t.id)]), await replica(t), await replica(t));
    connect(nodes[0], nodes[1]); await settle(nodes.slice(0, 2), 1);
    partition(...nodes); await nodes[0].stop();
    connect(nodes[1], nodes[2]); await settle(nodes.slice(1), 1); same(nodes);
    observations.push({ scenario: 'real-loopback-store-and-forward', originOffline: true, receiverHasRecord: true });
  } finally { await Promise.all(nodes.map(node => node.stop())); }
});

test('real TCP: ring propagation deduplicates records and an old snapshot cannot undo deletion', async () => {
  const t = trip(), nodes = [];
  try {
    for (let i = 0; i < 4; i++) nodes.push(await replica(t, [{ ...expense(t.id), data: {
      payer: i % 2 ? 'b' : 'a', currency: 'CNY', amount: (i + 1) * 100, shareA: 5000, mode: 'shared'
    } }]));
    for (let i = 0; i < 4; i++) connect(nodes[i], nodes[(i + 1) % 4]);
    await settle(nodes, 4);
    const stale = await nodes[0].browser.api.encryptedBundle(t);
    const target = nodes[0].browser.stored().events.find(value => value.data.amount === 100).id;
    nodes[3].append('delete', { target }); await settle(nodes, 5); same(nodes);
    for (const node of nodes) {
      assert.equal(await node.browser.api.mergeSyncedBundle(t, stale), 0);
      assert.equal(node.browser.api.summary(t).visible.length, 3);
      assert.equal(String(node.browser.api.summary(t).spent.get('CNY')), '900');
    }
    observations.push({ scenario: 'real-loopback-ring', devices: 4, duplicateMoney: false, deletedRecordResurrected: false });
  } finally { await Promise.all(nodes.map(node => node.stop())); }
});

test('real TCP: remaining peers sync after one device exits and the restarted peer catches up', async () => {
  const t = trip(), nodes = [];
  try {
    for (let i = 0; i < 3; i++) nodes.push(await replica(t, [expense(t.id)]));
    connect(nodes[0], nodes[1]); connect(nodes[1], nodes[2]); connect(nodes[2], nodes[0]);
    await settle(nodes, 3);
    const persisted = nodes[1].browser.stored(); await nodes[1].stop();
    partition(...nodes);
    nodes[0].append('expense', { payer: 'a', currency: 'USD', amount: 300, shareA: 5000, mode: 'shared' });
    nodes[2].append('topup', { person: 'b', currency: 'EUR', amount: 900 });
    connect(nodes[0], nodes[2]); await settle([nodes[0],nodes[2]], 5);
    nodes.push(await replica(t, [], persisted));
    connect(nodes[2], nodes[3]); await settle([nodes[0],nodes[2],nodes[3]], 5);
    same([nodes[0],nodes[2],nodes[3]]);
    observations.push({ scenario: 'real-loopback-restart', staleReplicaEvents: 3, recoveredReplicaEvents: 5 });
  } finally { await Promise.all(nodes.map(node => node.stop())); }
});

test('prototype: three-member splits, different funds, repayment and exchange conserve every currency', () => {
  const [log] = newLogs();
  log.append('opening', { memberId: members[0], currency: 'CNY', amount: 10000 });
  log.append('opening', { memberId: members[1], currency: 'CNY', amount: 5000 });
  log.append('opening', { memberId: members[2], currency: 'USD', amount: 2000 });
  log.append('expense', money(101));
  log.append('expense', { payerId: members[1], currency: 'USD', amount: 601, shares: splitMinor(601, members.slice(0,2)) });
  log.append('repayment', { fromId: members[1], toId: members[0], currency: 'CNY', amount: 34 });
  log.append('exchange', { memberId: members[2], fromCurrency: 'USD', fromAmount: 500, toCurrency: 'EUR', toAmount: 450 });
  const totals = log.totals();
  assert.equal(totals.cash[members[0]].CNY, '9933');
  assert.equal(totals.cash[members[1]].CNY, '4966');
  assert.deepEqual(totals.cash[members[2]], { USD: '1500', EUR: '450' });
  assert.deepEqual(totals.spent, { CNY: '101', USD: '601' });
  for (const code of ['CNY', 'USD']) assert.equal(Object.values(totals.net).reduce((sum, net) => sum + BigInt(net[code] || 0), 0n), 0n);
  assert.equal(totals.net[members[0]].CNY, '33'); assert.equal(totals.net[members[2]].CNY, '-33');
  log.members.add(randomUUID()); assert.deepEqual(log.totals(), totals);
  assert.deepEqual(splitMinor(1, members).map(value => value.amount), [1,0,0]);
  observations.push({ scenario: 'prototype-three-members', cents: [34,34,33], totals,
    note: 'Member roster replication, native migration, UI and a settlement transfer planner are not implemented.' });
});

test('prototype: retries and duplicate simulated Wi-Fi/Bluetooth deliveries produce one operation', () => {
  const [a,b] = newLogs(), key = randomBytes(32);
  a.append('expense', money(900));
  const frame = sealOperations(a, a.delta(b.digest()), key);
  // Delivery is simulated here. No Bluetooth or Wi-Fi Direct adapter runs in this process.
  assert.equal(b.receive(openOperations(b, frame, key)), 1);
  assert.equal(b.receive(openOperations(b, frame, key)), 0); // lost ACK, retry via another route
  assert.equal(b.receive(openOperations(b, frame, key)), 0); // original route arrives late
  assert.equal(b.operations.size, 1); assert.deepEqual(b.totals(), a.totals());
});

test('prototype: out-of-order origin sequences retain gaps and deletion can arrive before its target', () => {
  const [a,b] = newLogs();
  const first = a.append('expense', money(101));
  a.append('delete', { target: first.id });
  const third = a.append('topup', { memberId: members[1], currency: 'USD', amount: 500 });
  b.receive([third]); assert.equal(a.delta(b.digest()).length, 2);
  b.receive([a.snapshot().operations[1]]); b.receive([first]);
  assert.deepEqual(b.totals(), a.totals()); assert.equal(b.totals().spent.CNY, undefined);
});

test('prototype: clock skew, partitions and different delivery orders converge without timestamp winners', () => {
  const [a,b,c] = newLogs();
  a.append('expense', money(1000), 1);
  b.append('expense', { ...money(2000), payerId: members[1] }, Date.now() + 90 * 86400000);
  c.append('topup', { memberId: members[2], currency: 'EUR', amount: 700 }, 0);
  b.receive(a.delta(b.digest())); c.receive(b.delta(c.digest()).reverse()); a.receive(c.delta(a.digest())); b.receive(c.delta(b.digest()));
  assert.equal(canonical(a.digest()), canonical(b.digest())); assert.equal(canonical(b.digest()), canonical(c.digest()));
  assert.deepEqual(a.totals(), c.totals());
  const reopened = new OperationLog(a.snapshot()); assert.deepEqual(reopened.totals(), a.totals());
});

test('prototype: wrong keys, other trips and conflicting event IDs are rejected without a partial commit', () => {
  const [a,b] = newLogs(), key = randomBytes(32);
  const entry = a.append('expense', money(100)); b.receive([entry]);
  const frame = sealOperations(a, [entry], key);
  assert.throws(() => openOperations(b, frame, randomBytes(32)));
  assert.throws(() => openOperations(b, { ...frame, tripId: randomUUID() }, key));
  const extra = a.append('topup', { memberId: members[2], currency: 'USD', amount: 100 });
  const conflict = { ...entry, data: money(200) };
  assert.throws(() => b.receive([extra,conflict]), /conflicting event ID/);
  assert.equal(b.operations.size, 1);
  assert.throws(() => a.delta({ ...b.digest(), protocol: 1 }), /incompatible peer/);
});

test('prototype: incremental transfer counts digest metadata as well as encrypted new operations', () => {
  const [a,b] = newLogs(), key = randomBytes(32);
  for (let index = 0; index < 200; index++) a.append('expense', { ...money(1000 + index), note: '虚构骑行餐费' });
  b.receive(a.delta(b.digest()));
  const stableDigestsMatch = canonical(a.digest()) === canonical(b.digest());
  a.append('expense', money(1234));
  const delta = a.delta(b.digest()); assert.equal(delta.length, 1);
  const encryptedDeltaBytes = Buffer.byteLength(JSON.stringify(sealOperations(a, delta, key)));
  const bothDigestBytes = Buffer.byteLength(JSON.stringify(a.digest())) + Buffer.byteLength(JSON.stringify(b.digest()));
  const bothFullSnapshotBytes = Buffer.byteLength(JSON.stringify(sealOperations(a, a.snapshot().operations, key))) +
    Buffer.byteLength(JSON.stringify(sealOperations(b, b.snapshot().operations, key)));
  const incrementalWithIndexBytes = bothDigestBytes + encryptedDeltaBytes;
  assert.ok(incrementalWithIndexBytes < bothFullSnapshotBytes);
  observations.push({ scenario: 'prototype-delta-size', historicalOperations: 200, newOperations: 1, stableDigestsMatch,
    encryptedDeltaBytes, bothDigestBytes, incrementalWithIndexBytes, bothFullSnapshotBytes,
    note: 'Application JSON bytes only. Radio framing, encryption handshake and OS scheduling are not measured; digest indices still grow with history.' });
});
