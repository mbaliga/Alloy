// Results are cached per browser, keyed by inputsHash, and are never saved in the project (spec 2.4): only the setup is. A result
// is only reused when every input that can change it is the same, because the key includes the package version, the setup and a
// hash of the geometry the analysis saw. Typed arrays survive IndexedDB's structured clone.
const MAX_ENTRIES = 24, MAX_BYTES = 256 * 1024 * 1024;
const sizeOf = (r) => r.fields.reduce((a, f) => a + f.data.byteLength, 0) + 4096;

export function memoryStore() {
  const m = new Map();
  return { async get(k) { return m.get(k) || null; }, async put(k, v) { m.set(k, v); }, async del(k) { m.delete(k); }, async keys() { return [...m.keys()]; } };
}

export function idbStore(name = 'alloy-forge-analysis') {
  const open = () => new Promise((res, rej) => { const r = indexedDB.open(name, 1); r.onupgradeneeded = () => r.result.createObjectStore('r'); r.onsuccess = () => res(r.result); r.onerror = () => rej(r.error); });
  const run = async (mode, f) => { const db = await open(); return new Promise((res, rej) => { const tx = db.transaction('r', mode); const out = f(tx.objectStore('r')); tx.oncomplete = () => { db.close(); res(out && out.result); }; tx.onerror = () => rej(tx.error); }); };
  return { get: (k) => run('readonly', (s) => s.get(k)), put: (k, v) => run('readwrite', (s) => s.put(v, k)), del: (k) => run('readwrite', (s) => s.delete(k)), keys: () => run('readonly', (s) => s.getAllKeys()) };
}

export function createResultCache({ store = memoryStore(), maxEntries = MAX_ENTRIES, maxBytes = MAX_BYTES } = {}) {
  const meta = new Map(); // key -> { t, bytes }
  return {
    async get(key) { try { const v = await store.get(key); if (v) meta.set(key, { ...(meta.get(key) || {}), t: Date.now() }); return v || null; } catch (_) { return null; } },
    async put(key, result) {
      try {
        await store.put(key, result); meta.set(key, { t: Date.now(), bytes: sizeOf(result) });
        let total = [...meta.values()].reduce((a, m) => a + (m.bytes || 0), 0);
        const order = [...meta.entries()].sort((a, b) => a[1].t - b[1].t);
        while ((meta.size > maxEntries || total > maxBytes) && order.length > 1) { const [k, m] = order.shift(); await store.del(k); meta.delete(k); total -= m.bytes || 0; }
      } catch (_) { /* a full or blocked store only costs a recompute */ }
    },
    size: () => meta.size,
  };
}
