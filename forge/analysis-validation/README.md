# Analysis validation

`npm run test:analysis` (or `node analysis-validation/harness.mjs [--only <part of a file name>] [--json out.json] [--md out.md]`)
runs every case in `cases/*.json` through the real runner and a real package Worker (or, for `function` and `core` cases, the
package's own functions) and prints a table. A case that misses its tolerance fails the run (exit 1). Tolerances are written in the
case file before the run and are never adjusted by the harness. An analysis that returns `validity: "validated"` must pass **every**
one of its cases, or the run fails.

## Case file

```json
{ "analysis": "<package id>/<analysis id>", "package": "examples/ext/<folder>", "cases": [ ... ] }
```

Every case has an `id`, a `title` and a `reference` (the analytic derivation or the published source, with the numbers it gives).
Kinds:

| `kind` | What it runs | Compared |
|---|---|---|
| (none) | the analysis on a design (`setup`), through the runner and a Worker | `expect: [{ metric, value, unit, tol, where? }]` against the summary rows |
| `function` | pure functions of the package's `main.js` | `calls: [{ fn, args, value, tol, mul? }]` |
| `core` | the same, with the analysis input built from a design (`"$input"` in `args`) | as `function` |
| `compare` | the analysis `a` and a second package `b` on the same design | the **rise above ambient** of `a.metric` against `b.metric`, `tol` |
| `reference` | the analysis on a design, against numbers in a data file | `expect: [{ metric, refKey, tol }]`, the value comes from `values[refKey]` of `referenceFile` |

A tolerance is exactly one of `{ "rel": x }` (fraction of the expected value) or `{ "abs": x }` (in the unit of the value).

## Cases that need the Alloy Runner

A `reference` case compares an analysis with a number produced by an independent solver (Elmer, OpenFOAM) that the Alloy Runner
(step A4) runs once on the customer's or the maintainer's machine. The numbers are stored as a small data file, for example
`analysis-validation/reference/enclosure-elmer.json`:

```json
{
  "source": { "tool": "Elmer FEM", "version": "9.0", "runner": "Alloy Runner 0.1.0", "produced": "2027-01-15", "mesh": "tetrahedral, 1.2 million elements" },
  "setup": { },
  "values": { "max_temp_C": 61.8 }
}
```

`source` and `values` are required; `setup` (optional) is merged over the case's `setup` so the data file can carry the exact
design and settings the reference solver used. If the file does not exist the case is reported as

    SKIPPED (not a pass): skipped: needs runner (reference file ... not found)

A skipped case is **not** counted as a pass, does not fail the run, and, because the analysis then has a case that has not passed,
blocks the analysis from returning `validated`. The count appears in the summary line (`N case(s) SKIPPED`).

**Producing a reference file.** `node tools/make-reference.mjs --case structural/bracket-vs-elmer --runner <url> --token <token> [--mesh-mm n]` (`npm run reference:make -- ...`)
builds the case's design, sends it to **your** Alloy Runner with a two-mesh study, and writes the file only when the solve was done by a real Elmer (adapter `elmer`, never the
simulated solver) and the study converged; otherwise it refuses and writes nothing. It supports the structural bracket today; the enclosure case needs PCB heat sources, which the
runner protocol does not carry yet. **No reference file exists in this repository**, so both Elmer cases stay SKIPPED until someone with a real Elmer runs it.

Currently pending: `structural.json` / `bracket-vs-elmer` (A3, below), `thermal-3d.json` / `enclosure-vs-elmer-fine` (A2: 3D enclosure against Elmer within 5 % max-temperature error at
`fine`), and, for A5, `airflow.json` / `enclosure-forced-vs-openfoam` (total flow through the vents within 10 %, file `reference/airflow-enclosure-openfoam.json`, key `through_flow_L_s`) and `enclosure-natural-vs-chtmultiregionfoam` (peak temperature rise within 10 %, file `reference/airflow-natural-chtmultiregionfoam.json`, key `max_temperature_rise_K`). The runner's OpenFOAM adapter (`runner/`) is written and stub-tested but has never run OpenFOAM, and Forge does not submit flow jobs, so neither airflow reference can be produced yet.

### Documented expected divergence

A case of any kind may carry `"expectedDivergence": "<why, one sentence or more>"`. If the comparison (or any asserted value of the case) misses its tolerance, the row is printed as
`DIVERGES (documented expected divergence, NOT a pass)` and the run does not exit 1 for it. The tolerance is not changed; the case is not a pass,
it is counted separately in the summary line (`N DIVERGE`), and, like a skipped case, it blocks the analysis from returning `validated`. If the case
passes, it is a plain PASS.

Currently: `thermal-3d.json` / `enclosure-2-handheld-pc` (A1 against A2, 20.8 % apart, 15 % tolerance) and `structural.json` / `plate-hole-kirsch-default-voxelizer` (A3 plate with a hole at the default conservative voxelizer, 12.9 % under the Kirsch reference against 10 %). Both are listed in the summary line as DIVERGE and are not passes.

### Slow cases

`slow/*.json` holds cases that take minutes on the CPU (about 250 000 voxels at the quality `fine`). They are not in `npm run test:analysis`; `npm run test:analysis:slow` (or `node analysis-validation/harness.mjs --slow`) runs only them. Currently `slow/structural-fine.json`: the A3 cantilever at the quality fine, within 3 % of Euler-Bernoulli (the spec's wording). A result from it is only evidence when somebody has run it; the default run does not.

### Cases that need the Alloy Runner, A3

`structural.json` / `bracket-vs-elmer` (A3: an L bracket against Elmer: displacement and stiffness within 5 %, stress peak within 25 %; the tolerances are this repository's choice, the spec states none) reads `analysis-validation/reference/bracket-elmer.json` with `values` keys `max_displacement_mm`, `stiffness_N_per_mm` and `max_von_mises_MPa`. Until the Alloy Runner writes it, the case is SKIPPED (not a pass) and A3 stays `estimate`.

### A5 airflow cases

`cases/airflow.json` mixes `function` cases (the package's own reference problems, called through `__core`: `refCavity` Ghia lid-driven cavity, `refCylinder` Schaefer-Turek 2D-2 Strouhal number, `refPoiseuille`, `refDeVahl` the heated cavity), `run` cases through the real runner and a Worker (a closed box: no through flow; a duct: mass balance) and the two OpenFOAM `reference` cases (SKIPPED). The reference problems memoise their run, so a case with two calls on one problem runs it once. `slow/airflow-slow.json` has the Re 1000 cavity at 128 x 128 and the cylinder at 66 cells across (minutes on the CPU; `npm run test:analysis:slow`). The tolerances were written down before the runs; for the Ghia and Strouhal cases they are this repository's (the spec gives none), for the Nusselt number the spec's 2 %.
