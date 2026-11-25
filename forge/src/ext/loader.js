// Read, validate and verify an .alloyext package. Pure: no Worker, no DOM, no storage.
//
// Integrity: manifest.integrity = "sha256-" + hex( sha256( for each file, sorted by path: path + "\0" + hex(sha256(bytes)) + "\n" ) ),
// where alloy-extension.json is hashed as its canonical JSON (keys sorted, without the `integrity` field itself). So the
// manifest, and with it the permissions, is bound to the exact files: widening a permission or editing code is detected.
// It detects a package that was edited or truncated after it was built. It is NOT a signature: it does not say who made the
// package. Signing and a marketplace are out of scope for X0 (docs/EXTENSIONS_SPEC.md section 6).
import { readZip, writeZip } from './zip.js';
import { validateManifest, tierOf } from './manifest.js';

export const MANIFEST_FILE = 'alloy-extension.json';
const td = new TextDecoder();

const hex = (buf) => [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
export async function sha256Hex(bytes) { return hex(await crypto.subtle.digest('SHA-256', bytes)); }

const canon = (v) => (Array.isArray(v) ? v.map(canon) : v && typeof v === 'object' ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])])) : v);
/** @param {Map<string,Uint8Array>} files all files including alloy-extension.json (its `integrity` field is ignored) */
export async function computeIntegrity(files) {
  const te = new TextEncoder(); const all = new Map(files);
  let m;
  try { m = JSON.parse(td.decode(all.get(MANIFEST_FILE) || new Uint8Array())); } catch (_) { m = null; }
  if (m && typeof m === 'object') { delete m.integrity; all.set(MANIFEST_FILE, te.encode(JSON.stringify(canon(m)))); }
  let s = '';
  for (const p of [...all.keys()].sort()) s += `${p}\0${await sha256Hex(all.get(p))}\n`;
  return 'sha256-' + await sha256Hex(te.encode(s));
}

/** @param {Uint8Array|ArrayBuffer} bytes the .alloyext file */
export async function loadPackage(bytes) {
  const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const files = await readZip(u8);
  const raw = files.get(MANIFEST_FILE);
  if (!raw) throw new Error(`${MANIFEST_FILE} is missing from the package`);
  let manifest;
  try { manifest = JSON.parse(td.decode(raw)); } catch (e) { throw new Error(`${MANIFEST_FILE} is not valid JSON: ${e.message}`); }
  validateManifest(manifest, { hasFile: (p) => files.has(p) });
  const want = await computeIntegrity(files);
  if (manifest.integrity !== want) throw new Error(`integrity check failed: the files do not match the manifest (expected ${want.slice(0, 22)}…)`);
  return { manifest, files, tier: tierOf(manifest), source: td.decode(files.get(manifest.main)), packageSha: await sha256Hex(u8), bytes: u8 };
}

/** Build a package from a manifest (without integrity) and files. Used by tools/pack-ext.mjs and the tests. */
export async function buildPackage(manifest, fileMap) {
  const files = new Map(Object.entries(fileMap).map(([k, v]) => [k, typeof v === 'string' ? new TextEncoder().encode(v) : v]));
  const withManifest = new Map(files); withManifest.set(MANIFEST_FILE, new TextEncoder().encode(JSON.stringify({ ...manifest, integrity: undefined })));
  const m = { ...manifest, integrity: await computeIntegrity(withManifest) };
  validateManifest(m, { hasFile: (p) => files.has(p) });
  return writeZip({ ...Object.fromEntries(files), [MANIFEST_FILE]: JSON.stringify(m, null, 2) }, { deflate: true });
}
