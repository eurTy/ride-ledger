import {
  createHash, createHmac, createPrivateKey, createPublicKey, diffieHellman, generateKeyPairSync,
  randomBytes, randomUUID, sign, verify,
} from 'node:crypto';
import { canonical, hash, indexTrip, openBundle, peerAad, PeerProtocol, sealPeer, validId } from './peer-protocol.mjs';

const invitePrefix = 'RLTEAM1.';
const seatDomain = 'ride-ledger-team-seat-1';
const sessionDomain = 'ride-ledger-team-session-1';
const base64url = /^[A-Za-z0-9_-]+$/;
const p256 = () => generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const encodeKey = key => key.export({ type: 'spki', format: 'der' }).toString('base64url');
const trim = text => typeof text === 'string' ? text.replace(/^[\u0000-\u0020]+|[\u0000-\u0020]+$/g, '') : '';
export class Admission extends Error { }
export const validLegacySnapshotName = name => name.endsWith('.json') && validId(name.slice(0, -5));

function publicKey(encoded) {
  if (typeof encoded !== 'string' || encoded.length > 200 || !base64url.test(encoded)) throw new Error('设备公钥无效');
  const key = createPublicKey({ key: Buffer.from(encoded, 'base64url'), format: 'der', type: 'spki' });
  if (key.asymmetricKeyDetails?.namedCurve !== 'prime256v1' || encodeKey(key) !== encoded) throw new Error('设备公钥无效');
  return key;
}

export function verifySignature(key, body, proof) {
  return typeof proof === 'string' && proof.length <= 200 && base64url.test(proof) &&
    verify('sha256', Buffer.from(canonical(body)), publicKey(key), Buffer.from(proof, 'base64url'));
}

export function endpoint(raw = '') {
  if (typeof raw !== 'string') throw new Error('同步地址必须是有效的 HTTPS 地址');
  const value = raw.trim().replace(/\/+$/, '');
  if (!value) return '';
  const url = new URL(value);
  if (url.protocol !== 'https:' || !url.hostname || url.username || url.password || url.search || url.hash ||
      url.port === '0' || value.length > 250) throw new Error('同步地址必须是有效的 HTTPS 地址');
  return value;
}

function descriptor(input) {
  const { format, tripId, issuer, issuerKey } = input ?? {};
  if (format !== 1 || !validId(tripId) || !validId(issuer) || !Array.isArray(input.names) ||
      input.names.length < 1 || input.names.length > 5) throw new Error('小队资料无效');
  publicKey(issuerKey);
  const names = input.names.map(trim);
  if (names.some(name => !name || name.length > 30) || new Set(names.map(name => name.toLowerCase())).size !== names.length)
    throw new Error('成员名字须各不相同，每个最多 30 字');
  return { format, tripId, issuer, issuerKey, names };
}

function tripData(raw) {
  const team = descriptor(raw?.team);
  const name = trim(raw.name);
  if (raw.id !== team.tripId || !name || name.length > 80 || typeof raw.secret !== 'string' ||
      !base64url.test(raw.secret) || Buffer.from(raw.secret, 'base64url').length !== 32 ||
      !/^m[1-5]$/.test(raw.localPerson) || Number(raw.localPerson[1]) > team.names.length) throw new Error('小队邀请无效');
  return { id: team.tripId, name, team, members: team.names, secret: raw.secret,
    localPerson: raw.localPerson, syncUrl: endpoint(raw.syncUrl) };
}

export function readInvite(code) {
  const clean = String(code).trim();
  if (!clean.startsWith(invitePrefix) || clean.length > 4096 || !base64url.test(clean.slice(invitePrefix.length)))
    throw new Error('请使用安卓版的小队邀请二维码或邀请码');
  const raw = JSON.parse(Buffer.from(clean.slice(invitePrefix.length), 'base64url').toString('utf8'));
  return tripData({ ...raw, id: raw.team?.tripId, localPerson: 'm2' });
}

export function encodeInvite(raw) {
  const { team, name, secret, syncUrl } = tripData(raw);
  if (team.names.length < 2) throw new Error('单人旅行无需邀请码');
  return invitePrefix + Buffer.from(JSON.stringify({ team, name, secret, ...(syncUrl ? { syncUrl } : {}) })).toString('base64url');
}

function uuid3(text) {
  const bytes = createHash('md5').update(text).digest();
  bytes[6] = (bytes[6] & 15) | 48;
  bytes[8] = (bytes[8] & 63) | 128;
  return bytes.toString('hex').replace(/^(........)(....)(....)(....)(............)$/, '$1-$2-$3-$4-$5');
}

export const routeId = (trip, device) => uuid3(uuid3(`${trip.fingerprint}:${device}`).slice(0, 13).replace('-', ''));

function grantBody(trip, { device, key, member }) {
  const seat = Number(member?.[1]) - 1;
  if (!validId(device) || !/^m[1-5]$/.test(member) || seat >= trip.members.length ||
      (seat === 0) !== (device === trip.team.issuer) || (seat === 0) !== (key === trip.team.issuerKey))
    throw new Error('设备席位无效');
  publicKey(key);
  return { domain: seatDomain, team: trip.fingerprint, device, key, member, seat };
}

export function checkGrant(trip, grant, peer = grant) {
  const body = grantBody(trip, peer);
  if (!verifySignature(trip.team.issuerKey, body, grant?.signature) ||
      canonical(grant) !== canonical({ ...body, signature: grant.signature })) throw new Error('设备登记凭证无效');
}

// All claims use the same synchronous, durable seat table, including the HTTPS path.
export class DesktopTeams {
  constructor(saved, persist) {
    this.persist = persist;
    if (saved) this.data = JSON.parse(saved);
    else {
      const { privateKey } = p256();
      this.data = { device: randomUUID(), privateKey: privateKey.export({ type: 'pkcs8', format: 'der' }).toString('base64url'), seats: {} };
      persist(JSON.stringify(this.data));
    }
    this.device = this.data.device;
    this.privateKey = createPrivateKey({ key: Buffer.from(this.data.privateKey, 'base64url'), type: 'pkcs8', format: 'der' });
    this.key = encodeKey(createPublicKey(this.privateKey));
  }

  sign(body) { return sign('sha256', Buffer.from(canonical(body)), this.privateKey).toString('base64url'); }

  create(name, names) {
    const id = randomUUID();
    const trip = tripData({ id, name, secret: randomBytes(32).toString('base64url'), localPerson: 'm1',
      team: { format: 1, tripId: id, issuer: this.device, issuerKey: this.key, names } });
    const indexed = this.snapshot(trip, []);
    this.enroll(indexed, { device: this.device, key: this.key, member: 'm1' });
    return trip;
  }

  snapshot(raw, events) {
    const trip = tripData(raw);
    const { team } = trip;
    if ((trip.localPerson === 'm1') !== (team.issuer === this.device && team.issuerKey === this.key))
      throw new Error('请选择自己的名字');
    return indexTrip({ ...trip, fingerprint: hash(team), key: Buffer.from(trip.secret, 'base64url') }, events);
  }

  seat(trip, device = this.device) {
    return this.data.seats[trip.fingerprint]?.find(grant => grant.device === device);
  }

  ready(trip) {
    const grant = this.seat(trip);
    if (!grant) return false;
    checkGrant(trip, grant, { device: this.device, key: this.key, member: trip.localPerson });
    return true;
  }

  accept(trip, grant, peer) {
    checkGrant(trip, grant, peer);
    if (grant.device === this.device && grant.member !== trip.localPerson) throw new Error('本机已登记为其他成员');
    const seats = this.data.seats[trip.fingerprint] ?? [];
    const previous = seats.find(item => item.device === grant.device);
    if (previous) { checkGrant(trip, previous, peer); return; }
    if (seats.some(item => item.seat === grant.seat)) throw new Admission('这个名字已登记在另一台设备');
    if (seats.length >= trip.members.length) throw new Admission('小队设备名额已满');
    const next = { ...this.data, seats: { ...this.data.seats, [trip.fingerprint]: [...seats, grant] } };
    this.persist(JSON.stringify(next));
    this.data = next;
  }

  enroll(trip, peer) {
    if (trip.team.issuer !== this.device || trip.team.issuerKey !== this.key) throw new Admission('首次登记需与建队设备连通');
    const body = grantBody(trip, peer);
    const previous = this.seat(trip, peer.device);
    if (previous) { checkGrant(trip, previous, peer); return previous; }
    const grant = { ...body, signature: this.sign(body) };
    this.accept(trip, grant, peer);
    return grant;
  }
}

export class DesktopTeamProtocol {
  constructor(wire, trip, directory, commit, current) {
    Object.assign(this, { wire, trip, directory, commit, current });
  }

  write(key, value) { return this.wire.write(sealPeer(key, { ...value, domain: sessionDomain })); }

  async read(key, kind, first) {
    const body = openBundle(key, first ?? await this.wire.read(8192), peerAad(key));
    if (body.domain !== sessionDomain || body.kind !== kind) throw new Error('小队协议顺序无效');
    return body;
  }

  hello(kind, keys) {
    const { trip, directory } = this;
    const grant = directory.seat(trip);
    return { kind, team: trip.fingerprint, nonce: randomUUID(), device: directory.device, key: directory.key,
      member: trip.localPerson, ephemeral: encodeKey(keys.publicKey), ...(grant ? { grant } : {}) };
  }

  inspect(peer, kind, expectedRoute) {
    const { trip, directory } = this;
    if (peer?.kind !== kind || peer.team !== trip.fingerprint || !validId(peer.nonce) || !validId(peer.device) ||
        peer.device === directory.device || !/^m[1-5]$/.test(peer.member) || Number(peer.member[1]) > trip.members.length ||
        (expectedRoute && expectedRoute !== routeId(trip, peer.device))) throw new Error('小队设备资料无效');
    publicKey(peer.key);
    publicKey(peer.ephemeral);
    if (peer.grant) checkGrant(trip, peer.grant, peer);
    return peer;
  }

  transcript(offer, challenge) { return { domain: sessionDomain, offer: hash(offer), challenge: hash(challenge) }; }

  session(keys, peer, transcript) {
    const shared = diffieHellman({ privateKey: keys.privateKey, publicKey: publicKey(peer.ephemeral) });
    const extracted = createHmac('sha256', this.trip.key).update(shared).digest();
    const key = createHmac('sha256', extracted).update(canonical(transcript)).update(Buffer.from([1])).digest();
    return { ...this.trip, key };
  }

  ledger(session, remoteId) {
    if (!this.current(this.trip)) throw new Error('旅行已切换');
    return new PeerProtocol(this.wire, { deviceId: this.directory.device, remoteId,
      trips: new Map([[session.id, session]]) }, this.commit, this.current);
  }

  async exchange(expectedRoute) {
    const { directory, trip } = this;
    const keys = p256();
    const offer = { ...this.hello('offer', keys), domain: sessionDomain };
    await this.write(trip, offer);
    const reply = await this.read(trip, 'challenge');
    const peer = this.inspect(reply.hello, 'responder', expectedRoute);
    if (!peer.grant) throw new Admission('等待同伴完成首次登记');
    const proof = this.transcript(offer, peer);
    if (!verifySignature(peer.key, { ...proof, role: 'responder' }, reply.proof)) throw new Error('同伴设备认证失败');
    await this.write(trip, { kind: 'proof', proof: directory.sign({ ...proof, role: 'initiator' }) });
    const session = this.session(keys, peer, proof);
    const decision = await this.read(session, 'admission');
    if (!this.current(trip)) throw new Error('旅行已切换');
    if (decision.reason) throw new Admission(decision.reason);
    if (!directory.ready(trip)) directory.accept(trip, decision.grant, { device: directory.device, key: directory.key, member: trip.localPerson });
    directory.accept(trip, peer.grant, peer);
    return this.ledger(session, peer.device).exchange();
  }

  async accept(first) {
    const { directory, trip } = this;
    const offer = this.inspect(await this.read(trip, 'offer', first), 'offer');
    if (!directory.ready(trip)) throw new Admission('正在等待首次登记');
    const keys = p256();
    const challenge = this.hello('responder', keys);
    const transcript = this.transcript(offer, challenge);
    await this.write(trip, { kind: 'challenge', hello: challenge, proof: directory.sign({ ...transcript, role: 'responder' }) });
    const proof = await this.read(trip, 'proof');
    if (!verifySignature(offer.key, { ...transcript, role: 'initiator' }, proof.proof)) throw new Error('同伴设备认证失败');
    if (!this.current(trip)) throw new Error('旅行已切换');
    const session = this.session(keys, offer, transcript);
    const decision = { kind: 'admission' };
    try {
      const grant = offer.grant ?? directory.enroll(trip, offer);
      if (!offer.grant) decision.grant = grant;
      directory.accept(trip, grant, offer);
    } catch (error) {
      if (error instanceof Admission) await this.write(session, { kind: 'admission', reason: error.message });
      throw error;
    }
    await this.write(session, decision);
    return this.ledger(session, offer.device).accept(await this.wire.read());
  }
}
