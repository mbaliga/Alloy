#!/usr/bin/env node
import { loadConfig } from '../src/config.mjs';
import { createRunner, VERSION } from '../src/server.mjs';

let config;
try { config = loadConfig(); } catch (e) { console.error('alloy-runner: ' + e.message); process.exit(2); }
const r = createRunner({ config });
const addr = await r.listen();
console.log(`alloy-runner ${VERSION} listening on http://${addr.address}:${addr.port} (auth: ${config.token ? 'bearer token' : 'NONE, loopback development only'}; CORS origins: ${config.origins.join(', ') || 'none'})`);
for (const s of ['SIGINT', 'SIGTERM']) process.on(s, async () => { await r.close(); process.exit(0); });
