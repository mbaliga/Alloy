// BYOK AI editing. The key stays in the browser and goes only to the provider endpoint the user chose.
import { TYPES, fieldsFor, projectForAI } from './model.js';

export const PROVIDERS = {
  claude: { label: 'Claude (built in)', base: '', model: '' },
  anthropic: { label: 'Anthropic', base: 'https://api.anthropic.com', model: 'claude-sonnet-5-5' },
  openai: { label: 'OpenAI-compatible', base: 'https://api.openai.com/v1', model: 'gpt-4.1' },
};

export function systemPrompt() {
  const types = Object.entries(TYPES).map(([t, T]) => {
    const fs = fieldsFor(t).filter((f) => !['name'].includes(f.k)).map((f) => {
      const d = f.kind === 'enum' ? f.opts.map((o) => `"${o}"`).join('|') : f.kind === 'vec3' ? '[e,e,e]' : f.kind === 'num' ? 'expr' : f.kind === 'json' ? 'object' : 'text';
      return `${f.k}:${d}${f.tip ? ` — ${f.tip}` : ''}`;
    });
    return `• ${t}: ${T.desc}\n    ${fs.join('\n    ')}`;
  }).join('\n');
  return `You are the design copilot inside Alloy Forge, a lightweight parametric CAD editor for electronics enclosures and small products. You edit the user's project by returning structured operations.

COORDINATES: millimetres, right-handed, Z up. Angles in degrees. Trig functions in expressions use degrees.
PARAMETERS: the project has named parameters (name = expression). Every numeric field of a feature is an EXPRESSION STRING that may use parameters, + - * / % ^, parentheses, pi, and sin cos tan sqrt abs min max round floor ceil pow hypot atan2. Prefer adding or editing parameters over hard-coding numbers so the model stays parametric.
BODIES: features build named bodies ("Base", "Lid", …). op "add" unions, "cut" subtracts, "intersect" intersects, "ref" is a ghost shown for reference only (e.g. a PCB). body "*" on a cut applies to every body. Features evaluate top to bottom; order matters.
GEOMETRY KERNEL: settings.kernel is "manifold" (mesh-based solids, no edge fillets on existing edges; fast) or "occt" (true B-rep, needed by the pcb enclosure feature, which is generated from the project's pcb block). Change it with set_setting key "kernel". On manifold: Round corners via the radius field of box / shell_box. Use pattern/mirror fields instead of duplicating features.

FEATURE TYPES (all numeric fields are expression strings or numbers):
${types}
Every feature also has: name, body, op, and (except hole/standoff/split) pos and rot; plus mirror, pat, patN, patD, patAxis, patAngle, patCenter.

RESPONSE FORMAT — reply with ONE JSON object and nothing else:
{"explanation":"one or two plain sentences on what you changed and why","ops":[ ... ]}
Ops:
 {"op":"set_param","name":"wall","expr":"2.4"}
 {"op":"remove_param","name":"x"}
 {"op":"add_feature","feature":{"type":"hole","name":"M3 screw","body":"Lid","d":"3.4","pos":["10","0","baseH+8"],"dir":"-z"},"index":optional}
 {"op":"update_feature","id":"<id or name>","set":{"d":"4","pos":["1","2","3"]}}
 {"op":"remove_feature","id":"<id or name>"}
 {"op":"move_feature","id":"<id or name>","index":2}
 {"op":"set_setting","key":"detail","value":64}   (detail = circle segments, density = g/cm³; key "kernel" takes "manifold" or "occt")
 {"op":"set_pcb_component","component":{"name":"battery","kind":"box","box":[x0,x1,y0,y1,z0,z1],"insert":"rear"}}   (adds or replaces a keep-out in the pcb block; numbers only)
 {"op":"set_body_material","body":"Base","material":"pla"}   (material ids: pla petg asa abs pa12 pa-gf pc al6061 fr4 copper air steel-carbon stainless-304 al7075 brass-cuzn, or a project material; null clears)
 {"op":"set_condition","list":"heat","item":{"id":"u1","target":{"pcb":"battery"},"power_W":1.5}}   (analysis setup: lists heat | mounts | loads; mount kinds contact | fixed | slider, SI units: W, N, Pa, m/s^2; also remove_condition, set_ambient {ambient:{temperature_K,airspeed_ms,gravity}}, set_limit)
 {"op":"remove_pcb_component","name":"battery"}
 {"op":"set_joint","joint":{"id":"J1","name":"clamp cross-bolt","where":[0,0,0],"axis":"+z","bolt":{"size":"M3","grade":"8.8","length_mm":16},"tightening":{"mode":"torque","method":"nut_factor","torque_Nm":0.9,"nut_factor":0.2},"mu_clamp":0.2,"thread_locker":false,"clamps":[{"body":"Shell","thickness_mm":6},{"external":"rail","material":"steel-carbon","thickness_mm":3}],"insert":"none"}}   (a bolted joint for the durability analysis; insert none | heat_set_brass | metal_sleeve | moulded_nut; also remove_joint {id}, set_duty {duty} for the duty profile)
LOCKS: features, parameters and bodies marked "locked": true were locked by the user. Never change, remove or reorder them, and never add cuts/adds into a locked body. If the request needs that, return no ops for it and say what must be unlocked.
If the request is a question or needs no change, return "ops":[] and answer in "explanation". If the request is ambiguous in a way that matters, make the most reasonable choice, say so in "explanation", and keep it easy to change via parameters. Never invent feature types or fields. Imported assets can only be placed/cut, not edited.`;
}

function userContext(project, summary, selection) {
  const ctx = {
    project: projectForAI(project),
    build: summary,
    selection: selection || null,
  };
  return 'CURRENT STATE (JSON):\n' + JSON.stringify(ctx);
}

export function extractJSON(text) {
  const t = text.trim();
  try { return JSON.parse(t); } catch (_) {}
  const fence = t.match(/```(?:json)?\s*([\s\S]*?)```/);
  if (fence) { try { return JSON.parse(fence[1]); } catch (_) {} }
  const a = t.indexOf('{'), b = t.lastIndexOf('}');
  if (a >= 0 && b > a) { try { return JSON.parse(t.slice(a, b + 1)); } catch (_) {} }
  throw new Error('The model did not return valid JSON.');
}

export const needsKey = (cfg) => cfg.provider !== 'claude';
export async function hostedSample() { try { return window.claude ? await window.claude.use('sample') : null; } catch (_) { return null; } }

/** `meta` (optional) receives `usage: { inputTokens, outputTokens }` when the provider reports it (used by the design loop for budgets). */
export async function callModel(cfg, messages, signal, system = systemPrompt(), meta = null) {
  if (cfg.provider === 'claude') {
    const sample = await hostedSample();
    if (!sample) throw new Error('Built-in Claude is only available on the hosted page. Pick a provider and add a key in Settings.');
    const turns = messages.map((m, i) => (i === 0 ? { role: 'user', content: system + '\n\n---\n\n' + m.content } : m));
    try { return (await sample(turns, { cache: false, signal })).text; }
    catch (e) { throw new Error(e && e.code === 'not_granted' ? 'Claude access was declined for this page.' : (e && e.message) || 'Claude request failed'); }
  }
  if (!cfg.key) throw new Error('Add your API key in Settings first.');
  if (cfg.provider === 'anthropic') {
    const r = await fetch((cfg.base || PROVIDERS.anthropic.base).replace(/\/$/, '') + '/v1/messages', {
      method: 'POST', signal,
      headers: {
        'content-type': 'application/json', 'x-api-key': cfg.key, 'anthropic-version': '2023-06-01',
        'anthropic-dangerous-direct-browser-access': 'true',
      },
      body: JSON.stringify({ model: cfg.model, max_tokens: 4096, system, messages }),
    });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j?.error?.message || `Anthropic API error ${r.status}`);
    if (meta && j.usage) meta.usage = { inputTokens: j.usage.input_tokens ?? 0, outputTokens: j.usage.output_tokens ?? 0 };
    return (j.content || []).map((c) => c.text || '').join('');
  }
  const r = await fetch((cfg.base || PROVIDERS.openai.base).replace(/\/$/, '') + '/chat/completions', {
    method: 'POST', signal,
    headers: { 'content-type': 'application/json', ...(cfg.key ? { authorization: 'Bearer ' + cfg.key } : {}) },
    body: JSON.stringify({ model: cfg.model, messages: [{ role: 'system', content: system }, ...messages] }),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j?.error?.message || `API error ${r.status}`);
  if (meta && j.usage) meta.usage = { inputTokens: j.usage.prompt_tokens ?? 0, outputTokens: j.usage.completion_tokens ?? 0 };
  return j.choices?.[0]?.message?.content || '';
}

/**
 * One assist turn with automatic repair: proposes ops, the caller's `tryApply` validates + builds them;
 * on failure the error is fed back to the model up to `retries` times.
 */
export async function assist({ cfg, history, prompt, project, summary, selection, tryApply, signal, retries = 2 }) {
  const msgs = [...history, { role: 'user', content: userContext(project, summary, selection) + '\n\nREQUEST: ' + prompt }];
  let lastErr = null;
  for (let i = 0; i <= retries; i++) {
    const text = await callModel(cfg, msgs, signal);
    let plan;
    try {
      plan = extractJSON(text);
      if (!Array.isArray(plan.ops)) plan.ops = [];
      const res = await tryApply(plan.ops);
      return { plan, res, assistantText: text, userText: msgs[history.length].content };
    } catch (e) {
      lastErr = e;
      msgs.push({ role: 'assistant', content: text });
      msgs.push({ role: 'user', content: `That failed: ${e.message}\nFix it and return the corrected JSON object only.` });
    }
  }
  throw lastErr;
}

const FIELD_SYSTEM = `You convert a designer's plain-language value into an expression for one field of a parametric CAD feature (units: mm, degrees).
Expression syntax: numbers, parameter names, + - * / % ^, parentheses, pi, sin cos tan sqrt abs min max round floor ceil pow hypot atan2 (trig in degrees).
Prefer referencing existing parameters so the value stays parametric. If a new named quantity is clearly implied, you may add it to new_params instead of hard-coding.
Reply with ONE JSON object only:
 scalar field: {"expr":"...", "new_params":[{"name":"...","expr":"..."}], "note":"<=12 words"}
 vec3 field:   {"exprs":["x","y","z"], "new_params":[], "note":"<=12 words"}
Never change locked parameters; never reuse a parameter name that already exists in new_params.`;

/** Resolve natural language for a single field. Returns {expr|exprs, new_params, note}. */
export async function resolveField(cfg, { field, kind, text, feature, params, values, bodies }, signal) {
  const ctx = { field, kind, current: feature?.[field], feature, parameters: params, parameter_values: values, bodies };
  const msg = 'CONTEXT:\n' + JSON.stringify(ctx) + '\n\nVALUE AS TYPED: ' + text;
  const out = extractJSON(await callModel(cfg, [{ role: 'user', content: msg }], signal, FIELD_SYSTEM));
  if (kind === 'vec3' && !(Array.isArray(out.exprs) && out.exprs.length === 3)) throw new Error('model did not return 3 values');
  if (kind !== 'vec3' && typeof out.expr !== 'string' && typeof out.expr !== 'number') throw new Error('model did not return an expression');
  out.new_params = Array.isArray(out.new_params) ? out.new_params : [];
  return out;
}
