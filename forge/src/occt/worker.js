// Web Worker entry for the OCCT kernel. Loaded lazily by client.js on first use: replicad (MIT) is bundled here; the
// Open CASCADE build (replicad-opencascadejs, LGPL-2.1) is loaded at runtime as separate files next to this worker
// (occt/replicad_single.js + occt/replicad_single.wasm) so it stays replaceable. Nothing leaves the browser.
import * as R from 'replicad';
import { makeOps } from './ops.js';
import { createSession } from './session.js';

let session = null, booting = null;
const here = self.location.href;

async function boot() {
  if (session) return session;
  if (!booting) {
    booting = (async () => {
      const glue = await import(/* webpackIgnore: true */ new URL('occt/replicad_single.js', here).href);
      const oc = await (glue.default || glue)({ locateFile: (p) => new URL('occt/' + p, here).href });
      R.setOC(oc);
      session = createSession(makeOps(R, oc));
      return session;
    })();
  }
  try { return await booting; } catch (e) { booting = null; throw e; }
}

const collect = (res, set) => {
  for (const b of [...res.bodies, ...res.refs]) { set.add(b.view.pos.buffer); set.add(b.view.idx.buffer); b.mesh = undefined; }
};

self.onmessage = async (ev) => {
  const { id, type, payload } = ev.data;
  try {
    const S = await boot();
    if (type === 'init') return self.postMessage({ id, ok: true });
    if (type === 'build') {
      const res = await S.build(payload.project, payload.opts);
      const t = new Set(); collect(res, t);
      return self.postMessage({ id, ok: true, result: res }, [...t]);
    }
    if (type === 'check') return self.postMessage({ id, ok: true, result: S.check(payload.build, payload.opts) });
    if (type === 'step') { const u8 = await S.stepBytes(payload.build, payload.names); return self.postMessage({ id, ok: true, result: u8 }, [u8.buffer]); }
    if (type === 'dispose') { S.dispose(payload.build); return self.postMessage({ id, ok: true }); }
    throw new Error('unknown message ' + type);
  } catch (e) {
    self.postMessage({ id, ok: false, error: String((e && e.message) || e) });
  }
};
