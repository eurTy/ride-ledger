import { readdir, appendFile } from 'node:fs/promises';
import path from 'node:path';

const candidates = (await readdir('/Applications')).map(name => {
  const match = /^Xcode_(\d+)(?:\.(\d+))?(?:\.(\d+))?\.app$/.exec(name);
  return match ? { name, parts: match.slice(1).map(value => Number(value || 0)) } : null;
}).filter(value => value && value.parts[0] >= 26).sort((a, b) => {
  for (let index = 0; index < 3; index++) if (a.parts[index] !== b.parts[index]) return b.parts[index] - a.parts[index];
  return 0;
});
if (!candidates.length) throw new Error('No stable Xcode 26 or later is installed on this runner.');
if (!process.env.GITHUB_ENV) throw new Error('This selector runs inside GitHub Actions.');
const selected = path.join('/Applications', candidates[0].name, 'Contents', 'Developer');
await appendFile(process.env.GITHUB_ENV, 'DEVELOPER_DIR=' + selected + '\n');
console.log('Selected ' + candidates[0].name);
