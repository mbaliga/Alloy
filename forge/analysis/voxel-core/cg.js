// Matrix-free preconditioned conjugate gradient (Jacobi) on a sparse active-cell list, with one interface for two backends:
// a JS typed-array CPU backend (always available, any thread) and a WebGPU backend (gpu.js). The operator is a 6-neighbour
// stencil: (A x)_i = diag_i x_i - sum_f g_if x_nbr(i,f), with nbr = -1 where a face has no neighbour (its conductance lives in diag).
// A is symmetric positive definite when g >= 0 and diag_i >= sum_f g_if (with strict inequality somewhere).
//
// The CPU solver is a generator: `pcgSteps` yields {iter, rel} every `chunk` iterations, so the SAME code runs synchronously
// (runSync, used by tests and the validation harness) or asynchronously with cooperative yielding (runAsync, used in a package
// Worker so progress and cancel messages are handled between chunks).

/** y = A x for the stencil. */
export function stencilApply(n, nbr, g, diag, x, y) {
  for (let i = 0; i < n; i++) {
    const o = 6 * i; let s = diag[i] * x[i], j;
    j = nbr[o]; if (j >= 0) s -= g[o] * x[j];
    j = nbr[o + 1]; if (j >= 0) s -= g[o + 1] * x[j];
    j = nbr[o + 2]; if (j >= 0) s -= g[o + 2] * x[j];
    j = nbr[o + 3]; if (j >= 0) s -= g[o + 3] * x[j];
    j = nbr[o + 4]; if (j >= 0) s -= g[o + 4] * x[j];
    j = nbr[o + 5]; if (j >= 0) s -= g[o + 5] * x[j];
    y[i] = s;
  }
}

/** Jacobi-preconditioned CG, as a generator. `x` is the initial guess and is updated in place. Returns { iters, rel, converged }. */
export function* pcgSteps(op, b, x, { tol = 1e-8, maxIter = 5000, chunk = 25 } = {}) {
  const { n, nbr, g, diag } = op;
  const r = new Float64Array(n), z = new Float64Array(n), p = new Float64Array(n), q = new Float64Array(n);
  stencilApply(n, nbr, g, diag, x, q);
  let bn = 0; for (let i = 0; i < n; i++) { r[i] = b[i] - q[i]; bn += b[i] * b[i]; }
  bn = Math.sqrt(bn) || 1;
  let rz = 0, rr = 0;
  for (let i = 0; i < n; i++) { z[i] = r[i] / diag[i]; p[i] = z[i]; rz += r[i] * z[i]; rr += r[i] * r[i]; }
  let rel = Math.sqrt(rr) / bn, it = 0;
  while (rel > tol && it < maxIter) {
    stencilApply(n, nbr, g, diag, p, q);
    let pq = 0; for (let i = 0; i < n; i++) pq += p[i] * q[i];
    if (!(pq > 0)) break;
    const alpha = rz / pq;
    let rzn = 0; rr = 0;
    for (let i = 0; i < n; i++) { x[i] += alpha * p[i]; r[i] -= alpha * q[i]; z[i] = r[i] / diag[i]; rzn += r[i] * z[i]; rr += r[i] * r[i]; }
    const beta = rz > 0 ? rzn / rz : 0; rz = rzn;
    for (let i = 0; i < n; i++) p[i] = z[i] + beta * p[i];
    it++; rel = Math.sqrt(rr) / bn;
    if (it % chunk === 0) yield { iter: it, rel };
  }
  return { iters: it, rel, converged: rel <= tol };
}

/** Drive a generator to completion synchronously. */
export function runSync(gen) { let s = gen.next(); while (!s.done) s = gen.next(); return s.value; }

/** Drive a generator, yielding to the event loop between steps so a Worker can receive cancel messages. */
export async function runAsync(gen, { onStep, signal } = {}) {
  let s = gen.next();
  while (!s.done) {
    if (signal && signal.aborted) throw new Error('cancelled');
    if (onStep) onStep(s.value);
    await new Promise((r) => setTimeout(r, 0));
    s = gen.next();
  }
  return s.value;
}

/** CPU backend: the operator keeps the structure; `setDiag` changes the diagonal between solves. */
export const cpuBackend = {
  kind: 'cpu', label: 'CPU (JS typed arrays)',
  async createOperator({ n, nbr, g, diag }) {
    const op = { n, nbr, g: Float64Array.from(g), diag: Float64Array.from(diag) };
    return {
      kind: 'cpu', n,
      setDiag(d) { op.diag.set(d); },
      setConductance(gg) { op.g.set(gg); },
      solveSync(b, x0, o) { const x = Float64Array.from(x0); const r = runSync(pcgSteps(op, b, x, o)); return { x, ...r }; },
      async solve(b, x0, o = {}) { const x = Float64Array.from(x0); const r = await runAsync(pcgSteps(op, b, x, o), { signal: o.signal, onStep: o.onStep }); return { x, ...r }; },
      destroy() {},
    };
  },
};
