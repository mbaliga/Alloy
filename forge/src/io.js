// File I/O: STL/OBJ/STEP export, STEP/STL import.
import { bytesToB64 } from './kernel.js';

export function toSTL(bodies) {
  let n = 0;
  for (const b of bodies) n += b.mesh.idx.length / 3;
  return fixSTL(bodies, n);
}

function fixSTL(bodies, n) {
  const buf = new ArrayBuffer(84 + n * 50);
  const dv = new DataView(buf);
  const hdr = 'Alloy Forge';
  for (let i = 0; i < hdr.length; i++) dv.setUint8(i, hdr.charCodeAt(i));
  dv.setUint32(80, n, true);
  let o = 84;
  for (const b of bodies) {
    const p = b.mesh.pos, ix = b.mesh.idx;
    for (let t = 0; t < ix.length; t += 3) {
      const a = ix[t] * 3, q = ix[t + 1] * 3, c = ix[t + 2] * 3;
      const ux = p[q] - p[a], uy = p[q + 1] - p[a + 1], uz = p[q + 2] - p[a + 2];
      const vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
      let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      const l = Math.hypot(nx, ny, nz) || 1;
      dv.setFloat32(o, nx / l, true); dv.setFloat32(o + 4, ny / l, true); dv.setFloat32(o + 8, nz / l, true);
      let w = o + 12;
      for (const k of [a, q, c]) { dv.setFloat32(w, p[k], true); dv.setFloat32(w + 4, p[k + 1], true); dv.setFloat32(w + 8, p[k + 2], true); w += 12; }
      dv.setUint16(w, 0, true);
      o += 50;
    }
  }
  return new Blob([buf], { type: 'model/stl' });
}

export function toOBJ(bodies) {
  let s = '# Alloy Forge\n', off = 1;
  for (const b of bodies) {
    s += `o ${b.name}\n`;
    const p = b.mesh.pos;
    for (let i = 0; i < p.length; i += 3) s += `v ${p[i]} ${p[i + 1]} ${p[i + 2]}\n`;
    const ix = b.mesh.idx;
    for (let i = 0; i < ix.length; i += 3) s += `f ${ix[i] + off} ${ix[i + 1] + off} ${ix[i + 2] + off}\n`;
    off += p.length / 3;
  }
  return new Blob([s], { type: 'text/plain' });
}

const f = (x) => {
  let s = (+x).toPrecision(10);
  if (s.includes('e')) { let [m, e] = s.split('e'); if (!m.includes('.')) m += '.'; return m + 'E' + e.replace('+', ''); }
  return s.includes('.') ? s : s + '.';
};

/** Merge coplanar neighbouring triangles into planar faces with loops. Returns [{n, loops:[[v..]], outer:int[]|null, tris:[]}] */
function planarFaces(pos, idx) {
  const T = idx.length / 3, V = pos.length / 3;
  const nrm = new Float64Array(T * 3), off = new Float64Array(T);
  for (let t = 0; t < T; t++) {
    const a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
    const ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
    const vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
    let nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
    const l = Math.hypot(nx, ny, nz) || 1; nx /= l; ny /= l; nz /= l;
    nrm[t * 3] = nx; nrm[t * 3 + 1] = ny; nrm[t * 3 + 2] = nz;
    off[t] = nx * pos[a] + ny * pos[a + 1] + nz * pos[a + 2];
  }
  const dir = new Map();
  for (let t = 0; t < T; t++) for (let k = 0; k < 3; k++) dir.set(idx[t * 3 + k] * V + idx[t * 3 + (k + 1) % 3], t);
  const grp = new Int32Array(T).fill(-1);
  const groups = [];
  for (let s0 = 0; s0 < T; s0++) {
    if (grp[s0] >= 0) continue;
    const g = groups.length, tris = [s0]; grp[s0] = g;
    for (let q = 0; q < tris.length; q++) {
      const t = tris[q];
      for (let k = 0; k < 3; k++) {
        const nb = dir.get(idx[t * 3 + (k + 1) % 3] * V + idx[t * 3 + k]);
        if (nb === undefined || grp[nb] >= 0) continue;
        const dp = nrm[t * 3] * nrm[nb * 3] + nrm[t * 3 + 1] * nrm[nb * 3 + 1] + nrm[t * 3 + 2] * nrm[nb * 3 + 2];
        if (dp > 1 - 1e-9 && Math.abs(off[t] - off[nb]) < 1e-5) { grp[nb] = g; tris.push(nb); }
      }
    }
    groups.push(tris);
  }
  const faces = [];
  const single = (t) => faces.push({ n: [nrm[t * 3], nrm[t * 3 + 1], nrm[t * 3 + 2]], loops: [[idx[t * 3], idx[t * 3 + 1], idx[t * 3 + 2]]], outer: 0 });
  groups.forEach((tris, g) => {
    if (tris.length === 1) { single(tris[0]); return; }
    const out = new Map();
    for (const t of tris) for (let k = 0; k < 3; k++) {
      const a = idx[t * 3 + k], b = idx[t * 3 + (k + 1) % 3];
      const nb = dir.get(b * V + a);
      if (nb === undefined || grp[nb] !== g) { if (!out.has(a)) out.set(a, []); out.get(a).push(b); }
    }
    const loops = []; let ok = true;
    for (const [a0, list] of out) {
      while (list.length && ok) {
        const loop = [a0]; let cur = list.shift(), guard = 0;
        while (cur !== a0 && guard++ < 100000) {
          loop.push(cur); const nx = out.get(cur);
          if (!nx || !nx.length) { ok = false; break; }
          cur = nx.shift();
        }
        if (cur !== a0) ok = false; else loops.push(loop);
      }
      if (!ok) break;
    }
    const n = [nrm[tris[0] * 3], nrm[tris[0] * 3 + 1], nrm[tris[0] * 3 + 2]];
    let outerIdx = -1, nOuter = 0;
    if (ok) loops.forEach((lp, i) => {
      let ar = 0;
      for (let k = 0; k < lp.length; k++) {
        const p = lp[k] * 3, q = lp[(k + 1) % lp.length] * 3;
        ar += (pos[p + 1] * pos[q + 2] - pos[p + 2] * pos[q + 1]) * n[0] + (pos[p + 2] * pos[q] - pos[p] * pos[q + 2]) * n[1] + (pos[p] * pos[q + 1] - pos[p + 1] * pos[q]) * n[2];
      }
      if (ar > 0) { nOuter++; outerIdx = i; }
    });
    if (!ok || nOuter !== 1) { tris.forEach(single); return; }
    faces.push({ n, loops, outer: outerIdx });
  });
  return faces;
}

/** STEP AP203 B-rep with planar faces (coplanar triangles merged). Curved surfaces stay faceted. Opens in Creo, Fusion, FreeCAD, SolidWorks. */
export function toSTEP(bodies, name = 'Alloy Forge') {
  const L = [];
  let id = 0;
  const E = (s) => { id++; L.push(`#${id}=${s};`); return id; };
  const safe = (s) => String(s).replace(/'/g, '');
  const app = E("APPLICATION_CONTEXT('configuration controlled 3D designs of mechanical parts and assemblies')");
  E(`APPLICATION_PROTOCOL_DEFINITION('international standard','config_control_design',1994,#${app})`);
  const mech = E(`MECHANICAL_CONTEXT('none',#${app},'mechanical')`);
  const pdc = E(`PRODUCT_DEFINITION_CONTEXT('part definition',#${app},'design')`);
  const lu = E('(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.))');
  const au = E('(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT($,.RADIAN.))');
  const su = E('(NAMED_UNIT(*)SI_UNIT($,.STERADIAN.)SOLID_ANGLE_UNIT())');
  const unc = E(`UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-3),#${lu},'distance_accuracy_value','')`);
  const ctx = E(`(GEOMETRIC_REPRESENTATION_CONTEXT(3)GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#${unc}))GLOBAL_UNIT_ASSIGNED_CONTEXT((#${lu},#${au},#${su}))REPRESENTATION_CONTEXT('ctx','3D'))`);
  const origin = E("CARTESIAN_POINT('',(0.,0.,0.))");
  const dz = E("DIRECTION('',(0.,0.,1.))"), dx = E("DIRECTION('',(1.,0.,0.))");
  const axis = E(`AXIS2_PLACEMENT_3D('',#${origin},#${dz},#${dx})`);
  const solids = [];
  for (const b of bodies) {
    const p = b.mesh.pos, V = p.length / 3;
    const faces = planarFaces(p, b.mesh.idx);
    const vtx = new Int32Array(V).fill(0), pts = new Int32Array(V).fill(0);
    const vertex = (v) => {
      if (!vtx[v]) { pts[v] = E(`CARTESIAN_POINT('',(${f(p[v * 3])},${f(p[v * 3 + 1])},${f(p[v * 3 + 2])}))`); vtx[v] = E(`VERTEX_POINT('',#${pts[v]})`); }
      return vtx[v];
    };
    const edges = new Map();
    const edge = (a, c) => {
      const lo = Math.min(a, c), hi = Math.max(a, c), key = lo * V + hi;
      let e = edges.get(key);
      if (!e) {
        const va = vertex(lo), vb = vertex(hi);
        const dx_ = p[hi * 3] - p[lo * 3], dy = p[hi * 3 + 1] - p[lo * 3 + 1], dz_ = p[hi * 3 + 2] - p[lo * 3 + 2], len = Math.hypot(dx_, dy, dz_) || 1;
        const d = E(`DIRECTION('',(${f(dx_ / len)},${f(dy / len)},${f(dz_ / len)}))`);
        const v = E(`VECTOR('',#${d},${f(len)})`);
        const ln = E(`LINE('',#${pts[lo]},#${v})`);
        e = E(`EDGE_CURVE('',#${va},#${vb},#${ln},.T.)`); edges.set(key, e);
      }
      return e;
    };
    const faceIds = [];
    for (const fc of faces) {
      const bounds = fc.loops.map((lp, i) => {
        const oes = lp.map((a, k) => { const c = lp[(k + 1) % lp.length]; return '#' + E(`ORIENTED_EDGE('',*,*,#${edge(a, c)},${a < c ? '.T.' : '.F.'})`); });
        const el = E(`EDGE_LOOP('',(${oes.join(',')}))`);
        return E(i === fc.outer ? `FACE_OUTER_BOUND('',#${el},.T.)` : `FACE_BOUND('',#${el},.T.)`);
      });
      const lp0 = fc.loops[fc.outer], a = lp0[0] * 3, c = lp0[1] * 3;
      let rx = p[c] - p[a], ry = p[c + 1] - p[a + 1], rz = p[c + 2] - p[a + 2]; const rl = Math.hypot(rx, ry, rz) || 1;
      const pt = E(`CARTESIAN_POINT('',(${f(p[a])},${f(p[a + 1])},${f(p[a + 2])}))`);
      const nd = E(`DIRECTION('',(${f(fc.n[0])},${f(fc.n[1])},${f(fc.n[2])}))`);
      const rd = E(`DIRECTION('',(${f(rx / rl)},${f(ry / rl)},${f(rz / rl)}))`);
      const ax = E(`AXIS2_PLACEMENT_3D('',#${pt},#${nd},#${rd})`);
      const pl = E(`PLANE('',#${ax})`);
      faceIds.push(E(`ADVANCED_FACE('',(${bounds.map((x) => '#' + x).join(',')}),#${pl},.T.)`));
    }
    const sh = E(`CLOSED_SHELL('',(${faceIds.map((x) => '#' + x).join(',')}))`);
    solids.push(E(`MANIFOLD_SOLID_BREP('${safe(b.name)}',#${sh})`));
  }
  const prod = E(`PRODUCT('${safe(name)}','${safe(name)}','',(#${mech}))`);
  E(`PRODUCT_RELATED_PRODUCT_CATEGORY('part','',(#${prod}))`);
  const pdf = E(`PRODUCT_DEFINITION_FORMATION_WITH_SPECIFIED_SOURCE('','',#${prod},.NOT_KNOWN.)`);
  const pd = E(`PRODUCT_DEFINITION('design','',#${pdf},#${pdc})`);
  const pds = E(`PRODUCT_DEFINITION_SHAPE('','',#${pd})`);
  const rep = E(`ADVANCED_BREP_SHAPE_REPRESENTATION('',(#${axis},${solids.map((x) => '#' + x).join(',')}),#${ctx})`);
  E(`SHAPE_DEFINITION_REPRESENTATION(#${pds},#${rep})`);
  const date = new Date().toISOString().slice(0, 19);
  const head = `ISO-10303-21;\nHEADER;\nFILE_DESCRIPTION(('Alloy Forge export'),'2;1');\nFILE_NAME('${safe(name)}.stp','${date}',(''),(''),'Alloy Forge','Alloy Forge','');\nFILE_SCHEMA(('CONFIG_CONTROL_DESIGN'));\nENDSEC;\nDATA;\n`;
  return new Blob([head + L.join('\n') + '\nENDSEC;\nEND-ISO-10303-21;\n'], { type: 'application/step' });
}

// ───────────── import ─────────────

function bbox(pos) {
  const mn = [Infinity, Infinity, Infinity], mx = [-Infinity, -Infinity, -Infinity];
  for (let i = 0; i < pos.length; i += 3) for (let k = 0; k < 3; k++) { const v = pos[i + k]; if (v < mn[k]) mn[k] = v; if (v > mx[k]) mx[k] = v; }
  return { min: mn.map((v) => +v.toFixed(3)), max: mx.map((v) => +v.toFixed(3)) };
}

function packAsset(name, pos, idx) {
  pos = pos instanceof Float32Array ? pos : new Float32Array(pos);
  idx = idx instanceof Uint32Array ? idx : new Uint32Array(idx);
  return {
    name, tris: idx.length / 3, bbox: bbox(pos),
    pos: bytesToB64(new Uint8Array(pos.buffer, pos.byteOffset, pos.byteLength)),
    idx: bytesToB64(new Uint8Array(idx.buffer, idx.byteOffset, idx.byteLength)),
  };
}

function parseSTL(buf) {
  const dv = new DataView(buf);
  const n = dv.getUint32(80, true);
  if (84 + n * 50 === buf.byteLength) {
    const pos = new Float32Array(n * 9), idx = new Uint32Array(n * 3);
    for (let t = 0; t < n; t++) {
      const o = 84 + t * 50 + 12;
      for (let k = 0; k < 9; k++) pos[t * 9 + k] = dv.getFloat32(o + k * 4, true);
      idx[t * 3] = t * 3; idx[t * 3 + 1] = t * 3 + 1; idx[t * 3 + 2] = t * 3 + 2;
    }
    return { pos, idx };
  }
  const txt = new TextDecoder().decode(buf);
  const v = [...txt.matchAll(/vertex\s+(\S+)\s+(\S+)\s+(\S+)/g)];
  const pos = new Float32Array(v.length * 3), idx = new Uint32Array(v.length);
  v.forEach((m, i) => { pos[i * 3] = +m[1]; pos[i * 3 + 1] = +m[2]; pos[i * 3 + 2] = +m[3]; idx[i] = i; });
  return { pos, idx };
}

let occtPromise = null;
function loadOcct(base = '') {
  if (occtPromise) return occtPromise;
  occtPromise = new Promise((res, rej) => {
    const s = document.createElement('script');
    s.src = base + 'occt-import-js.js';
    s.onload = () => window.occtimportjs({ locateFile: (p) => base + p }).then(res, rej);
    s.onerror = () => { occtPromise = null; rej(new Error('Could not load the STEP reader (occt-import-js.js). Serve the app over http(s) with its wasm files next to index.html.')); };
    document.head.appendChild(s);
  });
  return occtPromise;
}

export async function importFile(file, base = '') {
  const buf = await file.arrayBuffer();
  const ext = file.name.toLowerCase().split('.').pop();
  const nm = file.name.replace(/\.[^.]+$/, '');
  if (ext === 'stl') { const { pos, idx } = parseSTL(buf); return packAsset(nm, pos, idx); }
  if (ext === 'stp' || ext === 'step') {
    const occt = await loadOcct(base);
    const r = occt.ReadStepFile(new Uint8Array(buf), { linearUnit: 'millimeter', linearDeflectionType: 'bounding_box_ratio', linearDeflection: 0.001, angularDeflection: 0.3 });
    if (!r.success || !r.meshes.length) throw new Error('STEP file could not be read');
    const pos = [], idx = [];
    let off = 0;
    for (const m of r.meshes) {
      const p = m.attributes.position.array;
      for (let i = 0; i < p.length; i++) pos.push(p[i]);
      for (const i of m.index.array) idx.push(i + off);
      off += p.length / 3;
    }
    return packAsset(nm, pos, idx);
  }
  throw new Error('Supported imports: .stp .step .stl');
}

// ───────────── minimal ZIP (store) so hosted pages can hand over .stp/.stl inside an allowed container ─────────────
const CRC = (() => { const t = new Uint32Array(256); for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; } return t; })();
const crc32 = (u) => { let c = 0xffffffff; for (let i = 0; i < u.length; i++) c = CRC[(c ^ u[i]) & 255] ^ (c >>> 8); return (c ^ 0xffffffff) >>> 0; };
export function zipOne(name, data) {
  const nb = new TextEncoder().encode(name), crc = crc32(data), n = data.length;
  const loc = new DataView(new ArrayBuffer(30)), cen = new DataView(new ArrayBuffer(46)), end = new DataView(new ArrayBuffer(22));
  loc.setUint32(0, 0x04034b50, true); loc.setUint16(4, 20, true); loc.setUint16(8, 0, true); loc.setUint32(14, crc, true); loc.setUint32(18, n, true); loc.setUint32(22, n, true); loc.setUint16(26, nb.length, true);
  cen.setUint32(0, 0x02014b50, true); cen.setUint16(4, 20, true); cen.setUint16(6, 20, true); cen.setUint32(16, crc, true); cen.setUint32(20, n, true); cen.setUint32(24, n, true); cen.setUint16(28, nb.length, true);
  const cdOff = 30 + nb.length + n;
  end.setUint32(0, 0x06054b50, true); end.setUint16(8, 1, true); end.setUint16(10, 1, true); end.setUint32(12, 46 + nb.length, true); end.setUint32(16, cdOff, true);
  return new Blob([loc, nb, data, cen, nb, end], { type: 'application/zip' });
}
