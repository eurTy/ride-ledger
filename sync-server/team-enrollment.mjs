import { createHash, createPublicKey, verify } from 'node:crypto';

const ownerDomain = 'ride-ledger-team-relay-owner-1';
const maxBytes = 48 * 1024;
const lifetime = 15 * 60 * 1000;
const identifier = /^[A-Za-z0-9_-]{43}$/;
const blob = (value, max) => typeof value === 'string' && value.length <= max && /^[A-Za-z0-9_-]+$/.test(value);
const sealed = value => value && typeof value.tripId === 'string' && value.tripId.length === 36 &&
  blob(value.iv, 16) && blob(value.data, 2400);
const envelope = ({ tripId, iv, data }) => ({ tripId, iv, data });

// The issuer keeps the authoritative roster in SQLite. This mailbox can safely expire or restart.
export function teamEnrollmentMailbox(reply) {
  const rooms = new Map();
  return async (req, res) => {
    if (req.method === 'GET') return reply(res, 200, { protocol: ownerDomain });
    if (req.method !== 'POST') return reply(res, 405, { error: 'method not allowed' });
    const token = req.headers['x-trip-token'];
    if (typeof token !== 'string' || !identifier.test(token)) return reply(res, 400, { error: 'invalid token' });

    try {
      let size = 0;
      const parts = [];
      for await (const part of req) {
        size += part.length;
        if (size > maxBytes) return reply(res, 413, { error: 'payload too large' });
        parts.push(part);
      }
      const message = JSON.parse(Buffer.concat(parts).toString('utf8'));
      const { action, issuerKey, id, request, proof, answers } = message;
      if (!blob(issuerKey, 160) || !['submit', 'issuer'].includes(action))
        return reply(res, 400, { error: 'invalid request' });
      if (action === 'issuer') {
        const key = createPublicKey({ key: Buffer.from(issuerKey, 'base64url'), format: 'der', type: 'spki' });
        const signed = Buffer.from(JSON.stringify({ domain: ownerDomain, token }));
        if (key.asymmetricKeyDetails?.namedCurve !== 'prime256v1' || !blob(proof, 160) ||
            !verify('sha256', signed, key, Buffer.from(proof, 'base64url')))
          return reply(res, 403, { error: 'issuer access denied' });
        if (!Array.isArray(answers) || answers.length > 12 || answers.some(item =>
          !item || typeof item.id !== 'string' || !identifier.test(item.id) || !sealed(item.decision)))
          return reply(res, 400, { error: 'invalid answers' });
      } else if (typeof id !== 'string' || !identifier.test(id) || !sealed(request)) {
        return reply(res, 400, { error: 'invalid enrollment request' });
      }

      const now = Date.now();
      for (const [key, room] of rooms) {
        for (const [id, item] of room) if (item.expires <= now) room.delete(id);
        if (!room.size) rooms.delete(key);
      }
      const roomKey = createHash('sha256').update(`${issuerKey}:${token}`).digest('hex');
      let room = rooms.get(roomKey);
      if (action === 'issuer') {
        if (!room) return reply(res, 200, { pending: [] });
        for (const { id, decision } of answers) {
          const item = room.get(id);
          if (item && !item.decision) {
            item.decision = envelope(decision);
            item.expires = now + lifetime;
          }
        }
        const pending = [...room].filter(([, item]) => !item.decision)
          .map(([id, item]) => ({ id, request: item.request }));
        return reply(res, 200, { pending });
      }

      const previous = room?.get(id);
      if (previous) return reply(res, 200, { decision: previous.decision });
      if (room?.size >= 12 || (!room && rooms.size >= 256)) return reply(res, 429, { error: 'mailbox busy' });
      if (!room) rooms.set(roomKey, room = new Map());
      room.set(id, { request: envelope(request), decision: null, expires: now + lifetime });
      return reply(res, 200, { decision: null });
    } catch {
      return reply(res, 400, { error: 'invalid enrollment message' });
    }
  };
}
