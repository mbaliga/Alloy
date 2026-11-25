// Manifest schema, validator and tier rule for `.alloyext` packages (docs/EXTENSIONS_SPEC.md section 2.1).
// The schema is a plain JSON Schema (draft-07 subset); `validateSchema` below implements exactly the keywords it uses, so the
// same object can be handed to any standard validator. No dependencies.
import { PERMISSIONS, CONTRIBUTES, FORGE_API } from './api-spec.js';

const FIELD = {
  type: 'object', required: ['k', 'kind'], additionalProperties: false,
  properties: {
    k: { type: 'string', pattern: '^[A-Za-z_][A-Za-z0-9_]*$' }, kind: { enum: ['num', 'vec3', 'enum', 'text'] },
    def: {}, tip: { type: 'string', maxLength: 300 }, opts: { type: 'array', items: { type: 'string' }, minItems: 1 }, opt: { type: 'boolean' },
  },
};
const ID = { type: 'string', pattern: '^[a-z0-9][a-z0-9-]*$', maxLength: 48 };
const LABEL = { type: 'string', minLength: 1, maxLength: 80 };
const list = (props, required = ['id', 'label']) => ({ type: 'array', maxItems: 64, items: { type: 'object', required, additionalProperties: true, properties: { id: ID, label: LABEL, ...props } } });

export const MANIFEST_SCHEMA = {
  $schema: 'http://json-schema.org/draft-07/schema#', title: 'Alloy Forge extension manifest', type: 'object',
  required: ['id', 'name', 'version', 'forgeApi', 'licence', 'main', 'permissions', 'integrity'], additionalProperties: false,
  properties: {
    id: { type: 'string', pattern: '^[a-z0-9]+(\\.[a-z0-9-]+)+$', maxLength: 100 },
    name: LABEL, version: { type: 'string', pattern: '^\\d+\\.\\d+\\.\\d+(-[0-9A-Za-z.-]+)?$' },
    forgeApi: { type: 'string', pattern: '^\\^?\\d+\\.\\d+$' }, author: { type: 'string', maxLength: 120 }, licence: { type: 'string', minLength: 1, maxLength: 80 },
    description: { type: 'string', maxLength: 400 },
    main: { type: 'string', pattern: '^[A-Za-z0-9_./-]+\\.js$' },
    permissions: { type: 'array', maxItems: 24, items: { type: 'string', maxLength: 120 } },
    contributes: {
      type: 'object', additionalProperties: false,
      properties: {
        featureTypes: list({ icon: { type: 'string', maxLength: 4 }, desc: { type: 'string', maxLength: 300 }, fields: { type: 'array', items: FIELD, maxItems: 24 } }, ['id', 'label', 'fields']),
        templates: list({}), stylePacks: list({}), checks: list({}), analyses: list({ fields: { type: 'array', items: { type: 'string' } }, description: { type: 'string', maxLength: 400 }, validity: { enum: ['estimate', 'validated', 'experimental'] }, checks: { type: 'array', maxItems: 16, items: { type: 'object', required: ['id', 'label', 'metric', 'op', 'limit'], additionalProperties: false, properties: { id: ID, label: LABEL, metric: { type: 'string', pattern: '^[\\w.-]{1,60}$' }, op: { enum: ['<=', '<', '>=', '>'] }, limit: { type: 'number' }, unit: { type: 'string', maxLength: 12 } } } } }),
        exporters: list({}), importers: list({}), libraries: list({}),
        agentTools: list({ description: { type: 'string', maxLength: 400 }, schema: { type: 'object' } }, ['id', 'label', 'schema']),
        loopSteps: list({}),
        panes: list({ file: { type: 'string', pattern: '^[A-Za-z0-9_./-]+\\.html$' } }, ['id', 'label', 'file']),
        overlays: list({}),
        settings: { type: 'object', maxProperties: 32, additionalProperties: { type: 'object', required: ['type'], properties: { type: { enum: ['number', 'string', 'boolean'] }, default: {} } } },
      },
    },
    integrity: { type: 'string', pattern: '^sha256-[0-9a-f]{64}$' },
  },
};

/** Minimal validator for the keywords used above. Returns a list of `path: message` strings (empty = valid). */
export function validateSchema(schema, value, path = '$') {
  const err = [];
  const t = (v) => (v === null ? 'null' : Array.isArray(v) ? 'array' : typeof v);
  if (schema.type && t(value) !== schema.type) return [`${path}: expected ${schema.type}, got ${t(value)}`];
  if (schema.enum && !schema.enum.includes(value)) err.push(`${path}: must be one of ${schema.enum.join(', ')}`);
  if (typeof value === 'string') {
    if (schema.minLength != null && value.length < schema.minLength) err.push(`${path}: too short`);
    if (schema.maxLength != null && value.length > schema.maxLength) err.push(`${path}: longer than ${schema.maxLength}`);
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) err.push(`${path}: "${value.slice(0, 40)}" does not match ${schema.pattern}`);
  }
  if (Array.isArray(value)) {
    if (schema.minItems != null && value.length < schema.minItems) err.push(`${path}: needs at least ${schema.minItems} item(s)`);
    if (schema.maxItems != null && value.length > schema.maxItems) err.push(`${path}: more than ${schema.maxItems} items`);
    if (schema.items) value.forEach((v, i) => err.push(...validateSchema(schema.items, v, `${path}[${i}]`)));
  }
  if (value && typeof value === 'object' && !Array.isArray(value)) {
    for (const k of schema.required || []) if (!(k in value)) err.push(`${path}: missing "${k}"`);
    if (schema.maxProperties != null && Object.keys(value).length > schema.maxProperties) err.push(`${path}: too many properties`);
    for (const [k, v] of Object.entries(value)) {
      if (schema.properties && schema.properties[k]) err.push(...validateSchema(schema.properties[k], v, `${path}.${k}`));
      else if (schema.additionalProperties === false) err.push(`${path}: unknown property "${k}"`);
      else if (schema.additionalProperties && typeof schema.additionalProperties === 'object') err.push(...validateSchema(schema.additionalProperties, v, `${path}.${k}`));
    }
  }
  return err;
}

const NET = /^network:([a-z0-9]([a-z0-9.-]*[a-z0-9])?)$/;

export const isNetworkPerm = (p) => NET.test(p);
export const networkHost = (p) => (NET.exec(p) || [])[1] || null;

/** Does `forgeApi` ("^1.0", "1.0") accept the host's API version? Same major, host minor >= requested minor. */
export function apiCompatible(range, host = FORGE_API) {
  const m = /^(\^?)(\d+)\.(\d+)$/.exec(range || ''); const [hM, hm] = host.split('.').map(Number);
  if (!m) return false;
  return +m[2] === hM && hm >= +m[3];
}

/** Tier is decided by what a package asks for, never by the author. */
export function tierOf(manifest) {
  for (const p of manifest.permissions || []) {
    if (isNetworkPerm(p)) return 'extension';
    if (PERMISSIONS[p] && PERMISSIONS[p].tier === 'extension') return 'extension';
  }
  return 'plugin';
}

/** Full validation: schema, permissions, contribution/permission pairing, duplicate ids, file references. Throws one Error listing every problem. */
export function validateManifest(m, { hasFile = () => true } = {}) {
  const problems = validateSchema(MANIFEST_SCHEMA, m);
  if (!problems.length) {
    const perms = new Set();
    for (const p of m.permissions) {
      if (perms.has(p)) problems.push(`permissions: "${p}" listed twice`);
      perms.add(p);
      if (!PERMISSIONS[p] && !isNetworkPerm(p)) problems.push(`permissions: unknown permission "${p}"`);
    }
    if (!apiCompatible(m.forgeApi)) problems.push(`forgeApi "${m.forgeApi}" is not supported by this Forge (API ${FORGE_API})`);
    for (const [kind, items] of Object.entries(m.contributes || {})) {
      const need = CONTRIBUTES[kind];
      if (need && Array.isArray(items) && items.length && !perms.has(need)) problems.push(`contributes.${kind} needs the "${need}" permission`);
      if (Array.isArray(items)) {
        const seen = new Set();
        for (const it of items) { if (seen.has(it.id)) problems.push(`contributes.${kind}: duplicate id "${it.id}"`); seen.add(it.id); }
      }
    }
    for (const a of (m.contributes || {}).analyses || []) if (new Set((a.checks || []).map((c) => c.id)).size !== (a.checks || []).length) problems.push(`analyses.${a.id}: duplicate check ids`);
    for (const t of (m.contributes || {}).featureTypes || []) {
      for (const f of t.fields) if (f.kind === 'enum' && !f.opts) problems.push(`featureTypes.${t.id}.${f.k}: enum fields need opts`);
      if (new Set(t.fields.map((f) => f.k)).size !== t.fields.length) problems.push(`featureTypes.${t.id}: duplicate field names`);
      for (const f of t.fields) if (['name', 'body', 'op', 'id', 'type', 'ext', 'suppressed', 'locked'].includes(f.k)) problems.push(`featureTypes.${t.id}: field name "${f.k}" is reserved`);
    }
    if (!hasFile(m.main)) problems.push(`main file "${m.main}" is not in the package`);
    for (const p of (m.contributes || {}).panes || []) if (!hasFile(p.file)) problems.push(`pane file "${p.file}" is not in the package`);
  }
  if (problems.length) { const e = new Error('Invalid extension manifest:\n- ' + problems.join('\n- ')); e.problems = problems; throw e; }
  return { tier: tierOf(m) };
}

/** What the install prompt shows (spec section 1): tier line plus each permission in words. */
export function describeInstall(m) {
  const tier = tierOf(m);
  const hosts = m.permissions.filter(isNetworkPerm).map(networkHost);
  const gpu = m.permissions.includes('compute.gpu');
  const edits = m.permissions.includes('doc.ops');
  const line = tier === 'plugin'
    ? `Plugin: ${edits ? 'reads and edits your design' : 'reads your design'}`
    : `Extension: runs its own code${gpu ? ' on your GPU' : ''}${hosts.length ? ', talks to ' + hosts.join(', ') : ''}${m.permissions.includes('compute.remote') ? ', talks to your Alloy Runner' : ''}`;
  const perms = m.permissions.map((p) => ({ id: p, text: isNetworkPerm(p) ? `make network requests to ${networkHost(p)} only` : PERMISSIONS[p].text }));
  return { tier, line, perms };
}
