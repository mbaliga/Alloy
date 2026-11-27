import { fake } from './fake.mjs';
import { elmer } from './elmer.mjs';
import { openfoam } from './openfoam.mjs';

export const ADAPTERS = { elmer, fake, openfoam };
export const KINDS = ['structural', 'thermal'];

/** Availability of every adapter, for /v1/health. */
export async function describeAdapters(config, adapters = ADAPTERS) {
  const out = {};
  for (const [id, a] of Object.entries(adapters)) { let st; try { st = await a.available(config); } catch (e) { st = { ok: false, reason: String(e.message || e) }; } out[id] = { label: a.label, kinds: a.kinds, real: a.real, ...st }; }
  return out;
}

/** The adapter for a job: the one named, or for "auto" the first real one that is available and handles the kind, then the simulated one when enabled. Throws with the reason. */
export async function pickAdapter(job, config, adapters = ADAPTERS) {
  const order = job.adapter === 'auto' ? ['elmer', 'fake'] : [job.adapter];
  const why = [];
  for (const id of order) {
    const a = adapters[id]; if (!a) { why.push(`${id}: unknown adapter`); continue; }
    if (!a.kinds.includes(job.kind)) { why.push(`${id}: does not handle ${job.kind}`); continue; }
    const st = await a.available(config); if (st.ok) return a; why.push(`${id}: ${st.reason || 'not available'}`);
  }
  throw Object.assign(new Error(`no solver is available for this job (${why.join('; ')})`), { code: 'NO_SOLVER' });
}
