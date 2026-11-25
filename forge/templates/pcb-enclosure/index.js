// "PCB enclosure" template: a generic, parametric enclosure with an invented demo board. Replace the pcb block with your
// own board (tag solids of an imported STEP assembly in the Objects pane) and the enclosure follows.
import demo from './demo.json' with { type: 'json' };
import { defaultFeature, emptyProject, clone } from '../../src/model.js';

export const TEMPLATE = { id: 'pcb-enclosure', label: 'PCB enclosure', desc: 'Parametric enclosure on the OCCT kernel: monolith, bullet or cell style, rear cap with fasteners, B-rep checks. Ships with an invented demo board.' };
export { demo };

export function pcbEnclosureProject(over = {}) {
  const P = emptyProject();
  P.name = over.name || demo.name;
  P.settings.kernel = 'occt';
  P.pcb = clone(demo.pcb);
  P.features = [defaultFeature('enclosure', { ...clone(demo.enclosure), spec: clone(demo.spec), ...(over.enclosure || {}) })];
  return P;
}
export { generateEnclosure } from './generate.js';

/**
 * A starter enclosure spec for any pcb block (used when a STEP assembly was tagged and there is no spec yet): a straight
 * body sized from the component extents, a chamfer band at the front and rear, and screw positions for the rear cap.
 * It is only a starting point; edit the station table in the enclosure feature.
 */
export function autoSpec(pcb, { wall = 1.6, clearance = 0.3 } = {}) {
  const boxes = (pcb.components || []).map((c) => (c.kind === 'cylinder'
    ? (c.cyl.axis === 'y' ? [c.cyl.c[0] - c.cyl.r, c.cyl.c[0] + c.cyl.r, c.cyl.a0, c.cyl.a1, c.cyl.c[1] - c.cyl.r, c.cyl.c[1] + c.cyl.r]
      : c.cyl.axis === 'z' ? [c.cyl.c[0] - c.cyl.r, c.cyl.c[0] + c.cyl.r, c.cyl.c[1] - c.cyl.r, c.cyl.c[1] + c.cyl.r, c.cyl.a0, c.cyl.a1]
        : [c.cyl.a0, c.cyl.a1, c.cyl.c[0] - c.cyl.r, c.cyl.c[0] + c.cyl.r, c.cyl.c[1] - c.cyl.r, c.cyl.c[1] + c.cyl.r])
    : c.box));
  if (!boxes.length) throw new Error('the pcb block has no components');
  const mn = (i) => Math.min(...boxes.map((b) => b[i])), mx = (i) => Math.max(...boxes.map((b) => b[i]));
  const inner = (pcb.components || []).filter((c) => c.insert !== 'bottom').map((c, i) => boxes[(pcb.components || []).indexOf(c)]);
  const body = inner.length ? inner : boxes;
  const pad = clearance + wall, cr = 1.2;
  const yB = Math.min(...body.map((b) => b[2])) - pad - cr - 0.8;
  const yT = Math.max(...body.map((b) => b[3])) + pad + 1.0;
  const hw = Math.max(Math.abs(mn(4)), Math.abs(mx(5))) + pad + cr;
  const xF = mn(0) - clearance - wall;
  const r = (v) => Math.round(v * 100) / 100;
  const station = (x, d, rr) => [x, r(hw - d), r(hw - d), r(yB + d), r(yT - d), rr, rr];
  const mono = [[xF, ...station(0, 1.2, 2.2).slice(1)], [r(xF + 1.5), ...station(0, 0, 2.6).slice(1)], ['rear-1.2', ...station(0, 0, 2.6).slice(1)], ['rear', ...station(0, 0.8, 2.2).slice(1)]];
  const bullet = [[xF, r(hw * 0.45), r(hw * 0.45), r(yT - (yT - yB) * 0.55), r(yT - 1.0), 3, 3], [r(xF + 3), r(hw * 0.75), r(hw * 0.75), r(yB + (yT - yB) * 0.25), r(yT), 3, 2.4], [r(xF + 12), ...station(0, 0, 2.6).slice(1)], ['rear-1.2', ...station(0, 0, 2.6).slice(1)], ['rear', ...station(0, 0.8, 2.2).slice(1)]];
  const zs = r(Math.min(hw * 0.55, 8));
  return {
    yBottom: r(yB), xFront: r(xF),
    stations: { monolith: mono, bullet },
    cap: { thickness: 1.8, plugDepth: 1.6, plugShrink: 0.15, plugComponents: (pcb.components || []).filter((c) => c.insert === 'rear' && c.kind !== 'cylinder').map((c) => c.name), edgeFillet: 0.8, screwPositions: [[r(yB + 2.5), -zs], [r(yB + 2.5), zs]], pilotLen: 9 },
    texture: { window: [r(xF + 3.6), r(mx(1) - 4), r(yB + 2.4), r(yT - 2.4)], nx: 8, ny: 3, keepClearHalfWidth: r(hw - 3) },
    print: { up: [-1, 0, 0] }, bedName: 'A1 mini',
  };
}
