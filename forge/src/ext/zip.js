// Minimal zip reader/writer for .alloyext packages. No dependencies: deflate goes through the platform's
// DecompressionStream / CompressionStream ('deflate-raw'), available in current browsers and Node 18+.
// The reader is deliberately strict: it refuses zip64, encryption, multi-disk archives, odd paths and anything that
// expands past the limits below, because the archive comes from outside.

export const LIMITS = { maxFiles: 256, maxFileBytes: 12 * 1024 * 1024, maxTotalBytes: 32 * 1024 * 1024, maxPackageBytes: 24 * 1024 * 1024 };

const te = new TextEncoder();
let CRC = null;
export function crc32(b) {
  if (!CRC) { CRC = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; CRC[n] = c >>> 0; } }
  let c = 0xffffffff;
  for (let i = 0; i < b.length; i++) c = CRC[(c ^ b[i]) & 255] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

export function safePath(p) {
  if (!p || p.length > 200 || p.startsWith('/') || p.includes('\\') || p.includes('\0') || /^[A-Za-z]:/.test(p)) return false;
  return p.split('/').every((s) => s && s !== '.' && s !== '..');
}

async function inflateRaw(data, max) {
  const ds = new DecompressionStream('deflate-raw');
  const w = ds.writable.getWriter(); w.write(data).catch(() => {}); w.close().catch(() => {});
  const r = ds.readable.getReader(); const parts = []; let n = 0;
  for (;;) {
    const { done, value } = await r.read();
    if (done) break;
    n += value.length;
    if (n > max) { r.cancel().catch(() => {}); throw new Error('zip entry expands past the size limit'); }
    parts.push(value);
  }
  const out = new Uint8Array(n); let o = 0; for (const p of parts) { out.set(p, o); o += p.length; }
  return out;
}

/** @returns {Promise<Map<string, Uint8Array>>} path -> bytes. Directory entries are skipped. */
export async function readZip(bytes, limits = LIMITS) {
  if (!(bytes instanceof Uint8Array)) bytes = new Uint8Array(bytes);
  if (bytes.length > limits.maxPackageBytes) throw new Error('package is larger than ' + (limits.maxPackageBytes >> 20) + ' MB');
  const dv = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let eocd = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 22 - 65535); i--) if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a zip file (no end-of-central-directory record)');
  const disk = dv.getUint16(eocd + 4, true), entries = dv.getUint16(eocd + 10, true), cdSize = dv.getUint32(eocd + 12, true), cdOff = dv.getUint32(eocd + 16, true);
  if (disk !== 0 || dv.getUint16(eocd + 6, true) !== 0) throw new Error('multi-disk zip is not supported');
  if (entries === 0xffff || cdOff === 0xffffffff) throw new Error('zip64 is not supported');
  if (entries > limits.maxFiles) throw new Error(`package has more than ${limits.maxFiles} files`);
  if (cdOff + cdSize > eocd) throw new Error('corrupt zip directory');
  const out = new Map(); let p = cdOff, total = 0;
  for (let e = 0; e < entries; e++) {
    if (p + 46 > bytes.length || dv.getUint32(p, true) !== 0x02014b50) throw new Error('corrupt zip directory');
    const flags = dv.getUint16(p + 8, true), method = dv.getUint16(p + 10, true), crc = dv.getUint32(p + 16, true);
    const csize = dv.getUint32(p + 20, true), usize = dv.getUint32(p + 24, true);
    const nl = dv.getUint16(p + 28, true), xl = dv.getUint16(p + 30, true), cl = dv.getUint16(p + 32, true), lho = dv.getUint32(p + 42, true);
    const name = new TextDecoder().decode(bytes.subarray(p + 46, p + 46 + nl));
    p += 46 + nl + xl + cl;
    if (name.endsWith('/')) continue;
    if (flags & 1) throw new Error(`"${name}" is encrypted`);
    if (!safePath(name)) throw new Error(`unsafe path in package: "${name}"`);
    if (out.has(name)) throw new Error(`duplicate entry "${name}"`);
    if (usize > limits.maxFileBytes) throw new Error(`"${name}" is larger than ${limits.maxFileBytes >> 20} MB`);
    total += usize; if (total > limits.maxTotalBytes) throw new Error('package expands past the total size limit');
    if (dv.getUint32(lho, true) !== 0x04034b50) throw new Error(`corrupt local header for "${name}"`);
    const start = lho + 30 + dv.getUint16(lho + 26, true) + dv.getUint16(lho + 28, true);
    if (start + csize > bytes.length) throw new Error(`"${name}" runs past the end of the file`);
    const raw = bytes.subarray(start, start + csize);
    let data;
    if (method === 0) data = raw.slice();
    else if (method === 8) data = await inflateRaw(raw, Math.min(usize, limits.maxFileBytes));
    else throw new Error(`"${name}": unsupported compression method ${method}`);
    if (data.length !== usize) throw new Error(`"${name}": size does not match the directory`);
    if (crc32(data) !== crc) throw new Error(`"${name}": checksum mismatch`);
    out.set(name, data);
  }
  return out;
}

/** @param {Record<string, Uint8Array|string>} files */
export async function writeZip(files, { deflate = false } = {}) {
  const chunks = [], central = []; let off = 0;
  const u16 = (v) => [v & 255, (v >> 8) & 255], u32 = (v) => [v & 255, (v >>> 8) & 255, (v >>> 16) & 255, (v >>> 24) & 255];
  for (const name of Object.keys(files).sort()) {
    if (!safePath(name)) throw new Error('unsafe path: ' + name);
    const data = typeof files[name] === 'string' ? te.encode(files[name]) : files[name];
    const nb = te.encode(name), crc = crc32(data);
    let body = data, method = 0;
    if (deflate && data.length > 64) {
      const cs = new CompressionStream('deflate-raw'); const w = cs.writable.getWriter(); w.write(data); w.close();
      const parts = []; const r = cs.readable.getReader(); for (;;) { const { done, value } = await r.read(); if (done) break; parts.push(value); }
      const n = parts.reduce((a, b) => a + b.length, 0); const z = new Uint8Array(n); let o = 0; for (const q of parts) { z.set(q, o); o += q.length; }
      if (z.length < data.length) { body = z; method = 8; }
    }
    const lh = new Uint8Array([0x50, 0x4b, 3, 4, ...u16(20), ...u16(0x0800), ...u16(method), ...u16(0), ...u16(0x21), ...u32(crc), ...u32(body.length), ...u32(data.length), ...u16(nb.length), ...u16(0)]);
    chunks.push(lh, nb, body);
    central.push(new Uint8Array([0x50, 0x4b, 1, 2, ...u16(20), ...u16(20), ...u16(0x0800), ...u16(method), ...u16(0), ...u16(0x21), ...u32(crc), ...u32(body.length), ...u32(data.length), ...u16(nb.length), ...u16(0), ...u16(0), ...u16(0), ...u16(0), ...u32(0), ...u32(off)]), nb);
    off += lh.length + nb.length + body.length;
  }
  const cdSize = central.reduce((a, b) => a + b.length, 0), n = Object.keys(files).length;
  const end = new Uint8Array([0x50, 0x4b, 5, 6, ...u16(0), ...u16(0), ...u16(n), ...u16(n), ...u32(cdSize), ...u32(off), ...u16(0)]);
  const all = [...chunks, ...central, end]; const out = new Uint8Array(all.reduce((a, b) => a + b.length, 0)); let o = 0;
  for (const c of all) { out.set(c, o); o += c.length; }
  return out;
}
