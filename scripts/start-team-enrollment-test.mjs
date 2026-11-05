import { randomUUID } from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { PhoneTunnel } from '../desktop/phone-tunnel.mjs';
import { startSyncServer } from '../sync-server/server.mjs';

const instanceId = randomUUID();
const relay = await startSyncServer({ port: 0, host: '127.0.0.1', enrollmentOnly: true, instanceId });
const tunnel = new PhoneTunnel({
  port: new URL(relay.origin).port,
  userData: fileURLToPath(new URL('../build/team-enrollment-tunnel/', import.meta.url)),
  instanceId,
});
let stopping = false;
async function stop() {
  if (stopping) return;
  stopping = true;
  tunnel.stop();
  await relay.close();
}
process.once('SIGINT', stop);
process.once('SIGTERM', stop);
try {
  const { url } = await tunnel.start();
  console.log(url);
  tunnel.on('status', status => {
    if (status.state === 'offline') {
      console.error(status.message);
      void stop();
    }
  });
} catch (error) {
  console.error(error.message);
  await stop();
  process.exitCode = 1;
}
