// Vendored from mbaliga/Shared-Libraries-asoc web/loop-engine @ 7a6ea4bf461aef0a94b812e550349755dd967d18. Do not edit here: change the engine there and re-run tools/vendor-loop-engine.mjs.
/* loop-engine — dependency-free ES-module port of Fonebrew's loop engine (domain/loop). See README.md. */
export * from './states.js';
export * from './params.js';
export { parse as parseCondition, check as checkCondition, evalCondition, makeScope, CondError } from './conditions.js';
export { validate as validateJsonSchema } from './jsonschema.js';
export { canonicalize, semanticDigest, sha256Hex } from './digest.js';
export { validateDefinition, findingsByObject, verifierMatches, RULESET_VERSION } from './validate.js';
export { conditionGatewayPolicy, RecordingGatewayPolicy, ReplayGatewayPolicy, ReplayDivergenceError, GatewayEvaluationError, orderedOutcomes } from './gateway.js';
export { RunLedger, PricingBook, moneyMinor, toLedgerEntries, toTreeNodes, ON_DEVICE_PRICING, CONSERVATIVE_DEFAULT_PRICING, TAG } from './ledger.js';
export { GraphRunner, PolicyBoundaryError, SideEffectsPossibleError, TargetStateUnknownError, TimeoutError, effectiveLimits, resolveExecutorKey } from './graph-runner.js';
export { LoopRunDriver, IllegalTransitionError, evaluateVerification } from './run-driver.js';
export { replayRun, compareRuns } from './replay.js';
export { loopConfigToDefinition, definitionToLoopConfig, expert } from './loop-config.js';
export { loopDefinitionSchema, loopRunSchema, validationRules } from './schemas.js';
export const ENGINE_VERSION = '1.0.0';
