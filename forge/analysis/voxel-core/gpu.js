// WebGPU backend of the matrix-free Jacobi-PCG: same interface as cpuBackend in cg.js. Compute shaders in WGSL. The whole CG
// iteration stays on the GPU (dot products are reduced on the device and alpha and beta live in a small storage buffer), so the
// host reads one number back every `batch` iterations. Single precision: the thermal package solves for temperature RISES above a
// reference so the arithmetic keeps its digits; expect a relative residual floor near 1e-6.
//
// Per iteration: matvec_p -> alpha -> update_xr -> beta -> update_p (five dispatches in one compute pass). Every kernel binds at
// most eight storage buffers (the WebGPU default limit), so the buffers are split per kernel through `layout: 'auto'`.
export const WG = 128;
export const WG_RED = 256;
export const GPU_MAX_CELLS = 65535 * WG;

/** Bindings each entry point uses (must match wgslSource exactly: with an auto layout an unused binding is not part of the layout). */
export const KERNELS = {
  matvec_x: [0, 1, 2, 3, 4, 8], init: [0, 3, 5, 6, 7, 8, 9, 10], reduce_init: [0, 10, 11], matvec_p: [0, 1, 2, 3, 7, 8, 10],
  alpha: [0, 10, 11], update_xr: [0, 3, 4, 5, 6, 7, 8, 10, 11], beta: [0, 10, 11], update_p: [0, 6, 7, 11],
};
// bindings: 0 params (uniform), 1 nbr, 2 g, 3 diag, 4 x, 5 r, 6 z, 7 p, 8 q, 9 b, 10 part, 11 scalars

/** WGSL source of every kernel. sc = [alpha, rz, beta, rr, pAp]. */
export function wgslSource() {
  const stencil = (v) => `
fn stencil_${v}(i: u32) -> f32 {
  var acc = diag[i] * ${v}v[i];
  for (var f = 0u; f < 6u; f = f + 1u) {
    let j = nbr[6u * i + f];
    if (j >= 0) { acc = acc - g[6u * i + f] * ${v}v[u32(j)]; }
  }
  return acc;
}`;
  return /* wgsl */ `
struct Params { n: u32, wgs: u32, pad0: u32, pad1: u32 };
@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read> nbr: array<i32>;
@group(0) @binding(2) var<storage, read> g: array<f32>;
@group(0) @binding(3) var<storage, read> diag: array<f32>;
@group(0) @binding(4) var<storage, read_write> xv: array<f32>;
@group(0) @binding(5) var<storage, read_write> rv: array<f32>;
@group(0) @binding(6) var<storage, read_write> zv: array<f32>;
@group(0) @binding(7) var<storage, read_write> pv: array<f32>;
@group(0) @binding(8) var<storage, read_write> qv: array<f32>;
@group(0) @binding(9) var<storage, read> bv: array<f32>;
@group(0) @binding(10) var<storage, read_write> part: array<f32>;
@group(0) @binding(11) var<storage, read_write> sc: array<f32>;
var<workgroup> sh: array<f32, ${WG_RED}>;
${stencil('x')}
${stencil('p')}

fn reduce_group(lid: u32, v: f32) -> f32 {
  sh[lid] = v; workgroupBarrier();
  for (var s = ${WG >> 1}u; s > 0u; s = s >> 1u) { if (lid < s) { sh[lid] = sh[lid] + sh[lid + s]; } workgroupBarrier(); }
  let r = sh[0]; workgroupBarrier();
  return r;
}
fn reduce_red(lid: u32, v: f32) -> f32 {
  sh[lid] = v; workgroupBarrier();
  for (var s = ${WG_RED >> 1}u; s > 0u; s = s >> 1u) { if (lid < s) { sh[lid] = sh[lid] + sh[lid + s]; } workgroupBarrier(); }
  let r = sh[0]; workgroupBarrier();
  return r;
}

@compute @workgroup_size(${WG})
fn matvec_x(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i < P.n) { qv[i] = stencil_x(i); }
}
@compute @workgroup_size(${WG})
fn init(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var a = 0.0; var c = 0.0;
  if (i < P.n) { let r = bv[i] - qv[i]; let z = r / diag[i]; rv[i] = r; zv[i] = z; pv[i] = z; a = r * z; c = r * r; }
  let s1 = reduce_group(lid.x, a);
  if (lid.x == 0u) { part[wid.x] = s1; }
  let s2 = reduce_group(lid.x, c);
  if (lid.x == 0u) { part[P.wgs + wid.x] = s2; }
}
@compute @workgroup_size(${WG_RED})
fn reduce_init(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0; var c = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${WG_RED}u) { a = a + part[k]; c = c + part[P.wgs + k]; }
  let s1 = reduce_red(lid.x, a);
  if (lid.x == 0u) { sc[1] = s1; }
  let s2 = reduce_red(lid.x, c);
  if (lid.x == 0u) { sc[3] = s2; sc[0] = 0.0; sc[2] = 0.0; sc[4] = 0.0; }
}
@compute @workgroup_size(${WG})
fn matvec_p(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var d = 0.0;
  if (i < P.n) { let a = stencil_p(i); qv[i] = a; d = pv[i] * a; }
  let s = reduce_group(lid.x, d);
  if (lid.x == 0u) { part[wid.x] = s; }
}
@compute @workgroup_size(${WG_RED})
fn alpha(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${WG_RED}u) { a = a + part[k]; }
  let pq = reduce_red(lid.x, a);
  if (lid.x == 0u) { sc[4] = pq; if (pq > 0.0 && sc[1] > 0.0) { sc[0] = sc[1] / pq; } else { sc[0] = 0.0; } }
}
@compute @workgroup_size(${WG})
fn update_xr(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var a = 0.0; var c = 0.0;
  if (i < P.n) {
    let al = sc[0];
    xv[i] = xv[i] + al * pv[i];
    let r = rv[i] - al * qv[i]; rv[i] = r;
    let z = r / diag[i]; zv[i] = z; a = r * z; c = r * r;
  }
  let s1 = reduce_group(lid.x, a);
  if (lid.x == 0u) { part[wid.x] = s1; }
  let s2 = reduce_group(lid.x, c);
  if (lid.x == 0u) { part[P.wgs + wid.x] = s2; }
}
@compute @workgroup_size(${WG_RED})
fn beta(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0; var c = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${WG_RED}u) { a = a + part[k]; c = c + part[P.wgs + k]; }
  let rzn = reduce_red(lid.x, a);
  let rr = reduce_red(lid.x, c);
  if (lid.x == 0u) { sc[2] = select(0.0, rzn / sc[1], sc[1] > 0.0); sc[1] = rzn; sc[3] = rr; }
}
@compute @workgroup_size(${WG})
fn update_p(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i < P.n) { pv[i] = zv[i] + sc[2] * pv[i]; }
}
`;
}

const U = { COPY_SRC: 4, COPY_DST: 8, UNIFORM: 64, STORAGE: 128, MAP_READ: 1 }; // GPUBufferUsage values, so the code needs no global

/**
 * Open a WebGPU device (in a package Worker with compute.gpu, or a page). Resolves to a backend object, or null when WebGPU is not
 * available (no navigator.gpu, no adapter, device refused). Never throws: a missing GPU is an expected case.
 */
export async function gpuBackend() {
  gpuBackend.lastError = null;
  try {
    if (typeof navigator === 'undefined' || !navigator.gpu) { gpuBackend.lastError = 'WebGPU is not available here (no navigator.gpu)'; return null; }
    const adapter = await navigator.gpu.requestAdapter(); if (!adapter) { gpuBackend.lastError = 'no WebGPU adapter'; return null; }
    const device = await adapter.requestDevice();
    let info = {}; try { info = adapter.info || (adapter.requestAdapterInfo ? await adapter.requestAdapterInfo() : {}) || {}; } catch (_) {}
    const module = device.createShaderModule({ code: wgslSource() });
    const cinfo = module.getCompilationInfo ? await module.getCompilationInfo() : { messages: [] };
    const errs = cinfo.messages.filter((m) => m.type === 'error'); if (errs.length) throw new Error('WGSL: ' + errs.map((m) => `${m.lineNum}:${m.message}`).join('; '));
    const pipelines = {};
    for (const name of Object.keys(KERNELS)) pipelines[name] = device.createComputePipeline({ layout: 'auto', compute: { module, entryPoint: name } });
    let lost = false; device.lost.then(() => { lost = true; });
    return {
      kind: 'gpu', label: `WebGPU${info.description || info.vendor ? ' (' + (info.description || info.vendor) + ')' : ''}`, device, maxCells: Math.min(GPU_MAX_CELLS, Math.floor(device.limits.maxStorageBufferBindingSize / 24)),
      get lost() { return lost; },
      async createOperator({ n, nbr, g, diag }) {
        if (n > GPU_MAX_CELLS) throw new Error(`${n} cells exceed what one WebGPU dispatch row covers (${GPU_MAX_CELLS})`);
        const wgs = Math.max(1, Math.ceil(n / WG));
        const buf = (bytes, usage) => device.createBuffer({ size: Math.max(16, bytes), usage });
        const B = {
          params: buf(16, U.UNIFORM | U.COPY_DST), nbr: buf(24 * n, U.STORAGE | U.COPY_DST), g: buf(24 * n, U.STORAGE | U.COPY_DST), diag: buf(4 * n, U.STORAGE | U.COPY_DST),
          x: buf(4 * n, U.STORAGE | U.COPY_DST | U.COPY_SRC), r: buf(4 * n, U.STORAGE), z: buf(4 * n, U.STORAGE), p: buf(4 * n, U.STORAGE), q: buf(4 * n, U.STORAGE), b: buf(4 * n, U.STORAGE | U.COPY_DST),
          part: buf(8 * wgs, U.STORAGE), sc: buf(32, U.STORAGE | U.COPY_SRC | U.COPY_DST),
          readX: buf(4 * n, U.COPY_DST | U.MAP_READ), readS: buf(32, U.COPY_DST | U.MAP_READ),
        };
        const order = [B.params, B.nbr, B.g, B.diag, B.x, B.r, B.z, B.p, B.q, B.b, B.part, B.sc];
        const groups = {};
        for (const [name, binds] of Object.entries(KERNELS)) groups[name] = device.createBindGroup({ layout: pipelines[name].getBindGroupLayout(0), entries: binds.map((b) => ({ binding: b, resource: { buffer: order[b] } })) });
        device.queue.writeBuffer(B.params, 0, new Uint32Array([n, wgs, 0, 0]));
        device.queue.writeBuffer(B.nbr, 0, Int32Array.from(nbr)); device.queue.writeBuffer(B.g, 0, Float32Array.from(g)); device.queue.writeBuffer(B.diag, 0, Float32Array.from(diag));
        const encode = (enc, seq) => {
          const pass = enc.beginComputePass();
          for (const name of seq) { pass.setPipeline(pipelines[name]); pass.setBindGroup(0, groups[name]); pass.dispatchWorkgroups(name === 'reduce_init' || name === 'alpha' || name === 'beta' ? 1 : wgs); }
          pass.end();
        };
        const readScalars = async () => {
          const enc = device.createCommandEncoder(); enc.copyBufferToBuffer(B.sc, 0, B.readS, 0, 32); device.queue.submit([enc.finish()]);
          await B.readS.mapAsync(1); const a = new Float32Array(B.readS.getMappedRange().slice(0)); B.readS.unmap(); return a;
        };
        return {
          kind: 'gpu', n,
          setDiag(d) { device.queue.writeBuffer(B.diag, 0, Float32Array.from(d)); },
          setConductance(gg) { device.queue.writeBuffer(B.g, 0, Float32Array.from(gg)); },
          async solve(b, x0, { tol = 1e-5, maxIter = 5000, batch = 20, signal, onStep } = {}) {
            if (lost) throw new Error('the GPU device was lost');
            let bn = 0; for (let i = 0; i < n; i++) bn += b[i] * b[i]; bn = Math.sqrt(bn) || 1;
            device.queue.writeBuffer(B.b, 0, Float32Array.from(b)); device.queue.writeBuffer(B.x, 0, Float32Array.from(x0));
            let enc = device.createCommandEncoder(); encode(enc, ['matvec_x', 'init', 'reduce_init']); device.queue.submit([enc.finish()]);
            let rel = Math.sqrt((await readScalars())[3]) / bn, it = 0, best = rel, stall = 0;
            while (rel > tol && it < maxIter) {
              if (signal && signal.aborted) throw new Error('cancelled');
              const k = Math.min(batch, maxIter - it);
              enc = device.createCommandEncoder();
              const seq = []; for (let s = 0; s < k; s++) seq.push('matvec_p', 'alpha', 'update_xr', 'beta', 'update_p');
              encode(enc, seq); device.queue.submit([enc.finish()]);
              it += k; const rr = (await readScalars())[3]; rel = Math.sqrt(rr) / bn;
              if (!Number.isFinite(rel)) throw new Error('the GPU solve produced NaN');
              if (onStep) onStep({ iter: it, rel });
              if (rel < best * 0.999) { best = rel; stall = 0; } else if (++stall >= 4) break; // single precision has a residual floor
            }
            enc = device.createCommandEncoder(); enc.copyBufferToBuffer(B.x, 0, B.readX, 0, 4 * n); device.queue.submit([enc.finish()]);
            await B.readX.mapAsync(1); const x = Float64Array.from(new Float32Array(B.readX.getMappedRange().slice(0, 4 * n))); B.readX.unmap();
            return { x, iters: it, rel, converged: rel <= Math.max(tol, 1e-4) };
          },
          destroy() { for (const k of Object.keys(B)) B[k].destroy(); },
        };
      },
    };
  } catch (e) { gpuBackend.lastError = String((e && e.message) || e); return null; }
}
