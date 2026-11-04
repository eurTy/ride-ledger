import net from 'node:net';
import os from 'node:os';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import Bonjour from 'bonjour-service';

const maxFrame = 12_000_000;
const validBundle = value => value && typeof value.tripId === 'string' &&
  typeof value.iv === 'string' && typeof value.data === 'string';

function readFrame(socket) {
  return new Promise((resolve, reject) => {
    let buffer = Buffer.alloc(0), done = false;
    const finish = (error, value) => {
      if (done) return; done = true;
      socket.off('data', data); socket.off('end', ended); socket.off('error', failed);
      if (error) reject(error); else resolve(value);
    };
    const failed = error => finish(error);
    const ended = () => finish(new Error('同伴连接中断'));
    const data = part => {
      buffer = Buffer.concat([buffer, part]);
      if (buffer.length < 4) return;
      const size = buffer.readInt32BE(0);
      if (size < 1 || size > maxFrame) { finish(new Error('同步数据大小无效')); socket.destroy(); return; }
      if (buffer.length < size + 4) return;
      try { finish(null, JSON.parse(buffer.subarray(4, size + 4).toString('utf8'))); }
      catch { finish(new Error('同步数据不完整')); }
    };
    socket.on('data', data); socket.once('end', ended); socket.once('error', failed);
  });
}
function writeFrame(socket, message) {
  const body = Buffer.from(JSON.stringify(message));
  if (body.length > maxFrame) throw new Error('旅行记录超出同步上限');
  const header = Buffer.alloc(4); header.writeInt32BE(body.length);
  socket.write(Buffer.concat([header, body]));
}

export class DesktopLanSync extends EventEmitter {
  constructor({ discovery = true, host = '0.0.0.0' } = {}) {
    super(); this.discovery = discovery; this.host = host;
    this.bundles = []; this.revision = ''; this.incoming = new Map(); this.peers = new Map(); this.busy = new Set();
    this.server = null; this.bonjour = null; this.browser = null; this.timer = null;
    this.sockets = new Set(); this.status = '局域网同步准备中';
  }
  async start() {
    this.server = net.createServer(socket => this.accept(socket));
    await new Promise((resolve, reject) => {
      this.server.once('error', reject); this.server.listen(0, this.host, resolve);
    });
    this.port = this.server.address().port;
    this.status = '同一热点下可自动同步';
    if (this.discovery) {
      try {
        const name = 'RideLedger-desktop-' + this.port;
        this.bonjour = new Bonjour({}, () => { this.status = '自动发现暂不可用，可用地址直连'; });
        this.bonjour.publish({ name, type: 'rideledger', protocol: 'tcp', port: this.port });
        this.browser = this.bonjour.find({ type: 'rideledger', protocol: 'tcp' }, service => {
          if (service.name === name) return;
          const host = service.addresses?.find(address => net.isIP(address) === 4) || service.referer?.address;
          if (!host || !service.port) return;
          this.peers.set(service.name, { host, port: service.port }); this.tick();
        });
        this.browser.on('down', service => this.peers.delete(service.name));
      } catch { this.status = '自动发现暂不可用，可用地址直连'; }
    }
    this.timer = setInterval(() => this.tick(), 10000); this.timer.unref();
  }
  snapshot() { return { version: 1, bundles: this.bundles }; }
  update(snapshot) {
    if (!snapshot || !Array.isArray(snapshot.bundles) || !snapshot.bundles.every(validBundle) ||
        Buffer.byteLength(JSON.stringify(snapshot)) > maxFrame) throw new Error('同步快照无效');
    const revision = snapshot.revision || crypto.createHash('sha256').update(JSON.stringify(snapshot.bundles)).digest('hex');
    const changed = revision !== this.revision;
    if (changed) { this.bundles = snapshot.bundles; this.revision = revision; }
    const queued = [...this.incoming.values()]; this.incoming.clear();
    if (changed) this.tick();
    return { bundles: queued, status: this.status, addresses: this.addresses() };
  }
  receive(message) {
    if (message.version !== 1 || !Array.isArray(message.bundles)) throw new Error('同步协议不一致');
    const known = new Set(this.bundles.map(bundle => bundle.tripId));
    let count = 0;
    for (const bundle of message.bundles) {
      if (!validBundle(bundle) || !known.has(bundle.tripId)) continue;
      const fingerprint = crypto.createHash('sha256').update(bundle.data).digest('hex');
      this.incoming.set(bundle.tripId + ':' + fingerprint, bundle); count++;
      if (this.incoming.size > 64) this.incoming.delete(this.incoming.keys().next().value);
    }
    if (count) this.emit('incoming');
  }
  track(socket) {
    this.sockets.add(socket);
    socket.setTimeout(12000, () => socket.destroy(new Error('同伴连接超时')));
    socket.on('error', () => {});
    socket.once('close', () => this.sockets.delete(socket));
  }
  async accept(socket) {
    this.track(socket);
    try {
      const message = await readFrame(socket);
      writeFrame(socket, this.snapshot()); this.receive(message);
      this.status = '局域网已交换记录';
    } catch { this.status = '同伴连接待重试'; }
    finally { socket.end(); }
  }
  tick() { for (const peer of this.peers.values()) this.exchange(peer); }
  async exchange(peer) {
    const key = peer.host + ':' + peer.port;
    if (this.busy.has(key)) return;
    this.busy.add(key);
    const socket = new net.Socket(); this.track(socket);
    try {
      await new Promise((resolve, reject) => {
        const timeout = setTimeout(() => { socket.destroy(); reject(new Error('连接超时')); }, 3500);
        socket.once('error', error => { clearTimeout(timeout); reject(error); });
        socket.connect(peer.port, peer.host, () => { clearTimeout(timeout); resolve(); });
      });
      writeFrame(socket, this.snapshot()); this.receive(await readFrame(socket));
      this.status = '局域网已交换记录';
    } catch { this.status = '等待同伴重新连接'; }
    finally { socket.end(); this.busy.delete(key); }
  }
  connectManual(raw) {
    const match = String(raw).trim().match(/^([^:\s]+):(\d{1,5})$/);
    if (!match || !net.isIP(match[1]) || Number(match[2]) < 1 || Number(match[2]) > 65535)
      throw new Error('请输入有效的 IP:端口');
    this.peers.set('manual', { host: match[1], port: Number(match[2]) }); this.tick();
  }
  addresses() {
    return Object.values(os.networkInterfaces()).flat().filter(address =>
      address && !address.internal && address.family === 'IPv4' &&
      !address.address.startsWith('169.254.')).map(address => address.address + ':' + this.port);
  }
  async close() {
    clearInterval(this.timer); this.browser?.stop(); this.bonjour?.destroy();
    for (const socket of this.sockets) socket.destroy();
    if (this.server) await new Promise(resolve => this.server.close(resolve));
  }
}
