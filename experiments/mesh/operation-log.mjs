// Feasibility prototype only. This schema is not used by the released Android app.
import { randomUUID, createHash, randomBytes, createCipheriv, createDecipheriv } from 'node:crypto';

const copy = value => JSON.parse(JSON.stringify(value));
const uuid = value => typeof value === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
export const canonical = value => Array.isArray(value) ? '[' + value.map(canonical).join(',') + ']'
  : value && typeof value === 'object' ? '{' + Object.keys(value).sort().map(key => JSON.stringify(key) + ':' + canonical(value[key])).join(',') + '}'
    : JSON.stringify(value);
const hash = value => createHash('sha256').update(canonical(value)).digest('hex');
const positive = value => Number.isSafeInteger(value) && value > 0;
const currency = value => typeof value === 'string' && /^[A-Z]{3}$/.test(value);

export function splitMinor(amount, memberIds) {
  if (!positive(amount) || !memberIds.length || new Set(memberIds).size !== memberIds.length || !memberIds.every(uuid))
    throw new Error('invalid split');
  const sorted = [...memberIds].sort();
  const base = Math.floor(amount / sorted.length), remainder = amount % sorted.length;
  return sorted.map((memberId, index) => ({ memberId, amount: base + (index < remainder ? 1 : 0) }));
}

export class OperationLog {
  constructor({ tripId, members, deviceId = randomUUID(), operations = [] }) {
    if (!uuid(tripId) || !uuid(deviceId) || !members.length || !members.every(uuid)) throw new Error('invalid identity');
    this.tripId = tripId; this.deviceId = deviceId; this.members = new Set(members);
    this.operations = new Map(); this.origins = new Map(); this.sequence = 0;
    this.receive(operations);
  }
  append(kind, data, at = Date.now()) {
    const event = { id: randomUUID(), tripId: this.tripId, origin: this.deviceId, sequence: this.sequence + 1,
      at, kind, data: copy(data) };
    this.receive([event]); return copy(event);
  }
  validate(event) {
    if (!event || !uuid(event.id) || event.tripId !== this.tripId || !uuid(event.origin) ||
        !positive(event.sequence) || !Number.isSafeInteger(event.at) || !event.data) throw new Error('invalid operation');
    const d = event.data, member = value => this.members.has(value);
    let valid = false;
    if (event.kind === 'opening' || event.kind === 'topup') valid = member(d.memberId) && currency(d.currency) && positive(d.amount);
    if (event.kind === 'expense') {
      valid = member(d.payerId) && currency(d.currency) && positive(d.amount) && Array.isArray(d.shares) && d.shares.length > 0 &&
        d.shares.every(share => member(share.memberId) && Number.isSafeInteger(share.amount) && share.amount >= 0) &&
        new Set(d.shares.map(share => share.memberId)).size === d.shares.length &&
        d.shares.reduce((sum, share) => sum + BigInt(share.amount), 0n) === BigInt(d.amount);
    }
    if (event.kind === 'repayment') valid = member(d.fromId) && member(d.toId) && d.fromId !== d.toId && currency(d.currency) && positive(d.amount);
    if (event.kind === 'exchange') valid = member(d.memberId) && currency(d.fromCurrency) && currency(d.toCurrency) &&
      d.fromCurrency !== d.toCurrency && positive(d.fromAmount) && positive(d.toAmount);
    if (event.kind === 'delete') valid = uuid(d.target);
    if (!valid) throw new Error('invalid operation data');
  }
  receive(values) {
    // Validate the complete batch before replacing state. Conflicting IDs do not silently win by arrival time.
    const next = new Map(this.operations), origins = new Map(this.origins);
    let sequence = this.sequence, added = 0;
    for (const raw of values) {
      const event = copy(raw); this.validate(event);
      const digest = hash(event), previous = next.get(event.id);
      if (previous && previous.hash !== digest) throw new Error('conflicting event ID');
      const originKey = event.origin + ':' + event.sequence;
      if (origins.has(originKey) && origins.get(originKey) !== event.id) throw new Error('conflicting origin sequence');
      if (!previous) { next.set(event.id, { event, hash: digest }); origins.set(originKey, event.id); added++; }
      if (event.origin === this.deviceId) sequence = Math.max(sequence, event.sequence);
    }
    this.operations = next; this.origins = origins; this.sequence = sequence;
    return added;
  }
  digest() {
    return { protocol: 2, tripId: this.tripId,
      events: Object.fromEntries([...this.operations].sort(([a], [b]) => a.localeCompare(b)).map(([id, item]) => [id, item.hash])) };
  }
  delta(remote) {
    if (remote.protocol !== 2 || remote.tripId !== this.tripId || !remote.events) throw new Error('incompatible peer');
    const missing = [];
    for (const [id, item] of this.operations) {
      if (Object.hasOwn(remote.events, id) && remote.events[id] !== item.hash) throw new Error('conflicting peer digest');
      if (!Object.hasOwn(remote.events, id)) missing.push(copy(item.event));
    }
    return missing;
  }
  snapshot() { return { tripId: this.tripId, members: [...this.members], operations: [...this.operations.values()].map(item => copy(item.event)) }; }
  totals() {
    const cash = {}, net = {}, spent = {};
    const add = (map, key, delta) => { map[key] = (map[key] || 0n) + BigInt(delta); };
    const cashFor = (id, code, delta) => add(cash[id] ||= {}, code, delta);
    const netFor = (id, code, delta) => add(net[id] ||= {}, code, delta);
    const events = [...this.operations.values()].map(item => item.event);
    const deleted = new Set(events.filter(event => event.kind === 'delete').map(event => event.data.target));
    for (const event of events) {
      if (event.kind === 'delete' || deleted.has(event.id)) continue;
      const d = event.data;
      if (event.kind === 'opening' || event.kind === 'topup') cashFor(d.memberId, d.currency, d.amount);
      if (event.kind === 'expense') {
        cashFor(d.payerId, d.currency, -d.amount); add(spent, d.currency, d.amount);
        netFor(d.payerId, d.currency, d.amount);
        for (const share of d.shares) netFor(share.memberId, d.currency, -share.amount);
      }
      if (event.kind === 'repayment') {
        cashFor(d.fromId, d.currency, -d.amount); cashFor(d.toId, d.currency, d.amount);
        netFor(d.fromId, d.currency, d.amount); netFor(d.toId, d.currency, -d.amount);
      }
      if (event.kind === 'exchange') {
        cashFor(d.memberId, d.fromCurrency, -d.fromAmount); cashFor(d.memberId, d.toCurrency, d.toAmount);
      }
    }
    return JSON.parse(JSON.stringify({ cash, net, spent }, (_key, value) => typeof value === 'bigint' ? value.toString() : value));
  }
}

export function sealOperations(log, operations, secret) {
  const iv = randomBytes(12), cipher = createCipheriv('aes-256-gcm', secret, iv);
  cipher.setAAD(Buffer.from('ride-ledger-experiment-v2:' + log.tripId));
  const data = Buffer.concat([cipher.update(JSON.stringify({ protocol: 2, operations })), cipher.final(), cipher.getAuthTag()]);
  return { protocol: 2, tripId: log.tripId, iv: iv.toString('base64url'), data: data.toString('base64url') };
}
export function openOperations(log, bundle, secret) {
  if (bundle.protocol !== 2 || bundle.tripId !== log.tripId) throw new Error('incompatible encrypted bundle');
  const bytes = Buffer.from(bundle.data, 'base64url');
  const decipher = createDecipheriv('aes-256-gcm', secret, Buffer.from(bundle.iv, 'base64url'));
  decipher.setAAD(Buffer.from('ride-ledger-experiment-v2:' + log.tripId)); decipher.setAuthTag(bytes.subarray(-16));
  const value = JSON.parse(Buffer.concat([decipher.update(bytes.subarray(0, -16)), decipher.final()]));
  if (value.protocol !== 2 || !Array.isArray(value.operations)) throw new Error('invalid operation envelope');
  return value.operations;
}
