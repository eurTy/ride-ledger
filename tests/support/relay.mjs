import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, realpath, rm } from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const deferred = () => {
  let resolve;
  const promise = new Promise(done => { resolve = done; });
  return { promise, resolve };
};

export async function startRelay() {
  const tempParent = await realpath(os.tmpdir());
  const directory = await mkdtemp(path.join(tempParent, 'ride-ledger-sync-test-'));
  const reservation = net.createServer();
  reservation.listen(0, '127.0.0.1');
  await once(reservation, 'listening');
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  let child;
  const start = async () => {
    child = spawn(process.execPath, [fileURLToPath(new URL('../../sync-server/server.mjs', import.meta.url))], {
      env: { ...process.env, PORT: String(port), HOST: '127.0.0.1', DATA_DIR: directory },
      windowsHide: true, stdio: ['ignore', 'pipe', 'pipe']
    });
    await new Promise((resolve, reject) => {
      let errors = '';
      const timeout = setTimeout(() => reject(new Error('Isolated sync relay did not start: ' + errors)), 5000);
      child.stderr.on('data', chunk => { errors += chunk; });
      child.once('error', error => { clearTimeout(timeout); reject(error); });
      child.once('exit', code => {
        if (code) { clearTimeout(timeout); reject(new Error('Relay exited: ' + errors)); }
      });
      child.stdout.on('data', chunk => {
        if (String(chunk).includes('listening on')) { clearTimeout(timeout); resolve(); }
      });
    });
  };
  const stop = async () => {
    if (!child || child.exitCode !== null) return;
    const exited = once(child, 'exit');
    child.kill();
    await exited;
  };
  const cleanup = async () => {
    await stop();
    const target = await realpath(directory);
    if (path.dirname(target) !== tempParent || !path.basename(target).startsWith('ride-ledger-sync-test-'))
      throw new Error('Test cleanup target escaped its temporary directory');
    await rm(target, { recursive: true, force: true });
  };
  try { await start(); }
  catch (error) { await cleanup(); throw error; }
  return { origin: 'http://127.0.0.1:' + port, directory, port, start, stop, cleanup };
}

// Each route represents one device's independently failing network path to the real relay.
export function routeTo(origin) {
  const route = { origin, requests: 0, mode: 'normal', transform: null, hold: null };
  route.fetch = async (url, options) => {
    route.requests++;
    if (route.mode === 'offline') throw new TypeError('Simulated unreachable network');
    if (route.mode === '503') return new Response('{}', { status: 503 });
    const response = await fetch(new URL(url, route.origin), options);
    if (route.mode === 'lost-ack') {
      await response.arrayBuffer();
      throw new TypeError('Response lost after the relay committed the upload');
    }
    if (route.mode === 'truncated') {
      await response.arrayBuffer();
      return new Response('{"snapshots":[', { status: 200 });
    }
    const body = await response.json();
    if (route.hold) {
      route.hold.entered.resolve();
      await route.hold.release.promise;
    }
    return new Response(JSON.stringify(route.transform ? await route.transform(body) : body),
      { status: response.status });
  };
  return route;
}
