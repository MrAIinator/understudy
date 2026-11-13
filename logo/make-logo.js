// Understudy logo: a blocky head whose right half has already been replaced by the copy.
// 32x32 pixel art, scaled to 512x512 (and 128 / 64 previews). Pure Node, no packages.
const fs = require("fs");
const zlib = require("zlib");
const path = require("path");

const N = 32;
const px = Array.from({ length: N }, () => Array(N).fill(null));
const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const set = (x, y, c) => { if (x >= 0 && y >= 0 && x < N && y < N) px[y][x] = c; };
const rect = (x0, y0, x1, y1, c) => { for (let y = y0; y <= y1; y++) for (let x = x0; x <= x1; x++) set(x, y, c); };

// deterministic noise
let seed = 1337;
const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff);

// background: near-black with faint scanline noise, darker at the edges
for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
  const edge = Math.max(Math.abs(x - 15.5), Math.abs(y - 15.5)) / 16;
  const base = edge > 0.8 ? "#07070a" : "#0d0d13";
  set(x, y, rnd() < 0.08 ? "#14141c" : base);
}

// the shadow double standing behind, offset down-right
rect(11, 9, 28, 26, "#17171f");                    // same head, a step behind
rect(26, 16, 27, 17, "#4a4a5c");                   // its eye, the only part that shows

// head 18x18 at (7,6)-(24,23); split at x=16
const L0 = 7, L1 = 15, R0 = 16, R1 = 24, T = 6, B = 23;
// left half: the player
rect(L0, T, L1, B, "#c48d63");                       // skin
rect(L0, T, L1, T + 3, "#3a2716");                   // hair
rect(L0, T + 4, L0, T + 6, "#3a2716");               // sideburn
set(L0 + 1, T + 4, "#3a2716"); set(L1, T + 4, "#3a2716");
rect(L0 + 2, T + 7, L0 + 3, T + 8, "#ffffff");       // eye white
set(L0 + 3, T + 7, "#3f5fb0"); set(L0 + 3, T + 8, "#2a3f7a"); // iris
rect(L0 + 4, T + 12, L1, T + 13, "#6e3b28");         // mouth
for (let y = T + 5; y <= B; y++) for (let x = L0; x <= L1; x++) if (rnd() < 0.12) set(x, y, "#b47e57");
// right half: the copy — matte black, one blank white eye
rect(R0, T, R1, B, "#050507");
for (let y = T; y <= B; y++) for (let x = R0; x <= R1; x++) if (rnd() < 0.10) set(x, y, "#0c0c11");
rect(R0 + 3, T + 7, R0 + 4, T + 8, "#ffffff");       // eye, same height as the player's
set(R0 + 2, T + 7, "#9aa3c7"); set(R0 + 5, T + 8, "#9aa3c7"); // faint glow

// glitch: a slice of the head slid to the right, red tear marks
const shift = (y, dx) => { const row = px[y].slice(); for (let x = L0; x <= R1 + 3; x++) px[y][x] = row[x - dx] ?? row[x]; };
shift(T + 10, 1); shift(T + 11, 1);
set(L0, T + 10, "#0d0d13"); set(L0, T + 11, "#0d0d13");
set(R1 + 2, T + 10, "#b3122a"); set(R1 + 3, T + 11, "#b3122a"); set(R1 + 5, T + 10, "#5c0a16");
// seam: a thin bright crack between the halves
for (let y = T; y <= B; y++) if (y % 3 !== 1) set(R0, y, y % 2 ? "#e8e8f0" : "#050507");

// write PNG
function png(file, scale) {
  const W = N * scale, raw = Buffer.alloc((W * 3 + 1) * W);
  for (let y = 0; y < W; y++) {
    raw[y * (W * 3 + 1)] = 0;
    for (let x = 0; x < W; x++) {
      const [r, g, b] = hex(px[Math.floor(y / scale)][Math.floor(x / scale)]);
      const o = y * (W * 3 + 1) + 1 + x * 3;
      raw[o] = r; raw[o + 1] = g; raw[o + 2] = b;
    }
  }
  const crcT = Array.from({ length: 256 }, (_, n) => { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; return c >>> 0; });
  const crc = (buf) => { let c = 0xffffffff; for (const v of buf) c = crcT[(c ^ v) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type), data]);
    const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
    return Buffer.concat([len, td, c]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(W, 0); ihdr.writeUInt32BE(W, 4); ihdr[8] = 8; ihdr[9] = 2;
  fs.writeFileSync(file, Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk("IHDR", ihdr),
    chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0))]));
}
png(path.join(__dirname, "understudy-512.png"), 16);
png(path.join(__dirname, "understudy-128.png"), 4);
png(path.join(__dirname, "understudy-64.png"), 2);
console.log("ok");
