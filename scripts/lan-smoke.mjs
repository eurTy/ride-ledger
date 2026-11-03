// Simulates a second device talking to the Android LAN listener over adb forward.
import crypto from 'node:crypto';
import net from 'node:net';

const code = process.env.INVITE_CODE;
const port = Number(process.argv[2]);
if (!code || !Number.isInteger(port) || port < 1 || port > 65535)
  throw new Error('Usage: INVITE_CODE=<code> node scripts/lan-smoke.mjs <forwarded-port>');

const trip = JSON.parse(Buffer.from(code, 'base64url').toString('utf8'));
const key = Buffer.from(trip.secret, 'base64url');
if (key.length !== 32) throw new Error('Invalid trip key');

const event = {
  id: crypto.randomUUID(), tripId: trip.id, kind: 'expense', at: Date.now(),
  data: { payer: 'b', currency: 'CNY', amount: 2000, shareA: 5000,
    mode: 'shared', note: '局域网联调测试' }
};
const iv = crypto.randomBytes(12);
const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
cipher.setAAD(Buffer.from(trip.id));
const encrypted = Buffer.concat([
  cipher.update(JSON.stringify({ events: [event] }), 'utf8'),
  cipher.final(), cipher.getAuthTag()
]);
const message = Buffer.from(JSON.stringify({ version: 1, bundles: [{
  tripId: trip.id, iv: iv.toString('base64url'), data: encrypted.toString('base64url')
}] }));
const header = Buffer.alloc(4);
header.writeUInt32BE(message.length);

const socket = net.connect(port, '127.0.0.1');
socket.setTimeout(12000);
let incoming = Buffer.alloc(0);
const response = new Promise((resolve, reject) => {
  socket.on('data', chunk => {
    incoming = Buffer.concat([incoming, chunk]);
    if (incoming.length < 4) return;
    const size = incoming.readUInt32BE();
    if (size < 1 || size > 12_000_000) return reject(new Error('Invalid frame length'));
    if (incoming.length >= size + 4) resolve(JSON.parse(incoming.subarray(4, size + 4)));
  });
  socket.on('error', reject);
  socket.on('timeout', () => reject(new Error('LAN response timed out')));
  socket.on('end', () => reject(new Error('LAN peer closed before response')));
});
socket.on('connect', () => socket.write(Buffer.concat([header, message])));
const peer = await response.finally(() => socket.destroy());
if (peer.version !== 1) throw new Error('Unexpected LAN protocol version');
const bundle = peer.bundles.find(item => item.tripId === trip.id);
if (!bundle) throw new Error('Android did not send this trip');
const ciphertext = Buffer.from(bundle.data, 'base64url');
const decipher = crypto.createDecipheriv('aes-256-gcm', key, Buffer.from(bundle.iv, 'base64url'));
decipher.setAAD(Buffer.from(trip.id));
decipher.setAuthTag(ciphertext.subarray(-16));
const plain = Buffer.concat([decipher.update(ciphertext.subarray(0, -16)), decipher.final()]);
const received = JSON.parse(plain.toString('utf8')).events;
if (!Array.isArray(received) || !received.length) throw new Error('Android snapshot was empty');
console.log(`LAN exchange succeeded; received ${received.length} Android events and sent 1 encrypted event.`);
