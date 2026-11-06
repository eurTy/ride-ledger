import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import test from 'node:test';
import { client, trip, expense, state } from './support/web-client.mjs';

test('the browser creates a two-person trip in team mode from the single entry', () => {
  const browser = client();
  const form = browser.element('trip-form');
  form.fields = { name: '骑行', personA: '甲' };
  form.querySelectorAll = () => [{ value: '乙' }];
  assert.match(browser.element('main').innerHTML, /新建小队旅行/);
  form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.deepEqual(browser.stored().trips[0].members, ['甲', '乙']);
  assert.match(browser.element('main').innerHTML, /全员平摊/);
});

test('the browser can save a solo trip without a companion', () => {
  const browser = client();
  const form = browser.element('trip-form');
  form.fields = { name: '独行', personA: '甲' };
  form.listeners.submit({ preventDefault() {}, currentTarget: form });
  assert.deepEqual(browser.stored().trips[0].members, ['甲']);
  browser.run("currentView = 'review'; render()");
  assert.doesNotMatch(browser.element('main').innerHTML, /成员还款/);
});

test('five-person preview settles shared expenses and member repayments by currency', () => {
  const team = { id: randomUUID(), name: '小队骑行', members: ['甲', '乙', '丙', '丁', '戊'], localPerson: 'm1' };
  const browser = client(state([team]));
  browser.run(`
    addEvent('opening', { person: 'm1', currency: 'CNY', amount: 200 });
    addEvent('expense', { payer: 'm5', currency: 'CNY', amount: 101, mode: 'shared',
      shares: teamShares(activeTrip(), 101, 'm5', 'shared') });
    addEvent('expense', { payer: 'm3', currency: 'USD', amount: 500, mode: 'personal',
      shares: teamShares(activeTrip(), 500, 'm3', 'personal') });
    addEvent('transfer', { from: 'm2', to: 'm5', currency: 'CNY', amount: 20 });
    currentView = 'review'; render();
  `);
  const restored = client(browser.stored());
  const summary = restored.api.summary(team);
  assert.deepEqual(browser.stored().events[1].data.shares, { m1: 21, m2: 20, m3: 20, m4: 20, m5: 20 });
  assert.equal(summary.memberCash.get('m1').get('CNY'), 200n);
  assert.equal(summary.memberNet.get('m2').get('CNY'), 0n);
  assert.equal(summary.memberNet.get('m5').get('CNY'), 61n);
  assert.equal(summary.memberNet.get('m3').get('USD'), 0n);
  assert.equal(summary.spent.get('USD'), 500n);
  assert.match(browser.element('main').innerHTML, /甲 应还 戊 0\.21 CNY/);
  assert.match(browser.element('main').innerHTML, /小队结算/);
});

test('the current ledger removes legacy trips and their records while preserving teams', () => {
  const team = { id: randomUUID(), name: '小队骑行', members: ['甲', '乙'], localPerson: 'm1' };
  const dual = trip();
  const current = { id: randomUUID(), tripId: team.id, kind: 'opening', at: 1,
    data: { person: 'm1', currency: 'CNY', amount: 150 } };
  const browser = client(state([dual, team], [expense(dual.id), current]));
  assert.deepEqual(browser.stored().trips, [team]);
  assert.deepEqual(browser.stored().events, [current]);
  assert.match(browser.element('main').innerHTML, /小队骑行/);
  assert.doesNotMatch(browser.element('main').innerHTML, /两人平摊/);
});

test('exchange currencies follow the latest visible record and keep removable local history', () => {
  const browser = client();
  const t = { ...trip(), members: ['甲', '乙'], localPerson: 'm1' };
  browser.api.setState(state([t]));
  assert.deepEqual(JSON.parse(JSON.stringify(browser.run('exchangeDefaults(activeTrip())'))),
    { fromCurrency: 'CNY', toCurrency: 'USD' });

  const first = { id: randomUUID(), tripId: t.id, kind: 'exchange', at: 100,
    data: { person: 'm1', fromCurrency: 'CNY', fromAmount: 1000,
      toCurrency: 'USD', toAmount: 140 } };
  const second = { id: randomUUID(), tripId: t.id, kind: 'exchange', at: 200,
    data: { person: 'm1', fromCurrency: 'USD', fromAmount: 140,
      toCurrency: 'EUR', toAmount: 130 } };
  browser.api.setState(state([t], [first]));
  const nextPrefs = browser.run(`nextExchangePrefs(activeTrip(), ${JSON.stringify(second.data)})`);
  assert.deepEqual(JSON.parse(JSON.stringify(nextPrefs[t.id])),
    { exchangeFromHistory: ['CNY'], exchangeToHistory: ['USD'] });
  const next = state([t], [first, second]);
  next.quickPrefs = JSON.parse(JSON.stringify(nextPrefs));
  browser.api.setState(next);
  assert.deepEqual(JSON.parse(JSON.stringify(browser.run('exchangeDefaults(activeTrip())'))),
    { fromCurrency: 'USD', toCurrency: 'EUR' });
  browser.run(`removeExchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'USD', 'EUR')`);
  assert.equal(browser.run(`exchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'EUR').length`), 0);
  assert.equal(browser.api.getState().events.length, 2);

  const deleted = { id: randomUUID(), tripId: t.id, kind: 'delete', at: 300,
    data: { target: second.id } };
  browser.api.setState({ ...browser.api.getState(), events: [first, second, deleted] });
  assert.deepEqual(JSON.parse(JSON.stringify(browser.run('exchangeDefaults(activeTrip())'))),
    { fromCurrency: 'CNY', toCurrency: 'USD' });
});

test('editing exchange currency immediately remembers the previous code without changing defaults', () => {
  const browser = client();
  const t = { ...trip(), members: ['甲', '乙'], localPerson: 'm1' };
  browser.api.setState(state([t]));
  browser.run("exchangeDraftCurrency = { from: 'CNY', to: 'USD' }");
  browser.run(`rememberExchangeDraftCurrency(${JSON.stringify(t.id)}, 'to', 'EUR')`);
  assert.equal(browser.run(`exchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'EUR').join(',')`),
    'USD');
  browser.run(`rememberExchangeDraftCurrency(${JSON.stringify(t.id)}, 'to', 'JPY')`);
  assert.equal(browser.run(`exchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'JPY').join(',')`),
    'EUR,USD');
  browser.run(`removeExchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'EUR', 'JPY')`);
  assert.equal(browser.run(`exchangeCurrencyHistory(${JSON.stringify(t.id)}, 'to', 'JPY').join(',')`),
    'USD');
  assert.deepEqual(JSON.parse(JSON.stringify(browser.run('exchangeDefaults(activeTrip())'))),
    { fromCurrency: 'CNY', toCurrency: 'USD' });
});

test('trip delete buttons appear only after entering delete mode and require confirmation', () => {
  const a = { ...trip(), members: ['甲', '乙'], localPerson: 'm1' };
  const b = { ...trip(), members: ['甲', '丙'], localPerson: 'm1' };
  const browser = client(state([a, b], [expense(a.id), expense(b.id)]));
  browser.run("currentView = 'trips'; render()");
  const main = browser.element('main');
  assert.doesNotMatch(main.innerHTML, /data-action="delete-trip"/);
  const toggle = { dataset: { action: 'toggle-trip-delete' },
    closest(selector) { return selector === '[data-action]' ? this : null; } };
  main.listeners.click({ target: toggle });
  assert.match(main.innerHTML, /data-action="delete-trip"/);
  assert.match(main.innerHTML, /删除旅行[^>]*>×/);
  const remove = { dataset: { action: 'delete-trip', tripId: a.id },
    matches(selector) { return selector === '[data-action="delete-trip"]'; },
    closest(selector) { return ['[data-trip-id]', '[data-action]'].includes(selector) ? this : null; } };
  main.listeners.click({ target: remove });
  assert.equal(browser.element('delete-trip-dialog').open, true);
  assert.equal(browser.stored().trips.length, 2);
  browser.element('confirm-delete-trip').listeners.click();
  assert.equal(browser.stored().trips.length, 1);
  assert.equal(browser.stored().events.length, 1);
  assert.equal(browser.api.getState().activeTripId, b.id);
  assert.doesNotMatch(main.innerHTML, /data-action="delete-trip"/);
});

test('imported currency text is escaped in settlement HTML', () => {
  const browser = client();
  browser.context.unsafe = new Map([['CNY<svg onload="x">', 500n]]);
  const html = browser.run("teamDebtText({members:['甲', '乙']}, new Map([['m1', unsafe], ['m2', new Map([...unsafe].map(([code, amount]) => [code, -amount]))]]))");
  assert.match(html, /&lt;svg/);
  assert.doesNotMatch(html, /<svg/);
});
