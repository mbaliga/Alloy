// The job store: queue, lifecycle, progress events, cancel, retention. States: queued -> running -> done | failed | cancelled.
// A finished job is deleted once every file it produced has been downloaded (unless it was submitted with options.keep) or after
// config.ttlSeconds, whichever comes first. The geometry a client sent is dropped from memory as soon as the solve ends.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { pickAdapter, ADAPTERS } from './adapters/index.mjs';

export const TERMINAL = new Set(['done', 'failed', 'cancelled']);
const MAX_EVENTS = 500;

export class JobStore {
  constructor({ config, adapters = ADAPTERS, now = () => Date.now(), qualification = null }) {
    this.config = config; this.adapters = adapters; this.now = now; this.jobs = new Map(); this.queue = []; this.active = 0; this.qualification = qualification;
    fs.mkdirSync(path.join(config.dataDir, 'jobs'), { recursive: true });
    this.timer = setInterval(() => this.sweep(), 15000); this.timer.unref();
  }

  create(job) {
    if (this.jobs.size >= this.config.maxJobs) { const e = new Error(`the runner holds ${this.config.maxJobs} jobs already; delete finished ones first`); e.status = 429; e.code = 'E_BUSY'; throw e; }
    const id = crypto.randomBytes(9).toString('hex'), dir = path.join(this.config.dataDir, 'jobs', id); fs.mkdirSync(dir, { recursive: true });
    const rec = { id, state: 'queued', stage: 'queued', progress: 0, kind: job.kind, label: job.label, adapter: job.adapter, created: this.now(), started: null, finished: null, events: [], seq: 0,
      error: null, result: null, files: {}, keep: job.options.keep, fetched: new Set(), dir, ac: new AbortController(), job };
    this.jobs.set(id, rec); this.event(rec, `queued (${job.bodies.length} bod${job.bodies.length === 1 ? 'y' : 'ies'}, ${job.kind})`);
    this.queue.push(id); queueMicrotask(() => this.pump());
    return rec;
  }

  event(rec, msg) { rec.events.push({ n: ++rec.seq, t: this.now(), msg: String(msg).slice(0, 300), progress: rec.progress, stage: rec.stage }); if (rec.events.length > MAX_EVENTS) rec.events.shift(); }
  get(id) { return this.jobs.get(id) || null; }
  list() { return [...this.jobs.values()].sort((a, b) => b.created - a.created); }

  /** What a client sees. `since` returns only events after that sequence number. */
  view(rec, since = 0) {
    const v = { id: rec.id, state: rec.state, stage: rec.stage, progress: rec.progress, kind: rec.kind, label: rec.label, adapter: rec.adapter, created: rec.created, started: rec.started, finished: rec.finished, keep: rec.keep,
      events: rec.events.filter((e) => e.n > since), next: rec.seq };
    if (rec.error) v.error = rec.error;
    if (rec.state === 'done') v.files = Object.entries(rec.files).map(([name, f]) => ({ name, bytes: f.bytes, mime: f.mime }));
    return v;
  }
  brief(rec) { const { id, state, stage, progress, kind, label, adapter, created, finished, keep } = this.view(rec); return { id, state, stage, progress, kind, label, adapter, created, finished, keep }; }

  async pump() {
    while (this.active < this.config.concurrency && this.queue.length) {
      const id = this.queue.shift(), rec = this.jobs.get(id);
      if (!rec || rec.state !== 'queued') continue;
      this.active++; this.#execute(rec).finally(() => { this.active--; this.pump(); });
    }
  }

  async #execute(rec) {
    const cfg = this.config;
    rec.state = 'running'; rec.started = this.now(); rec.stage = 'starting'; this.event(rec, 'started');
    const timeout = setTimeout(() => { this.event(rec, `stopped: the job ran longer than ${cfg.jobTimeoutSeconds} s`); rec.timedOut = true; rec.ac.abort(); }, cfg.jobTimeoutSeconds * 1000); timeout.unref();
    const ctx = { dir: rec.dir, signal: rec.ac.signal, config: cfg,
      log: (m) => this.event(rec, m), stage: (s) => { rec.stage = s; this.event(rec, s); },
      progress: (f) => { rec.progress = Math.max(rec.progress, Math.min(0.99, f)); } };
    try {
      const adapter = await pickAdapter(rec.job, cfg, this.adapters); rec.adapterUsed = adapter.id; this.event(rec, `solver: ${adapter.id}`);
      const out = await adapter.run(rec.job, ctx);
      if (rec.ac.signal.aborted) throw Object.assign(new Error('cancelled'), { code: 'CANCELLED' });
      const validity = this.#validity(adapter, out);
      const result = { protocol: 1, job: rec.id, kind: rec.kind, adapter: adapter.id, solver_real: !!adapter.real, simulated: !adapter.real, units: { length: 'mm', stress: 'MPa', temperature: 'degC', displacement: 'mm' },
        summary: out.summary, warnings: [...rec.job.warnings, ...(out.warnings || [])], info: out.info || {}, study: out.study || null, qualification: this.qualification, validity_hint: validity,
        bodies: rec.job.bodies.map((b) => b.name), seconds: (this.now() - rec.started) / 1000 };
      if (!adapter.real) result.warnings.push('SIMULATED: this result came from the runner\'s simulated solver, which solves no equation. It is for testing the protocol only.');
      const put = (name, data, mime) => { const p = path.join(rec.dir, name); fs.writeFileSync(p, data); rec.files[name] = { path: p, bytes: Buffer.byteLength(data), mime }; };
      put('result.vtu', out.vtu, 'application/xml'); put('result.json', JSON.stringify(result), 'application/json');
      rec.result = result; rec.progress = 1; rec.state = 'done'; rec.stage = 'done'; this.event(rec, 'done');
    } catch (e) {
      if (e && e.code === 'CANCELLED' || rec.ac.signal.aborted) { rec.state = 'cancelled'; rec.stage = 'cancelled'; rec.error = rec.timedOut ? { code: 'E_TIMEOUT', message: `stopped: longer than ${cfg.jobTimeoutSeconds} s` } : null; this.event(rec, rec.timedOut ? 'stopped (timeout)' : 'cancelled'); }
      else { rec.state = 'failed'; rec.stage = 'failed'; rec.error = { code: (e && e.code) || 'E_SOLVER', message: String((e && e.message) || e).slice(0, 2000) }; this.event(rec, 'failed: ' + rec.error.message.slice(0, 200)); }
    } finally {
      clearTimeout(timeout); rec.finished = this.now(); rec.job = { ...rec.job, bodies: rec.job.bodies.map((b) => ({ ...b, bytes: null })) }; // the geometry is dropped
      for (const d of fs.existsSync(rec.dir) ? fs.readdirSync(rec.dir) : []) if (/^(mesh-|solve-)/.test(d) || d === 'body.stl') fs.rmSync(path.join(rec.dir, d), { recursive: true, force: true }); // keep nothing but the result files
    }
  }

  /** What the runner can honestly say about a result's grade. Forge decides the final validity; "validated" is only ever hinted when a real solver ran, the study converged, and the operator's qualification file covers it. */
  #validity(adapter, out) {
    if (!adapter.real) return 'experimental';
    const q = this.qualification;
    if (out.study && out.study.converged && q && q.passed === true) return 'validated';
    return 'estimate';
  }

  cancel(id) {
    const rec = this.jobs.get(id); if (!rec) return null;
    if (rec.state === 'queued') { rec.state = 'cancelled'; rec.stage = 'cancelled'; rec.finished = this.now(); this.event(rec, 'cancelled before it started'); this.#drop(rec, false); }
    else if (rec.state === 'running') { this.event(rec, 'cancel requested'); rec.ac.abort(); }
    return rec;
  }

  /** The file a client asked for, marking it as downloaded. When everything has been fetched the job is deleted unless it is kept. */
  file(id, name) {
    const rec = this.jobs.get(id); if (!rec || rec.state !== 'done') return null;
    const f = rec.files[name]; if (!f) return null;
    const bytes = fs.readFileSync(f.path); rec.fetched.add(name);
    const all = Object.keys(rec.files).every((n) => rec.fetched.has(n));
    if (all && !rec.keep) setImmediate(() => this.remove(id)); // after the response has been read
    return { bytes, mime: f.mime };
  }

  #drop(rec) { try { fs.rmSync(rec.dir, { recursive: true, force: true }); } catch (_) { /* gone already */ } }
  remove(id) { const rec = this.jobs.get(id); if (!rec) return false; if (rec.state === 'running') { rec.ac.abort(); } this.jobs.delete(id); this.#drop(rec); return true; }

  sweep() {
    const t = this.now();
    for (const rec of [...this.jobs.values()]) if (TERMINAL.has(rec.state) && !rec.keep && rec.finished && t - rec.finished > this.config.ttlSeconds * 1000) this.remove(rec.id);
  }

  close() { clearInterval(this.timer); for (const rec of this.jobs.values()) { rec.ac.abort(); this.#drop(rec); } this.jobs.clear(); }
}
