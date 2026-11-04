import assert from 'node:assert/strict';
import { readdir } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';
import test from 'node:test';
import { client, trip, expense, state, join } from './support/web-client.mjs';
import { deferred, routeTo, startRelay } from './support/relay.mjs';
import { fixture, expectedSummary, invalidInvites } from './support/scenario.mjs';

const plain = value => JSON.parse(JSON.stringify(value));
const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
const ordered = events => plain(events).sort((a, b) => a.id.localeCompare(b.id));

test('invites retain Unicode names and both sharing directions without changing local identity', () => {
  for (const inviter of ['a', 'b']) {
    const t = { ...trip(inviter), name: '跨国骑行 🏕️', personA: '小明 🚴', personB: '朱桂江' };
    const source = client(state([t]));
    const receiver = client(state([]));
    const added = join(receiver, source.api.inviteCode(t));
    assert.equal(added.id, t.id);
    assert.equal(added.secret, t.secret);
    assert.equal(added.name, t.name);
    assert.equal(added.personA, t.personA);
    assert.equal(added.personB, t.personB);
    assert.equal(added.localPerson, inviter === 'a' ? 'b' : 'a');
    const secondDevice = client(state([]));
    assert.equal(join(secondDevice, source.api.inviteCode(t), inviter).localPerson, inviter);
    const before = plain(receiver.api.getState());
    join(receiver, source.api.inviteCode(t), inviter);
    assert.deepEqual(plain(receiver.api.getState()), before, 'joining twice must not switch roles');
  }
});

test('legacy invites and clipboard line wrapping are accepted consistently', () => {
  const t = trip();
  const legacy = { id: t.id, name: t.name, personA: t.personA, personB: t.personB, secret: t.secret };
  assert.equal(join(client(state([])), encode(legacy)).localPerson, 'b');
  const code = encode({ ...legacy, inviterRole: 'b' });
  for (const width of [17, 39, 40, 63, 64, 65]) {
    const wrapped = '\n  ' + code.match(new RegExp('.{1,' + width + '}', 'g')).join('\r\n') + '  \n';
    const receiver = client(state([]));
    assert.equal(join(receiver, wrapped)?.localPerson, 'a');
  }
});

test('invalid invitation fields are rejected before a travel is added', async t => {
  const valid = { ...trip(), inviterRole: 'a' };
  for (const { name, code } of invalidInvites(valid)) await t.test(name, () => {
    const receiver = client(state([]));
    join(receiver, code);
    assert.equal(receiver.api.getState().trips.length, 0);
    assert.equal(receiver.stored().trips.length, 0);
  });
});

test('a failed local save cannot leave a joined travel only in memory', () => {
  const source = client();
  const receiver = client(state([]));
  receiver.context.localStorage.setItem = () => { throw new DOMException('quota', 'QuotaExceededError'); };
  join(receiver, source.api.inviteCode(trip()));
  assert.equal(receiver.api.getState().trips.length, 0);
  assert.equal(receiver.stored().trips.length, 0);
});

test('an unresponsive connection times out and permits a later sync attempt', async () => {
  const browser = client(state([trip()]));
  browser.context.navigator.onLine = true;
  browser.context.setTimeout = callback => setTimeout(callback, 25);
  browser.context.clearTimeout = clearTimeout;
  browser.context.fetch = (_url, options) => new Promise((_resolve, reject) => {
    options.signal?.addEventListener('abort', () => reject(new DOMException('timeout', 'AbortError')));
  });
  const completed = await Promise.race([
    browser.api.syncNow().then(() => true), delay(180).then(() => false)
  ]);
  assert.equal(completed, true, 'a hung fetch must not hold the syncing flag indefinitely');
  let retried = false;
  browser.context.fetch = async () => { retried = true; return new Response('{"snapshots":[]}'); };
  await browser.api.syncNow();
  assert.equal(retried, true);
});

function pair(relay, data = fixture()) {
  const { t, events } = data;
  const a = client(state([t], events.filter((_, index) => index % 2 === 0)));
  const b = client(state([]));
  join(b, a.api.inviteCode(t));
  b.api.setState({ ...b.api.getState(), events: events.filter((_, index) => index % 2 === 1) });
  a.api.getState().quickPrefs[t.id] = { currency: 'CNY', payer: 'a' };
  b.api.getState().quickPrefs[t.id] = { currency: 'USD', payer: 'b' };
  a.api.save(); b.api.save();
  const paths = [routeTo(relay.origin), routeTo(relay.origin)];
  for (const [index, browser] of [a, b].entries()) browser.context.fetch = paths[index].fetch;
  return { ...data, a, b, paths };
}
async function converge(...browsers) {
  for (const browser of browsers) browser.context.navigator.onLine = true;
  for (let round = 0; round < 3; round++) await Promise.all(browsers.map(browser => browser.api.syncNow()));
}
function checkRecords(browser, t, events) {
  assert.deepEqual(ordered(browser.api.getState().events), ordered(events));
  assert.deepEqual(ordered(browser.stored().events), ordered(events));
  assert.equal(new Set(browser.stored().events.map(event => event.id)).size, events.length);
  assert.equal(browser.stored().trips[0].id, t.id);
}
function summaryOf(browser, t) {
  const summary = browser.api.summary(t);
  return Object.fromEntries(['cashA', 'cashB', 'spent', 'debt'].map(field => [field,
    Object.fromEntries([...summary[field]].map(([code, amount]) => [code, String(amount)]))]));
}
function checkFixture(pair) {
  for (const browser of [pair.a, pair.b]) {
    checkRecords(browser, pair.t, pair.events);
    assert.deepEqual(summaryOf(browser, pair.t), expectedSummary);
  }
  assert.equal(pair.a.stored().trips[0].localPerson, 'a');
  assert.equal(pair.b.stored().trips[0].localPerson, 'b');
  assert.equal(pair.a.stored().quickPrefs[pair.t.id].payer, 'a');
  assert.equal(pair.b.stored().quickPrefs[pair.t.id].payer, 'b');
}

test('real isolated relay: independent network failures converge without ledger errors', async t => {
  const relay = await startRelay();
  t.after(() => relay.cleanup());

  await t.test('relay rejects malformed travel IDs before persisting anything', async () => {
    const id = '-'.repeat(36);
    const response = await fetch(relay.origin + '/sync', { method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trip-Token': 't'.repeat(43) },
      body: JSON.stringify({ tripId: id, deviceId: trip().id, bundle: { tripId: id, iv: 'AAAA', data: 'AAAA' } }) });
    assert.equal(response.status, 400);
    assert.deepEqual(await readdir(relay.directory), []);
  });

  await t.test('offline join, offline entries and app restart before reconnect', async () => {
    const p = pair(relay);
    await p.a.api.syncNow(); await p.b.api.syncNow();
    assert.equal(p.paths[0].requests + p.paths[1].requests, 0);
    p.a = client(p.a.stored()); p.b = client(p.b.stored());
    p.a.context.fetch = p.paths[0].fetch; p.b.context.fetch = p.paths[1].fetch;
    await converge(p.a, p.b);
    checkFixture(p);
  });

  for (const failure of ['offline', '503', 'lost-ack', 'truncated']) {
    await t.test('one device recovers from ' + failure, async () => {
      const p = pair(relay);
      p.paths[0].mode = failure;
      await converge(p.a, p.b);
      assert.equal(p.a.element('sync-badge').classList.waiting, true);
      assert.deepEqual(ordered(p.a.stored().events), ordered(p.events.filter((_, index) => index % 2 === 0)));
      p.paths[0].mode = 'normal';
      await p.a.emit('online');
      await converge(p.a, p.b);
      checkFixture(p);
    });
  }

  await t.test('duplicate and reversed snapshots do not duplicate money or restore deleted entries', async () => {
    const p = pair(relay);
    for (const route of p.paths) route.transform = body => ({ snapshots:
      [...body.snapshots].reverse().flatMap(snapshot => [snapshot, snapshot, snapshot]) });
    await converge(p.a, p.b);
    checkFixture(p);
    assert.equal(p.a.api.summary(p.t).visible.some(event => event.id === p.events[5].id), false);
  });

  await t.test('local entry while a response is delayed survives response and later upload', async () => {
    const p = pair(relay);
    p.paths[0].hold = { entered: deferred(), release: deferred() };
    p.a.context.navigator.onLine = true;
    const request = p.a.api.syncNow();
    await p.paths[0].hold.entered.promise;
    p.a.api.addEvent('expense', { payer: 'a', currency: 'JPY', amount: 17, shareA: 5000,
      mode: 'shared', note: '请求途中新增' });
    const extra = p.a.stored().events.at(-1);
    p.paths[0].hold.release.resolve();
    await request;
    p.paths[0].hold = null;
    await converge(p.a, p.b);
    for (const browser of [p.a, p.b]) checkRecords(browser, p.t, [...p.events, extra]);
    assert.deepEqual(summaryOf(p.a, p.t), summaryOf(p.b, p.t));
  });

  await t.test('repeated sync triggers use one request while the connection is busy', async () => {
    const p = pair(relay);
    p.paths[0].hold = { entered: deferred(), release: deferred() };
    p.a.context.navigator.onLine = true;
    const request = p.a.api.syncNow();
    await p.paths[0].hold.entered.promise;
    await Promise.all(Array.from({ length: 12 }, () => p.a.api.syncNow()));
    assert.equal(p.paths[0].requests, 1);
    p.paths[0].hold.release.resolve(); await request; p.paths[0].hold = null;
    await converge(p.a, p.b); checkFixture(p);
  });

  await t.test('a corrupt snapshot cannot block healthy snapshots from other devices', async () => {
    const p = pair(relay);
    p.a.context.navigator.onLine = true; await p.a.api.syncNow();
    p.paths[1].transform = body => {
      const corrupt = { ...body.snapshots[0], data: 'AAAA' };
      return { snapshots: [corrupt, ...body.snapshots] };
    };
    p.b.context.navigator.onLine = true; await p.b.api.syncNow();
    checkRecords(p.b, p.t, p.events);
    assert.equal(p.b.element('sync-badge').classList.waiting, true);
    p.paths[1].transform = null;
    await converge(p.a, p.b); checkFixture(p);
  });

  await t.test('a wrong key and a separate travel with the same title cannot merge into the original', async () => {
    const p = pair(relay);
    await converge(p.a, p.b);
    const impostor = client(state([{ ...p.t, secret: Buffer.alloc(32, 19).toString('base64url') }],
      [expense(p.t.id)]));
    impostor.context.fetch = routeTo(relay.origin).fetch;
    impostor.context.navigator.onLine = true;
    await impostor.api.syncNow();
    assert.equal(impostor.element('sync-badge').classList.waiting, true);
    assert.equal(impostor.stored().events.length, 1);
    const other = { ...trip(), name: p.t.name };
    const independent = client(state([other], [expense(other.id)]));
    independent.context.fetch = routeTo(relay.origin).fetch;
    independent.context.navigator.onLine = true; await independent.api.syncNow();
    assert.equal(independent.stored().events.length, 1);
    await converge(p.a, p.b); checkFixture(p);
  });

  await t.test('deleting a travel during an outstanding request cannot recreate it', async () => {
    const p = pair(relay);
    p.paths[0].hold = { entered: deferred(), release: deferred() };
    p.a.context.navigator.onLine = true;
    const request = p.a.api.syncNow();
    await p.paths[0].hold.entered.promise;
    p.a.run(`pendingDeleteTripId=${JSON.stringify(p.t.id)}`);
    p.a.element('confirm-delete-trip').listeners.click();
    p.paths[0].hold.release.resolve(); await request;
    assert.equal(p.a.stored().trips.length, 0);
    assert.equal(p.a.stored().events.length, 0);
  });

  await t.test('relay shutdown and restart preserve uploads and retry offline additions', async () => {
    const p = pair(relay);
    p.a.context.navigator.onLine = true; await p.a.api.syncNow();
    await relay.stop();
    p.b.context.navigator.onLine = true; await p.b.api.syncNow();
    assert.equal(p.b.element('sync-badge').classList.waiting, true);
    await relay.start();
    await converge(p.a, p.b); checkFixture(p);
  });

  await t.test('different relay addresses keep data separate until both devices use the same service', async () => {
    const otherRelay = await startRelay();
    try {
      const p = pair(relay);
      p.paths[1].origin = otherRelay.origin;
      await converge(p.a, p.b);
      assert.equal(p.a.stored().events.length + p.b.stored().events.length, p.events.length);
      p.paths[1].origin = relay.origin;
      await converge(p.a, p.b); checkFixture(p);
    } finally { await otherRelay.cleanup(); }
  });
});
