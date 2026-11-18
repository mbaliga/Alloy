import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { wizardProject, applyOps, defaultFeature, clone } from './src/model.js';
import { makeScope } from './src/expr.js';
const wasm = await Module(); wasm.setup();

const sc = makeScope([{ name: 'a', expr: '2*3' }, { name: 'b', expr: 'a + sin(90) + 2^3' }]);
console.log('expr', sc.ev('b*2'), sc.ev('-(a)+max(1,5)'));

const P = wizardProject({ ports: [{ name: 'USB-C', side: '-y', along: 0, z: 2, w: 9.5, h: 3.4 }, { name: 'DC', side: '+x', along: 5, z: 1, w: 8, h: 6 }] });
let r = build(P, wasm);
console.log('status errors', Object.entries(r.status).filter(([, v]) => !v.ok));
for (const b of r.bodies) console.log('body', b.name, 'vol', b.volume.toFixed(1), 'tris', b.tris, 'bbox', b.bbox.min.map((v) => v.toFixed(1)), b.bbox.max.map((v) => v.toFixed(1)));
console.log('refs', r.refs.map((x) => x.name));
r.dispose();

// ops: AI-style edit
const { project: P2, log } = applyOps(P, [
  { op: 'set_param', name: 'wall', expr: '2.4' },
  { op: 'add_feature', feature: { type: 'slot', name: 'Vents', body: 'Lid', op: 'cut', length: 14, width: 2, h: 10, pos: [-10, 0, 'baseH+8-2'], pat: 'linear', patN: 5, patD: [5, 0, 0] } },
  { op: 'add_feature', feature: { type: 'hole', name: 'Screw', body: 'Lid', d: 3, pos: [0, 0, 'baseH+8+lidT'], dir: '-z', cskD: 6, depth: 5 } },
  { op: 'add_feature', feature: { type: 'split', body: 'Base', axis: 'z', offset: 10, keep: 'below', other: 'Cap' } },
]);
console.log(log);
r = build(P2, wasm);
console.log('errors', Object.entries(r.status).filter(([, v]) => !v.ok));
for (const b of r.bodies) console.log('body', b.name, 'vol', b.volume.toFixed(1));
r.dispose();
try { applyOps(P, [{ op: 'update_feature', id: 'nope', set: {} }]); } catch (e) { console.log('expected err:', e.message); }
try { applyOps(P, [{ op: 'add_feature', feature: { type: 'box', size: [1, 2] } }]); } catch (e) { console.log('expected err:', e.message); }
{
  const { project: P3 } = applyOps(wizardProject(), [
    { op: 'add_feature', feature: { type: 'box', name: 'Bar', body: 'Bar', size: [10, 10, 60], anchor: 'bottom', pos: [80, 0, 0] } },
    { op: 'add_feature', feature: { type: 'transform', body: 'Bar', mode: 'twist', axis: 'z', amount: 90, center: [80, 0, 0] } },
    { op: 'add_feature', feature: { type: 'transform', body: 'Base', mode: 'shear', axis: 'x', byAxis: 'z', amount: 0.3 } },
    { op: 'add_feature', feature: { type: 'transform', body: 'Lid', mode: 'bend', axis: 'x', byAxis: 'z', amount: 30, center: [0, 0, 0] } },
  ]);
  const r3 = build(P3, wasm);
  console.log('transform errs', Object.values(r3.status).filter((s) => !s.ok));
  for (const b of r3.bodies) console.log('T', b.name, b.volume.toFixed(0), b.tris);
  r3.dispose();
  const P4 = clone(P3); P4.bodies = { Lid: { locked: true } }; P4.params[0].locked = true;
  for (const ops of [[{ op: 'set_param', name: 'pcbL', expr: '70' }], [{ op: 'update_feature', id: 'Lid plate', set: { radius: '1' } }], [{ op: 'add_feature', feature: { type: 'hole', body: '*', d: 3 } }]])
    try { applyOps(P4, ops); console.log('LOCK FAIL'); } catch (e) { console.log('lock ok:', e.message.slice(0, 60)); }
}
