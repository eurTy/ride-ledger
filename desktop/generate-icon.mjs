import { writeFile } from 'node:fs/promises';
import { deflateSync } from 'node:zlib';

const crc32 = bytes => {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
};
const chunk = (name, content) => {
  const type = Buffer.from(name), size = Buffer.alloc(4), crc = Buffer.alloc(4);
  size.writeUInt32BE(content.length); crc.writeUInt32BE(crc32(Buffer.concat([type, content])));
  return Buffer.concat([size, type, content, crc]);
};
function distance(x, y, a, b, c, d) {
  const t = Math.max(0, Math.min(1, ((x - a) * (c - a) + (y - b) * (d - b)) / ((c - a) ** 2 + (d - b) ** 2)));
  return Math.hypot(x - a - t * (c - a), y - b - t * (d - b));
}
function png(size) {
  const rows = Buffer.alloc(size * (1 + size * 4));
  for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
    let alpha = 0, white = 0;
    for (const dx of [.25, .75]) for (const dy of [.25, .75]) {
      const px = (x + dx) * 256 / size, py = (y + dy) * 256 / size;
      if (Math.hypot(Math.max(48 - px, 0, px - 208), Math.max(48 - py, 0, py - 208)) > 48) continue;
      alpha++;
      if (Math.min(distance(px, py, 76, 180, 180, 76), distance(px, py, 112, 76, 180, 76),
        distance(px, py, 180, 76, 180, 144)) <= 10) white++;
    }
    const offset = y * (1 + size * 4) + 1 + x * 4;
    const amount = alpha ? white / alpha : 0;
    [33, 132, 91].forEach((color, index) => { rows[offset + index] = Math.round(color + (255 - color) * amount); });
    rows[offset + 3] = Math.round(255 * alpha / 4);
  }
  const header = Buffer.alloc(13); header.writeUInt32BE(size); header.writeUInt32BE(size, 4); header[8] = 8; header[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const sizes = [16, 32, 48, 64, 128, 256], images = sizes.map(png);
const header = Buffer.alloc(6 + sizes.length * 16); header.writeUInt16LE(1, 2); header.writeUInt16LE(sizes.length, 4);
let offset = header.length;
sizes.forEach((size, index) => {
  const entry = 6 + index * 16;
  header[entry] = size % 256; header[entry + 1] = size % 256;
  header.writeUInt16LE(1, entry + 4); header.writeUInt16LE(32, entry + 6);
  header.writeUInt32LE(images[index].length, entry + 8); header.writeUInt32LE(offset, entry + 12);
  offset += images[index].length;
});
await writeFile(new URL('./assets/icon.png', import.meta.url), images.at(-1));
await writeFile(new URL('./assets/icon.ico', import.meta.url), Buffer.concat([header, ...images]));
