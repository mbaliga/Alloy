// Installed packages (per browser) and what they contribute. Persistence is a small adapter so the registry runs the same in
// the browser (IndexedDB), in Node tests (a Map) and later on a server. Which packages are *enabled* is a property of the
// project (`project.extensions`), not of the registry.
import { loadPackage } from './loader.js';
import { registerExtTypes, unregisterExtTypes } from '../model.js';

/** In-memory store, used by tests and as the fallback when IndexedDB is unavailable (private windows). */
export function memoryStore() {
  const m = new Map();
  return { async list() { return [...m.values()]; }, async put(id, bytes) { m.set(id, { id, bytes }); }, async del(id) { m.delete(id); } };
}

/** IndexedDB store for the browser. Packages are kept as the original .alloyext bytes and re-verified on every load. */
export function idbStore(name = 'alloy-forge-ext') {
  const open = () => new Promise((res, rej) => { const r = indexedDB.open(name, 1); r.onupgradeneeded = () => r.result.createObjectStore('pkg', { keyPath: 'id' }); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  const run = async (mode, f) => { const db = await open(); return new Promise((res, rej) => { const tx = db.transaction('pkg', mode); const out = f(tx.objectStore('pkg')); tx.oncomplete = () => { db.close(); res(out && out.result); }; tx.onerror = () => rej(tx.error); }); };
  return { list: () => run('readonly', (s) => s.getAll()), put: (id, bytes) => run('readwrite', (s) => s.put({ id, bytes })), del: (id) => run('readwrite', (s) => s.delete(id)) };
}

export class ExtRegistry {
  constructor({ store = memoryStore(), onChange } = {}) { this.store = store; this.pkgs = new Map(); this.listeners = new Set(); if (onChange) this.listeners.add(onChange); this.loadErrors = []; }
  subscribe(f) { this.listeners.add(f); return () => this.listeners.delete(f); }
  #emit(what, id) { for (const f of this.listeners) try { f(what, id); } catch (_) {} }

  /** Load everything in the store. A package that no longer verifies is skipped and reported, never half-loaded. */
  async init() {
    this.loadErrors = [];
    for (const rec of await this.store.list().catch(() => [])) {
      try { this.#add(await loadPackage(rec.bytes)); } catch (e) { this.loadErrors.push({ id: rec.id, error: String(e.message || e) }); }
    }
    this.#emit('init');
    return this;
  }

  #add(pkg) {
    this.pkgs.set(pkg.manifest.id, pkg);
    registerExtTypes(pkg.manifest.id, (pkg.manifest.contributes || {}).featureTypes);
  }

  /** @returns {Promise<{pkg, replaced: string|null}>} */
  async install(bytes) {
    const pkg = await loadPackage(bytes);
    const prev = this.pkgs.get(pkg.manifest.id);
    await this.store.put(pkg.manifest.id, pkg.bytes);
    this.#add(pkg);
    this.#emit('install', pkg.manifest.id);
    return { pkg, replaced: prev ? prev.manifest.version : null };
  }

  async remove(id) {
    if (!this.pkgs.has(id)) return false;
    this.pkgs.delete(id); unregisterExtTypes(id);
    await this.store.del(id);
    this.#emit('remove', id);
    return true;
  }

  get(id) { return this.pkgs.get(id) || null; }
  list() { return [...this.pkgs.values()]; }
  /** All contributions of one kind from the packages in `ids` (default: all installed): [{ ext, item }] */
  contributions(kind, ids) {
    const out = [];
    for (const p of this.pkgs.values()) if (!ids || ids.includes(p.manifest.id)) for (const item of (p.manifest.contributes || {})[kind] || []) out.push({ ext: p.manifest.id, pkg: p, item });
    return out;
  }
}

/** Per-project state: is the package switched on in this project? A project entry survives even when the package is gone. */
export const projectEntry = (P, id) => (P.extensions || []).find((e) => e.id === id) || null;
export const enabledIn = (P, id) => { const e = projectEntry(P, id); return !!e && e.enabled !== false; };
