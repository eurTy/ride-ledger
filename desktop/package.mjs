import { spawn } from 'node:child_process';
import { mkdir, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const project = fileURLToPath(new URL('../', import.meta.url));
const temporary = path.join(project, 'build', 'packager-temp');
const cache = path.join(project, 'build', 'packager-cache');
await mkdir(temporary, { recursive: true }); await mkdir(cache, { recursive: true });
const metadataFile = require.resolve('electron-builder/package.json');
const metadata = JSON.parse(await readFile(metadataFile, 'utf8'));
const cli = path.join(path.dirname(metadataFile), metadata.bin['electron-builder']);
const child = spawn(process.execPath, [cli, '--config', 'desktop/builder.json', '--win', 'portable', '--x64'], {
  cwd: project, windowsHide: true, stdio: 'inherit',
  env: { ...process.env, TEMP: temporary, TMP: temporary, ELECTRON_BUILDER_CACHE: cache,
    CSC_IDENTITY_AUTO_DISCOVERY: 'false' }
});
child.once('error', error => { console.error(error.message); process.exitCode = 1; });
child.once('exit', code => { process.exitCode = code || 0; });
