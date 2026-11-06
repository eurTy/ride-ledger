const STORAGE_KEY = 'ride-ledger-web-v1';
if ('scrollRestoration' in history) history.scrollRestoration = 'manual';
let state;
try { state = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}'); }
catch { state = {}; }
state.trips ||= [];
state.events ||= [];
state.quickPrefs ||= {};
state.deviceId ||= crypto.randomUUID();
const teamIds = new Set(state.trips.filter(isTeam).map(trip => trip.id));
if (state.trips.some(trip => !isTeam(trip))) {
  state.trips = state.trips.filter(isTeam);
  state.events = state.events.filter(event => teamIds.has(event.tripId));
  state.quickPrefs = Object.fromEntries(Object.entries(state.quickPrefs).filter(([id]) => teamIds.has(id)));
  save();
}
if (!state.trips.some(trip => trip.id === state.activeTripId))
  state.activeTripId = state.trips[0]?.id || null;
let currentView = 'quick';
let recordFilter = 'all';
let deletingTrips = false;
let pendingDeleteTripId = null;
let exchangeDraftCurrency = null;
const desktop = window.rideLedgerDesktop;
let teamSync = null;
let teamInviteDraft = null;
let desktopSyncPending = false;
const RECORD_FILTERS = [
  { key: 'all', label: '全部' },
  { key: 'shared', label: '共同支出' },
  { key: 'opening', label: '原始资金' },
  { key: 'exchange', label: '换汇记录' }
];
function normalizeEventCurrency(event) {
  if (!event?.data) return false;
  let changed = false;
  for (const field of ['currency', 'fromCurrency', 'toCurrency']) {
    if (String(event.data[field] || '').toUpperCase() === 'RMB') {
      event.data[field] = 'CNY';
      changed = true;
    }
  }
  return changed;
}
let migratedCurrency = false;
for (const event of state.events) migratedCurrency = normalizeEventCurrency(event) || migratedCurrency;
if (migratedCurrency) localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
let syncing = false;
let toastTimer;

function save(nextState = state) { localStorage.setItem(STORAGE_KEY, JSON.stringify(nextState)); }
function activeTrip() { return state.trips.find(trip => trip.id === state.activeTripId); }
const newTripLabel = '新建小队旅行';
const joinTeamButton = '<button class="button" data-action="join-team">加入小队旅行</button>';
const initialMemberCount = 1;
function isTeam(trip) { return Array.isArray(trip?.members); }
function tripMembers(trip) {
  return trip.members.map((name, index) => ['m' + (index + 1), name]);
}
function personName(trip, person) {
  return tripMembers(trip).find(([id]) => id === person)?.[1] || '未知成员';
}
function personChoiceLabel(trip, person) {
  return personName(trip, person) + (trip.localPerson === person ? '（我）' : '');
}
function memberOptions(trip, selected = trip.localPerson) {
  return tripMembers(trip).map(([id]) => '<option value="' + id + '"' +
    (id === selected ? ' selected' : '') + '>' + escapeHtml(personChoiceLabel(trip, id)) + '</option>').join('');
}
function eventsFor(id) { return state.events.filter(event => event.tripId === id); }
function escapeHtml(value) {
  return String(value ?? '').replace(/[&<>"']/g, character =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
}
function currencyCode(raw) {
  const entered = String(raw).trim().toUpperCase();
  const code = entered === 'RMB' ? 'CNY' : entered;
  if (!/^[A-Z]{3}$/.test(code)) throw new Error('请输入三位货币代码，例如 CNY');
  return code;
}
function quickCurrency(prefs) {
  try { return currencyCode(prefs.currency || 'CNY'); }
  catch { return 'CNY'; }
}
function quickCurrencyHistory(prefs) {
  const current = quickCurrency(prefs);
  const legacy = Array.isArray(prefs.currencies) ? prefs.currencies : [];
  const stored = Array.isArray(prefs.currencyHistory) ? prefs.currencyHistory
    : [...legacy, ...(current === 'CNY' ? [] : ['CNY'])];
  const codes = [];
  for (const raw of stored) {
    try {
      const code = currencyCode(raw);
      if (code !== current && !codes.includes(code)) codes.push(code);
    } catch { /* Skip malformed historical values. */ }
  }
  return codes.slice(0, 5);
}
function rememberQuickCurrency(tripId, raw) {
  const currency = currencyCode(raw);
  const prefs = state.quickPrefs[tripId] ||= {};
  const previous = quickCurrency(prefs);
  if (currency !== previous) {
    prefs.currencyHistory = [previous, ...quickCurrencyHistory(prefs)]
      .filter((code, index, codes) => code !== currency && codes.indexOf(code) === index).slice(0, 5);
    prefs.currency = currency;
    delete prefs.currencies;
    save();
  }
  return currency;
}
function removeQuickCurrencyHistory(tripId, raw) {
  const code = currencyCode(raw);
  const prefs = state.quickPrefs[tripId] || {};
  const history = quickCurrencyHistory(prefs);
  if (!history.includes(code)) return;
  const nextPrefs = { ...prefs, currencyHistory: history.filter(item => item !== code) };
  delete nextPrefs.currencies;
  const nextState = { ...state, quickPrefs: { ...state.quickPrefs, [tripId]: nextPrefs } };
  save(nextState);
  state = nextState;
}
function currencyHistoryItems(history) {
  return history.map(code => '<div class="currency-history-item">' +
    '<button type="button" data-currency-history="' + code + '">' + code + '</button>' +
    '<button type="button" class="currency-history-remove" data-currency-history-remove="' + code +
    '" aria-label="移除曾用货币 ' + code + '">×</button></div>').join('');
}
function exchangeDefaults(trip) {
  for (const event of summary(trip).visible) {
    if (event.kind !== 'exchange') continue;
    try {
      const fromCurrency = currencyCode(event.data.fromCurrency);
      const toCurrency = currencyCode(event.data.toCurrency);
      if (fromCurrency !== toCurrency) return { fromCurrency, toCurrency };
    } catch { /* Skip a malformed imported record. */ }
  }
  return { fromCurrency: 'CNY', toCurrency: 'USD' };
}
function exchangeHistoryKey(field) {
  return field === 'from' ? 'exchangeFromHistory' : 'exchangeToHistory';
}
function exchangeCurrencyHistory(tripId, field, current) {
  const prefs = state.quickPrefs[tripId] || {};
  const stored = prefs[exchangeHistoryKey(field)];
  const history = [];
  let selected = '';
  try { selected = currencyCode(current); } catch { /* Allow a partial input. */ }
  for (const raw of Array.isArray(stored) ? stored : []) {
    try {
      const code = currencyCode(raw);
      if (code !== selected && !history.includes(code)) history.push(code);
    } catch { /* Skip an invalid saved code. */ }
  }
  return history.slice(0, 5);
}
function rememberExchangeDraftCurrency(tripId, field, raw) {
  const selected = currencyCode(raw);
  const previous = exchangeDraftCurrency?.[field];
  if (!previous || selected === previous) return selected;
  const key = exchangeHistoryKey(field);
  const prefs = { ...(state.quickPrefs[tripId] || {}) };
  prefs[key] = [previous, ...exchangeCurrencyHistory(tripId, field, previous)]
    .filter((code, index, codes) => code !== selected && codes.indexOf(code) === index).slice(0, 5);
  const nextState = { ...state, quickPrefs: { ...state.quickPrefs, [tripId]: prefs } };
  save(nextState);
  state = nextState;
  exchangeDraftCurrency[field] = selected;
  return selected;
}
function nextExchangePrefs(trip, data) {
  const previous = exchangeDefaults(trip);
  const prefs = { ...(state.quickPrefs[trip.id] || {}) };
  for (const field of ['from', 'to']) {
    const key = exchangeHistoryKey(field);
    const before = exchangeDraftCurrency?.[field] || previous[field + 'Currency'];
    const selected = data[field + 'Currency'];
    const candidates = before === selected ? exchangeCurrencyHistory(trip.id, field, before) :
      [before, ...exchangeCurrencyHistory(trip.id, field, before)];
    prefs[key] = candidates.filter((code, index, codes) =>
      code !== selected && codes.indexOf(code) === index).slice(0, 5);
  }
  return { ...state.quickPrefs, [trip.id]: prefs };
}
function removeExchangeCurrencyHistory(tripId, field, raw, current) {
  const code = currencyCode(raw);
  const history = exchangeCurrencyHistory(tripId, field, current);
  if (!history.includes(code)) return;
  const prefs = { ...(state.quickPrefs[tripId] || {}),
    [exchangeHistoryKey(field)]: history.filter(item => item !== code) };
  const nextState = { ...state, quickPrefs: { ...state.quickPrefs, [tripId]: prefs } };
  save(nextState);
  state = nextState;
}
function exchangeHistoryItems(history, field) {
  return history.map(code => '<div class="currency-history-item">' +
    '<button type="button" data-exchange-history-pick="' + field + '" data-currency-code="' +
    code + '">' + code + '</button>' +
    '<button type="button" class="currency-history-remove" data-exchange-history-remove="' + field +
    '" data-currency-code="' + code + '" aria-label="移除曾用货币 ' + code + '">×</button></div>').join('');
}
function refreshExchangeHistory(field, keepOpen = false) {
  const control = document.querySelector('#exchange-form [data-exchange-field="' + field + '"]');
  const input = control.querySelector('input');
  const menu = control.parentElement.querySelector('.currency-history-menu');
  const toggle = control.querySelector('.currency-history-toggle');
  const history = exchangeCurrencyHistory(activeTrip().id, field, input.value);
  const open = keepOpen && !menu.hidden && history.length > 0;
  toggle.hidden = history.length === 0;
  toggle.setAttribute('aria-expanded', String(open));
  menu.hidden = !open;
  menu.innerHTML = exchangeHistoryItems(history, field);
}
function refreshQuickCurrencyHistory(keepOpen = false) {
  const form = document.getElementById('quick-expense-form');
  if (!form) return;
  const history = quickCurrencyHistory(state.quickPrefs[activeTrip().id] || {});
  const toggle = form.querySelector('.currency-history-toggle');
  const menu = form.querySelector('.currency-history-menu');
  const open = keepOpen && !menu.hidden && history.length > 0;
  toggle.hidden = history.length === 0;
  toggle.setAttribute('aria-expanded', String(open));
  menu.hidden = !open;
  menu.innerHTML = currencyHistoryItems(history);
}
function scale(code) {
  try {
    return new Intl.NumberFormat('en', { style: 'currency', currency: code })
      .resolvedOptions().maximumFractionDigits;
  } catch { return 2; }
}
function parseAmount(raw, code) {
  const match = String(raw).trim().match(/^(\d+)(?:\.(\d+))?$/);
  if (!match) throw new Error('请输入有效的正数金额');
  const digits = scale(code);
  const fraction = match[2] || '';
  if (fraction.length > digits) throw new Error(code + ' 最多保留 ' + digits + ' 位小数');
  const value = BigInt(match[1]) * 10n ** BigInt(digits) +
    BigInt(fraction.padEnd(digits, '0') || '0');
  if (value <= 0n || value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('金额超出范围');
  return Number(value);
}
function formatAmount(minor, code) {
  const digits = scale(code);
  const sign = minor < 0n ? '-' : '';
  const absolute = minor < 0n ? -minor : minor;
  const base = 10n ** BigInt(digits);
  const fraction = digits ? '.' + String(absolute % base).padStart(digits, '0') : '';
  return sign + String(absolute / base) + fraction + ' ' + code;
}
function addEvent(kind, data, quickPrefs = state.quickPrefs) {
  const trip = activeTrip();
  if (!trip) throw new Error('请先选择旅行');
  if (!canRecord(trip)) throw new Error('请先完成首次登记');
  const entry = { id: crypto.randomUUID(), tripId: trip.id, kind, at: Date.now(), data };
  if (!validPeerEvent(entry, trip)) throw new Error('请检查金额、币种和成员');
  const nextState = { ...state, events: state.events.concat(entry), quickPrefs };
  save(nextState);
  state = nextState;
  render();
  syncNow();
}
function addTo(map, code, delta) { map.set(code, (map.get(code) || 0n) + delta); }
function teamShares(trip, amount, payer, mode) {
  if (mode === 'personal') return { [payer]: amount };
  const members = tripMembers(trip);
  const count = BigInt(members.length), minor = BigInt(amount);
  return Object.fromEntries(members.map(([id], index) => [id, Number(minor / count +
    (BigInt(index) < minor % count ? 1n : 0n))]));
}
function summary(trip) {
  const members = tripMembers(trip);
  const memberCash = new Map(members.map(([id]) => [id, new Map()]));
  const memberNet = new Map(members.map(([id]) => [id, new Map()]));
  const result = { memberCash, memberNet, spent: new Map(), visible: [] };
  const all = eventsFor(trip.id).sort((a, b) => a.at - b.at || a.id.localeCompare(b.id));
  const deleted = new Set(all.filter(event => event.kind === 'delete').map(event => event.data.target));
  const cash = (person, code, delta) => addTo(memberCash.get(person), code, delta);
  for (const event of all) {
    if (event.kind === 'delete' || deleted.has(event.id)) continue;
    const d = event.data;
    try {
      if (event.kind === 'opening' || event.kind === 'topup') {
        cash(d.person, d.currency, BigInt(d.amount));
      } else if (event.kind === 'expense') {
        const amount = BigInt(d.amount);
        if (!memberCash.has(d.payer)) continue;
        const shares = Object.entries(d.shares).map(([person, minor]) => [memberNet.get(person), BigInt(minor)]);
        if (shares.some(([balance, minor]) => !balance || minor < 0n) ||
            shares.reduce((total, [, minor]) => total + minor, 0n) !== amount) continue;
        addTo(memberNet.get(d.payer), d.currency, amount);
        for (const [balance, minor] of shares) addTo(balance, d.currency, -minor);
        cash(d.payer, d.currency, -amount);
        addTo(result.spent, d.currency, amount);
      } else if (event.kind === 'transfer') {
        const amount = BigInt(d.amount);
        const to = d.to;
        if (!memberCash.has(d.from) || !memberCash.has(to) || to === d.from) continue;
        cash(d.from, d.currency, -amount);
        cash(to, d.currency, amount);
        addTo(memberNet.get(d.from), d.currency, amount);
        addTo(memberNet.get(to), d.currency, -amount);
      } else if (event.kind === 'exchange') {
        cash(d.person, d.fromCurrency, -BigInt(d.fromAmount));
        cash(d.person, d.toCurrency, BigInt(d.toAmount));
      }
      result.visible.push(event);
    } catch { /* Ignore a malformed imported event. */ }
  }
  result.visible.reverse();
  return result;
}
function balanceText(map, empty = '尚未填写') {
  return map.size ? [...map].sort(([a], [b]) => a.localeCompare(b))
    .map(([code, amount]) => formatAmount(amount, code)).join('、') : empty;
}
function teamDebtText(trip, net) {
  const members = tripMembers(trip);
  const codes = new Set([...net.values()].flatMap(balance => [...balance.keys()]));
  const lines = [];
  for (const code of [...codes].sort()) {
    const amounts = members.map(([id]) => net.get(id).get(code) || 0n);
    for (let from = 0; from < members.length; from++) {
      for (let to = 0; to < members.length && amounts[from] < 0n; to++) {
        if (amounts[to] <= 0n) continue;
        const amount = -amounts[from] < amounts[to] ? -amounts[from] : amounts[to];
        amounts[from] += amount;
        amounts[to] -= amount;
        lines.push(escapeHtml(members[from][1]) + ' 应还 ' + escapeHtml(members[to][1]) +
          ' ' + escapeHtml(formatAmount(amount, code)));
      }
    }
  }
  return lines.length ? lines.join('<br>') : '目前已结清';
}
function eventLabel(trip, event) {
  const d = event.data;
  const name = person => personName(trip, person);
  if (event.kind === 'opening') return name(d.person) + ' · 原始资金';
  if (event.kind === 'topup') return name(d.person) + ' · 补充现金';
  if (event.kind === 'expense') return name(d.payer) + ' · ' + (d.mode === 'personal' ? '个人支出' : '共同支出');
  if (event.kind === 'transfer') return name(d.from) + ' · 还款' + '给 ' + name(d.to);
  if (event.kind === 'exchange') return name(d.person) + ' · 换汇';
  return '记录';
}
function eventAmount(event) {
  const d = event.data;
  if (event.kind === 'exchange') return formatAmount(BigInt(d.fromAmount), d.fromCurrency) +
    ' → ' + formatAmount(BigInt(d.toAmount), d.toCurrency);
  return formatAmount(BigInt(d.amount), d.currency);
}
function matchesRecordFilter(event) {
  if (recordFilter === 'all') return true;
  if (recordFilter === 'shared')
    return event.kind === 'expense' && event.data.mode !== 'personal';
  return event.kind === recordFilter;
}
function recordTime(timestamp) {
  const date = new Date(timestamp);
  const two = value => String(value).padStart(2, '0');
  return date.getFullYear() + '-' + two(date.getMonth() + 1) + '-' + two(date.getDate()) +
    ' ' + two(date.getHours()) + ':' + two(date.getMinutes());
}
function recordEntries(trip, visible) {
  const records = visible.filter(matchesRecordFilter);
  const entries = records.map(event =>
    '<div class="entry"><div><div class="entry-name">' + escapeHtml(eventLabel(trip, event)) +
    '</div><div class="entry-meta">' + escapeHtml(recordTime(event.at)) +
    (event.data.note ? ' · ' + escapeHtml(event.data.note) : '') +
    '</div></div><div class="entry-right"><span class="entry-amount">' +
    escapeHtml(eventAmount(event)) + '</span><button class="delete" title="删除记录" data-action="delete" data-event-id="' +
    escapeHtml(event.id) + '">×</button></div></div>').join('');
  const empty = recordFilter === 'all'
    ? '还没有记录。旅途中记下支出，之后在这里复盘。'
    : '暂无' + RECORD_FILTERS.find(filter => filter.key === recordFilter).label + '，可在“全部”查看其他流水。';
  return { count: records.length, html: entries || '<div class="empty">' + empty + '</div>' };
}
function updateRecordFilter(trip) {
  const visible = summary(trip).visible;
  const records = recordEntries(trip, visible);
  document.querySelector('.entries').innerHTML = records.html;
  document.getElementById('record-count').textContent =
    (recordFilter === 'all' ? records.count : records.count + ' / ' + visible.length) + ' 笔';
  document.querySelectorAll('.record-filter').forEach(button => {
    const selected = button.dataset.recordFilter === recordFilter;
    button.classList.toggle('active', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
}
function renderQuick(trip) {
  const members = tripMembers(trip);
  const prefs = state.quickPrefs[trip.id] || {};
  const selectedCurrency = quickCurrency(prefs);
  const history = quickCurrencyHistory(prefs);
  const payer = members.some(([id]) => id === prefs.payer) ? prefs.payer : trip.localPerson;
  const mode = prefs.mode === 'personal' ? 'personal' : 'shared';
  const payerControl = '<div class="split-select"><select name="payer" aria-label="谁付款">' +
    memberOptions(trip, payer) + '</select></div>';
  document.getElementById('main').innerHTML =
    '<section class="quick-screen"><div class="quick-heading"><span class="eyebrow">当前旅行</span><h1>' +
    escapeHtml(trip.name) + '</h1><p>记下刚才的支出，其他数据稍后再看。</p></div>' +
    '<form id="quick-expense-form" class="quick-card">' +
    '<div class="amount-row"><label class="amount-field">支付金额' +
    '<input name="amount" inputmode="decimal" autocomplete="off" required placeholder="0.00" aria-label="支付金额"></label>' +
    '<div class="currency-field"><label for="quick-currency">货币</label><div class="currency-control">' +
    '<input id="quick-currency" name="currency" maxlength="3" required autocomplete="off" spellcheck="false"' +
    ' aria-label="货币代码" value="' + selectedCurrency + '">' +
    '<button type="button" class="currency-history-toggle" aria-label="选择曾用货币" aria-expanded="false"' +
    (history.length ? '' : ' hidden') + '>⌄</button>' +
    '<div class="currency-history-menu" role="group" aria-label="曾用货币" hidden>' +
    currencyHistoryItems(history) +
    '</div></div></div></div>' +
    '<div class="routing-card"><div class="payer-row" role="group" aria-label="谁付款">' +
    '<span class="routing-label">谁付款</span>' + payerControl + '</div>' +
    '<div class="split-row"><label for="quick-mode" class="routing-label">怎么分摊</label>' +
    '<div class="split-select"><select id="quick-mode" name="mode" aria-label="怎么分摊">' +
    '<option value="shared" ' + (mode === 'shared' ? 'selected' : '') + '>' +
    '全员平摊</option>' +
    '<option value="personal" ' + (mode === 'personal' ? 'selected' : '') + '>仅付款人</option>' +
    '</select></div></div></div>' +
    '<label class="note-field">备注 <small>可选</small><input name="note" placeholder="例如：房费、饭费"></label>' +
    '<button class="button primary save-expense" type="submit">保存这笔支出</button></form>' +
    '<p class="quick-footnote">' + (desktop ? '无网时先记在本机，附近连通后自动补传。' : '小队试用账目保存在当前浏览器。') + '</p></section>';
}
function renderReview(trip) {
  const sum = summary(trip);
  const records = recordEntries(trip, sum.visible);
  const balances = tripMembers(trip).map(([id, name]) => '<div class="balance-row"><span>' +
    escapeHtml(name) + '</span><strong>' + escapeHtml(balanceText(sum.memberCash.get(id))) + '</strong></div>').join('');
  const settlement = teamDebtText(trip, sum.memberNet);
  document.getElementById('main').innerHTML =
    '<div class="page-heading"><span class="eyebrow">TRIP REVIEW</span><h1>旅行复盘</h1><p>' +
    escapeHtml(trip.name) + '</p></div>' +
    '<div class="cards"><div class="card"><h3>总支出</h3><strong class="stat-value">' +
    escapeHtml(balanceText(sum.spent, '暂无支出')) + '</strong></div>' +
    '<div class="card"><h3>各自携带现金余额</h3>' + balances + '</div><div class="card"><h3>' +
    '小队结算' + '</h3><div class="settlement">' + settlement +
    '</div><p class="muted">不同货币分别结算，原始资金不产生欠款。</p></div></div>' +
    '<div class="section-title"><h2>现金与结算</h2></div><div class="action-row">' +
    '<button class="button" data-action="opening">填写原始资金</button>' +
    '<button class="button" data-action="topup">补充现金</button>' +
    (tripMembers(trip).length > 1 ? '<button class="button" data-action="transfer">' +
      '成员还款' + '</button>' : '') +
    '<button class="button" data-action="exchange">记录换汇</button></div>' +
    '<div class="section-title"><h2>旅行记录</h2><span class="muted" id="record-count">' +
    (recordFilter === 'all' ? records.count : records.count + ' / ' + sum.visible.length) +
    ' 笔</span></div><div class="record-filters" role="group" aria-label="筛选旅行记录">' +
    RECORD_FILTERS.map(filter => '<button type="button" class="record-filter' +
      (filter.key === recordFilter ? ' active' : '') + '" data-action="record-filter" data-record-filter="' +
      filter.key + '" aria-pressed="' + (filter.key === recordFilter) + '">' + filter.label + '</button>').join('') +
    '</div><div class="entries">' + records.html + '</div>' +
    '<p class="sync-note">' + (desktop ? '流水保存在本机。' : '小队试用账目保存在当前浏览器。') + '</p>';
}
function renderTrips(trip) {
  const cards = state.trips.map(item =>
    '<div class="trip-item-row"><button class="trip-item ' + (item.id === trip.id ? 'active' : '') +
    '" data-trip-id="' + escapeHtml(item.id) + '"><span><strong>' + escapeHtml(item.name) +
    '</strong><small>我：' + escapeHtml(personName(item, item.localPerson)) + ' · ' +
    item.members.length + ' 人小队' +
    '</small></span><em>' + (item.id === trip.id ? '当前旅行' : '切换') + '</em></button>' +
    (deletingTrips ? '<button type="button" class="trip-delete" data-action="delete-trip" data-trip-id="' +
      escapeHtml(item.id) + '" aria-label="删除旅行 ' + escapeHtml(item.name) + '">×</button>' : '') +
    '</div>').join('');
  const layout =
    '<div class="page-heading"><span class="eyebrow">YOUR TRIPS</span><h1>我的旅行</h1>' +
    '<p>选择一段旅行后，记账页会一直打开它。</p></div>' +
    '<div class="action-row trip-actions"><button class="button primary" data-action="new">' + newTripLabel + '</button>' +
    '<button class="button trip-delete-toggle' + (deletingTrips ? ' delete-mode-active' : '') +
    '" data-action="toggle-trip-delete" aria-pressed="' + deletingTrips + '">' +
    (deletingTrips ? '完成' : '删除旅行') + '</button>' +
    '</div>' +
    (deletingTrips ? '<p class="trip-delete-hint">点右侧 × 选择旅行，随后确认删除。</p>' : '') +
    '<div class="trip-list">' + cards + '</div>';
  const canInvite = desktop && trip.team && trip.members.length > 1 && canRecord(trip);
  document.getElementById('main').innerHTML = layout +
    '<div class="section-title"><h2>小队成员</h2></div><div class="card"><p>' +
    trip.members.map(escapeHtml).join('、') + '</p></div>' +
    '<div class="section-title"><h2>同行与同步</h2></div><div class="action-row">' + joinTeamButton +
    (canInvite ? '<button class="button" data-action="invite">邀请同伴</button>' : '') +
    (canInvite && trip.localPerson === 'm1' ? '<button class="button" data-action="connection">跨 Wi-Fi 登记设置</button>' : '') +
    '</div><p class="sync-note" id="team-sync-status">' + escapeHtml(syncStatus(trip)) + '</p>';
}

function canRecord(trip) {
  return !desktop || !trip.team || trip.localPerson === 'm1' || (teamSync?.id === trip.id && teamSync.ready);
}
function syncStatus(trip) {
  if (!desktop || !trip?.team || trip.members.length < 2) return '账目保存在本机';
  return (teamSync?.id === trip.id && teamSync.status) || '正在查找附近设备';
}
function render() {
  const main = document.getElementById('main');
  const nav = document.getElementById('bottom-nav');
  const trip = activeTrip();
  nav.hidden = !trip;
  setBadge(syncStatus(trip));
  nav.querySelectorAll('[data-view]').forEach(button => {
    if (button.dataset.view === currentView) button.setAttribute('aria-current', 'page');
    else button.removeAttribute('aria-current');
  });
  if (!trip) {
    main.innerHTML = '<div class="welcome"><div class="symbol">↗</div><h1>下一段旅程，从这里开始</h1>' +
      '<p>建立旅行后，之后每次打开都会直接进入快速记账。</p>' +
      '<div class="action-row"><button class="button primary" data-action="new">' + newTripLabel + '</button></div>' +
      '<div class="welcome-sync"><h2>同行与同步</h2>' + joinTeamButton + '</div></div>';
    return;
  }
  if (currentView === 'trips') renderTrips(trip);
  else if (!canRecord(trip)) main.innerHTML = '<div class="page-heading"><h1>' + escapeHtml(trip.name) +
    '</h1><p>正在等待首次登记。请让建队设备打开这趟旅行。</p></div>';
  else if (currentView === 'review') renderReview(trip);
  else renderQuick(trip);
}
function toast(message) {
  const element = document.getElementById('toast');
  element.textContent = message;
  element.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => element.classList.remove('show'), 3200);
}
function openDialog(name) {
  if (name === 'trip') {
    document.getElementById('trip-dialog-title').textContent = newTripLabel;
    updateTripMemberControls();
  }
  const trip = activeTrip();
  if (trip) {
    document.querySelectorAll('.person-select').forEach(select => {
      select.innerHTML = memberOptions(trip);
      select.value = trip.localPerson;
    });
  }
  if (name === 'transfer' && trip) {
      document.querySelector('#transfer-to-field select').value = tripMembers(trip).find(([id]) => id !== trip.localPerson)[0];
  }
  if (name === 'exchange' && trip) {
    const defaults = exchangeDefaults(trip);
    exchangeDraftCurrency = { from: defaults.fromCurrency, to: defaults.toCurrency };
    document.getElementById('exchange-from-currency').value = defaults.fromCurrency;
    document.getElementById('exchange-to-currency').value = defaults.toCurrency;
    refreshExchangeHistory('from');
    refreshExchangeHistory('to');
  }
  if (name === 'connection' && trip) {
    document.getElementById('connection-url').value = trip.syncUrl || '';
  }
  document.getElementById(name + '-dialog').showModal();
}
function closeDialog(form) { form.closest('dialog').close(); form.reset(); }
function normalizeSyncUrl(raw) {
  if (typeof raw !== 'string') throw new Error('邀请码连接地址无效');
  if (!raw.trim()) return '';
  let url;
  try { url = new URL(raw.trim()); } catch { throw new Error('邀请码连接地址无效'); }
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash)
    throw new Error('同步连接必须使用 HTTPS');
  return url.href.replace(/\/+$/, '');
}
function setBadge(text, waiting = false) {
  const badge = document.getElementById('sync-badge');
  badge.textContent = text;
  badge.classList.toggle('waiting', waiting);
}
function validPeerEvent(event, trip) {
  const data = event.data;
  if (!data || !Number.isSafeInteger(event.at) ||
      !/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(event.id)) return false;
  const people = new Set(tripMembers(trip).map(([id]) => id));
  const person = value => people.has(value);
  const amount = value => Number.isSafeInteger(value) && value > 0 && value <= 922337203685477;
  const currency = value => typeof value === 'string' && /^[A-Z]{3}$/.test(value) && value.length === 3;
  switch (event.kind) {
    case 'opening': case 'topup': return person(data.person) && currency(data.currency) && amount(data.amount);
    case 'expense': {
      if (!person(data.payer) || !currency(data.currency) || !amount(data.amount) ||
          !data.shares || typeof data.shares !== 'object' || Array.isArray(data.shares)) return false;
      const shares = Object.entries(data.shares);
      return shares.length > 0 && shares.every(([id, value]) => person(id) && Number.isSafeInteger(value) && value >= 0 && value <= data.amount) &&
        shares.reduce((sum, [, value]) => sum + value, 0) === data.amount &&
        (data.mode !== 'personal' || (shares.length === 1 && data.shares[data.payer] === data.amount));
    }
    case 'transfer': return person(data.from) && person(data.to) && data.to !== data.from && currency(data.currency) && amount(data.amount);
    case 'exchange': return person(data.person) && currency(data.fromCurrency) && currency(data.toCurrency) &&
      data.fromCurrency !== data.toCurrency && amount(data.fromAmount) && amount(data.toAmount);
    case 'delete': return typeof data.target === 'string' && data.target.length > 0;
    default: return false;
  }
}

function samePeerData(left, right) {
  if (left === right) return true;
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object' ||
      Array.isArray(left) !== Array.isArray(right)) return false;
  const keys = Object.keys(left);
  return keys.length === Object.keys(right).length &&
    keys.every(key => Object.hasOwn(right, key) && samePeerData(left[key], right[key]));
}

function mergeIncomingEvents(trip, incoming) {
  const known = new Map(state.events.map(event => [event.id, event]));
  const fresh = [];
  for (const raw of incoming) {
    if (raw?.tripId !== trip.id || !raw.id) throw new Error('流水旅行不一致');
    // Context bridge arguments are read-only.
    const event = { ...raw, data: { ...raw.data } };
    normalizeEventCurrency(event);
    if (!validPeerEvent(event, trip)) throw new Error('流水格式无效');
    const previous = known.get(event.id);
    if (previous) {
      if (!samePeerData(previous, event)) throw new Error('同 ID 流水内容冲突');
      continue;
    }
    fresh.push(event);
    known.set(event.id, event);
  }
  if (!fresh.length) return 0;
  const nextState = { ...state, events: state.events.concat(fresh) };
  save(nextState); state = nextState;
  if (currentView !== 'quick') render();
  return fresh.length;
}

function savePeerBatch({ tripId, secret, events }) {
  const trip = state.trips.find(current => current.team && current.id === tripId && current.secret === secret);
  if (!trip) throw new Error('旅行已从本机移除');
  const added = mergeIncomingEvents(trip, events);
  if (added) {
    if (syncing) desktopSyncPending = true;
    else setTimeout(syncNow, 0);
  }
  return added;
}

async function syncNow() {
  if (!desktop) return;
  if (syncing) { desktopSyncPending = true; return; }
  syncing = true;
  const trip = activeTrip();
  try {
    const shared = trip?.team && trip.members.length > 1;
    const result = await desktop.updateTeam(shared ? trip : null, shared ? eventsFor(trip.id) : []);
    if (activeTrip()?.id !== trip?.id) return;
    const wasReady = trip && canRecord(trip);
    teamSync = result ? { ...result, id: trip.id } : null;
    if (trip && !wasReady && canRecord(trip)) render();
    else {
      const status = syncStatus(trip);
      setBadge(status);
      const details = document.getElementById('team-sync-status');
      if (details) details.textContent = status;
    }
  } catch { setBadge('本机账目已保留 · 等待附近连接', true); }
  finally {
    syncing = false;
    if (desktopSyncPending) { desktopSyncPending = false; setTimeout(syncNow, 0); }
  }
}

document.getElementById('main').addEventListener('click', async event => {
  const historyRemove = event.target.closest('[data-currency-history-remove]');
  if (historyRemove) {
    try {
      removeQuickCurrencyHistory(activeTrip().id, historyRemove.dataset.currencyHistoryRemove);
      refreshQuickCurrencyHistory(true);
    } catch { toast('移除失败，请检查浏览器存储空间'); }
    return;
  }
  const historyOption = event.target.closest('[data-currency-history]');
  if (historyOption) {
    const input = document.getElementById('quick-currency');
    input.value = rememberQuickCurrency(activeTrip().id, historyOption.dataset.currencyHistory);
    refreshQuickCurrencyHistory();
    return;
  }
  const historyToggle = event.target.closest('.currency-history-toggle');
  if (historyToggle) {
    const menu = document.querySelector('#quick-expense-form .currency-history-menu');
    menu.hidden = !menu.hidden;
    historyToggle.setAttribute('aria-expanded', String(!menu.hidden));
    return;
  }
  const historyMenu = document.querySelector('#quick-expense-form .currency-history-menu');
  if (historyMenu && !event.target.closest('.currency-control')) {
    historyMenu.hidden = true;
    document.querySelector('.currency-history-toggle').setAttribute('aria-expanded', 'false');
  }
  const tripButton = event.target.closest('[data-trip-id]');
  if (tripButton && !tripButton.matches('[data-action="delete-trip"]')) {
    state.activeTripId = tripButton.dataset.tripId;
    currentView = 'quick';
    deletingTrips = false;
    recordFilter = 'all';
    save();
    render();
    window.scrollTo(0, 0);
    syncNow();
    return;
  }
  const button = event.target.closest('[data-action]');
  if (!button) return;
  const action = button.dataset.action;
  if (action === 'new') return openDialog('trip');
  if (action === 'join-team') return openDialog(desktop ? 'join-method' : 'join-team');
  if (action === 'toggle-trip-delete') {
    deletingTrips = !deletingTrips;
    renderTrips(activeTrip());
    return;
  }
  if (action === 'delete-trip') {
    const trip = state.trips.find(item => item.id === button.dataset.tripId);
    if (!trip) return;
    pendingDeleteTripId = trip.id;
    document.getElementById('delete-trip-name').textContent = trip.name;
    document.getElementById('delete-trip-dialog').showModal();
    return;
  }
  if (action === 'record-filter') {
    recordFilter = button.dataset.recordFilter;
    updateRecordFilter(activeTrip());
    return;
  }
  if (action === 'delete') {
    if (confirm('删除这笔记录？')) addEvent('delete', { target: button.dataset.eventId });
    return;
  }
  if (action === 'invite') {
    try { await showTeamInvite(activeTrip()); }
    catch (error) { toast(error.message); }
    return;
  }
  openDialog(action);
});
document.getElementById('exchange-form').addEventListener('click', event => {
  const remove = event.target.closest('[data-exchange-history-remove]');
  if (remove) {
    const field = remove.dataset.exchangeHistoryRemove;
    const control = document.querySelector('#exchange-form [data-exchange-field="' + field + '"]');
    try {
      removeExchangeCurrencyHistory(activeTrip().id, field, remove.dataset.currencyCode,
        control.querySelector('input').value);
      refreshExchangeHistory(field, true);
    } catch { toast('移除失败，请检查浏览器存储空间'); }
    return;
  }
  const pick = event.target.closest('[data-exchange-history-pick]');
  if (pick) {
    const control = document.querySelector('#exchange-form [data-exchange-field="' +
      pick.dataset.exchangeHistoryPick + '"]');
    try {
      control.querySelector('input').value = rememberExchangeDraftCurrency(activeTrip().id,
        pick.dataset.exchangeHistoryPick, pick.dataset.currencyCode);
      refreshExchangeHistory(pick.dataset.exchangeHistoryPick);
    } catch { toast('货币记录失败，请检查浏览器存储空间'); }
    return;
  }
  const toggle = event.target.closest('#exchange-form .currency-history-toggle');
  if (toggle) {
    const control = toggle.closest('[data-exchange-field]');
    const field = control.dataset.exchangeField;
    const menu = control.parentElement.querySelector('.currency-history-menu');
    const wasOpen = !menu.hidden;
    for (const other of ['from', 'to']) refreshExchangeHistory(other);
    if (!wasOpen && !toggle.hidden) {
      menu.hidden = false;
      toggle.setAttribute('aria-expanded', 'true');
    }
  }
});
document.getElementById('exchange-form').addEventListener('input', event => {
  const field = event.target.id === 'exchange-from-currency' ? 'from' :
    event.target.id === 'exchange-to-currency' ? 'to' : null;
  if (!field) return;
  try { rememberExchangeDraftCurrency(activeTrip().id, field, event.target.value); }
  catch { /* Keep partially entered codes editable. */ }
  refreshExchangeHistory(field);
});
document.getElementById('exchange-form').addEventListener('change', event => {
  const field = event.target.id === 'exchange-from-currency' ? 'from' :
    event.target.id === 'exchange-to-currency' ? 'to' : null;
  if (!field) return;
  try { event.target.value = rememberExchangeDraftCurrency(activeTrip().id, field, event.target.value); }
  catch { /* Submit will show a validation message. */ }
  refreshExchangeHistory(field);
});
document.getElementById('bottom-nav').addEventListener('click', event => {
  const button = event.target.closest('[data-view]');
  if (!button) return;
  currentView = button.dataset.view;
  if (currentView !== 'trips') deletingTrips = false;
  render();
  window.scrollTo(0, 0);
});
document.getElementById('main').addEventListener('submit', event => {
  if (event.target.id !== 'quick-expense-form') return;
  event.preventDefault();
  const trip = activeTrip();
  const data = new FormData(event.target);
  try {
    const currency = currencyCode(data.get('currency'));
    const amount = parseAmount(data.get('amount'), currency);
    const payer = data.get('payer');
    const mode = data.get('mode');
    if (!tripMembers(trip).some(([id]) => id === payer) || !['shared', 'personal'].includes(mode))
      throw new Error('请检查付款人和分摊方式');
    rememberQuickCurrency(trip.id, currency);
    Object.assign(state.quickPrefs[trip.id], { currency, payer, mode });
    addEvent('expense', {
      payer, currency, amount, shares: teamShares(trip, amount, payer, mode),
      mode, note: String(data.get('note') || '').trim()
    });
    toast('已记账');
  } catch (error) { toast(error.message || '记录失败'); }
});
document.getElementById('main').addEventListener('input', event => {
  if (event.target.id !== 'quick-currency') return;
  try {
    rememberQuickCurrency(activeTrip().id, event.target.value);
    refreshQuickCurrencyHistory();
  } catch { /* Keep partially entered codes editable. */ }
});
document.getElementById('main').addEventListener('change', event => {
  if (event.target.id === 'quick-currency') {
    try {
      event.target.value = rememberQuickCurrency(activeTrip().id, event.target.value);
      refreshQuickCurrencyHistory();
    } catch { /* Submit will show a validation message. */ }
    return;
  }
  if (event.target.name !== 'mode') return;
  const details = document.querySelector('#quick-expense-form .split-details');
  if (details) details.hidden = event.target.value === 'personal';
});
document.querySelectorAll('dialog .cancel').forEach(button =>
  button.addEventListener('click', () => button.closest('dialog').close()));
document.getElementById('confirm-delete-trip').addEventListener('click', () => {
  const id = pendingDeleteTripId;
  if (!id || !state.trips.some(trip => trip.id === id)) return;
  const wasActive = state.activeTripId === id;
  const trips = state.trips.filter(trip => trip.id !== id);
  const quickPrefs = { ...state.quickPrefs };
  delete quickPrefs[id];
  const nextState = { ...state, trips, quickPrefs,
    events: state.events.filter(event => event.tripId !== id),
    activeTripId: wasActive ? trips[0]?.id || null : state.activeTripId };
  try { save(nextState); }
  catch { toast('删除失败，请检查浏览器存储空间'); return; }
  state = nextState;
  if (wasActive) {
    currentView = 'trips';
  }
  deletingTrips = false;
  recordFilter = 'all';
  pendingDeleteTripId = null;
  document.getElementById('delete-trip-dialog').close();
  render();
  toast('旅行及本机记录已删除');
});
document.getElementById('copy-invite').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(document.getElementById('invite-code').value); toast('邀请码已复制'); }
  catch { document.getElementById('invite-code').select(); toast('已选中邀请码，请手动复制'); }
});
async function showTeamInvite(trip) {
  const invite = await desktop.teamInvite(trip);
  document.getElementById('team-invite-image').src = invite.image;
  document.getElementById('invite-code').value = invite.code;
  document.getElementById('invite-role-note').textContent = trip.syncUrl
    ? '支持附近和异地登记。建队设备需要打开这趟旅行。'
    : '用手机扫码，或保存截图后从图片加入。';
  openDialog('invite');
}

function showTeamIdentity(invite) {
  const existing = state.trips.find(trip => trip.id === invite.id);
  if (existing && (existing.secret !== invite.secret || !samePeerData(existing.team, invite.team)))
    throw new Error('邀请码与本机已有小队不一致');
  teamInviteDraft = invite;
  const select = document.getElementById('team-join-member');
  select.innerHTML = '<option value="">请选择你的名字</option>' + invite.members.slice(1).map((name, index) =>
    '<option value="m' + (index + 2) + '">' + escapeHtml(name) + '</option>').join('');
  select.disabled = Boolean(existing);
  select.value = existing?.localPerson || '';
  document.getElementById('team-join-trip').textContent = invite.name;
  document.getElementById('join-method-dialog').close();
  openDialog('team-identity');
}

document.getElementById('pick-team-image').addEventListener('click', async () => {
  try {
    const invite = await desktop.readTeamImage();
    if (invite) showTeamIdentity(invite);
  } catch (error) { toast(error.message); }
});
document.getElementById('manual-team-invite').addEventListener('submit', async event => {
  event.preventDefault();
  try { showTeamIdentity(await desktop.readTeamInvite(document.getElementById('team-invite-input').value)); }
  catch (error) { toast(error.message); }
});
document.getElementById('team-identity-form').addEventListener('submit', event => {
  event.preventDefault();
  try {
    const invite = teamInviteDraft;
    const existing = state.trips.find(trip => trip.id === invite.id);
    const localPerson = existing?.localPerson || document.getElementById('team-join-member').value;
    if (!tripMembers(invite).slice(1).some(([id]) => id === localPerson)) throw new Error('请选择你的名字');
    if (existing && (!invite.syncUrl || existing.syncUrl === invite.syncUrl)) throw new Error('这个小队已在本机');
    const nextTrips = existing ? state.trips.map(trip => trip.id === invite.id ? { ...trip, syncUrl: invite.syncUrl } : trip) :
      [{ ...invite, localPerson }, ...state.trips];
    const next = { ...state, trips: nextTrips, activeTripId: invite.id };
    save(next); state = next;
    currentView = 'quick'; recordFilter = 'all';
    document.getElementById('team-invite-input').value = '';
    teamInviteDraft = null;
    closeDialog(event.currentTarget); render(); syncNow();
  } catch (error) { toast(error.message); }
});

function updateTripMemberControls() {
  const rows = document.getElementById('trip-extra-members').querySelectorAll('.trip-member-row');
  rows.forEach((row, index) => {
    const number = index + initialMemberCount;
    row.querySelector('span').textContent = '同伴 ' + number;
    row.querySelector('button').setAttribute('aria-label', '移除同伴 ' + number);
  });
  document.getElementById('add-trip-member').disabled = rows.length + initialMemberCount >= 5;
  document.getElementById('trip-member-count').textContent = (rows.length + initialMemberCount) + ' / 5 人';
}
document.getElementById('add-trip-member').addEventListener('click', () => {
  const list = document.getElementById('trip-extra-members');
  if (list.children.length + initialMemberCount >= 5) return;
  list.insertAdjacentHTML('beforeend',
    '<div class="trip-member-row"><label><span>同伴</span><input name="member" required maxlength="30" placeholder="输入同伴名字"></label>' +
    '<button type="button" class="button ghost member-remove">×</button></div>');
  updateTripMemberControls();
  list.lastElementChild.querySelector('input').focus();
});
document.getElementById('trip-extra-members').addEventListener('click', event => {
  const remove = event.target.closest('.member-remove');
  if (!remove) return;
  remove.closest('.trip-member-row').remove();
  updateTripMemberControls();
  document.getElementById('add-trip-member').focus();
});
document.getElementById('trip-form').addEventListener('submit', event => {
  event.preventDefault();
  const form = event.currentTarget, data = new FormData(form);
  const name = String(data.get('name') || '').trim();
  const members = [data.get('personA'),
    ...Array.from(form.querySelectorAll('input[name="member"]'), input => input.value)]
    .map(value => String(value || '').trim());
  if (!name || members.some(member => !member || member.length > 30)) return toast('请填写旅行和每位成员的名字');
  if (members.length > 5) return toast('每趟旅行最多 5 人');
  if (new Set(members.map(member => member.toLocaleLowerCase())).size !== members.length)
    return toast('每位成员的名字须不同，避免记账混淆');
  if (desktop) {
    const button = form.querySelector('button[type="submit"]');
    if (button) button.disabled = true;
    desktop.createTeam(name, members).then(trip => saveCreatedTrip(form, trip))
      .catch(error => toast(error.message)).finally(() => { if (button) button.disabled = false; });
  } else saveCreatedTrip(form, { id: crypto.randomUUID(), name, members, localPerson: 'm1' });
});
function saveCreatedTrip(form, trip) {
  const nextState = { ...state, trips: [trip, ...state.trips], activeTripId: trip.id };
  try { save(nextState); } catch { toast('创建失败，请检查浏览器存储空间'); return; }
  state = nextState;
  currentView = 'quick';
  deletingTrips = false;
  recordFilter = 'all';
  closeDialog(form);
  document.getElementById('trip-extra-members').innerHTML = '';
  updateTripMemberControls();
  render(); window.scrollTo(0, 0); syncNow();
}
document.getElementById('connection-form').addEventListener('submit', event => {
  event.preventDefault();
  try {
    const trip = activeTrip();
    const syncUrl = normalizeSyncUrl(document.getElementById('connection-url').value);
    const nextState = { ...state, trips: state.trips.map(item => item.id === trip.id
      ? { ...item, syncUrl } : item) };
    save(nextState); state = nextState;
    closeDialog(event.currentTarget); render(); syncNow();
  } catch (error) { toast(error.message); }
});
document.getElementById('host-on-this-computer').addEventListener('click', async event => {
  const button = event.currentTarget;
  const trip = activeTrip();
  button.disabled = true;
  try {
    toast('正在建立临时登记连接…');
    const { url } = await desktop.connectPhone();
    if (activeTrip()?.id !== trip.id) return;
    const syncUrl = normalizeSyncUrl(url);
    const next = { ...state, trips: state.trips.map(item => item.id === trip.id ? { ...item, syncUrl } : item) };
    save(next); state = next;
    document.getElementById('connection-dialog').close();
    await showTeamInvite(activeTrip()); syncNow();
  } catch (error) { toast(error.message); }
  finally { button.disabled = false; }
});
function registerEntryForm(id, kind, makeData, makePrefs = null) {
  document.getElementById(id).addEventListener('submit', event => {
    event.preventDefault();
    const form = event.currentTarget;
    try {
      const data = makeData(new FormData(form));
      addEvent(kind, data, makePrefs ? makePrefs(activeTrip(), data) : state.quickPrefs);
      closeDialog(form);
    } catch (error) { toast(error.message || '记录失败'); }
  });
}
registerEntryForm('opening-form', 'opening', d => {
  const currency = currencyCode(d.get('currency'));
  return { person: d.get('person'), currency, amount: parseAmount(d.get('amount'), currency) };
});
registerEntryForm('topup-form', 'topup', d => {
  const currency = currencyCode(d.get('currency'));
  return { person: d.get('person'), currency, amount: parseAmount(d.get('amount'), currency) };
});
registerEntryForm('transfer-form', 'transfer', d => {
  const trip = activeTrip();
  const from = d.get('from');
  const to = d.get('to');
  const currency = currencyCode(d.get('currency'));
  if (from === to || !tripMembers(trip).some(([id]) => id === to))
    throw new Error('请选择不同的付款人和收款人');
  return { from, currency, amount: parseAmount(d.get('amount'), currency), to };
});
registerEntryForm('exchange-form', 'exchange', d => {
  const fromCurrency = currencyCode(d.get('fromCurrency'));
  const toCurrency = currencyCode(d.get('toCurrency'));
  if (fromCurrency === toCurrency) throw new Error('请选择两种不同货币');
  return { person: d.get('person'), fromCurrency, toCurrency,
    fromAmount: parseAmount(d.get('fromAmount'), fromCurrency),
    toAmount: parseAmount(d.get('toAmount'), toCurrency) };
}, nextExchangePrefs);
render();
if (desktop) {
  document.title = '旅行账本';
  const subtitle = document.querySelector('.brand small');
  if (subtitle) subtitle.textContent = '电脑客户端';
  desktop.onPeerBatch?.(savePeerBatch);
  desktop.onRemoteChanged?.(syncNow);
}
window.addEventListener('pageshow', event => {
  if (event.persisted) { currentView = 'quick'; render(); }
  window.scrollTo(0, 0);
});
requestAnimationFrame(() => window.scrollTo(0, 0));
syncNow();
setInterval(syncNow, 15000);
window.addEventListener('online', syncNow);
document.addEventListener('visibilitychange', () => { if (!document.hidden) syncNow(); });
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
