// PCB tagging: turn the solids of an imported STEP assembly into the pcb block. Pure functions over the document.
//
//   asset = { kind:'assembly', name, stepB64, parts:[{ i, name, bbox:{min,max}, tris, tag }] }
//   tag   = '' | 'board' | 'component' | 'keepout' | 'keep'
//     board      the PCB: slides in from the rear, edge-supported
//     component  a part inside the enclosure (battery, sensor, ...): cavity + cradle + wall check
//     keepout    cavity only, no cradle (a cable swing, a clearance volume)
//     keep       "keep from original": the solid's volume stays in the shell (a clamp region of the original casing)
import { clone, emptyPcb, defaultFeature } from './model.js';
import { autoSpec } from '../templates/pcb-enclosure/index.js';

export const TAGS = [['', '—'], ['board', 'board'], ['component', 'component'], ['keepout', 'keep-out'], ['keep', 'keep original']];
const r3 = (v) => Math.round(v * 1000) / 1000;
const boxOf = (bb) => [r3(bb.min[0]), r3(bb.max[0]), r3(bb.min[1]), r3(bb.max[1]), r3(bb.min[2]), r3(bb.max[2])];
const safe = (s, i) => (String(s || '').replace(/[^\w -]+/g, '').trim().slice(0, 28) || `part ${i}`);

export function setTag(project, assetId, partIndex, tag) {
  const P = clone(project);
  const a = P.assets[assetId];
  if (!a || !a.parts || !a.parts[partIndex]) throw new Error('no such part');
  a.parts[partIndex].tag = tag;
  return P;
}

/** Rebuild pcb.components / pcb.keep from the tagged parts of one assembly asset. Components not tagged are left out. */
export function pcbFromTags(project, assetId, { addFeature = true } = {}) {
  const P = clone(project);
  const a = P.assets[assetId];
  if (!a || a.kind !== 'assembly') throw new Error('not an imported assembly');
  const pcb = Object.assign(emptyPcb(), P.pcb || {});
  const keepOld = (pcb.components || []).filter((c) => c.source !== assetId);
  const used = new Set(keepOld.map((c) => c.name));
  const uniq = (n) => { let k = n, i = 2; while (used.has(k)) k = `${n} ${i++}`; used.add(k); return k; };
  const comps = [], keeps = (pcb.keep || []).filter((k) => k.source !== assetId);
  for (const p of a.parts) {
    const name = uniq(safe(p.name, p.i));
    if (p.tag === 'board') comps.push({ name, kind: 'box', role: 'board', box: boxOf(p.bbox), source: assetId, clearance: 'normal', insert: 'rear', cradle: { t: 1.2, rear: true }, wall: { extra: 1.0 } });
    else if (p.tag === 'component') comps.push({ name, kind: 'box', role: 'component', box: boxOf(p.bbox), source: assetId, clearance: 'normal', insert: 'rear', cradle: { t: 1.2, rear: true }, wall: { extra: 1.0 } });
    else if (p.tag === 'keepout') comps.push({ name, kind: 'box', role: 'keepout', box: boxOf(p.bbox), source: assetId, clearance: 'normal' });
    else if (p.tag === 'keep') keeps.push({ name, source: assetId, part: { bbox: { min: p.bbox.min, max: p.bbox.max } }, boxes: [boxOf(p.bbox)] });
  }
  pcb.components = [...keepOld, ...comps];
  pcb.keep = keeps;
  P.pcb = pcb;
  let added = false;
  if (comps.length && P.features.some((f) => f.type === 'enclosure')) P.settings.kernel = 'occt'; // the enclosure feature is B-rep only
  if (addFeature && comps.length && !P.features.some((f) => f.type === 'enclosure')) {
    P.settings.kernel = 'occt';
    P.features.push(defaultFeature('enclosure', { name: 'PCB enclosure', spec: autoSpec(pcb) }));
    added = true;
  }
  return { project: P, components: comps.length, keeps: keeps.length, addedFeature: added };
}
