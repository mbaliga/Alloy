// Deterministic design checks for the design loop. Plain code, no model, no DOM: they take a project and the kernel's
// build result and return structured pass/fail results the loop (and the UI) can show.
//
//   runChecks(project, result, opts) -> { passed, checks: [{ id, label, severity, pass, detail, items? }], metrics }
//
// Hard checks (severity "hard") must all pass for the loop to finish:
//   features        no feature failed to build
//   bodies          the build produced at least one solid body
//   interference    no body overlaps a reference / keep-out part (op "ref", e.g. the PCB) or another body
//   min_wall        estimated minimum wall thickness >= opts.minWall (mm)
//   print_volume    every body fits inside settings.printVolume (x, y centred on the plate, z from 0)
//
// min_wall is an ESTIMATE: rays are cast inward from sampled surface points and the nearest exit is measured, so it
// finds thin walls and thin bosses but can miss a feature smaller than the sampling density. It is deterministic.

export const DEFAULTS = { minWall: 1.2, interferenceTol: 0.05, maxWallSamples: 900, maxRayWork: 4e7 };

const r2 = (v) => Math.round(v * 100) / 100;

export function fitsPrintVolume(bb, pv) {
  return bb.min[0] >= -pv[0] / 2 - 1e-3 && bb.max[0] <= pv[0] / 2 + 1e-3 && bb.min[1] >= -pv[1] / 2 - 1e-3 && bb.max[1] <= pv[1] / 2 + 1e-3 && bb.min[2] >= -1e-3 && bb.max[2] <= pv[2] + 1e-3;
}

const overlap = (a, b) => [0, 1, 2].every((i) => a.min[i] < b.max[i] - 1e-6 && b.min[i] < a.max[i] - 1e-6);

/** Volume (mm^3) shared by two manifolds, 0 when their boxes do not touch. */
export function sharedVolume(a, b) {
  if (!overlap(a.bbox, b.bbox)) return 0;
  const x = a.manifold.intersect(b.manifold);
  try { return x.isEmpty() ? 0 : x.volume(); } finally { x.delete(); }
}

// ---- minimum wall thickness estimate (inward ray casting on the mesh)

/** Möller–Trumbore, returns t > 0 or -1. */
function rayTri(ox, oy, oz, dx, dy, dz, pos, i0, i1, i2) {
  const ax = pos[i0], ay = pos[i0 + 1], az = pos[i0 + 2];
  const e1x = pos[i1] - ax, e1y = pos[i1 + 1] - ay, e1z = pos[i1 + 2] - az;
  const e2x = pos[i2] - ax, e2y = pos[i2 + 1] - ay, e2z = pos[i2 + 2] - az;
  const px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
  const det = e1x * px + e1y * py + e1z * pz;
  if (det > -1e-12 && det < 1e-12) return -1;
  const inv = 1 / det;
  const tx = ox - ax, ty = oy - ay, tz = oz - az;
  const u = (tx * px + ty * py + tz * pz) * inv;
  if (u < 0 || u > 1) return -1;
  const qx = ty * e1z - tz * e1y, qy = tz * e1x - tx * e1z, qz = tx * e1y - ty * e1x;
  const v = (dx * qx + dy * qy + dz * qz) * inv;
  if (v < 0 || u + v > 1) return -1;
  return (e2x * qx + e2y * qy + e2z * qz) * inv;
}

/**
 * Estimated minimum wall thickness of one body mesh { pos: Float32Array, idx: Uint32Array }.
 * Returns { min, at: [x,y,z] | null, samples } where min is Infinity if no inward ray ever hit the far side.
 */
export function estimateMinWall(mesh, { maxSamples = DEFAULTS.maxWallSamples, maxWork = DEFAULTS.maxRayWork } = {}) {
  const { pos, idx } = mesh;
  const T = idx.length / 3;
  if (!T) return { min: Infinity, at: null, samples: 0 };
  // thin walls live on larger faces: weight sampling toward every triangle when small, evenly strided otherwise
  let n = Math.min(T, maxSamples);
  n = Math.max(1, Math.min(n, Math.floor(maxWork / T)));
  const step = T / n;
  let min = Infinity, at = null, used = 0;
  for (let s = 0; s < n; s++) {
    const t = Math.min(T - 1, Math.floor(s * step));
    const a = idx[t * 3] * 3, b = idx[t * 3 + 1] * 3, c = idx[t * 3 + 2] * 3;
    const e1x = pos[b] - pos[a], e1y = pos[b + 1] - pos[a + 1], e1z = pos[b + 2] - pos[a + 2];
    const e2x = pos[c] - pos[a], e2y = pos[c + 1] - pos[a + 1], e2z = pos[c + 2] - pos[a + 2];
    let nx = e1y * e2z - e1z * e2y, ny = e1z * e2x - e1x * e2z, nz = e1x * e2y - e1y * e2x;
    const len = Math.hypot(nx, ny, nz);
    if (len < 1e-9) continue; // degenerate sliver
    nx /= len; ny /= len; nz /= len;
    const cx = (pos[a] + pos[b] + pos[c]) / 3, cy = (pos[a + 1] + pos[b + 1] + pos[c + 1]) / 3, cz = (pos[a + 2] + pos[b + 2] + pos[c + 2]) / 3;
    // inward = against the outward normal (manifold meshes are CCW-outward)
    const dx = -nx, dy = -ny, dz = -nz;
    const ox = cx + dx * 1e-3, oy = cy + dy * 1e-3, oz = cz + dz * 1e-3;
    let best = Infinity;
    for (let k = 0; k < T; k++) {
      if (k === t) continue;
      const t0 = rayTri(ox, oy, oz, dx, dy, dz, pos, idx[k * 3] * 3, idx[k * 3 + 1] * 3, idx[k * 3 + 2] * 3);
      if (t0 > 1e-4 && t0 < best) best = t0;
    }
    used++;
    if (best < min) { min = best + 1e-3; at = [cx, cy, cz]; }
  }
  return { min, at, samples: used };
}

/**
 * @param {object} project   the Forge project (settings.printVolume is used)
 * @param {object} result    kernel build() result: { bodies, refs, status }
 * @param {object} [opts]    { minWall, interferenceTol, ignorePairs: [[a,b]], skip: ['min_wall'] }
 */
export function runChecks(project, result, opts = {}) {
  const o = { ...DEFAULTS, ...opts };
  const skip = new Set(o.skip || []);
  const checks = [];
  const add = (c) => { if (!skip.has(c.id)) checks.push({ severity: 'hard', ...c }); };
  const features = project.features || [];
  const nameOf = (id) => features.find((f) => f.id === id)?.name || id;

  // 1. features
  const failing = Object.entries(result.status || {}).filter(([, v]) => v.ok === false).map(([id, v]) => ({ feature: nameOf(id), id, error: v.error }));
  if (result.fatal) failing.push({ feature: '(build)', error: result.fatal });
  add({ id: 'features', label: 'No failing features', pass: failing.length === 0, detail: failing.length ? failing.map((f) => `"${f.feature}": ${f.error}`).join('; ') : `${features.length} features built`, items: failing });

  // 2. bodies
  const bodies = result.bodies || [];
  add({ id: 'bodies', label: 'Produces a solid body', pass: bodies.length > 0, detail: bodies.length ? bodies.map((b) => b.name).join(', ') : 'the build produced no solid bodies' });

  // 3. interference (body vs reference / keep-out, and body vs body)
  const ignore = (o.ignorePairs || []).map((p) => p.join('|'));
  const hits = [];
  for (const b of bodies) {
    for (const r of result.refs || []) {
      if (ignore.includes(`${b.name}|${r.name}`)) continue;
      const v = sharedVolume(b, r);
      if (v > o.interferenceTol) hits.push({ a: b.name, b: r.name, kind: 'reference', volume_mm3: r2(v) });
    }
  }
  for (let i = 0; i < bodies.length; i++) for (let j = i + 1; j < bodies.length; j++) {
    if (ignore.includes(`${bodies[i].name}|${bodies[j].name}`) || ignore.includes(`${bodies[j].name}|${bodies[i].name}`)) continue;
    const v = sharedVolume(bodies[i], bodies[j]);
    if (v > o.interferenceTol) hits.push({ a: bodies[i].name, b: bodies[j].name, kind: 'body', volume_mm3: r2(v) });
  }
  add({ id: 'interference', label: 'No interference with reference parts or between bodies', pass: hits.length === 0,
    detail: hits.length ? hits.map((h) => `${h.a} overlaps ${h.b} by ${h.volume_mm3} mm³`).join('; ') : `${bodies.length} bodies, ${(result.refs || []).length} reference parts clear`, items: hits });

  // 4. minimum wall thickness (estimate)
  if (!skip.has('min_wall')) {
    let worst = { min: Infinity, body: null, at: null };
    for (const b of bodies) {
      const w = estimateMinWall(b.mesh || b.view);
      if (w.min < worst.min) worst = { min: w.min, body: b.name, at: w.at };
    }
    const known = Number.isFinite(worst.min);
    add({ id: 'min_wall', label: `Minimum wall thickness ≥ ${o.minWall} mm (estimate)`, pass: !known || worst.min >= o.minWall - 1e-6,
      detail: known ? `≈${r2(worst.min)} mm in ${worst.body} near (${worst.at.map(r2).join(', ')})` : 'no thin section found',
      items: known && worst.min < o.minWall - 1e-6 ? [{ body: worst.body, thickness_mm: r2(worst.min), at: worst.at.map(r2) }] : [], estimate: known ? r2(worst.min) : null });
  }

  // 5. print volume
  const pv = project.settings?.printVolume || [180, 180, 180];
  const off = bodies.filter((b) => !fitsPrintVolume(b.bbox, pv)).map((b) => ({ body: b.name, size_mm: b.bbox.max.map((v, i) => r2(v - b.bbox.min[i])), min: b.bbox.min.map(r2), max: b.bbox.max.map(r2) }));
  add({ id: 'print_volume', label: `Fits the ${pv.join('×')} mm print volume`, pass: off.length === 0,
    detail: off.length ? off.map((x) => `${x.body} ${x.size_mm.join('×')} mm at ${x.min.join(',')}..${x.max.join(',')} is outside`).join('; ') : 'every body fits', items: off });

  const hard = checks.filter((c) => c.severity === 'hard');
  return {
    passed: hard.every((c) => c.pass), checks, failed: hard.filter((c) => !c.pass).map((c) => c.id),
    metrics: { bodies: bodies.map((b) => ({ name: b.name, size_mm: b.bbox.max.map((v, i) => r2(v - b.bbox.min[i])), volume_mm3: Math.round(b.volume) })), minWall_mm: checks.find((c) => c.id === 'min_wall')?.estimate ?? null },
  };
}
