// Turns a project and a build into what an analysis receives: per-body material properties in SI, the loads and conditions, and
// the geometry (meshes in mm, per body). Anything assumed on the user's behalf becomes a warning that travels with the result.
import { materialOf, siProps, PROPS } from './materials.js';
import { validateConditions, defaultConditions } from './conditions.js';

const clone = (v) => JSON.parse(JSON.stringify(v));

export function resolveSetup(P, build) {
  const warnings = [];
  const bodies = [];
  for (const b of (build && build.bodies) || []) {
    const { material, assumed, missing } = materialOf(P, b.name);
    if (assumed) warnings.push(`Body "${b.name}" has no material${missing ? ` ("${missing}" is not available)` : ''}; ${material.name} is assumed.`);
    bodies.push({
      name: b.name, assumed, material: { id: material.id, name: material.name, props: siProps(material),
        quality: Object.fromEntries(PROPS.map((p) => [p.k, material.properties[p.k].quality])) },
    });
  }
  const conditions = clone((P.analysis && P.analysis.conditions) || defaultConditions());
  try { validateConditions(conditions, P); } catch (e) { warnings.push(`Conditions are not valid: ${e.message}`); }
  const pcb = new Set(((P.pcb && P.pcb.components) || []).map((c) => c.name));
  for (const h of conditions.heat) {
    if (h.target && h.target.pcb != null && !pcb.has(h.target.pcb)) warnings.push(`Heat source "${h.id}" targets PCB part "${h.target.pcb}", which does not exist.`);
    if (h.target && h.target.body != null && !bodies.some((b) => b.name === h.target.body)) warnings.push(`Heat source "${h.id}" targets body "${h.target.body}", which is not in the build.`);
  }
  const total = conditions.heat.reduce((a, h) => a + h.power_W, 0);
  return { bodies, conditions, pcb: clone((P && P.pcb) || {}), totalHeat_W: total, warnings, units: 'Physical quantities are SI (K, W, N, Pa, m/s, m/s²); geometry and positions are millimetres in the document frame.' };
}

/** The meshes of the bodies in a build (reference parts are left out: they are not part of the product). */
export function meshesOf(build) {
  return ((build && build.bodies) || []).map((b) => {
    const m = b.mesh || b.view;
    return { name: b.name, positions: Float32Array.from(m.pos), indices: Uint32Array.from(m.idx), bbox: JSON.parse(JSON.stringify(b.bbox)), volume_mm3: b.volume || 0, tris: b.tris || m.idx.length / 3 };
  });
}

export function prepareInput(P, build) {
  const setup = resolveSetup(P, build), bodies = meshesOf(build);
  const strip = clone({ ...P, assets: {} });
  return { setup, bodies, project: strip };
}
