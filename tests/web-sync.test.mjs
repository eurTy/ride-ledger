import assert from 'node:assert/strict';
import { randomUUID, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../web/app.js', import.meta.url), 'utf8');
const key = 'ride-ledger-web-v1';

function client(initial = {}) {
  const storage = new Map([[key, JSON.stringify(initial)]]);
  const elements = new Map();
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: '', textContent: '', innerHTML: '', hidden: false, listeners: {},
      classList: { toggle(name, value) { this[name] = value; }, add() {}, remove() {} },
      addEventListener(name, callback) { this.listeners[name] = callback; },
      querySelector(selector) { return element(id === 'join-identity' && selector === 'select'
        ? 'join-select' : id + ':' + selector); },
      querySelectorAll() { return []; },
      closest() { return element('join-dialog'); }, reset() {}, showModal() {}, close() {}
    });
    return elements.get(id);
  }
  const context = vm.createContext({
    TextEncoder, TextDecoder, btoa, atob, DOMException,
    crypto: webcrypto, history: {}, navigator: { onLine: false },
    localStorage: { getItem: name => storage.get(name) ?? null,
      setItem: (name, value) => storage.set(name, value) },
    document: { getElementById: element,
      querySelector(selector) {
        if (selector === '#join-form textarea[name="code"]') return element('join-code');
        if (selector === '#join-form select[name="localPerson"]') return element('join-select');
        return element(selector);
      }, querySelectorAll: () => [], addEventListener() {} },
    window: { addEventListener() {}, scrollTo() {} },
    FormData: class { constructor(form) { this.form = form; }
      get(name) { return name === 'code' ? element('join-code').value : element('join-select').value; } },
    requestAnimationFrame(callback) { callback(); }, setInterval() {},
    setTimeout() { return 1; }, clearTimeout() {}
  });
  vm.runInContext(source, context, { filename: 'web/app.js' });
  const run = expression => vm.runInContext(expression, context);
  const api = run('({ base64url, encryptedBundle, syncNow, save, inviteCode, getState: () => state, setState: next => state = next })');
  return { api, run, context, element, stored: () => JSON.parse(storage.get(key)) };
}

function trip(localPerson = 'a') {
  return { id: randomUUID(), name: '骑行', personA: '小明', personB: '阿杰',
    secret: Buffer.alloc(32, 7).toString('base64url'), localPerson };
}
function expense(tripId) {
  return { id: randomUUID(), tripId, kind: 'expense', at: Date.now(),
    data: { payer: 'a', currency: 'CNY', amount: 500, shareA: 5000, mode: 'shared' } };
}
function state(trips, events = []) {
  return { trips, events, quickPrefs: {}, activeTripId: trips[0]?.id || null, deviceId: randomUUID() };
}

test('a large encrypted ledger can be encoded and decoded', async () => {
  const browser = client();
  const t = trip();
  const events = Array.from({ length: 5000 }, () => expense(t.id));
  browser.api.setState(state([t], events));
  const bundle = await browser.api.encryptedBundle(t);
  const received = await browser.run('decryptBundle')(t, bundle);
  assert.equal(received.length, 5000);
  assert.deepEqual(JSON.parse(JSON.stringify(received[0])), events[0]);
});

test('records from an earlier trip survive a later sync failure and retry', async () => {
  const browser = client();
  const a = trip(), b = trip();
  const entry = expense(a.id);
  browser.api.setState(state([a], [entry]));
  const remote = await browser.api.encryptedBundle(a);
  browser.api.setState(state([a, b]));
  browser.api.save();
  browser.context.navigator.onLine = true;
  let fail = true;
  browser.context.fetch = async (_url, options) => {
    const { tripId } = JSON.parse(options.body);
    if (tripId === b.id && fail) throw new Error('network lost');
    return { ok: true, json: async () => ({ snapshots: tripId === a.id ? [remote] : [] }) };
  };
  await browser.api.syncNow();
  assert.deepEqual(browser.stored().events, [entry]);
  assert.equal(browser.element('sync-badge').classList.waiting, true);
  fail = false;
  await browser.api.syncNow();
  assert.equal(browser.stored().events.length, 1);
});

test('a failed trip does not stop a healthy trip from syncing', async () => {
  const browser = client();
  const a = trip(), b = trip();
  const entry = expense(b.id);
  browser.api.setState(state([b], [entry]));
  const remote = await browser.api.encryptedBundle(b);
  browser.api.setState(state([a, b]));
  browser.context.navigator.onLine = true;
  browser.context.fetch = async (_url, options) => {
    const { tripId } = JSON.parse(options.body);
    return tripId === a.id ? { ok: false, status: 403 } :
      { ok: true, json: async () => ({ snapshots: [remote] }) };
  };
  await browser.api.syncNow();
  assert.deepEqual(browser.stored().events, [entry]);
  assert.equal(browser.element('sync-badge').classList.waiting, true);
});

test('failed storage write leaves imported records eligible for retry', async () => {
  const browser = client();
  const t = trip();
  const entry = expense(t.id);
  browser.api.setState(state([t], [entry]));
  const remote = await browser.api.encryptedBundle(t);
  browser.api.setState(state([t]));
  browser.api.save();
  browser.context.navigator.onLine = true;
  browser.context.fetch = async () => ({ ok: true, json: async () => ({ snapshots: [remote] }) });
  const write = browser.context.localStorage.setItem;
  let fail = true;
  browser.context.localStorage.setItem = (name, value) => {
    if (fail) { fail = false; throw new DOMException('quota', 'QuotaExceededError'); }
    write(name, value);
  };
  await browser.api.syncNow();
  assert.equal(browser.api.getState().events.length, 0);
  assert.equal(browser.stored().events.length, 0);
  await browser.api.syncNow();
  assert.deepEqual(browser.stored().events, [entry]);
});

test('invite defaults to the other person and allows my second device', () => {
  const browser = client();
  const created = trip('a');
  const code = browser.api.inviteCode(created);
  browser.run("openDialog('join')");
  const input = browser.element('join-code');
  input.value = code;
  input.listeners.input({ target: input });
  const chooser = browser.element('join-select');
  assert.equal(chooser.value, 'b');
  assert.match(browser.element('join-preview').textContent, /阿杰/);
  chooser.value = 'a';
  chooser.listeners.change({ target: chooser });
  assert.match(browser.element('join-preview').textContent, /以“小明”加入/);
  const form = browser.element('join-form');
  form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.equal(browser.api.getState().trips[0].localPerson, 'a');
});

test('imported currency text is escaped in settlement HTML', () => {
  const browser = client();
  browser.context.unsafe = new Map([['CNY<svg onload="x">', 500n]]);
  const html = browser.run("debtText({personA:'甲', personB:'乙'}, unsafe)");
  assert.match(html, /&lt;svg/);
  assert.doesNotMatch(html, /<svg/);
});
