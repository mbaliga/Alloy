// The HTTP service (protocol 1). No dependencies; no outbound network of any kind (the runner never calls anything).
//   GET    /v1/health                    open: { ok, name, protocol }; with the token also the adapters and limits
//   POST   /v1/jobs                      submit (JSON, see protocol.mjs)               -> 202 { id, state }
//   GET    /v1/jobs                      list                                          -> { jobs: [...] }
//   GET    /v1/jobs/:id?since=N          status, progress, events after N               -> { state, progress, stage, events, next, files? }
//   POST   /v1/jobs/:id/cancel           cancel (queued or running)
//   GET    /v1/jobs/:id/result           summary JSON (same as files/result.json)
//   GET    /v1/jobs/:id/files/:name      a result file (result.vtu, result.json)
//   DELETE /v1/jobs/:id                  delete now
// Auth: Authorization: Bearer <token>. Jobs and files are deleted after download unless the job was submitted with options.keep.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { JobStore } from './jobs.mjs';
import { validateJob, HttpError, PROTOCOL } from './protocol.mjs';
import { describeAdapters, ADAPTERS } from './adapters/index.mjs';

export const VERSION = '0.1.0';
const digest = (s) => crypto.createHash('sha256').update(String(s)).digest();

export function readQualification(file) {
  if (!file) return null;
  try { const q = JSON.parse(fs.readFileSync(file, 'utf8')); return { passed: q.passed === true, cases: Array.isArray(q.cases) ? q.cases.slice(0, 50) : [], note: String(q.note || '').slice(0, 500), source: 'operator file ' + file }; }
  catch (e) { return { passed: false, cases: [], note: `qualification file could not be read: ${e.message}`, source: file }; }
}

export function createRunner({ config, adapters = ADAPTERS }) {
  const store = new JobStore({ config, adapters, qualification: readQualification(config.qualificationFile) });
  const tokenDigest = config.token ? digest(config.token) : null;

  const cors = (req, res) => {
    const o = req.headers.origin;
    if (o && (config.origins.includes(o) || config.origins.includes('*'))) {
      res.setHeader('Access-Control-Allow-Origin', config.origins.includes('*') ? '*' : o); res.setHeader('Vary', 'Origin');
      res.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type'); res.setHeader('Access-Control-Allow-Methods', 'GET, POST, DELETE, OPTIONS');
      res.setHeader('Access-Control-Max-Age', '600');
      if (req.headers['access-control-request-private-network']) res.setHeader('Access-Control-Allow-Private-Network', 'true');
    }
  };
  const send = (res, status, body, type = 'application/json') => { const b = typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body); res.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff', 'Content-Length': Buffer.byteLength(b) }); res.end(b); };
  const authed = (req) => {
    if (!tokenDigest) return true; // loopback development without a token (config refuses this anywhere else)
    const m = /^Bearer\s+(.+)$/i.exec(req.headers.authorization || ''); if (!m) return false;
    return crypto.timingSafeEqual(digest(m[1].trim()), tokenDigest);
  };
  const body = (req) => new Promise((resolve, reject) => {
    const chunks = []; let n = 0;
    req.on('data', (c) => { n += c.length; if (n > config.maxBodyBytes) { reject(new HttpError(413, `the request is larger than ${Math.round(config.maxBodyBytes / 1048576)} MB`, 'E_TOO_LARGE')); req.destroy(); } else chunks.push(c); });
    req.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || 'null')); } catch (_) { reject(new HttpError(400, 'the body is not valid JSON')); } });
    req.on('error', reject);
  });

  const server = http.createServer(async (req, res) => {
    cors(req, res);
    try {
      const url = new URL(req.url, 'http://runner'), p = url.pathname.replace(/\/+$/, '') || '/', method = req.method;
      if (method === 'OPTIONS') { res.writeHead(204); return res.end(); }
      if (method === 'GET' && p === '/v1/health') {
        const base = { ok: true, name: 'alloy-runner', version: VERSION, protocol: PROTOCOL };
        if (!authed(req)) return send(res, 200, base);
        return send(res, 200, { ...base, authenticated: true, adapters: await describeAdapters(config, adapters), qualification: store.qualification, limits: { max_body_mb: Math.round(config.maxBodyBytes / 1048576), max_bodies: config.maxBodies, max_tets: config.maxTets, max_jobs: config.maxJobs, ttl_s: config.ttlSeconds, job_timeout_s: config.jobTimeoutSeconds, concurrency: config.concurrency } });
      }
      if (!authed(req)) { res.setHeader('WWW-Authenticate', 'Bearer'); throw new HttpError(401, 'missing or wrong bearer token', 'E_AUTH'); }
      if (method === 'POST' && p === '/v1/jobs') {
        const job = validateJob(await body(req), config), rec = store.create(job);
        return send(res, 202, { id: rec.id, state: rec.state });
      }
      if (method === 'GET' && p === '/v1/jobs') return send(res, 200, { jobs: store.list().map((r) => store.brief(r)) });
      const m = /^\/v1\/jobs\/([0-9a-f]{18})(?:\/(cancel|result|files\/([A-Za-z0-9_.-]{1,60})))?$/.exec(p);
      if (m) {
        const rec = store.get(m[1]); if (!rec) throw new HttpError(404, 'no such job (it may have been deleted after download)', 'E_NOT_FOUND');
        if (!m[2] && method === 'GET') return send(res, 200, store.view(rec, parseInt(url.searchParams.get('since') || '0', 10) || 0));
        if (!m[2] && method === 'DELETE') { store.remove(rec.id); return send(res, 200, { id: rec.id, deleted: true }); }
        if (m[2] === 'cancel' && method === 'POST') { store.cancel(rec.id); return send(res, 200, { id: rec.id, state: rec.state }); }
        if ((m[2] === 'result' || m[3]) && method === 'GET') {
          if (rec.state !== 'done') throw new HttpError(409, `the job is ${rec.state}`, 'E_NOT_READY');
          const f = store.file(rec.id, m[3] || 'result.json'); if (!f) throw new HttpError(404, 'no such file', 'E_NOT_FOUND');
          return send(res, 200, f.bytes, f.mime);
        }
      }
      throw new HttpError(404, 'not found', 'E_NOT_FOUND');
    } catch (e) {
      const status = e.status || 500; if (res.headersSent) return res.end();
      send(res, status, { error: { code: e.code || 'E_INTERNAL', message: status === 500 ? 'internal error' : e.message } });
    }
  });
  server.requestTimeout = 5 * 60 * 1000;
  return { server, store, listen: () => new Promise((r) => server.listen(config.port, config.host, () => r(server.address()))), close: () => new Promise((r) => { store.close(); server.close(() => r()); server.closeAllConnections?.(); }) };
}
