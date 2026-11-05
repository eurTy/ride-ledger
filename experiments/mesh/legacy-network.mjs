import { createHash } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopLanSync } from '../../desktop/lan-sync.mjs';
import { client, state } from '../../tests/support/web-client.mjs';

// Real released TCP framing, real AES-GCM and the existing ledger merge implementation.
// Loopback links validate the data protocol, not Wi-Fi, Android or Bluetooth radios.
export async function replica(trip, events = [], persisted) {
  const browser = client(persisted || state([trip], events));
  const lan = new DesktopLanSync({ discovery: false, host: '127.0.0.1' });
  await lan.start();
  const node = { browser, lan, trip, active: true, sent: 0, received: 0 };
  const track = lan.track.bind(lan);
  lan.track = socket => {
    track(socket);
    socket.once('close', () => { node.sent += socket.bytesWritten; node.received += socket.bytesRead; });
  };
  node.pump = async () => {
    if (!node.active) return;
    const revision = createHash('sha256').update(JSON.stringify(browser.stored().events)).digest('hex');
    const result = lan.update({ revision, bundles: [await browser.api.encryptedBundle(trip)] });
    for (const bundle of result.bundles) await browser.api.mergeSyncedBundle(trip, bundle);
  };
  node.append = (kind, data) => browser.api.addEvent(kind, data);
  node.stop = async () => { if (!node.active) return; node.active = false; await lan.close(); };
  await node.pump(); return node;
}
export function connect(left, right) {
  // Discovery normally fills this peer table; the test controls topology and disables all LAN broadcast.
  left.lan.peers.set('probe-' + right.lan.port, { host: '127.0.0.1', port: right.lan.port });
  left.lan.tick();
}
export function partition(...nodes) { for (const node of nodes) node.lan.peers.clear(); }
export async function settle(nodes, expected, timeout = 7000) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    for (const node of nodes) await node.pump();
    // Explicit test scheduler: no claim is made about the production 10/15 second UI timers.
    for (const node of nodes) if (node.active) node.lan.tick();
    if (nodes.every(node => node.browser.stored().events.length === expected)) return;
    await delay(30);
  }
  throw new Error('replicas did not converge: ' + nodes.map(node => node.browser.stored().events.length).join(','));
}
