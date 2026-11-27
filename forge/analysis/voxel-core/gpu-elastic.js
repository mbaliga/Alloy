// WebGPU backend of the elastic solve (elastic.js): the same Jacobi-preconditioned CG, the operator applied matrix-free in a node-gather
// form so no atomics are needed. One thread per dof: node n, component c; it visits the (up to 8) elements around the node, and for
// each one multiplies the row (3a + c) of that element's 24 x 24 matrix with the 24 displacements of the element's nodes. The whole
// iteration stays on the device (dot products reduced there, alpha and beta in a small buffer); the host reads one number back every
// `batch` iterations. Single precision: expect a relative residual floor near 1e-6. Constrained dofs have inv = 0: their rows give 0
// and the vectors of the solve stay 0 there, so no mask is needed in the gathers.
//
// Every kernel binds at most eight storage buffers (the WebGPU default limit). Bindings: 0 params (uniform), 1 nodeElem, 2 elemNodes,
// 3 elemProp (vec2: E h, matrix index), 4 Ke (nmat x 576), 5 inv, 6 x, 7 r, 8 z, 9 p, 10 q, 11 b, 12 part, 13 sc.
export const EWG = 128;
export const EWG_RED = 256;
export const ELASTIC_KERNELS = {
  matvec_x: [0, 1, 2, 3, 4, 5, 6, 10], init: [0, 5, 7, 8, 9, 10, 11, 12], reduce_init: [0, 12, 13], matvec_p: [0, 1, 2, 3, 4, 5, 9, 10, 12],
  alpha: [0, 12, 13], update_xr: [0, 5, 6, 7, 8, 9, 10, 12, 13], beta: [0, 12, 13], update_p: [0, 8, 9, 13],
};

export function elasticWgsl() {
  const matvec = (v) => `
fn gather_${v}(i: u32) -> f32 {
  let node = i / 3u; let c = i % 3u; var acc = 0.0;
  for (var s = 0u; s < 8u; s = s + 1u) {
    let ea = nodeElem[8u * node + s];
    if (ea < 0) { continue; }
    let e = u32(ea) >> 3u; let a = u32(ea) & 7u; let pr = elemProp[e]; let base = u32(pr.y) * 576u + (3u * a + c) * 24u;
    var t = 0.0;
    for (var b = 0u; b < 8u; b = b + 1u) {
      let nd = 3u * u32(elemNodes[8u * e + b]);
      t = t + Ke[base + 3u * b] * ${v}v[nd] + Ke[base + 3u * b + 1u] * ${v}v[nd + 1u] + Ke[base + 3u * b + 2u] * ${v}v[nd + 2u];
    }
    acc = acc + pr.x * t;
  }
  return acc;
}`;
  return /* wgsl */ `
struct Params { n: u32, wgs: u32, pad0: u32, pad1: u32 };
@group(0) @binding(0) var<uniform> P: Params;
@group(0) @binding(1) var<storage, read> nodeElem: array<i32>;
@group(0) @binding(2) var<storage, read> elemNodes: array<i32>;
@group(0) @binding(3) var<storage, read> elemProp: array<vec2<f32>>;
@group(0) @binding(4) var<storage, read> Ke: array<f32>;
@group(0) @binding(5) var<storage, read> inv: array<f32>;
@group(0) @binding(6) var<storage, read_write> xv: array<f32>;
@group(0) @binding(7) var<storage, read_write> rv: array<f32>;
@group(0) @binding(8) var<storage, read_write> zv: array<f32>;
@group(0) @binding(9) var<storage, read_write> pv: array<f32>;
@group(0) @binding(10) var<storage, read_write> qv: array<f32>;
@group(0) @binding(11) var<storage, read> bv: array<f32>;
@group(0) @binding(12) var<storage, read_write> part: array<f32>;
@group(0) @binding(13) var<storage, read_write> sc: array<f32>;
var<workgroup> sh: array<f32, ${EWG_RED}>;
${matvec('x')}
${matvec('p')}

fn reduce_group(lid: u32, v: f32) -> f32 {
  sh[lid] = v; workgroupBarrier();
  for (var s = ${EWG >> 1}u; s > 0u; s = s >> 1u) { if (lid < s) { sh[lid] = sh[lid] + sh[lid + s]; } workgroupBarrier(); }
  let r = sh[0]; workgroupBarrier();
  return r;
}
fn reduce_red(lid: u32, v: f32) -> f32 {
  sh[lid] = v; workgroupBarrier();
  for (var s = ${EWG_RED >> 1}u; s > 0u; s = s >> 1u) { if (lid < s) { sh[lid] = sh[lid] + sh[lid + s]; } workgroupBarrier(); }
  let r = sh[0]; workgroupBarrier();
  return r;
}

@compute @workgroup_size(${EWG})
fn matvec_x(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i < P.n) { if (inv[i] == 0.0) { qv[i] = 0.0; } else { qv[i] = gather_x(i); } }
}
@compute @workgroup_size(${EWG})
fn init(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var a = 0.0; var c = 0.0;
  if (i < P.n) { let r = bv[i] - qv[i]; let z = r * inv[i]; rv[i] = r; zv[i] = z; pv[i] = z; a = r * z; c = r * r; }
  let s1 = reduce_group(lid.x, a);
  if (lid.x == 0u) { part[wid.x] = s1; }
  let s2 = reduce_group(lid.x, c);
  if (lid.x == 0u) { part[P.wgs + wid.x] = s2; }
}
@compute @workgroup_size(${EWG_RED})
fn reduce_init(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0; var c = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${EWG_RED}u) { a = a + part[k]; c = c + part[P.wgs + k]; }
  let s1 = reduce_red(lid.x, a);
  if (lid.x == 0u) { sc[1] = s1; }
  let s2 = reduce_red(lid.x, c);
  if (lid.x == 0u) { sc[3] = s2; sc[0] = 0.0; sc[2] = 0.0; sc[4] = 0.0; }
}
@compute @workgroup_size(${EWG})
fn matvec_p(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var d = 0.0;
  if (i < P.n) { var a = 0.0; if (inv[i] != 0.0) { a = gather_p(i); } qv[i] = a; d = pv[i] * a; }
  let s = reduce_group(lid.x, d);
  if (lid.x == 0u) { part[wid.x] = s; }
}
@compute @workgroup_size(${EWG_RED})
fn alpha(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${EWG_RED}u) { a = a + part[k]; }
  let pq = reduce_red(lid.x, a);
  if (lid.x == 0u) { sc[4] = pq; if (pq > 0.0 && sc[1] > 0.0) { sc[0] = sc[1] / pq; } else { sc[0] = 0.0; } }
}
@compute @workgroup_size(${EWG})
fn update_xr(@builtin(global_invocation_id) gid: vec3<u32>, @builtin(local_invocation_id) lid: vec3<u32>, @builtin(workgroup_id) wid: vec3<u32>) {
  let i = gid.x; var a = 0.0; var c = 0.0;
  if (i < P.n) {
    let al = sc[0];
    xv[i] = xv[i] + al * pv[i];
    let r = rv[i] - al * qv[i]; rv[i] = r;
    let z = r * inv[i]; zv[i] = z; a = r * z; c = r * r;
  }
  let s1 = reduce_group(lid.x, a);
  if (lid.x == 0u) { part[wid.x] = s1; }
  let s2 = reduce_group(lid.x, c);
  if (lid.x == 0u) { part[P.wgs + wid.x] = s2; }
}
@compute @workgroup_size(${EWG_RED})
fn beta(@builtin(local_invocation_id) lid: vec3<u32>) {
  var a = 0.0; var c = 0.0;
  for (var k = lid.x; k < P.wgs; k = k + ${EWG_RED}u) { a = a + part[k]; c = c + part[P.wgs + k]; }
  let rzn = reduce_red(lid.x, a);
  let rr = reduce_red(lid.x, c);
  if (lid.x == 0u) { sc[2] = select(0.0, rzn / sc[1], sc[1] > 0.0); sc[1] = rzn; sc[3] = rr; }
}
@compute @workgroup_size(${EWG})
fn update_p(@builtin(global_invocation_id) gid: vec3<u32>) {
  let i = gid.x;
  if (i < P.n) { pv[i] = zv[i] + sc[2] * pv[i]; }
}
`;
}

const EU = { COPY_SRC: 4, COPY_DST: 8, UNIFORM: 64, STORAGE: 128, MAP_READ: 1 };
export const GPU_ELASTIC_MAX_DOFS = 65535 * EWG;

/**
 * Open a WebGPU device for the elastic solve. Resolves to a backend with createOperator({ ne, nn, elemNodes, nodeElem, scale, mIdx, Ks, inv })
 * -> { solve(b, x0, opts) }, or null when WebGPU is not available (never throws: a missing GPU is an expected case).
 */
export async function gpuElasticBackend() {
  gpuElasticBackend.lastError = null;
  try {
    if (typeof navigator === 'undefined' || !navigator.gpu) { gpuElasticBackend.lastError = 'WebGPU is not available here (no navigator.gpu)'; return null; }
    const adapter = await navigator.gpu.requestAdapter(); if (!adapter) { gpuElasticBackend.lastError = 'no WebGPU adapter'; return null; }
    const device = await adapter.requestDevice();
    let info = {}; try { info = adapter.info || (adapter.requestAdapterInfo ? await adapter.requestAdapterInfo() : {}) || {}; } catch (_) {}
    const module = device.createShaderModule({ code: elasticWgsl() });
    const cinfo = module.getCompilationInfo ? await module.getCompilationInfo() : { messages: [] };
    const errs = cinfo.messages.filter((m) => m.type === 'error'); if (errs.length) throw new Error('WGSL: ' + errs.map((m) => `${m.lineNum}:${m.message}`).join('; '));
    const pipelines = {};
    for (const name of Object.keys(ELASTIC_KERNELS)) pipelines[name] = device.createComputePipeline({ layout: 'auto', compute: { module, entryPoint: name } });
    let lost = false; device.lost.then(() => { lost = true; });
    const maxBuf = device.limits.maxStorageBufferBindingSize;
    return {
      kind: 'gpu', label: `WebGPU${info.description || info.vendor ? ' (' + (info.description || info.vendor) + ')' : ''}`, device,
      maxDofs: Math.min(GPU_ELASTIC_MAX_DOFS, Math.floor(maxBuf / 32) * 3),
      get lost() { return lost; },
      async createOperator({ ne, nn, elemNodes, nodeElem, scale, mIdx, Ks, inv }) {
        const n = 3 * nn;
        if (n > GPU_ELASTIC_MAX_DOFS) throw new Error(`${n} dofs exceed what one WebGPU dispatch row covers (${GPU_ELASTIC_MAX_DOFS})`);
        if (32 * nn > maxBuf || 32 * ne > maxBuf) throw new Error(`the model needs a storage buffer of ${Math.max(32 * nn, 32 * ne)} bytes; this GPU allows ${maxBuf}`);
        const wgs = Math.max(1, Math.ceil(n / EWG)), buf = (bytes, usage) => device.createBuffer({ size: Math.max(16, bytes), usage });
        const B = {
          params: buf(16, EU.UNIFORM | EU.COPY_DST), nodeElem: buf(32 * nn, EU.STORAGE | EU.COPY_DST), elemNodes: buf(32 * ne, EU.STORAGE | EU.COPY_DST), elemProp: buf(8 * ne, EU.STORAGE | EU.COPY_DST),
          Ke: buf(4 * 576 * Ks.length, EU.STORAGE | EU.COPY_DST), inv: buf(4 * n, EU.STORAGE | EU.COPY_DST),
          x: buf(4 * n, EU.STORAGE | EU.COPY_DST | EU.COPY_SRC), r: buf(4 * n, EU.STORAGE), z: buf(4 * n, EU.STORAGE), p: buf(4 * n, EU.STORAGE), q: buf(4 * n, EU.STORAGE), b: buf(4 * n, EU.STORAGE | EU.COPY_DST),
          part: buf(8 * wgs, EU.STORAGE), sc: buf(32, EU.STORAGE | EU.COPY_SRC | EU.COPY_DST), readX: buf(4 * n, EU.COPY_DST | EU.MAP_READ), readS: buf(32, EU.COPY_DST | EU.MAP_READ),
        };
        const order = [B.params, B.nodeElem, B.elemNodes, B.elemProp, B.Ke, B.inv, B.x, B.r, B.z, B.p, B.q, B.b, B.part, B.sc];
        const groups = {};
        for (const [name, binds] of Object.entries(ELASTIC_KERNELS)) groups[name] = device.createBindGroup({ layout: pipelines[name].getBindGroupLayout(0), entries: binds.map((b) => ({ binding: b, resource: { buffer: order[b] } })) });
        const prop = new Float32Array(2 * ne); for (let e = 0; e < ne; e++) { prop[2 * e] = scale[e]; prop[2 * e + 1] = mIdx[e]; }
        const ke = new Float32Array(576 * Ks.length); Ks.forEach((K, m) => ke.set(K, 576 * m));
        device.queue.writeBuffer(B.params, 0, new Uint32Array([n, wgs, 0, 0]));
        device.queue.writeBuffer(B.nodeElem, 0, Int32Array.from(nodeElem)); device.queue.writeBuffer(B.elemNodes, 0, Int32Array.from(elemNodes));
        device.queue.writeBuffer(B.elemProp, 0, prop); device.queue.writeBuffer(B.Ke, 0, ke); device.queue.writeBuffer(B.inv, 0, Float32Array.from(inv));
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
          async solve(b, x0, { tol = 1e-5, maxIter = 20000, batch = 10, signal, onStep } = {}) {
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
              if (rel < best * 0.999) { best = rel; stall = 0; } else if (++stall >= 6) break; // single precision has a residual floor
            }
            enc = device.createCommandEncoder(); enc.copyBufferToBuffer(B.x, 0, B.readX, 0, 4 * n); device.queue.submit([enc.finish()]);
            await B.readX.mapAsync(1); const x = Float64Array.from(new Float32Array(B.readX.getMappedRange().slice(0, 4 * n))); B.readX.unmap();
            return { x, iters: it, rel, converged: rel <= Math.max(tol, 1e-4) };
          },
          destroy() { for (const k of Object.keys(B)) B[k].destroy(); },
        };
      },
    };
  } catch (e) { gpuElasticBackend.lastError = String((e && e.message) || e); return null; }
}
