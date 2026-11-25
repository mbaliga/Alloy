// Main-thread side of the OCCT kernel: spawns the worker on first use (so the 20+ MB wasm is only fetched when a
// document actually uses the OCCT kernel), and wraps build results in objects shaped like the Manifold kernel's
// (bodies/refs/status/params + dispose()), plus .check() and .stepBlob() which run in the worker.
//
//   const occt = createOcctClient({ workerUrl: 'occt-worker.js', onState });
//   const built = await occt.build(project);   built.bodies[0].view.pos ...   await built.check()   await built.stepBlob()
//
// createLocalClient(session) gives the same interface in-process (Node tests, headless runs).

const TRAP = /memory access|unreachable|out of memory|abort|table index/i;

function wrap(raw, api) {
  const res = { ...raw, kernel: 'occt' };
  for (const b of [...res.bodies, ...res.refs]) b.mesh = b.view;
  let gone = false;
  res.dispose = () => { if (gone) return; gone = true; api.dispose(raw.id).catch(() => {}); };
  res.check = (opts) => api.check(raw.id, { ...opts, status: undefined });
  res.stepBlob = async (names) => new Blob([await api.step(raw.id, names)], { type: 'application/step' });
  return res;
}

export function createOcctClient({ workerUrl = 'occt-worker.js', onState = () => {} } = {}) {
  let worker = null, seq = 0, state = 'idle', ready = null, restart = false;
  const pending = new Map();
  const set = (s, detail) => { state = s; try { onState(s, detail); } catch (_) { /* UI hook must not break the kernel */ } };
  const fail = (e) => { for (const p of pending.values()) p.rej(e); pending.clear(); };
  function spawn() {
    if (worker && restart) { worker.terminate(); worker = null; ready = null; restart = false; }
    if (worker) return;
    set('loading');
    worker = new Worker(workerUrl);
    worker.onmessage = (ev) => {
      const { id, ok, result, error } = ev.data, p = pending.get(id);
      if (!p) return;
      pending.delete(id);
      ok ? p.res(result) : p.rej(new Error(error));
    };
    worker.onerror = (ev) => { set('error', ev.message); fail(new Error('OCCT worker failed: ' + (ev.message || 'load error'))); worker = null; ready = null; };
  }
  function call(type, payload, transfer = []) {
    spawn();
    return new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); worker.postMessage({ id, type, payload }, transfer); });
  }
  const api = {
    get state() { return state; },
    /** Start loading the kernel without building anything. */
    ready() { if (!ready) { ready = call('init', {}).then(() => { set('ready'); }, (e) => { ready = null; set('error', e.message); throw e; }); } return ready; },
    async build(project, opts) {
      await api.ready();
      set('building');
      try {
        const raw = await call('build', { project, opts });
        if (Object.values(raw.status || {}).some((s) => s.ok === false && TRAP.test(s.error || ''))) restart = true; // an OCCT trap leaves the wasm heap suspect: use a fresh worker next time
        set('ready');
        return wrap(raw, api);
      } catch (e) { set('error', e.message); restart = true; throw e; }
    },
    check: (build, opts) => call('check', { build, opts }),
    step: (build, names) => call('step', { build, names }),
    dispose: (build) => call('dispose', { build }),
    terminate() { if (worker) worker.terminate(); worker = null; ready = null; set('idle'); },
  };
  return api;
}

/** In-process client over an OCCT session (Node). */
export function createLocalClient(session, { onState = () => {} } = {}) {
  const api = {
    state: 'ready',
    ready: async () => { onState('ready'); },
    async build(project, opts) {
      const raw = await session.build(project, opts);
      // plain clones: the browser worker hands over copies, and callers may keep results across builds
      return wrap(raw, api);
    },
    check: async (build, opts) => session.check(build, opts),
    step: (build, names) => session.stepBytes(build, names),
    dispose: async (build) => session.dispose(build),
  };
  return api;
}
