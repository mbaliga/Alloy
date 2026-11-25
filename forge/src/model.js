// Project model: feature schema registry, defaults, enclosure wizard, op application (used by UI undo and AI edits).
import { checkSyntax } from './expr.js';

const V3 = (a, b, c) => [String(a), String(b), String(c)];

const COMMON_HEAD = [
  { k: 'name', kind: 'text', def: '' },
  { k: 'body', kind: 'text', def: 'Body', tip: 'Which body this feature builds. "*" on a cut hits every body.' },
  { k: 'op', kind: 'enum', opts: ['add', 'cut', 'intersect', 'ref'], def: 'add', tip: 'add = union, cut = subtract, intersect, ref = ghost display only' },
];
const PLACE = [
  { k: 'pos', kind: 'vec3', def: V3(0, 0, 0), tip: 'Placement (mm)' },
  { k: 'rot', kind: 'vec3', def: V3(0, 0, 0), tip: 'Rotation about X, Y, Z in degrees' },
];
const COMMON_TAIL = [
  { k: 'mirror', kind: 'enum', opts: ['', 'x', 'y', 'z', 'xy'], def: '', tip: 'Mirror copy across the YZ (x), XZ (y) or XY (z) plane through the origin; "xy" makes 4 copies' },
  { k: 'pat', kind: 'enum', opts: ['none', 'linear', 'polar'], def: 'none', tip: 'Repeat the feature' },
  { k: 'patN', kind: 'num', def: '2', show: (f) => f.pat !== 'none', tip: 'Instance count' },
  { k: 'patD', kind: 'vec3', def: V3(10, 0, 0), show: (f) => f.pat === 'linear', tip: 'Step between instances' },
  { k: 'patAxis', kind: 'enum', opts: ['x', 'y', 'z'], def: 'z', show: (f) => f.pat === 'polar' },
  { k: 'patAngle', kind: 'num', def: '360', show: (f) => f.pat === 'polar', tip: '360 spreads instances evenly round the full circle' },
  { k: 'patCenter', kind: 'vec3', def: V3(0, 0, 0), show: (f) => f.pat === 'polar', tip: 'A point on the rotation axis' },
];

const DIRS = ['+z', '-z', '+x', '-x', '+y', '-y'];

export const TYPES = {
  box: {
    label: 'Box', icon: '▭', place: true,
    desc: 'Rectangular block, optionally with rounded corners',
    fields: [
      { k: 'size', kind: 'vec3', def: V3(10, 10, 10), tip: 'X, Y, Z extents' },
      { k: 'radius', kind: 'num', def: '0', tip: 'Corner radius' },
      { k: 'edges', kind: 'enum', opts: ['vertical', 'all'], def: 'vertical', tip: 'Round only the vertical edges, or every edge' },
      { k: 'anchor', kind: 'enum', opts: ['center', 'bottom', 'min'], def: 'center', tip: 'center = pos is the middle; bottom = centred in XY, sitting on pos.z; min = pos is the min corner' },
    ],
  },
  cylinder: {
    label: 'Cylinder', icon: '◯', place: true,
    desc: 'Cylinder or cone along local Z',
    fields: [
      { k: 'd', kind: 'num', def: '10', tip: 'Diameter' },
      { k: 'd2', kind: 'num', def: '', opt: true, tip: 'Top diameter (blank = same, makes a cone otherwise)' },
      { k: 'h', kind: 'num', def: '10', tip: 'Height' },
      { k: 'anchor', kind: 'enum', opts: ['bottom', 'center'], def: 'bottom' },
    ],
  },
  sphere: {
    label: 'Sphere', icon: '●', place: true,
    desc: 'Sphere',
    fields: [{ k: 'd', kind: 'num', def: '10', tip: 'Diameter' }],
  },
  slot: {
    label: 'Slot', icon: '▬', place: true,
    desc: 'Obround (stadium) prism, long axis along local X. Good for vents.',
    fields: [
      { k: 'length', kind: 'num', def: '12', tip: 'Overall length including rounded ends' },
      { k: 'width', kind: 'num', def: '3' },
      { k: 'h', kind: 'num', def: '10', tip: 'Extrusion height along local Z' },
      { k: 'anchor', kind: 'enum', opts: ['center', 'bottom'], def: 'center' },
    ],
  },
  hole: {
    label: 'Hole', icon: '◎', place: false, dirBased: true, defOp: 'cut',
    desc: 'Drilled hole starting at pos and going along dir (into the material). Optional counterbore or countersink.',
    fields: [
      { k: 'd', kind: 'num', def: '3', tip: 'Diameter' },
      { k: 'depth', kind: 'num', def: '0', tip: '0 = through everything' },
      { k: 'dir', kind: 'enum', opts: DIRS, def: '-z', tip: 'Drill direction' },
      { k: 'cboreD', kind: 'num', def: '0', tip: 'Counterbore diameter (0 = none)' },
      { k: 'cboreDepth', kind: 'num', def: '2' },
      { k: 'cskD', kind: 'num', def: '0', tip: 'Countersink diameter at the surface, 90° (0 = none)' },
    ],
  },
  standoff: {
    label: 'Standoff', icon: '⊙', place: false, dirBased: true,
    desc: 'Cylindrical boss standing from pos along dir, with an optional pilot hole. For PCB posts and screw bosses.',
    fields: [
      { k: 'd', kind: 'num', def: '6', tip: 'Outer diameter' },
      { k: 'h', kind: 'num', def: '4' },
      { k: 'holeD', kind: 'num', def: '2.5', tip: 'Pilot hole diameter (0 = solid)' },
      { k: 'holeDepth', kind: 'num', def: '4' },
      { k: 'dir', kind: 'enum', opts: DIRS, def: '+z' },
    ],
  },
  shell_box: {
    label: 'Shell box', icon: '▢', place: true,
    desc: 'Hollow rounded box (an enclosure half). Sits on pos.z, centred in XY.',
    fields: [
      { k: 'size', kind: 'vec3', def: V3(60, 40, 20), tip: 'Outer X, Y, Z' },
      { k: 'wall', kind: 'num', def: '2' },
      { k: 'floor', kind: 'num', def: '2', tip: 'Thickness of the bottom' },
      { k: 'radius', kind: 'num', def: '3', tip: 'Outer corner radius' },
      { k: 'top', kind: 'enum', opts: ['open', 'closed'], def: 'open' },
    ],
  },
  enclosure: {
    label: 'PCB enclosure', icon: '⬢', place: false, noCommon: true, occt: true,
    desc: 'Parametric enclosure generated from the pcb block (components, keep-outs, cut-outs). Needs the OCCT kernel. Builds a shell body and a rear cap body, with a B-rep scorecard.',
    fields: [
      { k: 'name', kind: 'text', def: '' },
      { k: 'body', kind: 'text', def: 'Shell', tip: 'Body name of the enclosure shell' },
      { k: 'capBody', kind: 'text', def: 'Cap', tip: 'Body name of the rear cap (blank = no separate cap body)' },
      { k: 'style', kind: 'enum', opts: ['monolith', 'bullet', 'cell'], def: 'monolith', tip: 'monolith = straight body; bullet = tapered snout; cell = monolith with a Voronoi cell texture' },
      { k: 'wall', kind: 'num', def: '1.6', tip: 'Minimum wall (mm)' },
      { k: 'clearance', kind: 'num', def: '0.3', tip: 'Printed-fit clearance around every internal part (mm)' },
      { k: 'cap', kind: 'enum', opts: ['plug-screws', 'none'], def: 'plug-screws', tip: 'Rear cap with a plug and screws, or an open rear' },
      { k: 'fastener', kind: 'enum', opts: ['M2', 'M2.5', 'M3'], def: 'M2', show: (f) => f.cap !== 'none', tip: 'Sets pilot hole, clearance hole and countersink sizes' },
      { k: 'textureDensity', kind: 'num', def: '1', show: (f) => f.style === 'cell', tip: 'Cell count multiplier (1 = the template default)' },
      { k: 'textureDepth', kind: 'num', def: '0.7', show: (f) => f.style === 'cell', tip: 'Recess depth of the cells (mm)' },
      { k: 'textureGap', kind: 'num', def: '1.1', show: (f) => f.style === 'cell', tip: 'Land between cells (mm)' },
      { k: 'textureSeed', kind: 'num', def: '11', show: (f) => f.style === 'cell', tip: 'Random seed of the cell layout' },
      { k: 'spec', kind: 'json', def: {}, tip: 'Frame and station tables: yBottom, xFront, xRear, stations {monolith, bullet}, cap, texture, print' },
    ],
  },
  import: {
    label: 'Import', icon: '⬡', place: true,
    desc: 'An imported STEP/STL mesh asset',
    fields: [
      { k: 'asset', kind: 'text', def: '', tip: 'Asset id (set by the importer)' },
      { k: 'scale', kind: 'num', def: '1' },
    ],
  },
  transform: {
    label: 'Transform', icon: '⤧', place: false, noCommon: true,
    desc: 'Deform or move a whole body: move, rotate, scale, shear, twist, taper or bend. Applies to everything built in that body so far.',
    fields: [
      { k: 'name', kind: 'text', def: '' },
      { k: 'body', kind: 'text', def: 'Body', tip: 'Body to transform ("*" = all bodies)' },
      { k: 'mode', kind: 'enum', opts: ['move', 'rotate', 'scale', 'shear', 'twist', 'taper', 'bend'], def: 'move' },
      { k: 'offset', kind: 'vec3', def: V3(0, 0, 10), show: (f) => f.mode === 'move', tip: 'Translation (mm)' },
      { k: 'angles', kind: 'vec3', def: V3(0, 0, 45), show: (f) => f.mode === 'rotate', tip: 'Rotation about X, Y, Z (degrees), about center' },
      { k: 'factor', kind: 'vec3', def: V3(1, 1, 1), show: (f) => f.mode === 'scale', tip: 'Scale per axis about center (use -1 to mirror)' },
      { k: 'axis', kind: 'enum', opts: ['x', 'y', 'z'], def: 'z', show: (f) => ['shear', 'twist', 'taper', 'bend'].includes(f.mode), tip: 'shear: the axis that gets displaced. twist/taper: the axis it happens along. bend: the length axis' },
      { k: 'byAxis', kind: 'enum', opts: ['x', 'y', 'z'], def: 'x', show: (f) => f.mode === 'shear' || f.mode === 'bend', tip: 'shear: displacement grows along this axis. bend: the body curls toward +this axis' },
      { k: 'amount', kind: 'num', def: '0.3', show: (f) => ['shear', 'twist', 'taper', 'bend'].includes(f.mode), tip: 'shear: mm of slide per mm (0.3 ≈ 17°). twist: total degrees end to end. taper: scale at the far end (0.5 = half). bend: total arc degrees' },
      { k: 'center', kind: 'vec3', def: V3(0, 0, 0), show: (f) => f.mode !== 'move', tip: 'Pivot / origin of the deformation' },
      { k: 'refine', kind: 'num', def: '1.5', show: (f) => ['twist', 'taper', 'bend'].includes(f.mode), tip: 'Max edge length (mm) before bending; smaller = smoother and heavier' },
    ],
  },
  split: {
    label: 'Split', icon: '⫽', place: false, noCommon: true,
    desc: 'Cut a body in two with an axis-aligned plane. keeps one side in "body" and moves the other into the body named "other". Use for lid/base.',
    fields: [
      { k: 'name', kind: 'text', def: '' },
      { k: 'body', kind: 'text', def: 'Body' },
      { k: 'axis', kind: 'enum', opts: ['x', 'y', 'z'], def: 'z' },
      { k: 'offset', kind: 'num', def: '10', tip: 'Plane position along the axis' },
      { k: 'keep', kind: 'enum', opts: ['below', 'above'], def: 'below', tip: 'Which side stays in "body"' },
      { k: 'other', kind: 'text', def: 'Lid', tip: 'Body name for the other half (blank = discard)' },
    ],
  },
};

export function fieldsFor(type) {
  const T = TYPES[type];
  if (!T) return [];
  if (T.noCommon) return T.fields;
  const head = type === 'hole' || type === 'standoff'
    ? [...COMMON_HEAD, { k: 'pos', kind: 'vec3', def: V3(0, 0, 0), tip: 'Start point (mm)' }]
    : COMMON_HEAD;
  const mid = T.place ? [...T.fields, ...PLACE] : T.fields;
  const withPos = type === 'hole' || type === 'standoff' ? head.concat(mid) : head.concat(mid);
  return withPos.concat(COMMON_TAIL);
}

let uid = 1;
export function newId() { return 'f' + (uid++).toString(36) + Math.floor(Math.random() * 1296).toString(36); }

export function defaultFeature(type, over = {}) {
  const T = TYPES[type];
  if (!T) throw new Error('unknown feature type ' + type);
  const f = { id: newId(), type, suppressed: false, locked: false };
  for (const fd of fieldsFor(type)) f[fd.k] = fd.kind === 'json' ? clone(fd.def) : Array.isArray(fd.def) ? [...fd.def] : fd.def;
  if (T.defOp) f.op = T.defOp;
  if (!f.name) f.name = T.label;
  return Object.assign(f, over);
}

export const KERNELS = ['manifold', 'occt'];
export const DOC_VERSION = 3;

/**
 * The PCB block (document v3): what the enclosure template reads. Coordinates are millimetres in the template frame
 * (x = length with the front at -x, y = up, z = width).
 *   components[]  { name, kind:'box'|'cylinder', box:[x0,x1,y0,y1,z0,z1] | cyl:{axis,a0,a1,c:[u,v],r}, role, clearance:'tight'|'normal'|'loose',
 *                   sensitive, emitter:{direction:[x,y,z], beamR, reach}, insert:'rear'|'bottom'|null, cradle:{t,rear,downTo,upTo,zExtra},
 *                   edgeSlot:{y:[y0,y1],depth}, wall:{extra,minY}|null, exposed:{dir,label}, paddle:{shrink,label}, locked }
 *   cutouts[]     { name, shape:'box', box } | { name, shape:'cylinder', axis, center:[x,y,z], r, length }
 *   supports[]    { name, box }   solid ribs/blocks added to the cradles
 *   keep[]        { name, source:<asset id>, part:{bbox}, boxes:[[x0,x1,y0,y1,z0,z1]] }  regions kept from an imported original solid
 *   board         { outline:[[x,z]...], thickness }  informational
 */
export function emptyPcb() { return { name: '', board: null, components: [], cutouts: [], supports: [], keep: [] }; }

export function emptyProject() {
  return {
    v: DOC_VERSION, name: 'Untitled', params: [], features: [], assets: {}, bodies: {}, pcb: emptyPcb(),
    settings: { detail: 48, density: 1.24, printVolume: [180, 180, 180], kernel: 'manifold' },
  };
}

export const clone = (p) => JSON.parse(JSON.stringify(p));

/** Bring any saved project (v1, v2, v3) up to the current document version. Never mutates its input. */
export function migrate(p) {
  const out = Object.assign(emptyProject(), clone(p || {}));
  out.settings = Object.assign({ detail: 48, density: 1.24, printVolume: [180, 180, 180], kernel: 'manifold' }, out.settings || {});
  if (!KERNELS.includes(out.settings.kernel)) out.settings.kernel = 'manifold';
  out.pcb = Object.assign(emptyPcb(), out.pcb || {});
  out.bodies = out.bodies || {};
  out.assets = out.assets || {};
  out.v = DOC_VERSION;
  return out;
}

export const usesOcct = (P) => P?.settings?.kernel === 'occt';
export const needsOcct = (P) => (P.features || []).some((f) => !f.suppressed && TYPES[f.type]?.occt);

// ───────────────────────── Enclosure wizard ─────────────────────────

export const WIZARD_DEFAULTS = {
  pcbL: 60, pcbW: 40, pcbT: 1.6, clr: 0.6, wall: 2, floor: 2, lidT: 2, cornerR: 3,
  innerH: 14, standoffH: 4, standoffD: 6, screwD: 2.5, holeInset: 3.5, lipH: 2.5, lipTol: 0.25,
  ports: [{ name: 'USB-C', side: '-y', along: 0, z: 2, w: 9.5, h: 3.4 }],
};

export function wizardProject(o = {}) {
  const w = { ...WIZARD_DEFAULTS, ...o };
  const P = emptyProject();
  P.name = o.name || 'Enclosure';
  P.params = [
    { name: 'pcbL', expr: String(w.pcbL) }, { name: 'pcbW', expr: String(w.pcbW) }, { name: 'pcbT', expr: String(w.pcbT) },
    { name: 'clr', expr: String(w.clr) }, { name: 'wall', expr: String(w.wall) }, { name: 'floorT', expr: String(w.floor) },
    { name: 'lidT', expr: String(w.lidT) }, { name: 'cornerR', expr: String(w.cornerR) },
    { name: 'innerH', expr: String(w.innerH) }, { name: 'standoffH', expr: String(w.standoffH) },
    { name: 'standoffD', expr: String(w.standoffD) }, { name: 'screwD', expr: String(w.screwD) },
    { name: 'holeInset', expr: String(w.holeInset) }, { name: 'lipH', expr: String(w.lipH) }, { name: 'lipTol', expr: String(w.lipTol) },
    { name: 'inL', expr: 'pcbL + 2*clr' }, { name: 'inW', expr: 'pcbW + 2*clr' },
    { name: 'outL', expr: 'inL + 2*wall' }, { name: 'outW', expr: 'inW + 2*wall' },
    { name: 'baseH', expr: 'floorT + innerH' },
  ];
  const F = [];
  F.push(defaultFeature('shell_box', {
    name: 'Base shell', body: 'Base', op: 'add', size: ['outL', 'outW', 'baseH'], wall: 'wall', floor: 'floorT', radius: 'cornerR', top: 'open',
  }));
  F.push(defaultFeature('standoff', {
    name: 'PCB standoffs', body: 'Base', op: 'add', d: 'standoffD', h: 'standoffH', holeD: 'screwD', holeDepth: 'standoffH',
    pos: ['pcbL/2 - holeInset', 'pcbW/2 - holeInset', 'floorT'], dir: '+z', mirror: 'xy',
  }));
  // Lid: plate + lip that drops into the cavity. Positioned above the base (exploded) so both are visible.
  F.push(defaultFeature('box', {
    name: 'Lid plate', body: 'Lid', op: 'add', size: ['outL', 'outW', 'lidT'], radius: 'cornerR', edges: 'vertical', anchor: 'bottom', pos: ['0', '0', 'baseH + 8'],
  }));
  F.push(defaultFeature('box', {
    name: 'Lid lip', body: 'Lid', op: 'add', size: ['inL - 2*lipTol', 'inW - 2*lipTol', 'lipH'], radius: 'max(cornerR - wall, 0.5)', edges: 'vertical', anchor: 'bottom', pos: ['0', '0', 'baseH + 8 - lipH'],
  }));
  F.push(defaultFeature('box', {
    name: 'PCB (reference)', body: 'PCB', op: 'ref', size: ['pcbL', 'pcbW', 'pcbT'], anchor: 'bottom', pos: ['0', '0', 'floorT + standoffH'],
  }));
  for (const port of w.ports || []) {
    const side = port.side || '-y';
    const horiz = side === '+x' || side === '-x';
    const sign = side[0] === '-' ? -1 : 1;
    const z = `floorT + standoffH + pcbT + ${port.z ?? 0} + ${port.h ?? 3}/2`;
    const edge = horiz ? `${sign}*outL/2` : `${sign}*outW/2`;
    const along = String(port.along ?? 0);
    F.push(defaultFeature('box', {
      name: port.name || 'Port', body: '*', op: 'cut',
      size: horiz ? ['wall*3', String(port.w ?? 9), String(port.h ?? 3)] : [String(port.w ?? 9), 'wall*3', String(port.h ?? 3)],
      anchor: 'center', pos: horiz ? [edge, along, z] : [along, edge, z],
    }));
  }
  P.features = F;
  return P;
}

// ───────────────────────── Op application ─────────────────────────

const isNum = (s) => s !== '' && s != null;

export function normalizeFeatureFields(f) {
  // Coerce numbers to strings and validate syntax of expression fields.
  const out = {};
  for (const fd of fieldsFor(f.type)) {
    if (!(fd.k in f)) continue;
    let v = f[fd.k];
    if (fd.kind === 'num') {
      if (typeof v === 'number') v = String(v);
      if (typeof v !== 'string') throw new Error(`${f.type}.${fd.k} must be a number or expression`);
      if (v !== '' || !fd.opt) checkSyntax(v === '' && !fd.opt ? '0' : v);
    } else if (fd.kind === 'vec3') {
      if (!Array.isArray(v) || v.length !== 3) throw new Error(`${f.type}.${fd.k} must be an array of 3`);
      v = v.map((x) => (typeof x === 'number' ? String(x) : x));
      v.forEach((x) => { if (typeof x !== 'string') throw new Error(`${f.type}.${fd.k} entries must be numbers/expressions`); checkSyntax(x); });
    } else if (fd.kind === 'enum') {
      v = String(v ?? '');
      if (!fd.opts.includes(v)) throw new Error(`${f.type}.${fd.k} must be one of ${fd.opts.map((o) => `"${o}"`).join(', ')}`);
    } else if (fd.kind === 'json') {
      if (v == null || typeof v !== 'object' || Array.isArray(v)) throw new Error(`${f.type}.${fd.k} must be an object`);
      v = clone(v);
    } else if (fd.kind === 'text') v = String(v ?? '');
    out[fd.k] = v;
  }
  return out;
}

export function validateComponent(c) {
  const num = (v) => typeof v === 'number' && Number.isFinite(v);
  if (c.kind === 'cylinder') {
    const y = c.cyl;
    if (!y || !['x', 'y', 'z'].includes(y.axis || 'x') || ![y.a0, y.a1, y.r].every(num) || !Array.isArray(y.c) || !y.c.every(num)) throw new Error(`pcb component "${c.name}": cylinder needs cyl {axis,a0,a1,c:[u,v],r} as numbers`);
  } else {
    if (c.kind && c.kind !== 'box') throw new Error(`pcb component "${c.name}": kind must be box or cylinder`);
    if (!Array.isArray(c.box) || c.box.length !== 6 || !c.box.every(num)) throw new Error(`pcb component "${c.name}": box must be [x0,x1,y0,y1,z0,z1] numbers`);
  }
}

export const bodyLocked = (P, b) => !!(P.bodies && P.bodies[b] && P.bodies[b].locked);
export const featureLocked = (P, f) => !!f.locked || (f.body !== '*' && bodyLocked(P, f.body));
function guard(P, f, verb) {
  if (featureLocked(P, f)) throw new Error(`"${f.name}" is locked by the user (${f.locked ? 'feature' : 'body ' + f.body} lock) — cannot ${verb} it. Tell the user it needs unlocking.`);
}
function guardBody(P, body, op) {
  if (op === 'ref') return;
  const targets = body === '*' ? Object.keys(P.bodies || {}) : [body];
  for (const b of targets) if (bodyLocked(P, b)) throw new Error(`body "${b}" is locked by the user — cannot change it`);
}
function findFeature(P, ref) {
  return P.features.find((x) => x.id === ref) || P.features.find((x) => x.name === ref);
}

/** Apply ops to a CLONE of the project. Returns {project, log}. Throws on invalid ops. */
export function applyOps(project, ops) {
  const P = clone(project);
  const log = [];
  for (const op of ops) {
    switch (op.op) {
      case 'set_param': {
        if (!/^[A-Za-z_][A-Za-z_0-9]*$/.test(op.name || '')) throw new Error(`bad parameter name "${op.name}"`);
        const expr = typeof op.expr === 'number' ? String(op.expr) : String(op.expr);
        checkSyntax(expr);
        const ex = P.params.find((p) => p.name === op.name);
        if (ex && ex.locked) throw new Error(`parameter "${op.name}" is locked by the user`);
        if (ex) { log.push(`~ ${op.name}: ${ex.expr} → ${expr}`); ex.expr = expr; }
        else { P.params.push({ name: op.name, expr }); log.push(`+ param ${op.name} = ${expr}`); }
        break;
      }
      case 'remove_param': {
        const i = P.params.findIndex((p) => p.name === op.name);
        if (i < 0) throw new Error(`no parameter "${op.name}"`);
        if (P.params[i].locked) throw new Error(`parameter "${op.name}" is locked by the user`);
        P.params.splice(i, 1); log.push(`− param ${op.name}`); break;
      }
      case 'add_feature': {
        const spec = op.feature || {};
        if (!TYPES[spec.type]) throw new Error(`unknown feature type "${spec.type}"`);
        const f = defaultFeature(spec.type);
        const fields = normalizeFeatureFields({ ...spec, type: spec.type });
        Object.assign(f, fields);
        if (spec.suppressed) f.suppressed = true;
        guardBody(P, f.body, f.op);
        if (!fields.name) f.name = TYPES[spec.type].label;
        const idx = Number.isInteger(op.index) ? Math.max(0, Math.min(P.features.length, op.index)) : P.features.length;
        P.features.splice(idx, 0, f);
        log.push(`+ ${f.type} "${f.name}"`);
        break;
      }
      case 'update_feature': {
        const f = findFeature(P, op.id);
        if (!f) throw new Error(`no feature "${op.id}"`);
        guard(P, f, 'edit');
        if (op.set && 'body' in op.set) guardBody(P, String(op.set.body), f.op);
        const merged = normalizeFeatureFields({ ...f, ...(op.set || {}), type: f.type });
        const changed = Object.keys(op.set || {}).filter((k) => k in merged);
        const bad = Object.keys(op.set || {}).filter((k) => !(k in merged) && k !== 'suppressed');
        if (bad.length) throw new Error(`feature "${f.name}" (${f.type}) has no field(s): ${bad.join(', ')}`);
        for (const k of changed) f[k] = merged[k];
        if ('suppressed' in (op.set || {})) f.suppressed = !!op.set.suppressed;
        log.push(`~ "${f.name}": ${[...changed, ...('suppressed' in (op.set || {}) ? ['suppressed'] : [])].join(', ')}`);
        break;
      }
      case 'remove_feature': {
        const f = findFeature(P, op.id);
        if (!f) throw new Error(`no feature "${op.id}"`);
        guard(P, f, 'remove');
        P.features.splice(P.features.indexOf(f), 1); log.push(`− ${f.type} "${f.name}"`); break;
      }
      case 'move_feature': {
        const f = findFeature(P, op.id);
        if (!f) throw new Error(`no feature "${op.id}"`);
        guard(P, f, 'reorder');
        P.features.splice(P.features.indexOf(f), 1);
        P.features.splice(Math.max(0, Math.min(P.features.length, op.index | 0)), 0, f);
        log.push(`↕ "${f.name}"`); break;
      }
      case 'set_setting': {
        if (op.key === 'kernel') {
          if (!KERNELS.includes(op.value)) throw new Error(`kernel must be one of ${KERNELS.join(', ')}`);
          P.settings.kernel = op.value; log.push(`~ kernel = ${op.value}`); break;
        }
        if (!['detail', 'density'].includes(op.key)) throw new Error(`unknown setting "${op.key}"`);
        const v = Number(op.value);
        if (!Number.isFinite(v)) throw new Error('setting value must be a number');
        P.settings[op.key] = op.key === 'detail' ? Math.max(8, Math.min(160, Math.round(v))) : v;
        log.push(`~ ${op.key} = ${P.settings[op.key]}`); break;
      }
      case 'set_pcb_component': {
        const c = op.component || {};
        if (!/^[A-Za-z_][\w -]*$/.test(c.name || '')) throw new Error('pcb component needs a name');
        validateComponent(c);
        P.pcb = Object.assign(emptyPcb(), P.pcb || {});
        const i = P.pcb.components.findIndex((x) => x.name === c.name);
        if (i >= 0 && P.pcb.components[i].locked) throw new Error(`pcb component "${c.name}" is locked by the user`);
        if (i >= 0) { P.pcb.components[i] = c; log.push(`~ pcb component "${c.name}"`); } else { P.pcb.components.push(c); log.push(`+ pcb component "${c.name}"`); }
        break;
      }
      case 'remove_pcb_component': {
        P.pcb = Object.assign(emptyPcb(), P.pcb || {});
        const i = P.pcb.components.findIndex((x) => x.name === op.name);
        if (i < 0) throw new Error(`no pcb component "${op.name}"`);
        if (P.pcb.components[i].locked) throw new Error(`pcb component "${op.name}" is locked by the user`);
        P.pcb.components.splice(i, 1); log.push(`− pcb component "${op.name}"`); break;
      }
      default: throw new Error(`unknown op "${op.op}"`);
    }
  }
  return { project: P, log };
}

/** Compact JSON for the AI: hide assets' bytes, drop default-valued optional fluff. */
export function projectForAI(P) {
  const features = P.features.map((f) => {
    const o = { id: f.id, type: f.type };
    for (const fd of fieldsFor(f.type)) {
      if (!(fd.k in f)) continue;
      if (fd.show && !fd.show(f)) continue;
      o[fd.k] = f[fd.k];
    }
    if (f.suppressed) o.suppressed = true;
    if (featureLocked(P, f)) o.locked = true;
    return o;
  });
  return { name: P.name, pcb: (P.pcb && (P.pcb.components || []).length) ? P.pcb : undefined, params: P.params.map((p) => (p.locked ? { ...p, locked: true } : { name: p.name, expr: p.expr })), bodies: P.bodies || {}, features, settings: P.settings, assets: Object.fromEntries(Object.entries(P.assets || {}).map(([k, a]) => [k, { name: a.name, tris: a.tris, bbox: a.bbox }])) };
}
