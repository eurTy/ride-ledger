import { randomUUID, webcrypto } from 'node:crypto';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const source = readFileSync(new URL('../../web/app.js', import.meta.url), 'utf8');
const key = 'ride-ledger-web-v1';

export function client(initial = {}) {
  const storage = new Map([[key, JSON.stringify(initial)]]);
  const elements = new Map();
  const windowListeners = new Map(), documentListeners = new Map();
  const listen = registry => (name, callback) => {
    if (!registry.has(name)) registry.set(name, []);
    registry.get(name).push(callback);
  };
  function element(id) {
    if (!elements.has(id)) elements.set(id, {
      value: '', textContent: '', innerHTML: '', hidden: false, listeners: {}, fields: {},
      classList: { toggle(name, value) { this[name] = value; }, add() {}, remove() {} },
      addEventListener(name, callback) { this.listeners[name] = callback; },
      setAttribute(name, value) { this[name] = value; }, removeAttribute(name) { delete this[name]; },
      querySelector(selector) { return element(id === 'join-identity' && selector === 'select'
        ? 'join-select' : id + ':' + selector); },
      querySelectorAll() { return []; },
      closest() { return element('join-dialog'); }, reset() {},
      showModal() { this.open = true; }, close() { this.open = false; }
    });
    return elements.get(id);
  }
  const context = vm.createContext({
    TextEncoder, TextDecoder, btoa, atob, DOMException, AbortController, AbortSignal,
    crypto: webcrypto, history: {}, navigator: { onLine: false },
    localStorage: { getItem: name => storage.get(name) ?? null,
      setItem: (name, value) => storage.set(name, value) },
    document: { getElementById: element,
      querySelector(selector) {
        if (selector === '#join-form textarea[name="code"]') return element('join-code');
        if (selector === '#join-form select[name="localPerson"]') return element('join-select');
        return element(selector);
      }, querySelectorAll: () => [], addEventListener: listen(documentListeners) },
    window: { addEventListener: listen(windowListeners), scrollTo() {} },
    FormData: class { constructor(form) { this.form = form; }
      get(name) {
        if (this.form === element('join-form'))
          return name === 'code' ? element('join-code').value : element('join-select').value;
        return this.form.fields[name] ?? null;
      } },
    requestAnimationFrame(callback) { callback(); }, setInterval() {},
    setTimeout() { return 1; }, clearTimeout() {}
  });
  vm.runInContext(source, context, { filename: 'web/app.js' });
  const run = expression => vm.runInContext(expression, context);
  const api = run('({ base64url, encryptedBundle, decryptBundle, tripToken, syncNow, save, ' +
    'inviteCode, parseInviteCode, addEvent, summary, getState: () => state, setState: next => state = next })');
  const emit = async (name, event = {}, target = 'window') => {
    const registry = target === 'window' ? windowListeners : documentListeners;
    await Promise.all((registry.get(name) || []).map(callback => callback(event)));
  };
  return { api, run, context, element, emit,
    stored: () => JSON.parse(storage.get(key)) };
}

export function trip(localPerson = 'a') {
  return { id: randomUUID(), name: '骑行', personA: '小明', personB: '阿杰',
    secret: Buffer.alloc(32, 7).toString('base64url'), localPerson };
}
export function expense(tripId) {
  return { id: randomUUID(), tripId, kind: 'expense', at: Date.now(),
    data: { payer: 'a', currency: 'CNY', amount: 500, shareA: 5000, mode: 'shared' } };
}
export function state(trips, events = []) {
  return { trips, events, quickPrefs: {}, activeTripId: trips[0]?.id || null, deviceId: randomUUID() };
}
export function join(browser, code, ownRole) {
  browser.run("openDialog('join')");
  const input = browser.element('join-code');
  input.value = code;
  input.listeners.input({ target: input });
  if (ownRole) {
    const chooser = browser.element('join-select');
    chooser.value = ownRole;
    chooser.listeners.change({ target: chooser });
  }
  const form = browser.element('join-form');
  form.listeners.submit({ preventDefault() {}, currentTarget: form });
  return browser.api.getState().trips[0];
}
