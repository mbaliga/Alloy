// Sample plugin. A feature type is a recipe: expand() returns built-in feature specs and the kernel builds them.
forge.featureType.register('rib', {
  expand(v) {
    const along = v.axis === 'y' ? [v.thickness, v.length, v.height] : [v.length, v.thickness, v.height];
    return [{ type: 'box', name: 'wall', size: along, anchor: 'bottom', pos: v.pos }];
  },
});

// A check gets the design being checked as input.project (in the design loop that is the candidate, not necessarily the open
// document) and returns a row for the scorecard. forge.doc.get() would read the open document instead.
const MIN = 1.2;
forge.checks.register('rib-thickness', async (input) => {
  const doc = input.project;
  const params = Object.fromEntries(doc.params.map((p) => [p.name, p.expr]));
  const num = (e) => { const n = Number(e); return Number.isFinite(n) ? n : (e in params && Number.isFinite(Number(params[e])) ? Number(params[e]) : null); };
  const ribs = doc.features.filter((f) => f.type === 'xyz.mdhv.sample-rib/rib' && !f.suppressed);
  const thin = ribs.map((f) => ({ name: f.name, t: num(String(f.thickness)) })).filter((r) => r.t !== null && r.t < MIN);
  return { pass: thin.length === 0, validity: 'estimate', detail: ribs.length ? (thin.length ? thin.map((r) => `${r.name} ${r.t} mm`).join(', ') + ` is thinner than ${MIN} mm` : `${ribs.length} rib(s) ≥ ${MIN} mm`) : 'no ribs in this design', items: thin };
});
