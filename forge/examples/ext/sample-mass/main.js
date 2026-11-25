// Sample analysis plugin. An analysis receives everything it needs in `input`: the bodies' meshes (mm), each body's material in SI,
// and the loads and conditions. It returns { summary, fields, warnings, validity }; the host adds the id, the version and the inputs
// hash, validates the shape, and decides what a check on it may do. `ctx.progress(0..1)` reports progress, `ctx.signal` cancels.
const safe = (s) => String(s).replace(/[^\w.-]+/g, '_').slice(0, 40);
const tick = () => new Promise((r) => setTimeout(r, 0)); // let the Worker read an abort message between chunks

forge.analysis.register('mass-properties', {
  async run(input, ctx) {
    const rows = [], fields = [], warnings = [];
    let totalV = 0, totalM = 0; const cog = [0, 0, 0];
    for (let bi = 0; bi < input.bodies.length; bi++) {
      const b = input.bodies[bi], mat = input.setup.bodies.find((x) => x.name === b.name).material;
      const p = b.positions, idx = b.indices; let V = 0; const c = [0, 0, 0];
      for (let t = 0; t < idx.length; t += 3) {
        if ((t / 3) % 20000 === 0) { if (ctx.signal.aborted) throw new Error('cancelled'); await tick(); }
        const a = idx[t] * 3, bb = idx[t + 1] * 3, cc = idx[t + 2] * 3;
        const ax = p[a], ay = p[a + 1], az = p[a + 2], bx = p[bb], by = p[bb + 1], bz = p[bb + 2], cx = p[cc], cy = p[cc + 1], cz = p[cc + 2];
        const v = (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6; // signed tetrahedron with the origin
        V += v; c[0] += v * (ax + bx + cx) / 4; c[1] += v * (ay + by + cy) / 4; c[2] += v * (az + bz + cz) / 4;
      }
      if (!(V > 0)) { warnings.push(`Body "${b.name}": the mesh volume is not positive (${V.toFixed(3)} mm³); the mesh may be open or inverted.`); continue; }
      const centre = c.map((x) => x / V), grams = mat.props.rho * V * 1e-6; // kg/m³ * mm³ = 1e-9 kg = 1e-6 g
      rows.push({ metric: `volume_${safe(b.name)}`, value: V / 1000, unit: 'cm³' }, { metric: `mass_${safe(b.name)}`, value: grams, unit: 'g', where: centre });
      totalV += V; totalM += grams; for (let k = 0; k < 3; k++) cog[k] += centre[k] * grams;
      // surface field: distance of each vertex from the body's own centre of mass, one value per vertex (the order of the mesh)
      const d = new Float32Array(p.length / 3);
      for (let i = 0; i < d.length; i++) d[i] = Math.hypot(p[i * 3] - centre[0], p[i * 3 + 1] - centre[1], p[i * 3 + 2] - centre[2]);
      fields.push({ name: `distance_from_cog:${b.name}`, unit: 'mm', kind: 'surface', body: b.name, data: d });
      ctx.progress((bi + 1) / input.bodies.length);
    }
    if (totalM > 0) for (let k = 0; k < 3; k++) cog[k] /= totalM;
    const lead = [{ metric: 'total_mass', value: totalM, unit: 'g', where: cog }, { metric: 'total_volume', value: totalV / 1000, unit: 'cm³' }];
    return { summary: [...lead, ...rows], fields, warnings, validity: 'validated' };
  },
});

forge.analysis.register('section-density', {
  async run(input, ctx) {
    const fields = [], rows = [], warnings = []; let vox = 0, maxRho = 0;
    for (let bi = 0; bi < input.bodies.length; bi++) {
      if (ctx.signal.aborted) throw new Error('cancelled');
      const b = input.bodies[bi], rho = input.setup.bodies.find((x) => x.name === b.name).material.props.rho;
      const size = Math.max(b.bbox.max[0] - b.bbox.min[0], b.bbox.max[1] - b.bbox.min[1], b.bbox.max[2] - b.bbox.min[2]);
      const h = Math.max(1, size / 48); // voxel size in mm, at most 48 per side; the grid is stated with the field
      const g = await forge.geometry.voxelize(b.name, h);
      const data = new Float32Array(g.data.length); let n = 0;
      for (let i = 0; i < data.length; i++) if (g.data[i]) { data[i] = rho; n++; }
      vox += n * h * h * h; maxRho = Math.max(maxRho, rho);
      fields.push({ name: `density:${b.name}`, unit: 'kg/m³', kind: 'voxel', data, grid: { origin: g.origin, h: g.h, dims: g.dims } });
      warnings.push(`Body "${b.name}" voxelised at ${h.toFixed(2)} mm (${g.dims.join('×')} cells).`);
      ctx.progress((bi + 1) / input.bodies.length);
    }
    rows.push({ metric: 'solid_volume', value: vox / 1000, unit: 'cm³' }, { metric: 'max_density', value: maxRho, unit: 'kg/m³' });
    return { summary: rows, fields, warnings, validity: 'experimental' };
  },
});
