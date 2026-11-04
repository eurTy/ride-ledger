import test from 'node:test';
import assert from 'node:assert/strict';
import { setTimeout as delay } from 'node:timers/promises';
import { client, trip, expense, state } from './support/web-client.mjs';

function native(initial, incoming = []) {
  let stored = JSON.stringify(initial), exchanges = 0;
  return {
    loadState: () => stored,
    writeState: value => { stored = value; },
    onRemoteChanged() {}, copyText: async () => {}, shareInvite: async () => {},
    exchangeLan: async () => { exchanges++; return { bundles: incoming.splice(0), addresses: [], status: 'connected' }; },
    exchanges: () => exchanges
  };
}

test('iOS commits to the native ledger file and a failed write cannot alter in-memory money', () => {
  const t = trip(), bridge = native(state([t]));
  const app = client({}, { iosBridge: bridge });
  app.context.localStorage.setItem = () => { throw new Error('WebKit storage must not be authoritative'); };
  app.api.addEvent('opening', { person: 'a', currency: 'USD', amount: 1000 });
  assert.equal(app.stored().events.length, 1);
  const before = JSON.parse(JSON.stringify(app.api.getState()));
  bridge.writeState = () => { throw new Error('native disk full'); };
  assert.throws(() => app.api.addEvent('expense', { payer: 'b', currency: 'USD', amount: 500, shareA: 5000 }), /disk full/);
  assert.deepEqual(JSON.parse(JSON.stringify(app.api.getState())), before);
});

test('iOS exchanges encrypted LAN records while offline without posting to a nonexistent local relay', async () => {
  const t = trip(), entry = expense(t.id);
  const sender = client(state([t], [entry]));
  const incoming = [await sender.api.encryptedBundle(t)];
  const bridge = native(state([{ ...t, localPerson: 'b' }]), incoming);
  const app = client({}, { iosBridge: bridge });
  app.context.fetch = () => { throw new Error('a local-only iOS trip must not use HTTP relay'); };
  for (let i = 0; i < 30 && app.run('syncing'); i++) await delay(5);
  await app.api.syncNow();
  assert.equal(app.context.navigator.onLine, false);
  assert.ok(bridge.exchanges() >= 1);
  assert.equal(app.stored().events[0].id, entry.id);
  await app.api.syncNow();
  assert.equal(app.stored().events.length, 1);
});

test('iOS rejects a native read failure instead of replacing the existing ledger with an empty one', () => {
  const bridge = native(state([trip()]));
  bridge.loadState = () => { throw new Error('native ledger unavailable'); };
  assert.throws(() => client({}, { iosBridge: bridge }), /native ledger unavailable/);
});
