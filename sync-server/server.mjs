import http from 'node:http';
import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const port = Number(process.env.PORT || 8787);
const dataDir = path.resolve(process.env.DATA_DIR || path.join(import.meta.dirname, 'data'));
const webDir = path.resolve(import.meta.dirname, '../web');
const maxBytes = 12_000_000;
await fs.mkdir(dataDir, { recursive: true });

const validId = value => typeof value === 'string' &&
  /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value);
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const locks = new Map();

async function withLock(id, action) {
  const prior = locks.get(id) || Promise.resolve();
  const current = prior.catch(() => {}).then(action);
  locks.set(id, current);
  try { return await current; }
  finally { if (locks.get(id) === current) locks.delete(id); }
}

function reply(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

const server = http.createServer(async (req, res) => {
  if (req.method === 'GET' && req.url === '/health') return reply(res, 200, { ok: true });
  const staticFiles = {
    '/': ['index.html', 'text/html; charset=utf-8'],
    '/index.html': ['index.html', 'text/html; charset=utf-8'],
    '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
    '/styles.css': ['styles.css', 'text/css; charset=utf-8'],
    '/sw.js': ['sw.js', 'text/javascript; charset=utf-8'],
  };
  if (req.method === 'GET' && staticFiles[req.url]) {
    const [name, type] = staticFiles[req.url];
    try {
      const content = await fs.readFile(path.join(webDir, name));
      res.writeHead(200, { 'Content-Type': type, 'Cache-Control': 'no-store' });
      return res.end(content);
    } catch { return reply(res, 404, { error: 'not found' }); }
  }
  if (req.method !== 'POST' || req.url !== '/sync') return reply(res, 404, { error: 'not found' });
  try {
    let size = 0;
    const parts = [];
    for await (const part of req) {
      size += part.length;
      if (size > maxBytes) return reply(res, 413, { error: 'payload too large' });
      parts.push(part);
    }
    const message = JSON.parse(Buffer.concat(parts).toString('utf8'));
    const { tripId, deviceId, bundle } = message;
    const token = req.headers['x-trip-token'];
    if (!validId(tripId) || !validId(deviceId) || typeof token !== 'string' || token.length < 32 ||
        !bundle || bundle.tripId !== tripId || typeof bundle.iv !== 'string' ||
        typeof bundle.data !== 'string' || bundle.data.length > maxBytes)
      return reply(res, 400, { error: 'invalid request' });

    const result = await withLock(tripId, async () => {
      const file = path.join(dataDir, `${tripId}.json`);
      let record;
      try { record = JSON.parse(await fs.readFile(file, 'utf8')); }
      catch (error) {
        if (error.code !== 'ENOENT') throw error;
        record = { tokenHash: hash(token), snapshots: {} };
      }
      const actual = Buffer.from(hash(token), 'hex');
      const expected = Buffer.from(record.tokenHash, 'hex');
      if (actual.length !== expected.length || !crypto.timingSafeEqual(actual, expected))
        return { code: 403, body: { error: 'trip access denied' } };
      if (!record.snapshots[deviceId] && Object.keys(record.snapshots).length >= 8)
        return { code: 409, body: { error: 'device limit reached' } };
      record.snapshots[deviceId] = bundle;
      const temp = `${file}.${crypto.randomUUID()}.tmp`;
      await fs.writeFile(temp, JSON.stringify(record), { mode: 0o600 });
      await fs.rename(temp, file);
      return { code: 200, body: { snapshots: Object.values(record.snapshots) } };
    });
    reply(res, result.code, result.body);
  } catch (error) {
    console.error(error);
    reply(res, 500, { error: 'internal error' });
  }
});

const host = process.env.HOST || '127.0.0.1';
server.listen(port, host, () => console.log(`Ride Ledger sync server listening on ${host}:${port}`));
