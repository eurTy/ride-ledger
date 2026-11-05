import { createCipheriv, createDecipheriv, createHash, createHmac, randomBytes, randomUUID, timingSafeEqual } from 'node:crypto';

export const maxFrame = 12_000_000;
const maxSessionBytes = 32_000_000;
const maxEvents = 10000;
const maxTrips = 100;
const batchBytes = 48000;
export const validId = value => typeof value === 'string' && value.length === 36 &&
  /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i.test(value);
export const validBundle = value => value && typeof value.tripId === 'string' &&
  typeof value.iv === 'string' && typeof value.data === 'string';
const validHash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

// Keep unread bytes across frames. Android may put several frames in one TCP read.
export class PeerWire {
  constructor(socket) {
    this.socket = socket;
    this.reader = socket[Symbol.asyncIterator]();
    this.buffer = Buffer.alloc(0);
    this.bytesRead = 0;
  }

  async exact(size) {
    const parts = [];
    let remaining = size;
    while (remaining > 0) {
      if (!this.buffer.length) {
        const { value, done } = await this.reader.next();
        if (done) throw new Error('同伴连接中断');
        this.buffer = value;
      }
      const count = Math.min(this.buffer.length, remaining);
      parts.push(this.buffer.subarray(0, count));
      this.buffer = this.buffer.subarray(count);
      remaining -= count;
    }
    this.bytesRead += size;
    return parts.length === 1 ? parts[0] : Buffer.concat(parts, size);
  }

  async read() {
    const size = (await this.exact(4)).readInt32BE();
    if (size < 1 || size > maxFrame || this.bytesRead + size > maxSessionBytes)
      throw new Error('同步数据大小无效');
    return JSON.parse((await this.exact(size)).toString('utf8'));
  }

  async write(message) {
    const body = Buffer.from(JSON.stringify(message));
    if (body.length > maxFrame) throw new Error('旅行记录超出同步上限');
    const header = Buffer.alloc(4);
    header.writeInt32BE(body.length);
    await new Promise((resolve, reject) => {
      this.socket.write(Buffer.concat([header, body]), error => error ? reject(error) : resolve());
    });
  }
}

function decimal(value) {
  if (!Number.isFinite(value)) throw new Error('同步数字无效');
  const plain = String(value);
  if (!plain.includes('e')) return plain;
  const [coefficient, exponent] = plain.split('e');
  const sign = coefficient.startsWith('-') ? '-' : '';
  const [integer, fraction = ''] = coefficient.slice(sign.length).split('.');
  const digits = integer + fraction;
  const point = integer.length + Number(exponent);
  if (point <= 0) return sign + '0.' + '0'.repeat(-point) + digits;
  if (point >= digits.length) return sign + digits + '0'.repeat(point - digits.length);
  return sign + digits.slice(0, point) + '.' + digits.slice(point);
}

// Match Android JSONObject.quote and BigDecimal.toPlainString before hashing.
const quote = value => JSON.stringify(value).replaceAll('/', '\\/');
function canonical(value) {
  if (typeof value === 'string') return quote(value);
  if (typeof value === 'number') return decimal(value);
  if (value === null || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return '[' + value.map(canonical).join(',') + ']';
  if (isObject(value)) {
    return '{' + Object.keys(value).sort().map(key => quote(key) + ':' + canonical(value[key])).join(',') + '}';
  }
  throw new Error('同步数据格式无效');
}

const hash = value => createHash('sha256').update(canonical(value)).digest('hex');
const peerAad = trip => 'ride-ledger-peer-v2:' + trip.id;

function openBundle(trip, bundle, aad) {
  if (!validBundle(bundle) || bundle.tripId !== trip.id) throw new Error('旅行不一致');
  const iv = Buffer.from(bundle.iv, 'base64url');
  const data = Buffer.from(bundle.data, 'base64url');
  if (iv.length !== 12 || data.length < 16 || data.length > 8_000_000) throw new Error('加密数据大小无效');
  const cipher = createDecipheriv('aes-256-gcm', trip.key, iv, { authTagLength: 16 });
  cipher.setAAD(Buffer.from(aad));
  cipher.setAuthTag(data.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(data.subarray(0, -16)), cipher.final()]).toString('utf8'));
}

function sealPeer(trip, payload) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', trip.key, iv);
  cipher.setAAD(Buffer.from(peerAad(trip)));
  const encrypted = Buffer.concat([cipher.update(JSON.stringify(payload)), cipher.final(), cipher.getAuthTag()]);
  return { tripId: trip.id, iv: iv.toString('base64url'), data: encrypted.toString('base64url') };
}

function legacyEvents(trip, bundle) {
  const { events } = openBundle(trip, bundle, trip.id);
  if (!Array.isArray(events) || events.length > maxEvents) throw new Error('同步记录格式无效');
  return events;
}

export function peerSnapshot({ deviceId, peerTrips, bundles }) {
  if (!validId(deviceId) || !Array.isArray(peerTrips)) return null;
  if (peerTrips.length > maxTrips || peerTrips.length !== bundles.length) throw new Error('同步旅行数量无效');
  const byId = new Map(bundles.map(bundle => [bundle.tripId, bundle]));
  const trips = new Map();
  for (const { id, secret } of peerTrips) {
    const key = typeof secret === 'string' && Buffer.from(secret, 'base64url');
    if (!validId(id) || key?.length !== 32 || trips.has(id)) throw new Error('旅行密钥无效');
    const trip = { id, secret, key, events: new Map(), hashes: Object.create(null) };
    for (const event of legacyEvents(trip, byId.get(id))) {
      if (!validId(event.id) || event.tripId !== id || !isObject(event.data) || trip.events.has(event.id))
        throw new Error('同步记录格式无效');
      for (const field of ['currency', 'fromCurrency', 'toCurrency']) {
        if (typeof event.data[field] === 'string' && event.data[field].toUpperCase() === 'RMB') event.data[field] = 'CNY';
      }
      trip.events.set(event.id, event);
      trip.hashes[event.id] = hash(event);
    }
    trip.root = hash(trip.hashes);
    trips.set(id, trip);
  }
  return { deviceId, bundles, trips };
}

export class PeerProtocol {
  constructor(wire, snapshot, commit, isCurrent) {
    this.wire = wire;
    this.snapshot = snapshot;
    this.commit = commit;
    this.isCurrent = isCurrent;
    this.deviceId = snapshot.deviceId;
    this.common = new Map();
    this.manifestFailed = false;
  }

  async exchange(modern) {
    this.session = randomUUID();
    if (!modern) return this.exchangeLegacy();
    await this.wire.write(this.hello());
    this.readHello(await this.wire.read());
    await this.wire.write(this.index());
    this.readIndex(await this.wire.read());
    await this.sendDeltas();
    await this.wire.write(this.frame('end'));
    const end = await this.receiveDeltas();
    await this.wire.write(this.frame('confirm', { receipts: this.receipts() }));
    return this.result(this.confirmed(end.receipts, this.expectedReceipts()));
  }

  async accept(first) {
    this.session = first.session;
    if (!validId(this.session)) throw new Error('同步会话无效');
    if (first.version === 1) return this.acceptLegacy(first);
    this.readHello(first);
    await this.wire.write(this.hello(true));
    this.readIndex(await this.wire.read());
    await this.wire.write(this.index());
    await this.receiveDeltas();
    await this.sendDeltas();
    await this.wire.write(this.frame('end', { receipts: this.receipts() }));
    const confirmation = await this.wire.read();
    this.checkFrame(confirmation, 'confirm');
    return this.result(this.confirmed(confirmation.receipts, this.expectedReceipts()));
  }

  frame(kind, data) {
    return { version: 2, kind, session: this.session, deviceId: this.deviceId, ...data };
  }

  payload(type, data) {
    return { type, session: this.session, sender: this.deviceId, ...data };
  }

  checkFrame(frame, kind) {
    if (frame.version !== 2 || frame.kind !== kind || frame.session !== this.session || frame.deviceId !== this.remoteId)
      throw new Error('同步消息顺序无效');
  }

  open(trip, bundle, type) {
    const payload = openBundle(trip, bundle, peerAad(trip));
    if (payload.type !== type || payload.session !== this.session || payload.sender !== this.remoteId)
      throw new Error('同步消息身份不一致');
    return payload;
  }

  hello(onlyCommon = false) {
    const bundles = [];
    for (const trip of this.snapshot.trips.values()) {
      if (onlyCommon && !this.common.has(trip.id)) continue;
      bundles.push(sealPeer(trip, this.payload('manifest', { root: trip.root, count: trip.events.size })));
    }
    return this.frame('hello', { bundles });
  }

  readHello(frame) {
    this.remoteId = frame.deviceId;
    this.checkFrame(frame, 'hello');
    if (!validId(this.remoteId) || this.deviceId === this.remoteId ||
        !Array.isArray(frame.bundles) || frame.bundles.length > maxTrips) throw new Error('同伴设备身份无效');
    for (const bundle of frame.bundles) {
      const trip = this.snapshot.trips.get(bundle?.tripId);
      if (!trip) continue;
      try {
        const { root, count } = this.open(trip, bundle, 'manifest');
        if (!validHash(root) || !Number.isInteger(count) || count < 0 || count > maxEvents || this.common.has(trip.id))
          throw new Error('同步摘要无效');
        this.common.set(trip.id, { trip, root, count, missing: new Set(), sent: [], received: [], failed: false });
      } catch { this.manifestFailed = true; }
    }
    if (!this.common.size) throw new Error('请先加入同一旅行');
  }

  index() {
    const bundles = [];
    for (const { trip, root } of this.common.values()) {
      if (root !== trip.root) bundles.push(sealPeer(trip, this.payload('index', { entries: trip.hashes })));
    }
    return this.frame('index', { bundles });
  }

  readIndex(frame) {
    this.checkFrame(frame, 'index');
    if (!Array.isArray(frame.bundles) || frame.bundles.length > this.common.size) throw new Error('同步索引无效');
    const seen = new Set();
    for (const bundle of frame.bundles) {
      const entry = this.common.get(bundle?.tripId);
      if (!entry || seen.has(entry.trip.id)) throw new Error('索引旅行不一致');
      seen.add(entry.trip.id);
      try {
        const { entries } = this.open(entry.trip, bundle, 'index');
        if (!isObject(entries) || Object.keys(entries).length !== entry.count || hash(entries) !== entry.root ||
            !Object.entries(entries).every(([id, digest]) => validId(id) && validHash(digest)))
          throw new Error('同伴索引与摘要不一致');
        entry.remoteHashes = entries;
      } catch { entry.failed = true; }
    }
    for (const entry of this.common.values()) {
      if (entry.root === entry.trip.root) entry.remoteHashes = entry.trip.hashes;
      if (!entry.remoteHashes) { entry.failed = true; continue; }
      for (const [id, digest] of Object.entries(entry.remoteHashes)) {
        if (!Object.hasOwn(entry.trip.hashes, id)) entry.missing.add(id);
        else if (entry.trip.hashes[id] !== digest) entry.failed = true;
      }
    }
  }

  async sendDeltas() {
    for (const entry of this.common.values()) {
      if (entry.failed) continue;
      let batch = [], bytes = 0;
      for (const [id, event] of entry.trip.events) {
        if (Object.hasOwn(entry.remoteHashes, id)) continue;
        const size = Buffer.byteLength(JSON.stringify(event));
        if (batch.length && bytes + size > batchBytes) {
          await this.sendBatch(entry, batch);
          batch = [];
          bytes = 0;
        }
        batch.push(event);
        bytes += size;
      }
      if (batch.length) await this.sendBatch(entry, batch);
    }
  }

  async sendBatch(entry, events) {
    const bundle = sealPeer(entry.trip, this.payload('events', { events }));
    await this.wire.write(this.frame('delta', { bundle }));
    entry.sent.push(hash(bundle));
  }

  async receiveDeltas() {
    for (let frames = 0; frames < 20000; frames++) {
      const next = await this.wire.read();
      if (next.kind === 'end') {
        this.checkFrame(next, 'end');
        return next;
      }
      this.checkFrame(next, 'delta');
      const entry = this.common.get(next.bundle?.tripId);
      if (!entry) throw new Error('同步旅行未授权');
      if (entry.failed) continue;
      try {
        const { events } = this.open(entry.trip, next.bundle, 'events');
        if (!Array.isArray(events) || !events.length || events.length > maxEvents || !events.every(event =>
          validId(event?.id) && Object.hasOwn(entry.remoteHashes, event.id) && hash(event) === entry.remoteHashes[event.id]))
          throw new Error('流水与摘要不一致');
        await this.commit(entry.trip, events);
        for (const event of events) entry.missing.delete(event.id);
        entry.received.push(hash(next.bundle));
      } catch { entry.failed = true; }
    }
    throw new Error('同步消息数量过多');
  }

  receipt(trip, sender, receiver, frames) {
    const token = createHmac('sha256', trip.key).update(canonical({ domain: 'ride-ledger-durable-ack-v2',
      tripId: trip.id, session: this.session, sender, receiver, frames })).digest('base64url');
    return { tripId: trip.id, token };
  }

  receipts() {
    if (this.manifestFailed) return [];
    return [...this.common.values()].filter(entry => !entry.failed && !entry.missing.size && this.isCurrent(entry.trip))
      .map(entry => this.receipt(entry.trip, this.remoteId, this.deviceId, entry.received));
  }

  expectedReceipts() {
    return [...this.common.values()].map(entry => this.receipt(entry.trip, this.deviceId, this.remoteId, entry.sent));
  }

  confirmed(receipts, expected) {
    if (!Array.isArray(receipts) || receipts.length > maxTrips || !expected.length) return false;
    const tokens = new Map(receipts.map(value => [value?.tripId, value?.token]));
    return expected.every(({ tripId, token }) => {
      const other = tokens.get(tripId);
      return typeof other === 'string' && Buffer.byteLength(other) === token.length &&
        timingSafeEqual(Buffer.from(token), Buffer.from(other));
    });
  }

  result(acknowledged) {
    const complete = acknowledged && !this.manifestFailed && [...this.common.values()].every(entry =>
      !entry.failed && !entry.missing.size && this.isCurrent(entry.trip));
    return { complete, modern: true, remoteId: this.remoteId };
  }

  legacyMessage() {
    return { version: 1, bundles: this.snapshot.bundles, peerProtocol: 2, deviceId: this.deviceId, session: this.session };
  }

  async mergeLegacy(message) {
    if (message.version !== 1 || !Array.isArray(message.bundles) || message.bundles.length > maxTrips)
      throw new Error('同步协议不一致');
    const received = new Map();
    let failed = false;
    for (const bundle of message.bundles) {
      const trip = this.snapshot.trips.get(bundle?.tripId);
      if (!trip) continue;
      try {
        if (received.has(trip.id)) throw new Error('同步旅行重复');
        await this.commit(trip, legacyEvents(trip, bundle));
        received.set(trip.id, this.receipt(trip, this.remoteId, this.deviceId, [hash(bundle)]));
      } catch { failed = true; }
    }
    return { received, failed };
  }

  expectedLegacy(received) {
    return this.snapshot.bundles.filter(bundle => received.has(bundle.tripId)).map(bundle =>
      this.receipt(this.snapshot.trips.get(bundle.tripId), this.deviceId, this.remoteId, [hash(bundle)]));
  }

  async exchangeLegacy() {
    await this.wire.write(this.legacyMessage());
    const response = await this.wire.read();
    if (response.peerProtocol !== 2) return { legacy: response };
    if (response.session !== this.session || !validId(response.deviceId) || response.deviceId === this.deviceId)
      throw new Error('同步会话不匹配');
    this.remoteId = response.deviceId;
    const { received, failed } = await this.mergeLegacy(response);
    const complete = !failed && this.confirmed(response.receipts, this.expectedLegacy(received));
    await this.wire.write({ version: 1, kind: 'ack', session: this.session, receipts: [...received.values()] });
    return this.result(complete);
  }

  async acceptLegacy(first) {
    this.remoteId = first.deviceId;
    const { received, failed } = await this.mergeLegacy(first);
    await this.wire.write({ ...this.legacyMessage(), receipts: [...received.values()] });
    const confirmation = await this.wire.read();
    const complete = !failed && confirmation.version === 1 && confirmation.kind === 'ack' &&
      confirmation.session === this.session && this.confirmed(confirmation.receipts, this.expectedLegacy(received));
    return this.result(complete);
  }
}
