import net from 'node:net';
import dgram from 'node:dgram';
import os from 'node:os';
import { EventEmitter } from 'node:events';
import Bonjour from 'bonjour-service';
import { PeerWire, validBundle, validId } from './peer-protocol.mjs';
import { Admission, DesktopTeamProtocol, routeId } from './team.mjs';

const teamDiscoveryPort = 45872;
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
  constructor({ discovery = true, host = '0.0.0.0', commit, teams } = {}) {
    super(); this.discovery = discovery; this.host = host; this.commit = commit;
    this.peers = new Map(); this.busy = new Set(); this.sockets = new Set();
    this.teams = teams;
    this.teamStatus = '等待附近设备连接';
  }
  async start() {
    this.server = net.createServer(socket => this.accept(socket));
    await new Promise((resolve, reject) => {
      this.server.once('error', reject); this.server.listen(0, this.host, resolve);
    });
    this.port = this.server.address().port;
    if (this.discovery) {
      try { this.bonjour = new Bonjour({}, () => {}); } catch { /* Scoped UDP discovery remains available. */ }
      this.startUdp();
    }
    this.teamTimer = setInterval(() => { this.beacon(); this.tick(); }, 4000);
    this.teamTimer.unref();
  }

  startUdp() {
    const udp = this.udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
    let replyAt = 0, replies = 0;
    udp.on('error', () => {});
    udp.on('message', (packet, remote) => {
      if (!this.teamTrip || packet.length >= 1024 ||
          !/^(?:10\.|192\.168\.|172\.(?:1[6-9]|2\d|3[01])\.)/.test(remote.address)) return;
      try {
        const value = JSON.parse(packet.toString('utf8'));
        const scope = this.teamTrip.fingerprint.slice(0, 12);
        if (value.team !== scope || value.app !== 'ride-ledger-nearby' || value.version !== 2 || !validId(value.deviceId) ||
            value.deviceId === this.teamRoute || !Number.isInteger(value.port) || value.port < 1 || value.port > 65535) return;
        this.remember('udp:' + value.deviceId, { host: remote.address, port: value.port, route: value.deviceId,
          expires: Date.now() + 40000, team: this.teamTrip.fingerprint });
        const now = Date.now();
        if (now - replyAt >= 1000) { replyAt = now; replies = 0; }
        if (value.request === true && replies++ < 16) this.sendBeacon(remote.address, false);
      } catch { /* Ignore unrelated datagrams. */ }
    });
    udp.bind(teamDiscoveryPort, () => {
      try { udp.setBroadcast(true); this.udpReady = true; this.beacon(); } catch { /* mDNS remains available. */ }
    });
  }

  beacon() {
    if (!this.udpReady || !this.teamRoute) return;
    const addresses = new Set(['255.255.255.255']);
    for (const { address, netmask } of localInterfaces().slice(0, 12)) {
      const broadcast = (addressNumber(address) | ~addressNumber(netmask)) >>> 0;
      addresses.add([24, 16, 8, 0].map(shift => (broadcast >>> shift) & 255).join('.'));
    }
    for (const address of addresses) this.sendBeacon(address, true);
  }

  sendBeacon(address, request) {
    const packet = Buffer.from(JSON.stringify({ app: 'ride-ledger-nearby', version: 2,
      deviceId: this.teamRoute, port: this.port, request, team: this.teamTrip.fingerprint.slice(0, 12) }));
    this.udp.send(packet, teamDiscoveryPort, address, () => {});
  }

  updateTeam(trip) {
    const ready = trip && this.teams.ready(trip);
    const previous = this.teamTrip;
    this.teamTrip = trip;
    this.teamRoute = trip ? routeId(trip, this.teams.device) : '';
    if (previous?.fingerprint !== trip?.fingerprint) {
      this.teamStatus = '等待附近设备连接';
      this.peers.clear();
      this.teamAdvertisement?.stop(); this.teamBrowser?.stop();
      if (trip && this.bonjour) this.announceTeam(trip);
      this.beacon();
    }
    if (previous?.root !== trip?.root) this.tick();
    return trip && { status: this.teamStatus, ready };
  }

  announceTeam(trip) {
    const type = 'rl' + trip.fingerprint.slice(0, 12);
    const name = 'RideLedger-' + this.teamRoute.slice(0, 8);
    try {
      this.teamAdvertisement = this.bonjour.publish({ name, type, protocol: 'tcp', port: this.port,
        txt: { p: '2', id: this.teamRoute } });
      this.teamBrowser = this.bonjour.find({ type, protocol: 'tcp' }, service => {
        if (!this.currentTeam(trip)) return;
        const route = String(service.txt?.id);
        const host = service.addresses?.find(net.isIPv4) || service.referer?.address;
        if (validId(route) && route !== this.teamRoute && net.isIPv4(host || '') && service.port > 0)
          this.remember('team:dns:' + service.name, { host, port: service.port, route, team: trip.fingerprint });
      });
      this.teamBrowser.on('down', service => this.peers.delete('team:dns:' + service.name));
    } catch { /* Scoped UDP discovery remains available. */ }
  }

  currentTeam(trip) {
    return this.teamTrip?.fingerprint === trip.fingerprint && this.teamTrip.secret === trip.secret &&
      this.teamTrip.localPerson === trip.localPerson;
  }

  teamProtocol(wire, trip) {
    return new DesktopTeamProtocol(wire, trip, this.teams, this.commit, current => this.currentTeam(current));
  }

  setTeamStatus(status) {
    if (this.teamStatus === status) return;
    this.teamStatus = status;
    this.emit('incoming');
  }

  remember(name, peer) {
    const previous = this.peers.get(name);
    if (previous?.host === peer.host && previous.port === peer.port) {
      previous.expires = peer.expires;
      return;
    }
    if (this.peers.size >= 32 && !previous) return;
    this.peers.set(name, peer);
    this.tick();
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
      const message = await wire.read(8192);
      const trip = this.teamTrip;
      if (!validBundle(message) || !trip || trip.id !== message.tripId) return;
      const result = await this.teamProtocol(wire, trip).accept(message);
      if (result.complete && this.currentTeam(trip)) this.setTeamStatus('附近已同步 · 同伴已确认保存');
    } catch { /* Invalid or interrupted incoming requests are retried by the caller. */ }
    finally { socket.end(); }
  }
  tick() {
    const trip = this.teamTrip;
    if (this.closing || !trip) return;
    const ready = this.teams.ready(trip);
    const issuer = ready ? '' : routeId(trip, trip.team.issuer);
    const now = Date.now();
    for (const [name, peer] of this.peers) {
      if (peer.expires && peer.expires < now) this.peers.delete(name);
      else if (peer.team === trip.fingerprint && (!peer.retryAt || peer.retryAt <= now) && (ready || peer.route === issuer))
        this.exchange(peer, trip);
    }
  }
  async exchange(peer, trip) {
    const key = peer.host + ':' + peer.port;
    if (this.busy.has(key)) return;
    const socket = new net.Socket();
    if (!this.track(socket)) return;
    this.busy.add(key);
    try {
      await connect(socket, peer);
      const result = await this.teamProtocol(new PeerWire(socket), trip).exchange(peer.route);
      if (!result.complete) throw new Error('保存确认待重试');
      if (this.currentTeam(trip)) this.setTeamStatus('附近已同步 · 同伴已确认保存');
      peer.failures = 0;
      peer.retryAt = 0;
    } catch (error) {
      peer.failures = Math.min((peer.failures || 0) + 1, 5);
      peer.retryAt = Date.now() + Math.min(60000, 5000 * 2 ** (peer.failures - 1));
      if (this.currentTeam(trip)) this.setTeamStatus(error instanceof Admission ? error.message : '本机账目已保留 · 等待附近重连');
    }
    finally { socket.end(); this.busy.delete(key); }
  }
  async close() {
    this.closing = true;
    clearInterval(this.teamTimer); this.teamBrowser?.stop(); this.bonjour?.destroy();
    this.udpReady = false;
    try { this.udp?.close(); } catch { /* It may not have opened. */ }
    for (const socket of this.sockets) socket.destroy();
    if (this.server) await new Promise(resolve => this.server.close(resolve));
  }
}
