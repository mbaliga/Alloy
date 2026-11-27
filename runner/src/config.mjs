// Runner configuration, from the environment only (no config file, nothing fetched). Every limit has a default that suits one engineer's server.
import os from 'node:os';
import path from 'node:path';

const int = (v, d, lo = 1, hi = Infinity) => { const n = v === undefined || v === '' ? d : parseInt(v, 10); if (!Number.isFinite(n) || n < lo || n > hi) throw new Error(`invalid number "${v}"`); return n; };
const isLoopback = (h) => ['127.0.0.1', '::1', 'localhost'].includes(h);

export function loadConfig(env = process.env, overrides = {}) {
  const c = {
    host: env.ALLOY_RUNNER_HOST || '127.0.0.1',
    port: int(env.ALLOY_RUNNER_PORT, 8787, 0, 65535),
    token: env.ALLOY_RUNNER_TOKEN || '',
    allowNoAuth: env.ALLOY_RUNNER_ALLOW_NO_AUTH === '1',
    origins: (env.ALLOY_RUNNER_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean),
    dataDir: env.ALLOY_RUNNER_DATA || path.join(os.tmpdir(), 'alloy-runner'),
    ttlSeconds: int(env.ALLOY_RUNNER_TTL_S, 3600, 1),
    jobTimeoutSeconds: int(env.ALLOY_RUNNER_JOB_TIMEOUT_S, 3600, 1),
    maxBodyBytes: int(env.ALLOY_RUNNER_MAX_BODY_MB, 100, 1, 2048) * 1024 * 1024,
    maxJobs: int(env.ALLOY_RUNNER_MAX_JOBS, 50, 1),
    maxBodies: int(env.ALLOY_RUNNER_MAX_BODIES, 16, 1),
    maxTets: int(env.ALLOY_RUNNER_MAX_TETS, 400000, 1000),
    concurrency: int(env.ALLOY_RUNNER_CONCURRENCY, 1, 1, 16),
    enableFake: env.ALLOY_RUNNER_ENABLE_FAKE === '1',
    gmshBin: env.ALLOY_RUNNER_GMSH || 'gmsh',
    elmerBin: env.ALLOY_RUNNER_ELMER || 'ElmerSolver',
    openfoamBin: env.ALLOY_RUNNER_OPENFOAM_BIN || '',
    qualificationFile: env.ALLOY_RUNNER_QUALIFICATION || '',
    ...overrides,
  };
  if (!c.token && !(c.allowNoAuth && isLoopback(c.host))) throw new Error('ALLOY_RUNNER_TOKEN is not set. The runner needs a bearer token (use a long random string). For local development only, ALLOY_RUNNER_ALLOW_NO_AUTH=1 is accepted when the host is loopback.');
  if (c.token && c.token.length < 16) throw new Error('ALLOY_RUNNER_TOKEN is shorter than 16 characters');
  return c;
}
