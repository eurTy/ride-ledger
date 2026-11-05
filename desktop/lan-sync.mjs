import net from 'node:net';
import dgram from 'node:dgram';
import os from 'node:os';
import crypto from 'node:crypto';
import { EventEmitter } from 'node:events';
import Bonjour from 'bonjour-service';
import { maxFrame, PeerProtocol, PeerWire, peerSnapshot, validBundle, validId } from './peer-protocol.mjs';

const discoveryPort = 45871;
const localInterfaces = () => Object.values(os.networkInterfaces()).flat().filter(address =>
  address && !address.internal && address.family === 'IPv4' && !address.address.startsWith('169.254.'));
const addressNumber = ip => ip.split('.').reduce((value, octet) => value * 256 + Number(octet), 0);

function connect(socket, peer) {
  return new Promise((resolve, reject) => {
    const finish = error => {
      clearTimeout(timeout);
      socket.off('error', finish);
      socket.off('close', closed);
      if (error) reject(error); else resolve();
    };
    const closed = () => finish(new Error('同伴连接中断'));
    const timeout = setTimeout(() => socket.destroy(new Error('连接超时')), 3500);
    socket.once('error', finish);
    socket.once('close', closed);
    socket.connect(peer.port, peer.host, () => finish());
  });
}

export class DesktopLanSync extends EventEmitter {
  constructor({ discovery = true, host = '0.0.0.0', commit } = {}) {
    super(); this.discovery = discovery; this.host = host; this.commit = commit;
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
        this.serviceName = 'RideLedger-desktop-' + this.port;
        this.bonjour = new Bonjour({}, () => { this.status = '自动发现暂不可用，可用地址直连'; });
        this.browser = this.bonjour.find({ type: 'rideledger', protocol: 'tcp' }, service => {
          if (service.name === this.serviceName || String(service.txt?.id) === this.peerState?.deviceId) return;
          const host = service.addresses?.find(address => net.isIP(address) === 4) || service.referer?.address;
          if (net.isIPv4(host || '') && service.port > 0)
            this.remember(service.name, { host, port: service.port, modern: String(service.txt?.p) === '2' });
        });
        this.browser.on('down', service => this.peers.delete(service.name));
      } catch { this.status = '自动发现暂不可用，可用地址直连'; }
      this.startUdp();
    }
    this.timer = setInterval(() => {
      this.beacon();
      this.tick();
    }, 10000);
    this.timer.unref();
  }

  announce() {
    const deviceId = this.peerState?.deviceId;
    if (!this.discovery || !deviceId || this.announcedId === deviceId) return;
    this.announcedId = deviceId;
    try {
      this.advertisement?.stop();
      this.advertisement = this.bonjour?.publish({ name: this.serviceName, type: 'rideledger', protocol: 'tcp',
        port: this.port, txt: { p: '2', id: deviceId } });
    } catch { this.status = '服务发现待重试，可用地址直连'; }
    this.beacon();
  }

  startUdp() {
    const udp = this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    let replyAt = 0, replies = 0;
    udp.on('error', () => {});
    udp.on('message', (packet, remote) => {
      const deviceId = this.peerState?.deviceId;
      if (!deviceId || packet.length >= 1024 ||
          !/^(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)/.test(remote.address)) return;
      try {
        const value = JSON.parse(packet.toString('utf8'));
        if (value.app !== 'ride-ledger-nearby' || value.version !== 2 || !validId(value.deviceId) ||
            value.deviceId === deviceId || !Number.isInteger(value.port) || value.port < 1 || value.port > 65535) return;
        this.remember('udp:' + value.deviceId, { host: remote.address, port: value.port, modern: true, expires: Date.now() + 40000 });
        const now = Date.now();
        if (now - replyAt >= 1000) { replyAt = now; replies = 0; }
        if (value.request === true && replies++ < 16) this.sendBeacon(remote.address, remote.port, false);
      } catch { /* Ignore unrelated datagrams. */ }
    });
    udp.bind(discoveryPort, () => {
      try { udp.setBroadcast(true); this.udpReady = true; this.beacon(); } catch { /* NSD and manual IP remain available. */ }
    });
  }

  beacon() {
    if (!this.udpReady || !this.peerState) return;
    const addresses = new Set(['255.255.255.255']);
    for (const { address, netmask } of localInterfaces().slice(0, 12)) {
      const broadcast = (addressNumber(address) | ~addressNumber(netmask)) >>> 0;
      addresses.add([24, 16, 8, 0].map(shift => (broadcast >>> shift) & 255).join('.'));
    }
    for (const address of addresses) this.sendBeacon(address, discoveryPort, true);
  }

  sendBeacon(address, port, request) {
    const packet = Buffer.from(JSON.stringify({ app: 'ride-ledger-nearby', version: 2,
      deviceId: this.peerState.deviceId, port: this.port, request }));
    this.udp.send(packet, port, address, () => {});
  }

  remember(name, peer) {
    const previous = this.peers.get(name);
    if (previous?.host === peer.host && previous.port === peer.port) {
      previous.modern ||= peer.modern;
      previous.expires = peer.expires;
      return;
    }
    if (this.peers.size >= 32 && !previous && name !== 'manual') return;
    this.peers.set(name, peer);
    this.tick();
  }

  snapshot() { return { version: 1, bundles: this.bundles }; }
  update(snapshot) {
    if (!snapshot || !Array.isArray(snapshot.bundles) || !snapshot.bundles.every(validBundle) ||
        Buffer.byteLength(JSON.stringify(snapshot)) > maxFrame) throw new Error('同步快照无效');
    const revision = snapshot.revision || crypto.createHash('sha256').update(JSON.stringify(snapshot.bundles)).digest('hex');
    const changed = revision !== this.revision;
    if (changed) {
      this.peerState = this.commit ? peerSnapshot(snapshot) : null;
      this.bundles = snapshot.bundles;
      this.revision = revision;
      this.announce();
    }
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

  setStatus(status) {
    if (this.status === status) return;
    this.status = status;
    this.emit('incoming');
  }

  protocol(wire) {
    if (!this.peerState) throw new Error('本机账本正在加载');
    return new PeerProtocol(wire, this.peerState, this.commit, trip =>
      this.peerState?.trips.get(trip.id)?.secret === trip.secret);
  }

  completed(result, peer) {
    if (result.legacy) {
      this.receive(result.legacy);
      this.setStatus('同热点已交换记录（旧协议）');
    } else if (result.complete) {
      if (peer) peer.modern = true;
      this.setStatus('同热点已同步 · 同伴已确认保存');
    } else throw new Error('保存确认待重试');
  }

  track(socket) {
    if (this.sockets.size >= 8 || this.closing) { socket.destroy(); return false; }
    this.sockets.add(socket);
    socket.setNoDelay(true);
    socket.setTimeout(15000, () => socket.destroy(new Error('同伴连接超时')));
    const deadline = setTimeout(() => socket.destroy(new Error('同伴连接超时')), 180000);
    deadline.unref();
    socket.on('error', () => {});
    socket.once('close', () => { clearTimeout(deadline); this.sockets.delete(socket); });
    return true;
  }
  async accept(socket) {
    if (!this.track(socket)) return;
    try {
      const wire = new PeerWire(socket);
      const message = await wire.read();
      if (message.version === 2 || (this.peerState && message.peerProtocol === 2 &&
          validId(message.deviceId) && message.deviceId !== this.peerState.deviceId && validId(message.session))) {
        this.completed(await this.protocol(wire).accept(message));
      } else {
        await wire.write(this.snapshot());
        this.completed({ legacy: message });
      }
    } catch { this.setStatus('本机账目已保留 · 等待同伴重连'); }
    finally { socket.end(); }
  }
  tick() {
    if (this.closing || !this.bundles.length) return;
    const now = Date.now();
    for (const [name, peer] of this.peers) {
      if (peer.expires && peer.expires < now) this.peers.delete(name);
      else if (!peer.retryAt || peer.retryAt <= now) this.exchange(peer);
    }
  }
  async exchange(peer) {
    const key = peer.host + ':' + peer.port;
    if (this.busy.has(key)) return;
    const socket = new net.Socket();
    if (!this.track(socket)) return;
    this.busy.add(key);
    try {
      await connect(socket, peer);
      const wire = new PeerWire(socket);
      if (this.peerState) this.completed(await this.protocol(wire).exchange(Boolean(peer.modern)), peer);
      else {
        await wire.write(this.snapshot());
        this.completed({ legacy: await wire.read() });
      }
      peer.failures = 0;
      peer.retryAt = 0;
    } catch {
      peer.failures = Math.min((peer.failures || 0) + 1, 5);
      peer.retryAt = Date.now() + Math.min(60000, 5000 * 2 ** (peer.failures - 1));
      this.setStatus('本机账目已保留 · 等待同伴重连');
    }
    finally { socket.end(); this.busy.delete(key); }
  }
  connectManual(raw) {
    const match = String(raw).trim().match(/^([^:\s]+):(\d{1,5})$/);
    if (!match || !net.isIP(match[1]) || Number(match[2]) < 1 || Number(match[2]) > 65535)
      throw new Error('请输入有效的 IP:端口');
    const previous = this.peers.get('manual');
    const port = Number(match[2]);
    if (previous?.host === match[1] && previous.port === port) previous.retryAt = 0;
    else this.remember('manual', { host: match[1], port });
    this.tick();
  }
  addresses() {
    if (this.host !== '0.0.0.0') return [this.host + ':' + this.port];
    return localInterfaces().map(address => address.address + ':' + this.port);
  }
  async close() {
    this.closing = true;
    clearInterval(this.timer); this.browser?.stop(); this.bonjour?.destroy();
    this.udpReady = false;
    try { this.udp?.close(); } catch { /* It may not have opened. */ }
    for (const socket of this.sockets) socket.destroy();
    if (this.server) await new Promise(resolve => this.server.close(resolve));
  }
}
