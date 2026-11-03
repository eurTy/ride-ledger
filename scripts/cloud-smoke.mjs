// Adds one encrypted expense through a second device identity to verify cloud pull.
import crypto from 'node:crypto';

const endpoint = process.argv[2];
const code = process.env.INVITE_CODE;
if (!endpoint || !code) throw new Error('Usage: INVITE_CODE=<code> node scripts/cloud-smoke.mjs <endpoint>');
const trip = JSON.parse(Buffer.from(code, 'base64url').toString('utf8'));
const key = Buffer.from(trip.secret, 'base64url');
const event = {
  id: crypto.randomUUID(), tripId: trip.id, kind: 'expense', at: Date.now(),
  data: { payer: 'b', currency: 'CNY', amount: 1000, shareA: 5000,
    mode: 'shared', note: '云同步联调测试' }
};
const iv = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
cipher.setAAD(Buffer.from(trip.id));
const data = Buffer.concat([
  cipher.update(JSON.stringify({ events: [event] }), 'utf8'),
  cipher.final(), cipher.getAuthTag()
]);
const token = crypto.createHmac('sha256', key)
  .update(`ride-ledger-cloud-v1:${trip.id}`).digest('base64url');
const response = await fetch(`${endpoint}/sync`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', 'X-Trip-Token': token },
  body: JSON.stringify({ tripId: trip.id, deviceId: crypto.randomUUID(),
    bundle: { tripId: trip.id, iv: iv.toString('base64url'), data: data.toString('base64url') } })
});
if (!response.ok) throw new Error(`Server returned ${response.status}`);
console.log(`Posted encrypted test event ${event.id}`);
