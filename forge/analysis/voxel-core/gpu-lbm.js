// WebGPU backend of the D3Q19 lattice Boltzmann flow solver (lbm.js): the same collision-streaming step as one compute kernel, one thread
// per lattice cell, populations in two storage buffers (structure of arrays, pull streaming, ping-pong), macroscopic density and velocity
// written every step to a small buffer that the host reads back every few hundred steps. Single precision. The thermal lattice is not
// on the GPU (A5 phase 2 runs on the CPU). Kernel layout and the boundary rules are those of lbm.js, which is the reference.
//
// Bindings (six storage buffers, under the WebGPU default of eight): 0 params (uniform), 1 f in, 2 f out, 3 flags (u32 per cell),
// 4 ubc (3 floats per cell, velocity of LBM_VEL cells when `hasUbc`), 5 force (3 floats per cell), 6 macro (4 floats per cell: rho, ux, uy, uz).
export const LBM_WG = 64;
const LBM_ROW = 65535; // workgroups per dispatch row

export function lbmWgsl() {
  return /* wgsl */ `
struct Params {
  nx: u32, ny: u32, nz: u32, n: u32,
  per: u32, hasUbc: u32, hasForce: u32, rowWgs: u32,
  tau0: f32, cs: f32, pad0: f32, pad1: f32,
  uWall: vec4<f32>, uVel: vec4<f32>, bodyF: vec4<f32>, u0: vec4<f32>,
};
@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read_write> fin: array<f32>;
@group(0) @binding(2) var<storage, read_write> fout: array<f32>;
@group(0) @binding(3) var<storage, read> flags: array<u32>;
@group(0) @binding(4) var<storage, read> ubc: array<f32>;
@group(0) @binding(5) var<storage, read> frc: array<f32>;
@group(0) @binding(6) var<storage, read_write> mac: array<f32>;

const CX = array<i32, 19>(0, 1, -1, 0, 0, 0, 0, 1, -1, 1, -1, 1, -1, 1, -1, 0, 0, 0, 0);
const CY = array<i32, 19>(0, 0, 0, 1, -1, 0, 0, 1, -1, -1, 1, 0, 0, 0, 0, 1, -1, 1, -1);
const CZ = array<i32, 19>(0, 0, 0, 0, 0, 1, -1, 0, 0, 0, 0, 1, -1, -1, 1, 1, -1, -1, 1);
const OPP = array<u32, 19>(0u, 2u, 1u, 4u, 3u, 6u, 5u, 8u, 7u, 10u, 9u, 12u, 11u, 14u, 13u, 16u, 15u, 18u, 17u);
const W = array<f32, 19>(0.33333333, 0.05555556, 0.05555556, 0.05555556, 0.05555556, 0.05555556, 0.05555556, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778, 0.02777778);
const FDX = array<i32, 6>(1, -1, 0, 0, 0, 0);
const FDY = array<i32, 6>(0, 0, 1, -1, 0, 0);
const FDZ = array<i32, 6>(0, 0, 0, 0, 1, -1);

fn feq(i: u32, r: f32, u: vec3<f32>) -> f32 {
  let cu = f32(CX[i]) * u.x + f32(CY[i]) * u.y + f32(CZ[i]) * u.z;
  return W[i] * r * (1.0 + 3.0 * cu + 4.5 * cu * cu - 1.5 * dot(u, u));
}

// index of the cell at (x - dx, y - dy, z - dz), or -1 beyond a non-periodic edge
fn source(x: u32, y: u32, z: u32, dx: i32, dy: i32, dz: i32) -> i32 {
  var sx = i32(x) - dx; var sy = i32(y) - dy; var sz = i32(z) - dz;
  let nx = i32(P.nx); let ny = i32(P.ny); let nz = i32(P.nz);
  if (sx < 0) { if ((P.per & 1u) != 0u) { sx = sx + nx; } else { return -1; } } else if (sx >= nx) { if ((P.per & 1u) != 0u) { sx = sx - nx; } else { return -1; } }
  if (sy < 0) { if ((P.per & 2u) != 0u) { sy = sy + ny; } else { return -1; } } else if (sy >= ny) { if ((P.per & 2u) != 0u) { sy = sy - ny; } else { return -1; } }
  if (sz < 0) { if ((P.per & 4u) != 0u) { sz = sz + nz; } else { return -1; } } else if (sz >= nz) { if ((P.per & 4u) != 0u) { sz = sz - nz; } else { return -1; } }
  return sx + nx * (sy + ny * sz);
}

fn cell_of(gid: vec3<u32>) -> u32 { return gid.x + gid.y * P.rowWgs * ${LBM_WG}u; }

@compute @workgroup_size(${LBM_WG})
fn init(@builtin(global_invocation_id) gid: vec3<u32>) {
  let c = cell_of(gid);
  if (c >= P.n) { return; }
  let t = flags[c] & 7u;
  var u = P.u0.xyz;
  if (t == 1u || t == 2u) { u = vec3<f32>(0.0); }
  if (t == 3u) { u = P.uVel.xyz; if (P.hasUbc != 0u) { u = vec3<f32>(ubc[3u * c], ubc[3u * c + 1u], ubc[3u * c + 2u]); } }
  for (var i = 0u; i < 19u; i = i + 1u) { let v = feq(i, 1.0, u); fin[i * P.n + c] = v; fout[i * P.n + c] = v; }
  mac[4u * c] = 1.0; mac[4u * c + 1u] = u.x; mac[4u * c + 2u] = u.y; mac[4u * c + 3u] = u.z;
}

@compute @workgroup_size(${LBM_WG})
fn step(@builtin(global_invocation_id) gid: vec3<u32>) {
  let c = cell_of(gid);
  if (c >= P.n) { return; }
  let N = P.n;
  let fl = flags[c]; let ty = fl & 7u;
  if (ty == 1u || ty == 2u) { return; }
  let x = c % P.nx; let y = (c / P.nx) % P.ny; let z = c / (P.nx * P.ny);
  if (ty >= 3u) { // boundary cell: equilibrium of the wanted state plus the non-equilibrium part of the fluid neighbour
    let d = (fl >> 3u) & 7u;
    let nb = source(x, y, z, -FDX[d], -FDY[d], -FDZ[d]);
    if (nb < 0) { return; }
    let n = u32(nb);
    var rn = 0.0; var mn = vec3<f32>(0.0);
    for (var i = 0u; i < 19u; i = i + 1u) { let v = fin[i * N + n]; rn = rn + v; mn = mn + v * vec3<f32>(f32(CX[i]), f32(CY[i]), f32(CZ[i])); }
    let un = mn / rn;
    var rb = 1.0; var ub = un;
    if (ty == 3u) { rb = rn; ub = P.uVel.xyz; if (P.hasUbc != 0u) { ub = vec3<f32>(ubc[3u * c], ubc[3u * c + 1u], ubc[3u * c + 2u]); } }
    for (var i = 0u; i < 19u; i = i + 1u) { fout[i * N + c] = feq(i, rb, ub) + (fin[i * N + n] - feq(i, rn, un)); }
    mac[4u * c] = rb; mac[4u * c + 1u] = ub.x; mac[4u * c + 2u] = ub.y; mac[4u * c + 3u] = ub.z;
    return;
  }
  var f: array<f32, 19>;
  var r = 0.0; var m = vec3<f32>(0.0);
  for (var i = 0u; i < 19u; i = i + 1u) {
    let s = source(x, y, z, CX[i], CY[i], CZ[i]);
    var v = 0.0;
    if (s < 0) { v = fin[OPP[i] * N + c]; }
    else {
      let t = flags[u32(s)] & 7u;
      if (t == 1u) { v = fin[OPP[i] * N + c]; }
      else if (t == 2u) { v = fin[OPP[i] * N + c] + 6.0 * W[i] * (f32(CX[i]) * P.uWall.x + f32(CY[i]) * P.uWall.y + f32(CZ[i]) * P.uWall.z); }
      else { v = fin[i * N + u32(s)]; }
    }
    f[i] = v; r = r + v; m = m + v * vec3<f32>(f32(CX[i]), f32(CY[i]), f32(CZ[i]));
  }
  var F = P.bodyF.xyz;
  if (P.hasForce != 0u) { F = F + vec3<f32>(frc[3u * c], frc[3u * c + 1u], frc[3u * c + 2u]); }
  let ir = 1.0 / r;
  let u = (m + 0.5 * F) * ir;
  var tau = P.tau0;
  if (P.cs > 0.0) {
    var pxx = 0.0; var pyy = 0.0; var pzz = 0.0; var pxy = 0.0; var pxz = 0.0; var pyz = 0.0;
    for (var i = 0u; i < 19u; i = i + 1u) {
      let fq0 = f[i] - feq(i, r, u); let cx = f32(CX[i]); let cy = f32(CY[i]); let cz = f32(CZ[i]);
      pxx = pxx + cx * cx * fq0; pyy = pyy + cy * cy * fq0; pzz = pzz + cz * cz * fq0; pxy = pxy + cx * cy * fq0; pxz = pxz + cx * cz * fq0; pyz = pyz + cy * cz * fq0;
    }
    let pn = sqrt(pxx * pxx + pyy * pyy + pzz * pzz + 2.0 * (pxy * pxy + pxz * pxz + pyz * pyz));
    tau = 0.5 * (P.tau0 + sqrt(P.tau0 * P.tau0 + 25.455844 * P.cs * P.cs * pn * ir));
  }
  let om = 1.0 / tau; let fq = 1.0 - 0.5 * om;
  let hasF = F.x != 0.0 || F.y != 0.0 || F.z != 0.0;
  for (var i = 0u; i < 19u; i = i + 1u) {
    var v = f[i] - om * (f[i] - feq(i, r, u));
    if (hasF) {
      let ci = vec3<f32>(f32(CX[i]), f32(CY[i]), f32(CZ[i]));
      v = v + fq * W[i] * (3.0 * dot(ci - u, F) + 9.0 * dot(ci, u) * dot(ci, F));
    }
    fout[i * N + c] = v;
  }
  mac[4u * c] = r; mac[4u * c + 1u] = u.x; mac[4u * c + 2u] = u.y; mac[4u * c + 3u] = u.z;
}
`;
}

export const LBM_KERNELS = { init: 'init', step: 'step' };
const LBM_MAX_CELLS = LBM_ROW * LBM_WG * 64; // far beyond any buffer limit; the real limit is the device's

/**
 * The WebGPU backend, or null (`lbmGpuBackend.lastError` says why). `create(spec)` uploads a simulation described like lbm.js's createLbm
 * (dims, flags, nu, cs, periodic, uVel, uWall, ubc, bodyForce, force, u0; no thermal) and returns { step(n), macro(), setForce(a), destroy(), cells }.
 */
export async function lbmGpuBackend() {
  lbmGpuBackend.lastError = null;
  try {
    if (typeof navigator === 'undefined' || !navigator.gpu) { lbmGpuBackend.lastError = 'WebGPU is not available here (no navigator.gpu)'; return null; }
    const adapter = await navigator.gpu.requestAdapter(); if (!adapter) { lbmGpuBackend.lastError = 'no WebGPU adapter'; return null; }
    const lim = adapter.limits, want = {};
    for (const k of ['maxStorageBufferBindingSize', 'maxBufferSize']) if (lim && lim[k]) want[k] = lim[k];
    const device = await adapter.requestDevice({ requiredLimits: want });
    let info = {}; try { info = adapter.info || (adapter.requestAdapterInfo ? await adapter.requestAdapterInfo() : {}) || {}; } catch (_) {}
    const module = device.createShaderModule({ code: lbmWgsl() });
    const cinfo = module.getCompilationInfo ? await module.getCompilationInfo() : { messages: [] };
    const errs = cinfo.messages.filter((m) => m.type === 'error'); if (errs.length) throw new Error('WGSL: ' + errs.map((m) => `${m.lineNum}:${m.message}`).join('; '));
    const pipelines = {}; for (const name of Object.keys(LBM_KERNELS)) pipelines[name] = device.createComputePipeline({ layout: 'auto', compute: { module, entryPoint: name } });
    let lost = false; device.lost.then(() => { lost = true; });
    const U = { UNIFORM: 0x40, STORAGE: 0x80, COPY_SRC: 0x04, COPY_DST: 0x08, MAP_READ: 0x01 };
    const maxBind = device.limits.maxStorageBufferBindingSize;
    return {
      kind: 'gpu', label: `WebGPU${info.description || info.vendor ? ' (' + (info.description || info.vendor) + ')' : ''}`, device, maxCells: Math.min(LBM_MAX_CELLS, Math.floor(maxBind / (19 * 4))),
      get lost() { return lost; },
      async create(spec) {
        const [nx, ny, nz] = spec.dims, n = nx * ny * nz;
        if (n * 19 * 4 > maxBind) throw new Error(`${n} cells need ${(n * 76 / 1048576).toFixed(0)} MB of populations, more than one storage buffer of this device holds (${(maxBind / 1048576).toFixed(0)} MB)`);
        const buf = (bytes, usage) => device.createBuffer({ size: Math.max(16, bytes), usage });
        const wgsTotal = Math.ceil(n / LBM_WG), rowWgs = Math.min(wgsTotal, LBM_ROW), rows = Math.ceil(wgsTotal / rowWgs);
        const B = { params: buf(112, U.UNIFORM | U.COPY_DST), fa: buf(76 * n, U.STORAGE), fb: buf(76 * n, U.STORAGE), flags: buf(4 * n, U.STORAGE | U.COPY_DST), ubc: buf(spec.ubc ? 12 * n : 16, U.STORAGE | U.COPY_DST),
          force: buf(spec.force || spec.hasForce ? 12 * n : 16, U.STORAGE | U.COPY_DST), mac: buf(16 * n, U.STORAGE | U.COPY_SRC), read: buf(16 * n, U.COPY_DST | U.MAP_READ) };
        const flags32 = new Uint32Array(n); for (let i = 0; i < n; i++) flags32[i] = spec.flags[i];
        device.queue.writeBuffer(B.flags, 0, flags32);
        if (spec.ubc) device.queue.writeBuffer(B.ubc, 0, spec.ubc);
        if (spec.force) device.queue.writeBuffer(B.force, 0, spec.force);
        const per = (spec.periodic || [false, false, false]).reduce((a, p, i) => a | (p ? 1 << i : 0), 0), pb = new ArrayBuffer(112), pu = new Uint32Array(pb), pf = new Float32Array(pb);
        pu.set([nx, ny, nz, n, per, spec.ubc ? 1 : 0, spec.force || spec.hasForce ? 1 : 0, rowWgs]); pf[8] = 3 * spec.nu + 0.5; pf[9] = spec.cs || 0;
        const v4 = (o, a) => { pf[o] = a[0]; pf[o + 1] = a[1]; pf[o + 2] = a[2]; };
        v4(12, spec.uWall || [0, 0, 0]); v4(16, spec.uVel || [0, 0, 0]); v4(20, spec.bodyForce || [0, 0, 0]); v4(24, spec.u0 || [0, 0, 0]);
        device.queue.writeBuffer(B.params, 0, pb);
        const group = (pipe, inB, outB, only) => device.createBindGroup({ layout: pipe.getBindGroupLayout(0), entries: [[0, B.params], [1, inB], [2, outB], [3, B.flags], [4, B.ubc], [5, B.force], [6, B.mac]].filter(([binding]) => !only || only.includes(binding)).map(([binding, buffer]) => ({ binding, resource: { buffer } })) });
        device.pushErrorScope('validation');
        const gAB = group(pipelines.step, B.fa, B.fb), gBA = group(pipelines.step, B.fb, B.fa), gInit = group(pipelines.init, B.fa, B.fb, [0, 1, 2, 3, 4, 6]);
        let enc = device.createCommandEncoder(), pass = enc.beginComputePass(); pass.setPipeline(pipelines.init); pass.setBindGroup(0, gInit); pass.dispatchWorkgroups(rowWgs, rows); pass.end(); device.queue.submit([enc.finish()]);
        const verr = await device.popErrorScope(); if (verr) throw new Error('WebGPU validation: ' + String(verr.message).slice(0, 200));
        let flip = 0, steps = 0;
        return {
          kind: 'gpu', cells: n,
          get steps() { return steps; },
          /** Run k steps and wait for the device (so progress and cancel work between chunks). */
          async step(k) {
            if (lost) throw new Error('the GPU device was lost');
            const e = device.createCommandEncoder(), p = e.beginComputePass(); p.setPipeline(pipelines.step);
            for (let s = 0; s < k; s++) { p.setBindGroup(0, flip ? gBA : gAB); p.dispatchWorkgroups(rowWgs, rows); flip ^= 1; }
            p.end(); device.queue.submit([e.finish()]); steps += k; await device.queue.onSubmittedWorkDone();
          },
          setForce(a) { device.queue.writeBuffer(B.force, 0, a); },
          /** Density and velocity of the latest step: { rho, ux, uy, uz } Float32Arrays. */
          async macro() {
            const e = device.createCommandEncoder(); e.copyBufferToBuffer(B.mac, 0, B.read, 0, 16 * n); device.queue.submit([e.finish()]);
            await B.read.mapAsync(1); const a = new Float32Array(B.read.getMappedRange().slice(0, 16 * n)); B.read.unmap();
            const rho = new Float32Array(n), ux = new Float32Array(n), uy = new Float32Array(n), uz = new Float32Array(n);
            for (let c = 0; c < n; c++) { rho[c] = a[4 * c]; ux[c] = a[4 * c + 1]; uy[c] = a[4 * c + 2]; uz[c] = a[4 * c + 3]; }
            return { rho, ux, uy, uz };
          },
          destroy() { for (const k of Object.keys(B)) B[k].destroy(); },
        };
      },
    };
  } catch (e) { lbmGpuBackend.lastError = String((e && e.message) || e); return null; }
}
