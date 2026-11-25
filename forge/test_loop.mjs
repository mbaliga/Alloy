// Headless tests for the design loop (node --test). The model is mocked; the kernel is the real Manifold build.
import { test, before } from 'node:test';
import assert from 'node:assert/strict';
import Module from 'manifold-3d';
import { build } from './src/kernel.js';
import { wizardProject, applyOps } from './src/model.js';
import { runChecks } from './src/checks.js';
import { runDesignLoop, runVariants, LOOP_DEFINITION, lockedIntact } from './src/design-loop.js';
import { validateDefinition } from './src/loop/index.js';

let wasm;
before(async () => { wasm = await Module(); wasm.setup(); });

const P0 = () => wizardProject();
const reply = (explanation, ops) => JSON.stringify({ explanation, ops });
const setp = (name, expr) => ({ op: 'set_param', name, expr });
const isCritic = (system) => /Score the design/.test(system);

/** scripted model: designer replies in order (last one repeats); critic replies in order. Records calls. */
function mockModel({ designer, critic = [], usage = null }) {
  const calls = { designer: 0, critic: 0, log: [] };
  const fn = async (cfg, messages, signal, system, meta) => {
    const crit = isCritic(system);
    const list = crit ? critic : designer;
    const i = crit ? calls.critic++ : calls.designer++;
    calls.log.push({ crit, content: messages[0].content });
    if (usage && meta) meta.usage = usage;
    return list[Math.min(i, list.length - 1)];
  };
  fn.calls = calls;
  return fn;
}
const run = (o) => runDesignLoop({ project: P0(), wasm, goal: 'a sturdy enclosure', cfg: {}, ...o }).promise;
const nodes = (r) => r.run.nodeAttempts.map((a) => a.nodeId);

test('the loop definition validates with the vendored engine', () => {
  const rep = validateDefinition(LOOP_DEFINITION);
  assert.equal(rep.valid, true, JSON.stringify(rep.findings));
});

test('round 1 fails a check, round 2 fixes it: iterate, gateway routes, approve, export, SUCCEEDED_VERIFIED', async () => {
  const model = mockModel({ designer: [reply('thin wall', [setp('wall', '0.4')]), reply('thicker wall', [setp('wall', '2.4')])] });
  let exported = null, approvals = 0;
  const r = await run({ callModel: model, onExport: (p) => { exported = p; }, approve: () => { approvals++; return { decision: 'approved' }; } });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED', r.reason);
  assert.equal(r.rounds.length, 2);
  assert.equal(r.rounds[0].hardPassed, false);
  assert.ok(r.rounds[0].checks.find((c) => c.id === 'min_wall' && !c.pass), 'round 1 fails min_wall');
  assert.equal(r.rounds[1].hardPassed, true);
  assert.equal(model.calls.designer, 2);
  assert.match(model.calls.log[1].content, /FAILED CHECK min_wall/, 'round 2 sees the failed check');
  // gateway routing: iterate after round 1, finish after round 2
  const g = r.run.nodeAttempts.map((a) => a.nodeId);
  assert.deepEqual(g, ['designer', 'build', 'check', 'critic', 'designer', 'build', 'check', 'critic', 'approve', 'export']);
  const rec = r.run.eventLog.map((e) => e.toState);
  assert.ok(rec.includes('WAITING_USER'), 'human approval waited');
  assert.equal(approvals, 1);
  assert.equal(exported.params.find((p) => p.name === 'wall').expr, '2.4');
  assert.equal(r.project.params.find((p) => p.name === 'wall').expr, '2.4');
  assert.equal(P0().params.find((p) => p.name === 'wall').expr, '2');
});

test('interference with a reference part and print volume are caught and fixed', async () => {
  const model = mockModel({ designer: [reply('tight', [setp('clr', '-1'), setp('pcbL', '400')]), reply('fix', [setp('clr', '0.6'), setp('pcbL', '60')])] });
  const r = await run({ callModel: model });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  const failed = r.rounds[0].checks.filter((c) => !c.pass).map((c) => c.id);
  assert.ok(failed.includes('print_volume'), failed.join());
  assert.equal(r.rounds[1].hardPassed, true);
});

test('invalid ops become a finding and the loop recovers', async () => {
  const model = mockModel({ designer: [reply('oops', [{ op: 'nope' }]), 'not json at all', reply('fine', [])] });
  const r = await run({ callModel: model });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  assert.equal(r.rounds.length, 3);
  assert.equal(r.rounds[0].checks[0].id, 'ops_valid');
  assert.equal(r.rounds[0].hardPassed, false);
  assert.match(model.calls.log[1].content, /ops were rejected/);
  assert.equal(r.rounds[1].hardPassed, false, 'unparseable JSON is a failed round too');
});

test('budget: round limit stops the loop with STOPPED_BUDGET (budget:steps)', async () => {
  const model = mockModel({ designer: [reply('bad', [setp('wall', '0.4')])] });
  const r = await run({ callModel: model, maxRounds: 2 });
  assert.equal(r.state, 'STOPPED_BUDGET');
  assert.match(r.reason, /budget:steps/);
  assert.equal(r.ok, false);
  assert.equal(model.calls.designer, 2, 'no model call past the round budget');
  assert.equal(r.project, null, 'nothing exported');
  assert.ok(!r.run.eventLog.some((e) => e.toState === 'WAITING_USER'), 'never reached approval');
});

test('budget: token cap stops the loop (budget:tokens), using provider-reported usage', async () => {
  const model = mockModel({ designer: [reply('bad', [setp('wall', '0.4')])], usage: { inputTokens: 600, outputTokens: 400 } });
  const r = await run({ callModel: model, maxTokens: 2500, maxRounds: 6 });
  assert.equal(r.state, 'STOPPED_BUDGET');
  assert.match(r.reason, /budget:tokens/);
  assert.equal(r.usage.tokens, 3000);
  assert.equal(r.usage.estimated, false);
});

test('budget: cost cap counts only when prices are set (budget:cost)', async () => {
  const model = mockModel({ designer: [reply('bad', [setp('wall', '0.4')])], usage: { inputTokens: 1000, outputTokens: 1000 } });
  const r = await run({ callModel: model, maxCost: 0.05, prices: { inPerM: 10000, outPerM: 10000 }, maxRounds: 6 });
  assert.equal(r.state, 'STOPPED_BUDGET');
  assert.match(r.reason, /budget:cost/);
  assert.ok(r.usage.cost >= 0.05);
  // without prices the same cap is ignored
  const m2 = mockModel({ designer: [reply('bad', [setp('wall', '0.4')])], usage: { inputTokens: 1000, outputTokens: 1000 } });
  const r2 = await run({ callModel: m2, maxCost: 0.05, maxRounds: 2 });
  assert.match(r2.reason, /budget:steps/);
});

test('token usage is estimated when the provider reports none', async () => {
  const r = await run({ callModel: mockModel({ designer: [reply('ok', [])] }) });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  assert.equal(r.usage.estimated, true);
  assert.ok(r.usage.tokens > 0);
});

test('locks are respected: a locked parameter cannot be changed and the item is untouched', async () => {
  const base = P0();
  base.params.find((p) => p.name === 'wall').locked = true;
  const model = mockModel({ designer: [reply('try', [setp('wall', '3')]), reply('ok', [setp('floorT', '2.4')])] });
  const r = await run({ project: base, callModel: model });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  assert.match(r.rounds[0].checks.find((c) => c.id === 'ops_valid').detail, /locked/);
  assert.equal(r.project.params.find((p) => p.name === 'wall').expr, '2');
  assert.ok(lockedIntact(base, r.project));
});

test('critic: score below threshold iterates, then at/above passes', async () => {
  const model = mockModel({
    designer: [reply('v1', []), reply('v2', [setp('cornerR', '4')])],
    critic: ['{"score": 4, "notes": ["too plain"]}', '{"score": 8.5, "notes": ["nice"]}'],
  });
  const r = await run({ callModel: model, styleBrief: 'friendly and rounded', threshold: 7 });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  assert.equal(r.rounds.length, 2);
  assert.equal(r.rounds[0].hardPassed, true);
  assert.equal(r.rounds[0].passed, false, 'hard checks pass but the score is below the threshold');
  assert.equal(r.rounds[1].score, 8.5);
  assert.match(model.calls.log.filter((c) => !c.crit)[1].content, /Critic scored 4/);
});

test('critic failure is treated as skipped with a warning (hard checks still gate)', async () => {
  const model = mockModel({ designer: [reply('v1', [])], critic: ['definitely not json'] });
  const r = await run({ callModel: model, styleBrief: 'x' });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED');
  assert.equal(r.rounds[0].skipped, true);
  assert.match(r.rounds[0].notes[0], /critic unavailable/);
});

test('human rejection routes back to the designer with the feedback, then approval exports', async () => {
  const model = mockModel({ designer: [reply('v1', []), reply('v2', [setp('cornerR', '3.5')])] });
  const answers = [{ decision: 'rejected', feedback: 'make the corners rounder' }, { decision: 'approved' }];
  let exported = null;
  const r = await run({ callModel: model, approve: () => answers.shift(), onExport: (p) => { exported = p; } });
  assert.equal(r.state, 'SUCCEEDED_VERIFIED', r.reason);
  assert.deepEqual(nodes(r).filter((n) => n === 'approve' || n === 'export'), ['approve', 'approve', 'export']);
  assert.match(model.calls.log[1].content, /make the corners rounder/);
  assert.equal(exported.params.find((p) => p.name === 'cornerR').expr, '3.5');
});

test('cancel via AbortSignal ends CANCELLED and frees the kernel build', async () => {
  const ac = new AbortController();
  const model = async (cfg, m, signal) => new Promise((res, rej) => { signal?.addEventListener('abort', () => rej(new DOMException('aborted', 'AbortError'))); setTimeout(() => ac.abort(), 20); });
  const r = await run({ callModel: model, signal: ac.signal });
  assert.equal(r.state, 'CANCELLED');
  assert.equal(r.project, null);
});

test('cancel while waiting for human approval', async () => {
  const model = mockModel({ designer: [reply('v1', [])] });
  const ac = new AbortController();
  const r = await run({ callModel: model, signal: ac.signal, approve: () => new Promise(() => setTimeout(() => ac.abort(), 10)) });
  assert.equal(r.state, 'CANCELLED');
});

test('variants: N parallel loops, each with its own budget, sorted best first', async () => {
  let n = 0;
  const model = async (cfg, messages, signal, system) => {
    if (isCritic(system)) return '{"score": 9, "notes": []}';
    const hint = /Direction for this attempt: (.*?)\. Style/.exec(system)?.[1] || '';
    // the "minimise" variant proposes a design that can never pass; the others are fine
    if (/Minimise/.test(hint)) return reply('too thin', [setp('wall', '0.3')]);
    n++;
    return reply('ok', [setp('cornerR', String(2 + n))]);
  };
  const list = await runVariants({ n: 3, project: P0(), wasm, goal: 'enclosure', cfg: {}, callModel: model, styleBrief: 'tidy', maxRounds: 2 });
  assert.equal(list.length, 3);
  assert.deepEqual(list.map((v) => v.ok), [true, true, false]);
  assert.equal(list[2].state, 'STOPPED_BUDGET');
  assert.ok(list[2].partial);
  assert.ok(list[0].project && list[0].project.params.find((p) => p.name === 'cornerR'));
  assert.ok(new Set(list.map((v) => v.hint)).size === 3, 'different directions');
});

// ---------------------------------------------------------------- checks (unit)
const check = (P) => { const r = build(P, wasm); try { return runChecks(P, r); } finally { r.dispose(); } };
const byId = (res, id) => res.checks.find((c) => c.id === id);

test('checks: the default wizard enclosure passes everything', () => {
  const res = check(P0());
  assert.equal(res.passed, true, JSON.stringify(res.checks.filter((c) => !c.pass)));
  assert.deepEqual(res.checks.map((c) => c.id), ['features', 'bodies', 'interference', 'min_wall', 'print_volume']);
});

test('checks: minimum wall estimate tracks the wall parameter', () => {
  const thick = byId(check(applyOps(P0(), [setp('wall', '3'), setp('floorT', '3')]).project), 'min_wall');
  const thin = byId(check(applyOps(P0(), [setp('wall', '0.6'), setp('floorT', '2')]).project), 'min_wall');
  assert.ok(Math.abs(thin.estimate - 0.6) < 0.15, `thin ${thin.estimate}`);
  assert.equal(thin.pass, false);
  // the standoff ring ((6-2.5)/2 = 1.75 mm) is the next-thinnest section, so a thick wall reports that
  assert.ok(Math.abs(thick.estimate - 1.75) < 0.1, `thick ${thick.estimate}`);
  assert.equal(thick.pass, true);
});

test('checks: PCB keep-out interference', () => {
  const res = check(applyOps(P0(), [setp('clr', '-1.5')]).project);
  assert.equal(byId(res, 'interference').pass, false);
  assert.match(byId(res, 'interference').detail, /PCB/);
});

test('checks: print volume, with the plate centred in X/Y and Z from 0', () => {
  const P = P0(); P.settings.printVolume = [60, 60, 12];
  const res = check(P);
  assert.equal(byId(res, 'print_volume').pass, false);
  const ok = P0(); ok.settings.printVolume = [70, 50, 40];
  assert.equal(byId(check(ok), 'print_volume').pass, true);
});

test('checks: a failing feature fails the features check', () => {
  const P = applyOps(P0(), [{ op: 'add_feature', feature: { type: 'box', name: 'Broken', body: 'Base', size: ['nope', '1', '1'] } }]).project;
  const res = check(P);
  assert.equal(byId(res, 'features').pass, false);
  assert.match(byId(res, 'features').detail, /Broken/);
});

test('checks: two overlapping bodies interfere', () => {
  const P = applyOps(P0(), [{ op: 'add_feature', feature: { type: 'box', name: 'Blocker', body: 'Block', size: ['20', '20', '20'], anchor: 'bottom', pos: ['0', '0', '0'] } }]).project;
  assert.equal(byId(check(P), 'interference').pass, false);
});
