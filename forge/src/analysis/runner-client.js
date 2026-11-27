// The Forge side of the Alloy Runner that is NOT a package: the job list in the Analyze pane (list, cancel, delete) and the connection
// test in Settings. It talks to the runner URL the user typed and to nothing else, with the token the user pasted; it never sends
// geometry (the only call that does is the package's job POST, which the host gates on the user's confirmation, src/ext/api.js).
// `getRunner()` returns { url, token } or null; `fetchFn` defaults to the page's fetch.

export function createRunnerClient({ getRunner, fetchFn }) {
  const f = (...a) => (fetchFn || globalThis.fetch)(...a);
  async function call(method, path) {
    const r = getRunner && getRunner();
    if (!r || !r.url) { const e = new Error('No Alloy Runner is set (Settings > Extensions > Alloy Runner).'); e.code = 'E_UNAVAILABLE'; throw e; }
    let res;
    try { res = await f(r.url.replace(/\/+$/, '') + path, { method, headers: r.token ? { Authorization: 'Bearer ' + r.token } : {}, redirect: 'error', credentials: 'omit', referrerPolicy: 'no-referrer' }); }
    catch (e) { const x = new Error(`Cannot reach the runner (${String(e.message || e).slice(0, 120)}): check the address, that it is running and that its CORS origins include this page.`); x.code = 'E_NETWORK'; throw x; }
    const text = await res.text(); let json = null; try { json = JSON.parse(text); } catch (_) { /* not JSON */ }
    if (!res.ok) { const x = new Error(json && json.error ? `${json.error.message} (${json.error.code})` : `HTTP ${res.status}`); x.code = (json && json.error && json.error.code) || 'E_HTTP'; x.status = res.status; throw x; }
    return json;
  }
  return {
    /** Connection test: does the address answer like a runner, does it accept the token, what solvers does it have. */
    async test() {
      const h = await call('GET', '/v1/health');
      if (!h || h.name !== 'alloy-runner') throw Object.assign(new Error('That address does not answer like an Alloy Runner.'), { code: 'E_NOT_RUNNER' });
      let authError = null;
      if (!h.authenticated) { try { await call('GET', '/v1/jobs'); } catch (e) { authError = e.message; } }
      return { name: h.name, version: h.version, protocol: h.protocol, authenticated: !!h.authenticated, authError, adapters: h.adapters || null, qualification: h.qualification || null, limits: h.limits || null };
    },
    async list() { return (await call('GET', '/v1/jobs')).jobs; },
    async cancel(id) { return call('POST', `/v1/jobs/${encodeURIComponent(id)}/cancel`); },
    async remove(id) { return call('DELETE', `/v1/jobs/${encodeURIComponent(id)}`); },
  };
}

/** One line for a solver adapter in the connection test. */
export const adapterLine = (id, a) => `${id}: ${a.ok ? 'ready' : 'not available'}${a.real === false ? ' (simulated, protocol tests only)' : ''}${a.ok ? '' : ` (${a.reason || 'unknown reason'})`}`;
