import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

// Independent legacy fixtures for the Android compatibility checks.
export function inviteCode(trip) {
  const { id, name, personA, personB, secret, localPerson, syncUrl } = trip;
  return Buffer.from(JSON.stringify({ id, name, personA, personB, secret, inviterRole: localPerson,
    ...(syncUrl ? { syncUrl } : {}) })).toString('base64url');
}

export function parseInviteCode(code) {
  return JSON.parse(Buffer.from(code, 'base64url').toString('utf8'));
}

export function encryptedBundle(trip, events) {
  const iv = randomBytes(12);
  const cipher = createCipheriv('aes-256-gcm', Buffer.from(trip.secret, 'base64url'), iv);
  cipher.setAAD(Buffer.from(trip.id));
  const data = Buffer.concat([cipher.update(JSON.stringify({ events })), cipher.final(), cipher.getAuthTag()]);
  return { tripId: trip.id, iv: iv.toString('base64url'), data: data.toString('base64url') };
}

export function decryptBundle(trip, bundle) {
  const data = Buffer.from(bundle.data, 'base64url');
  const cipher = createDecipheriv('aes-256-gcm', Buffer.from(trip.secret, 'base64url'), Buffer.from(bundle.iv, 'base64url'));
  cipher.setAAD(Buffer.from(trip.id));
  cipher.setAuthTag(data.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(data.subarray(0, -16)), cipher.final()]).toString('utf8')).events;
}
