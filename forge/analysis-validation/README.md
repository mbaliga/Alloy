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

Currently pending: `thermal-3d.json` / `enclosure-vs-elmer-fine` (A2: 3D enclosure against Elmer within 5 % max-temperature error at
`fine`).

### Documented expected divergence (compare cases)

A `compare` case may carry `"expectedDivergence": "<why, one sentence or more>"`. If the comparison misses its tolerance, the row is printed as
`DIVERGES (documented expected divergence, NOT a pass)` and the run does not exit 1 for it. The tolerance is not changed; the case is not a pass,
it is counted separately in the summary line (`N DIVERGE`), and, like a skipped case, it blocks the analysis from returning `validated`. If the case
passes, it is a plain PASS. Currently: `thermal-3d.json` / `enclosure-2-handheld-pc` (A1 against A2, 20.8 % apart, 15 % tolerance).
