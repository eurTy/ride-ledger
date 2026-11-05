import { spawnSync } from 'node:child_process';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const project = fileURLToPath(new URL('../../', import.meta.url));
const output = path.join(project, 'build', 'mesh-feasibility');
await mkdir(output, { recursive: true });
const result = spawnSync(process.execPath, ['--test', '--test-reporter=tap', 'experiments/mesh/mesh-feasibility.test.mjs'], {
  cwd: project, encoding: 'utf8', timeout: 120000, windowsHide: true,
  env: { ...process.env, MESH_REPORT_DIRECTORY: output }
});
const log = (result.stdout || '') + (result.stderr || '');
await writeFile(path.join(output, 'test.tap'), log);
let measurements = [];
try { measurements = JSON.parse(await readFile(path.join(output, 'measurements.json'), 'utf8')); } catch {}
const report = {
  generatedAt: new Date().toISOString(),
  status: result.status === 0 ? 'protocol_checks_passed_radio_validation_pending' : 'failed',
  passed: Number(/^# pass (\d+)/m.exec(log)?.[1] || 0), failed: Number(/^# fail (\d+)/m.exec(log)?.[1] || 0),
  scope: 'Real loopback TCP with production framing and ledger merge; separate multi-member operation-log prototype.',
  hardwareValidation: { androidToAndroid: 'not_performed', hotspotRadio: 'not_performed', wifiDirect: 'not_performed', bluetooth: 'not_performed',
    reason: 'This program does not operate Android wireless hardware. Radio discovery, pairing, range, power and vendor compatibility require physical Android devices.' },
  releasedAppChanged: false, iosRemainsPaused: true, measurements
};
await writeFile(path.join(output, 'report.json'), JSON.stringify(report, null, 2));
console.log(log.trim());
console.log('\nReport: ' + path.join(output, 'report.json'));
process.exitCode = result.status === 0 ? 0 : 1;
