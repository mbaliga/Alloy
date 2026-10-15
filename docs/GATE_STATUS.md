Warning: truncated output (original token count: 49218)
Total output lines: 3114

# Alloy engine gate status

Date: 2026-10-15

## 2026-10-16 three-printer static profile compatibility pre-gate

PR #12 exact head
[`d07b58f430fba107c1fa9d955f9b93c0cc2ecbb0`](https://github.com/mbaliga/Alloy/commit/d07b58f430fba107c1fa9d955f9b93c0cc2ecbb0)
passed all three jobs in the new static profile compatibility matrix:
[workflow run 38786843166](https://github.com/mbaliga/Alloy/actions/runs/38786843166).
The jobs resolved the pinned Bambu Studio profiles for A1 Mini, A1 and P1S
against the pinned SliceBeam/Prusa configuration-key surface and passed the
declared critical-field checks. Their retained artifacts are
`profile-compatibility-a1-mini` (11998352169),
`profile-compatibility-a1` (11997253722), and
`profile-compatibility-p1s` (11997348526). This is a static adapter
compatibility **pre-gate only**. It does not establish that Alloy applies each
resolved setting correctly at runtime, that the three native slicers match
Bambu Studio, or that any printer can safely execute the generated output.

The same head's Python tooling and desktop Orca A1 Mini reference-slice checks
also passed; the reference artifact is
[11998228014](https://github.com/mbaliga/Alloy/actions/runs/38786842821/artifacts/11998228014).
The optional native-engine workflow had not completed at the time of this
entry. G3 semantic parity, G4 runtime profile parity, Bambu Handy acceptance,
physical qualification for all printers, and signed production release remain
open. Keep print and release claims fail-closed.


## 2026-10-15 G3 comparator investigation (not a parity pass)

Inspection of exact-head native run [38488883737](https://github.com/mbaliga/Alloy/actions/runs/38488883737)
found that the new G3 comparator failed its last gate after the native build,
emulator instrumentation, and G3 export had succeeded. The retained report
[11863749550](https://github.com/mbaliga/Alloy/actions/runs/38488883737/artifacts/11863749550)
was measuring machine-start extrusion and acceleration commands before the
first model layer as if they belonged to the sliced object. I corrected the
local comparison scope to begin at explicit layer markers and added regression
coverage. Re-evaluating the retained exact-head desktop and native artifacts
locally reduced positive-E deltas to 0.03% (cube), 0.89% (thin-wall frame),
and 0.35% (travel obstacle); the overhang/support fixture still differs by
6.04%. This is diagnostic evidence only—the corrected comparator has not yet
passed a fresh hosted exact-head run.

The unresolved checks remain material: desktop-vs-native reported print-time
deltas are 9.14–18.02% against the 2% gate on all four fixtures, the
support-bearing fixture's header filament estimates differ by about 8.9%, and
acceleration-command warnings remain. Do not relax the gates or describe G3 as
passed. The updated workflows now rerun both the desktop reference and native
comparison when comparator code changes, so the next exact-head CI run can
validate the fix without reusing stale-head evidence.


## 2026-10-15 exact PR-head software and G3 comparison

PR #12 head `f3842b272df76c7a7b7bb39a92b46eabd49a2b57` has completed its exact-head Android, desktop-reference,
source-snapshot, parity-tooling, and native-engine workflows. Android v1 run
[38488883765](https://github.com/mbaliga/Alloy/actions/runs/38488883765)
passed its acceptance suite and produced CI QA APK artifact
[11864271997](https://github.com/mbaliga/Alloy/actions/runs/38488883765/artifacts/11864271997)
(SHA-256 `d0972752b845b1ab768b25fe6746c9b3dbe30a851225bc108762b5f3aefeb057`).
This ordinary APK does not include or prove native slicing.

Parity tooling run
[38488883711](https://github.com/mbaliga/Alloy/actions/runs/38488883711),
official Orca source snapshot
[38488883701](https://github.com/mbaliga/Alloy/actions/runs/38488883701),
and desktop Orca A1 Mini reference-slice run
[38488883743](https://github.com/mbaliga/Alloy/actions/runs/38488883743)
passed. The desktop reference artifact
[11861195383](https://github.com/mbaliga/Alloy/actions/runs/38488883743/artifacts/11861195383)
has SHA-256 `22a9991348666d1044e7330059bde6eedd83f515fa82471b00cb1ffb5bd85cea`
and is tied to this exact PR head.

Native run
[38488883737](https://github.com/mbaliga/Alloy/actions/runs/38488883737)
built the native release and instrumentation APKs, passed API-35 ARM-translation
instrumentation and exported G3 evidence. It **failed** at step 31,
“Compare native G3 output with exact-PR-head Orca reference”; the five-fixture
software pilot steps were skipped after that failure. The native-vs-desktop
comparison report is artifact
[11863749550](https://github.com/mbaliga/Alloy/actions/runs/38488883737/artifacts/11863749550)
(SHA-256 `aeb50231ad5cd2005320cf75e82e6260fdc1e5b8cd2965b382c199f1c458c6fa`).
Until that report's specific parity mismatches are resolved and a fresh exact-head
comparison passes, G3 parity remains **FAIL / NO-GO**. These hosted runs do not
establish physical A1 Mini/A1/P1S print acceptance, Bambu Handy import, direct-send
qualification, production signing, or release approval. Keep those gates closed.

## 2026-10-14 G3 Orca reference export and validation

PR #12 source checkpoint `baa36d95c1224706ca6bd983293a04a07fb3c76d` passed the
parity tooling run [38486520421](https://github.com/mbaliga/Alloy/actions/runs/38486520421),
official Orca source snapshot [38486520339](https://github.com/mbaliga/Alloy/actions/runs/38486520339),
and desktop Orca reference-slice run
[38486520525](https://github.com/mbaliga/Alloy/actions/runs/38486520525).
The desktop run successfully sliced all four checked-in Android mesh fixtures
with OrcaSlicer 2.4.2 and pinned A1 Mini profiles. Artifact
[11860102035](https://github.com/mbaliga/Alloy/actions/runs/38486520525/artifacts/11860102035)
has ZIP SHA-256
`d5946e9fad66fd698592c95f5807ea8dafb87a49db9636da338d3cc99b57a8fd`.
The parity suite passed locally with 101 tests and no failures.

The preceding desktop run [38486269262](https://github.com/mbaliga/Alloy/actions/runs/38486269262)
failed after slicing because the workflow supplied an absolute path to
`--export-3mf` while also specifying `--outputdir`, causing Orca to prefix
the output directory twice. The artifact log established the exact failure.
The workflow now passes a filename relative to the selected output directory,
and a regression test guards the CLI contract.

For checkpoint `baa36d9`, Android run
[38486520487](https://github.com/mbaliga/Alloy/actions/runs/38486520487)
was queued and native run
[38486520405](https://github.com/mbaliga/Alloy/actions/runs/38486520405)
was pending at the last check. The successful desktop reference is not an
Android/native semantic comparison. G2 provenance, G3 parity, physical A1
Mini acceptance, Bambu Handy acceptance, signed install/upgrade, production
signing, and release approval remain open. Keep direct-send and physical-print
claims fail-closed.

## 2026-10-14 A1/P1S catalog test correction and desktop baseline

PR #12 head [`a7ceb6bc444d2c139bac732048c05e9eb00c80ed`](https://github.com/mbaliga/Alloy/commit/a7ceb6bc444d2c139bac732048c05e9eb00c80ed) fixes the sole failure reported by the preceding ordinary Android acceptance run: the A1/P1S material catalog test looked for lowercase `dry PVA only` in Bambu's title-case “Dry PVA only” guidance. The assertion now normalizes case and includes a diagnostic message. The fresh Android run [38479632636](https://github.com/mbaliga/Alloy/actions/runs/38479632636) completed successfully: APK build and emulator acceptance suite passed, and artifact `alloy-v1-release-ci-apk` (ID `11856349296`) has ZIP SHA-256 `96ae24f2a9e75c603e9b13e40502922163e340db3a8fd559bb3517b8e923e11e`. The hosted test output reached 131/195 invocations with 1 skipped and 0 failed before Gradle reported BUILD SUCCESSFUL.

The separate native-engine build for the same source, run [38479632639](https://github.com/mbaliga/Alloy/actions/runs/38479632639), is queued and has not passed. The ordinary APK does not contain the optional native engine and proves no native slicing or printer send.

Desktop run [38478213726](https://github.com/mbaliga/Alloy/actions/runs/38478213726) passed the pinned Orca 2.4.2 G3 profile checks and sliced four mesh inputs copied from Android assets. This is a successful desktop reference/recipe check only; a same-source native-vs-desktop semantic comparison is still required. G2 provenance and G3 semantic parity remain open, as do physical A1 Mini acceptance, Bambu Handy acceptance, signed install/upgrade, production signing, and release approval. Keep direct-send and physical-print claims fail-closed.

## 2026-10-13 combined-source software verification

PR #12 candidate source
[`f009282dd50a2485fab5e071abad406709314373`](https://github.com/mbaliga/Alloy/commit/f009282dd50a2485fab5e071abad406709314373)
was tested against the PR #13 base
[`5e52089c6c31c0390a2e4bc93bf480b4c7f1195b`](https://github.com/mbaliga/Alloy/commit/5e52089c6c31c0390a2e4bc93bf480b4c7f1195b).
The combined Android run
[38365197015](https://github.com/mbaliga/Alloy/actions/runs/38365197015)
passed 194 tests with 0 failures. The combined native run
[38365197013](https://github.com/mbaliga/Alloy/actions/runs/38365197013)
passed the ARM64 legacy native build, isolated official Orca libslic3r
compile/link probe, API-35 ARM-translation instrumentation, G3 export and
five-fixture software pilot. The five-fixture manifest is software-only.

This does not wire the official Orca compile probe into the shipping JNI
slicer, pass G2 provenance, or establish G3 semantic parity. Fresh G2
provenance remains **FAIL / NO-GO**; desktop slicer parity, G4 profile runtime
parity, Bambu TreeSupport3D parity, physical A1 Mini qualification, Bambu
Handy acceptance, signed install/upgrade and interruption/recovery remain
open. Physical and direct-send qualification remain pending. Do not promote
print-ready or release claims.

## 2026-10-13 latest Android and G2 recheck

Android run
[`38321723583`](https://github.com/mbaliga/Alloy/actions/runs/38321723583)
on `a2ef790b7a6415c17c31342df894e0986fa96876` completed successfully. The
hosted log reports 203 test invocations, 11 skipped and 0 failures; the skipped
set includes the native engine smoke and bed-envelope tests because this was
the ordinary Android build without the optional native library. It published
artifact `11779906126`, ZIP SHA-256
`4df92ce3642d00c9a890bb849c60b70154c3425731dbfe6751e0c8f6eb1aa5f7`. This
does not prove native slicing or A1 Mini/A1/P1S envelope behavior.

A local G2 diagnostic used the exact pinned mobile source
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
and the then-current official Orca `main`,
`a28c9c3a827bbae9a2fcfe0434bdfabaecdb91e4`. It found 66/72 exact-history
sample intervals and 288/477 (60.38%) exact common engine files, below the 85%
threshold. The separate BBL.ini anchor still has no exact official history.
This local reproduction is diagnostic, not a hosted G2 workflow result; G2
remains **FAIL / NO-GO** pending refreshed hosted evidence and a valid profile
anchor. It authorizes no engine promotion or print claim.

## Current source checkpoint — 2026-10-13

### Ordinary Android v1

Run [`38319130553`](https://github.com/mbaliga/Alloy/actions/runs/38319130553)
on commit `a2fdb5f7d2a5817fc57e305431ea77a318e710ca` passed the acceptance
suite: **190 tests, 1 skipped, 0 failures**. It published
`alloy-v1-release-ci-apk`, artifact `11778127759`, ZIP SHA-256
`ff278c4be5abd7c84a5aa55223addd4176ef3dfd10355694065c26cc956826d2`.
This current ordinary APK includes the JPEG learning artwork and the dry-PVA
AMS Lite routing clarification. It is not a native-enabled slicer build and
proves no slicing or printer send.

The preceding Android run
[`38318024075`](https://github.com/mbaliga/Alloy/actions/runs/38318024075)
verified the JPEG path correction with the same 190-test suite (1 skipped,
0 failed). The original learning-artwork runs
[`38317656996`](https://github.com/mbaliga/Alloy/actions/runs/38317656996)
and
[`38317880788`](https://github.com/mbaliga/Alloy/actions/runs/38317880788)
each failed two tests because `LearningCatalog.Article` accepted PNG paths
only. Commit
[`0b2db87`](https://github.com/mbaliga/Alloy/commit/0b2db876d3829236f18680082333df00387cb5f3)
allows PNG/JPG/JPEG names under the bounded `learn/` path; catalog loading
still opens and validates every referenced image.

The capability copy on A1 Mini spools also retains its fail-closed states:
direct feed is the default for TPU/PVA, and AMS Lite consideration for PVA
requires a confirmed-dry spool and an exact route-compatible profile. This
copy is planning guidance, not material qualification.

### Native engine, slicing and release evidence

Native run
[`38315395721`](https://github.com/mbaliga/Alloy/actions/runs/38315395721)
has completed its pinned source dependency, OpenCV, GMP, MPFR and
optional-engine release APK build. It published release APK artifact
`11778506239` (ZIP SHA-256
`18816864c35c702de6a190c1a2fd57f4fb3e7f3aa8fe509bbd4277b16b9c9161`) and
instrumentation APK artifact `11778541204` (ZIP SHA-256
`000575528ecf9ba015c10aa124fa725ba259d815b3f37dbc3fffaf01cedd76c2`). Run `38315395721` ultimately concluded **failure** at its API-35 emulator
instrumentation workflow step. The uploaded diagnostic report artifact
`11778129246` contains Gradle output ending in `BUILD SUCCESSFUL` for
`:app:connectedReleaseAndroidTest` (13m22s) and JUnit XML with 190 tests, 0
failures, 2 skips; the reported native cube, assembly, arc and pilot-fixture
smokes passed. This conflicts with the enclosing GitHub step's failed
conclusion, so it is evidence of test execution but not a passing native
workflow. The action-level failure still needs diagnosis. Follow-up
[`38316210206`](https://github.com/mbaliga/Alloy/actions/runs/38316210206)
was cancelled after extending the first native-load test timeout to 180 seconds.


Commit
[`f36f67b`](https://github.com/mbaliga/Alloy/commit/f36f67b75ed346fff1d34f1ea030159acc417427)
adds review-only native slicing and package staging for all three initial
Bambu profiles, asserting that each remains unverified for physical sending.
Commit
[`a2ef790`](https://github.com/mbaliga/Alloy/commit/a2ef790b7a6415c17c31342df894e0986fa96876)
adds a differentiating envelope fixture: a 200 mm part must be rejected for
the A1 Mini and sliced/package-validated under A1 and P1S profiles. This is
designed to catch profile wiring that merely loads the right names while the
native path keeps the wrong bed size. The earlier f36 Android run
[`38320988598`](https://github.com/mbaliga/Alloy/actions/runs/38320988598)
completed successfully for the ordinary Android suite; see the
2026-10-13 checkpoint above. Native run
[`38320985893`](https://github.com/mbaliga/Alloy/actions/runs/38320985893)
remains in progress and does not contain the added envelope fixture. Native
run
[`38321723592`](https://github.com/mbaliga/Alloy/actions/runs/38321723592)
is pending. The a2ef790 cross-profile native tests remain unverified until
that optional-native run completes successfully.
The prior native run
[`38305679147`](https://github.com/mbaliga/Alloy/actions/runs/38305679147)
failed 2 of 162 tests: ARM translation exceeded a 30-second
`System.loadLibrary` timeout, and the typed-recipe test failed with an empty
trace after 94.6 seconds. Commit
[`6e01a9b`](https://github.com/mbaliga/Alloy/commit/6e01a9b83fc93ce8a9b3bbafec51efb279a37ae3)
adds logcat and raw test-result capture; commit
[`50520eb`](https://github.com/mbaliga/Alloy/commit/50520eb52b67cc54bb0116502b28ca18cf15b5d9)
extends only the native-load test timeout. The typed-recipe failure still
needs actionable diagnostics if it recurs.

G2 Orca provenance remains **FAIL / NO-GO**; the latest local
diagnostic is documented in the 2026-10-13 checkpoint above. The prior hosted
measurement (66/72 sample intervals; 314/479 exact common files, 65.55%) has
not yet been refreshed by a hosted run. G3 semantic parity, G4 profile runtime
parity, Bambu TreeSupport3D parity, physical A1 Mini acceptance, Bambu Handy
import acceptance and production signing remain open. Keep every print and
release claim fail-closed until its matching evidence passes.

2026-10-10 official Orca Android compile-probe failure and repair: native run
[`38026286081`](https://github.com/mbaliga/Alloy/actions/runs/38026286081)
completed its source-built dependency closure, then failed while compiling
`official_orca_libslic3r`. The diagnostic artifact shows that the generated
`libslic3r_version.h` defined `SLIC3R_VERSION` as `""1.0.0""`; the official
template supplies the C++ string-literal quotes, while the legacy Gradle
configuration already passes embedded quotes. The first repair uses plain
template values and filters the legacy version macros from the official
target (commit
[`c37bea15`](https://github.com/mbaliga/Alloy/commit/c37bea15b6e6942f01080bfed9c30aca7be01c8a)).
Review then identified that directory-wide `add_definitions` could still leak
those macros into the official target. The follow-up scopes the three version
definitions to the legacy `slic3r` target and adds regression checks (commit
[`dada7c98`](https://github.com/mbaliga/Alloy/commit/dada7c98d704118714c319a64700f2670afb000e)).
`ci/validate_native_wiring.py` and its 11 focused tests pass locally. Native
run [`38029368382`](https://github.com/mbaliga/Alloy/actions/runs/38029368382)
is testing the first repair; run
[`38029581758`](https://github.com/mbaliga/Alloy/actions/runs/38029581758)
contains both repairs and is queued. Neither rerun has produced Android
compile, instrumentation, or pilot evidence yet. G2/G3/G4, TreeSupport3D,
physical-printer acceptance, Bambu Handy import, and production signing remain
open.

2026-10-09 native integration recovery checkpoint: commit
`64fc3503a1bb1812f8e6a0870a7a8c765198793c` removes the Android-incompatible
MSVC `/imsvc` include override. Native Actions run
[`37883851621`](https://github.com/mbaliga/Alloy/actions/runs/37883851621)
then passed the complete pinned source-dependency build, GMP/MPFR source build,
ARM64 native app compile, API-35 ARM-translation instrumentation, G3 evidence
export, and isolated five-fixture A1 Mini software-pilot lane. The instrumented
native run reported **187 tests, 0 failures, 2 skipped**; the native smoke
class contained 7 tests (5 passed, 2 skipped), and the separate pilot test
passed. Artifacts: native release CI APK ZIP SHA-256
`d69137ac5c6074b1af7466e27aee950542dda2fbfa43cbadc0d8444ed63566dc`, G3
evidence ZIP `5b3e854b340f101e97432fae7b7e3c48aaafe78db4e1b4b4746bc7262cb33532`,
and A1 Mini pilot ZIP
`c9300883ef7f1d55d7023b1632a5afb9889fa1f56cba526e4d7d34e8bd197c76`.
Ordinary Android run
[`37883851602`](https://github.com/mbaliga/Alloy/actions/runs/37883851602)
also passed and published `alloy-v1-release-ci-apk` (artifact ZIP SHA-256
`f399275b175629abb01c65d9eed52953c0193df5d18cdd699bea56ec5ebc8f0a`). These
are emulator/software and CI-debug artifacts only: G2 provenance remains
**FAIL / NO-GO**, G3 semantic parity remains open, and neither physical printer
acceptance, Bambu Handy import, nor production signing is proven.

2026-10-08 offline troubleshooting usability checkpoint: symptom search now
updates as the user types, begins with stop-now cards rather than expanding all
26 troubleshooting entries at once, and ranks matching hard-stop guidance
ahead of routine remedies. `LearningCatalog.searchTroubleshooting` owns that
ordering contract, with instrumentation coverage for empty-query safety and
live-query priority. Current Android v1 run
[`37810227273`](https://github.com/mbaliga/Alloy/actions/runs/37810227273)
passed its build and acceptance suite (zero failures; nine skipped cases) and
published `alloy-v1-release-ci-apk`. The current native pilot artifact remains
from successful run [`37798978170`](https://github.com/mbaliga/Alloy/actions/runs/37798978170);
no native-engine source changed in this UI-only update. Both artifacts are
software QA only and do not qualify physical print, Bambu Handy acceptance,
or production release.

The first attempted source update at commit `75caec9` failed the static Android
wiring check because a large `MainActivity.java` transfer was truncated. The
full file was restored from the last successful source commit and the corrected
commit `5c0af09` passed. The failed intermediate commit is not a release
candidate.

2026-10-08 reproducible dual-upstream provenance report:
The audit now records the upstream name/head and reports both the best
history-constrained match and a separate current-ref tree snapshot. This avoids
mistaking a current tree resemblance for source ancestry. The regenerated
Orca run [`37741025557`](https://github.com/mbaliga/Alloy/actions/runs/37741025557)
still fails G2 at 66/72 sample intervals and 314/479 (65.55%) best-history
matches; the current Orca ref is 42/479 (8.77%). The separate Bambu diagnostic
[`37741025606`](https://github.com/mbaliga/Alloy/actions/runs/37741025606)
completed as a diagnostic, with 14/72 intervals, 101/445 (22.70%) best-history
matches, and 124/445 (27.87%) current-ref snapshot matches. Neither changes
the G2 outcome. Artifact ZIP digests: Orca
`21599b5abbc762c45f48cd3f8fcc4fad9676a447e36e6a00fd0f067787f82a31`;
Bambu Studio
`2c13bbf5c5ba6a7a29f056604f0cfd4a6ad5cf009abe024afc96dea27ba38777`.

2026-10-08 Bambu Studio alternative-lineage diagnostic:
A separate diagnostic workflow compares the pinned mobile engine against
Bambu Studio's official `master` history without replacing or relaxing G2.
Run [`37740670272`](https://github.com/mbaliga/Alloy/actions/runs/37740670272)
completed successfully as a diagnostic, but found no high-confidence baseline:
14/72 sample files had exact history intervals; the best constrained candidate
`fdba5967faaa6417a440840f6d491a93a61c9e05` matches 101/445 common files
(22.70%). The examined Bambu ref `da8b44ee34dd349f2ae0df3f1cbae366df482354`
matches 124/445 (27.87%) as a tree snapshot only, outside the sample-derived
historical candidate result. Artifact ZIP SHA-256:
`f3a3c4954cea9938fefa9f16910939e2a247121fb321343cc914f4316fd566f1`.

The same revision's Orca gate
[`37740670338`](https://github.com/mbaliga/Alloy/actions/runs/37740670338)
remains **INSUFFICIENT / FAIL**: 66/72 samples, 314/479 (65.55%) at its best
history candidate; current Orca HEAD snapshot is 42/479 (8.77%). Artifact ZIP
SHA-256: `6e2728de060239c2f2158fe9a3a35446e25f3cc4e90390bde703311c99ef8ac6`.
Neither upstream comparison establishes production slicer provenance or
semantic parity. The Bambu result is diagnostic only, not a replacement pass.

2026-10-08 fresh G2 provenance checkpoint:
The G2 audit is now wired to run on milestone-branch changes to the native
engine source and its audit scripts. Run
[`37739472063`](https://github.com/mbaliga/Alloy/actions/runs/37739472063)
against pinned mobile commit `d996a9cadb65b354997f2d5d8734b46bb9ea4efd`
and official Orca HEAD `18b70844d1dfaeede60a834f5fec41dd81dfe0e2` remains
**INSUFFICIENT / FAIL**: only 66/72 sampled files have exact-history intervals
and the best candidate `83738cacd84c616b62fc3cc15be8b65665569be8` matches
314/479 common files (65.55%), below the unchanged 85% threshold. The profile
anchor still has no exact introduction in official Orca history. The audit
report now names the six unmatched samples: `Config.cpp`,
`Feature/FuzzySkin/FuzzySkin.cpp`, `PrintConfig.cpp`,
`SLA/IndexedMesh.cpp`, `SVG.hpp`, and `Utils.hpp`. Evidence artifact ZIP
SHA-256: `f84c7a373febf2dbed1cac0a4f3bc2badbccc08dd69cfebcb715931866cbe02e`.
This improves diagnosis only; it does not pass G2 or justify promoting the
engine baseline.

2026-10-08 shared model-cache race checkpoint:
commit `462ad6ca49c049444960e36f83845a15093a740c` serializes ZIP bundle
materialization and rollback with model-cache writes and pruning. A regression
confirms bundle extraction cannot run while another cache mutation owns the
shared lock. Android v1 run
[`37728381340`](https://github.com/mbaliga/Alloy/actions/runs/37728381340)
passed **193 instrumentation tests (9 skipped, 0 failures)**. Native run
[`37728381335`](https://github.com/mbaliga/Alloy/actions/runs/37728381335)
passed **186 native instrumentation tests (2 skipped, 0 failures)** and the
isolated five-fixture no-support PLA pilot export. Artifact ZIP digests are
recorded in `ON_DEVICE_MVP_CHECKLIST.md`. These are software/emulator results;
the pilot remains physically unqualified, and G2/G3 parity, TreeSupport3D,
Bambu Handy, printer dispatch, and production signing remain open.

2026-10-07 slicing progress surface and native evidence harness checkpoint:
commit `02f9cd64b8b41ccdf3f06e921922e04bd65eae84` adds a full-screen,
progress-driven filament sweep that uses the supplied Alloy hothead artwork.
The head position follows durable `SliceJobStore` progress, retains its
position while cancellation is acknowledged, announces percent and phase to
accessibility services, and never animates to a fabricated completion. Its
focused contract is `FilamentSweepLoaderTest` (3 tests). A follow-up geometry
correction makes the nozzle position coincide with both rail endpoints at 0%
and 100%, keeps the full hothead inside the
horizontal screen padding, starts with an empty rail, refreshes the waiting
copy during prolonged zero-progress phases, and wraps long center messages to
fit narrow phones. The updated release and instrumentation APKs built, and the
three focused tests passed on the local
API-35 ARM64 emulator. A final screenshot smoke check was inconclusive because
Android System UI stopped responding on the local emulator.

The first hosted run exposed a real shell-quoting error after the native
emulator booted, before G3 instrumentation. Commit `fb3cc300` removes the
nested quoted Bash command and uses the runner's POSIX shell with `set -eu`;
the workflow contract validator passes. Hosted verification for
`fb3cc3000eba55be386734c15a2fd41dda1b41bd` is in progress in Android Actions
run [37553047222](https://github.com/mbaliga/Alloy/actions/runs/37553047222)
and queued for native run
[37553047277](https://github.com/mbaliga/Alloy/actions/runs/37553047277).
Neither this UI test nor a successful workflow syntax check changes the G2
official-port result, G3 semantic parity, G4 A1 Mini runtime, TreeSupport3D,
physical-printer, Bambu Handy, or production-signing gates.

2026-10-02 local material/profile regression checkpoint: commits
`e627b8d044c77ec80fbaab448c54f983298df845` and
`93975a27c893eeb18dff68e67be17ccfeb95e5b9` were assembled with the CI-debug
signing switch, installed on the local API-35 ARM64 emulator, and ran the
complete ordinary release instrumentation suite: **176 tests, 0 failures**.
This covers the individually source-linked A1 Mini material/spool catalog and
the conservative P1S source-backed 256 × 256 × 250 mm planning envelope. The
ordinary package intentionally skips native-only cases; this is local Android
regression evidence, not native-runtime, G2/G3/G4, physical-printer, Bambu
Handy, signing, or production-release evidence.

2026-10-02 local ARM64 release-instrumentation checkpoint: commit
`cb59cc803da7c34b0d2eae20715d93f4474facf4` was assembled with the
CI-debug signing switch, installed on the local API-35 ARM64 emulator, and
ran **176 instrumentation tests with 0 failures**. This includes the
source-bounded native recipe projection contract: Alloy does not manufacture
`internal_solid_infill_acceleration` when the pinned official A1 Mini profile
revision `07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4` does not resolve it. The
same revision was independently checked by `ci/verify_profile_snapshot.py`
(26 typed fields, 131 native scalar projections, and 11 provenance blobs).
The native-enabled slice segment remains intentionally skipped in this
Java-only package. This is local Android and profile-projection evidence only;
it does not pass the hosted native runtime, G2/G3/G4, TreeSupport3D, Bambu
Handy, physical-printer, signing, or production-release gates.

2026-10-01 Android distribution-notice checkpoint: Android v1 Actions run
[`36855211178`](https://github.com/mbaliga/Alloy/actions/runs/36855211178)
passed at delivery commit `07a326ddbcdc536a477fb5f93f73c60d4789925f`.
It built the CI-debug-signed release APK, ran the full Android pipeline
acceptance suite, and exercised
`AndroidPipelineTest.libnoiseLgplNoticeIsRetainedInPackagedAssets`. That test
opens the installed APK asset and verifies the retained LGPL-2.1 header and
version text. This is evidence that the pinned libnoise notice is packaged in
the ordinary Android artifact; it is not native-runtime evidence, a complete
license-distribution review, an official Orca port, slicer parity, physical
printer evidence, Bambu Handy acceptance, or production signing.

2026-10-01 native hosted-runtime KVM evidence: native Actions run
[`36847078236`](https://github.com/mbaliga/Alloy/actions/runs/36847078236)
completed the source dependency build, source-built GMP/MPFR closure, ARM64
release APK build, and ARM64 instrumentation APK compilation. It then failed
*before* the instrumentation command ran because its hosted runner exposed
`/dev/kvm` with an empty `kvm` group; the first trigger-only setup was
insufficient and the x86_64 API-35 emulator correctly refused software
emulation. Commit `8d692ab9` replaces that incomplete setup with the emulator
action's documented `99-kvm4all.rules` mode-0666 rule, udev reload and trigger
before both emulator phases. The follow-up run is required before any native
runtime or G3 evidence can be claimed. This failure is not a build success,
native-engine promotion, physical-print evidence, or a reason to lower the
runtime gate.

2026-10-01 official source-manifest checkpoint: the dedicated official-source
workflow now emits a content-addressed inventory of all 466 regular files in
the pinned `OrcaSlicer@ff9ce434a24873c18fd3c996b48d7f4fc…34218 tokens truncated…nfirms the transition;
the firmware vocabulary and physical behavior remain part of the open A1 Mini
acceptance gate.

The pinned FTPS path now uses Bouncy Castle JSSE's explicit session-resumption
API, forces TLS 1.2 for the Bambu file service, and verifies that the data
channel resumed the exact control-channel session before accepting `STOR`.
This closes the implementation gap, but the hardware gate is still open until
an A1 Mini upload proves the behavior end to end.

The live transaction now runs in `PrinterJobService`, an Android foreground
service with a persisted lease and heartbeat. Activity recreation reattaches to
the service without falsely promoting the job to recovery; a real process
restart remains fail-closed because the in-memory owner disappears. The
service does not auto-retry or use `START_STICKY`; force-stop and unconfirmed
printer state still require explicit review. Network callbacks are now bound to
the live worker lease as well as job/artifact identity, so an expired service
cannot overwrite a transaction claimed by a newer worker.

Inventory maintenance now distinguishes scheduled service that is due/overdue
from service due within the next 14 days, while retaining the existing reorder
threshold and local persistence behavior. The default PLA stock is tracked in
grams; a paired printer's COMPLETED event subtracts the estimated material
once, using the durable transaction ID to make repeated telemetry harmless.

The bundled model library is catalog-driven and verifies each example's
SHA-256 again when it is opened. The box/lid assembly and its selectable parts
are therefore available immediately on a fresh install, while community STL/
OBJ/3MF files remain import-only until redistribution terms are audited. OBJ
object/group names, negative face references and polygon faces are preserved
for inspection; valid multi-object 3MF packages are covered on-device with
unit and component-transform fixtures, and ASCII STL lines are bounded before
vertex parsing.
Imported document-provider sources are copied atomically into a bounded,
SHA-256-addressed app-private model cache before they are saved into a plate.
This keeps a phone project usable offline after a browser/cloud URI grant is
revoked or the provider is unavailable; pruning only removes unreferenced
cache files.
The Model Atlas now adds a bounded newest-first index of imported cache files
for one-tap offline reopen. The index stores no duplicate geometry and verifies
the cache filename, size, modification metadata and SHA-256 identity before an
entry is shown; missing or modified files are evicted from the shelf.

STEP/STP intake is now wired through the native OCCT boundary in native-enabled
Android builds. Content-detected CAD sources are tessellated into a durable
3MF cache object before the Java renderer, Parts inspector, project archive or
slicer sees them. The Android emulator check used a supplied Redmagic chassis
STEP and produced a 6,428-triangle, watertight one-part view; malformed or
empty OCCT output is rejected rather than shown as a successful blank import.

The Parts inspector now exposes a persisted, reversible conservative repair
pass. It removes degenerate and duplicate facets, welds near-duplicate
vertices within 0.0001 mm, and normalizes winding for closed connected
components. It deliberately does not fill open boundaries automatically;
remaining holes stay visible in the geometry-health warning for review.

The phone project can now be saved as a bounded `.alloy.zip` archive. The
archive embeds imported STL/OBJ/3MF sources, up to eight plate snapshots, transforms,
the typed recipe, workshop inventory state and bounded per-plate modeling
history timelines, then restores them into app-private storage on open. ZIP
path, size, duplicate-record and numeric-state checks fail closed. Archive
format v3 binds each embedded model to a byte count and SHA-256 digest, carries
the history cursor, caps compressed input, and rejects unreferenced model
entries; formats v1 and v2 remain readable. The archive also carries a bounded
PNG workspace thumbnail, and the app keeps a bounded local history of recent
portable archive URIs. Richer project metadata is not yet embedded.

The multi-plate workflow now slices each populated plate sequentially and
stages a separate validated `.gcode.3mf` for each one. `BatchSliceJobService`
consumes an offline-materialized request, checkpoints each plate and only
exposes the completed batch after revalidation. Android can export those
artifacts as `.alloy-batch.zip`, and the Linux bridge validates the same
archive. This is a portable bundle, not a claim that Bambu LAN accepts a
multi-plate transaction; the process-loss recovery gate remains explicit.

The native build now fails closed on the GMP/MPFR boundary. A clean checkout
first validates the checked-in fallback inputs, then the native/release
workflows run `ci/build_gmp_mpfr_android.sh`, verify pinned official archive
hashes, cross-build the arm64 libraries and require source-built validation
before CMake runs. The local compile/link and emulator runtime smoke now pass;
hosted reproducibility and the remaining engine/profile/hardware gates are
still required before promotion.

## Desktop reference audit

The installed Bambu Studio 02.08.02.61 CLI can now be used as an external
reference without depending on a GUI capture. The identical checked-in
`travel_obstacle.stl` was packaged and sliced through a temporary copied
profile directory using the A1 mini 0.4 nozzle and Bambu PLA profile. The
desktop result reported 140 layers, 28 mm maximum Z and 56m14s estimated
duration, and emitted `plate_1.gcode` successfully.

The first reference was not like-for-like with Android, so a second temporary
desktop project now overlays the Android recipe: 0.20 mm layers, 15% infill,
organic/tree support, 5 mm auto-brim, wipe and Slope Lift. The profile
identity harness now passes all 62 source-backed fields after aligning the
support interface, tree-branch, speed, brim-gap and automatic-wall sentinel
values to the resolved desktop recipe. With the desktop PLA density restored,
the matched obstacle comparison now reports valid mass evidence as well. It
passes layer count, profile identity, filament length and major feature
coverage, but still fails production tolerance on time (6.11%), filament mass
(4.79%) and motion counts; the acceleration sequence diverges at a later
support transition. The next parity gate is to resolve that support-motion
and acceleration difference, then repeat the comparison across all four
fixtures—not to promote from a single obstacle.

The native boundary now projects Bambu's `retract_before_travel` into both
the filament-prefixed and active top-level native keys. A wipe-off experiment
confirmed the setting is isolated and valid, but did not close the remaining
motion gap: the best controlled wipe-off comparison is still 5.27% slower,
4.79% higher in filament and 11.64% different in travel moves. This mapping
is retained as correctness hardening; it is not being counted as a parity win.

The native-enabled release instrumentation suite was rerun after this change:
**153 tests finished, 0 failures, 1 intentional skip** on the API-35 ARM64
emulator. The desktop-flavor acceleration adjustment is now restricted to an
explicit `gcode_flavor=marlin` parity run; the shipped Marlin 2 compatibility
recipe retains its reviewed default start acceleration.

The product visual gate is also still open. The current A1 study screen is a
readable native 3D scene, but it is not yet the supplied luxury configurator
direction: it lacks the reference's immersive whitespace, hero-object focus,
material/finish callouts and finished marketing composition. It must not be
described as production-ready or as a faithful implementation of the supplied
reference.

The next visual milestone is emulator-verified at
`/private/tmp/alloy-visual-v8.png`. The A1 study now uses the reference's
selector choreography more directly: size above the hero, material and nozzle
at the sides, and printer/scale context at the lower edge. The runway is four
receding presentation-only PEI sheets, and the scale props use restrained
stone/sage/brass materials instead of saturated debug colours. This is a
composition and material-language improvement only; the visual gate remains
open because the supplied machine mesh still needs a higher-fidelity material,
lighting, and hero-render pass.

The follow-up render pass is captured at `/private/tmp/alloy-visual-v9-study.png`.
It removes the in-frame instructional hint and prototype wording from the
marketing surface, adds bounded neutral specular response and low-amplitude
powder-coat variation to the welded machine shell, and keeps the four-plate
runway and selector hierarchy intact. The release APK builds successfully and
the shader path was exercised on the API-35 ARM64 emulator. The visual gate
remains open: the source mesh still needs authored region segmentation or a
higher-fidelity licensed render asset before this can be called premium or
production-ready.

The following segmentation pass is captured at
`/private/tmp/alloy-visual-v10-study.png`. It derives presentation-only
upright and front-carriage bands from the supplied mesh coordinates, giving
the machine a light frame / dark carriage separation instead of one uniform
gray shell. The APK builds and the capture was exercised on the API-35 ARM64
emulator. This is a measurable material-fidelity improvement, but the visual
gate remains open pending authored render assets or further art direction.

Release-wiring audit follow-up: the packaged model catalog now includes the
four-solid `travel_obstacle.stl` motion-parity fixture, and the native wiring
validator explicitly classifies `reduce_infill_retraction` and `travel_slope`
as import-only parity keys rather than reporting false profile drift. The
release, transport, Android, native, and model-asset validators now all pass
on this checkpoint. This closes repository-integrity regressions; it does not
close the native parity or physical-printer gates.

The independent parity suite was rerun after the audit repairs: **66/66
tests passed**. This confirms the catalog and validator changes did not alter
the checked-in profile, comparison, or artifact contracts.

Release packaging was also exercised on this checkpoint. The signed
debug-key release APK and AAB both build successfully; the AAB contains the
owner-provided Alloy logo, splash resource, A1 reference mesh, and bundled
model/profile assets. Hashes from the local build are:

- APK `6526000af71c4d4f8362b6ce4df620c331a422ff4878ac037c3d29232ac13070`
- AAB `a74388c2cb7ac5d7319e0588261306f79371995365d722130459ede5e6e4a369`

These are CI/debug-signed verification artifacts, not production signing
evidence; the protected production-readiness record remains intentionally
unpromoted.

The next visual pass is now installed and emulator-checked: the A1 Mini study
uses the reference-inspired hero composition, quiet white stage, centred Alloy
wordmark, floating printer/material/nozzle/build-volume/scale callouts, and a
two-action bottom surface. The final emulator capture is retained at
`/private/tmp/alloy-reference-composition-v6-study-real.png`. This is a
directional product-surface milestone, not final art direction or a claim
that the supplied reference has been reproduced pixel-for-pixel.

The Android wiring guard was tightened on this checkpoint so future APK/AAB
builds fail if the owner-provided Alloy logo is missing, empty, or no longer
referenced by the splash drawable. The manifest launcher icon, splash logo,
release assets, and Android model wiring all remain covered by the validator.
The Android, release, transport, native, model-asset, and parity checks pass;
the visual-quality, native parity, physical-printer, and production-signing
gates remain open.

The next emulator visual capture is `/private/tmp/alloy-visual-v11-runway.png`.
The presentation runway now uses four full-depth PEI-sheet silhouettes with
bounded perspective reduction and overlap; the prior shallow 72 mm sheets
read as disconnected grid fragments on portrait phones. The debug APK builds,
installs, and opens the study successfully after this change. This improves
the requested A1 Mini marketing composition, but does not close the broader
visual-quality gate: the machine still needs authored region materials,
higher-fidelity lighting, and a final reference-led art pass.

The follow-up capture is `/private/tmp/alloy-visual-v13-fullscreen.png`.
Presentation sheets now sit on the supplied machine's bed plane, exposing the
runway instead of letting the welded bed shell hide it; the sheet grip tabs
also use a separate edge material. The standalone study window explicitly
requests fullscreen so status/navigation chrome cannot contaminate a product
capture. The debug APK builds and the study opens on the API-35 emulator.
The visual gate remains open because the supplied reference still calls for a
more authored hero asset, lighting treatment, and material system.

The native parity audit was also rerun against the fresh G3 travel-obstacle
evidence while reviewing this checkpoint. The 62-field profile identity still
passes, with 140/140 layers, print time within 0.23%, filament within 1.52%,
and travel moves within 1.30%. The result remains **pass with warnings**:
desktop and Android emit different early feature-transition counts (1023 vs
1027), and the normalized acceleration sequence first diverges at index 29
(`P1500` versus `P500`). These are still open parity work, not a production
readiness pass.

## Linux milestone

**Status: implemented lightweight bridge; native desktop engine intentionally
frozen while Android is the active production target.**

The earlier assumption that Linux required a new full slicer was too strong:
Bambu Studio itself now documents Linux AppImage/Flathub distribution paths,
and OrcaSlicer, PrusaSlicer and UltiMaker Cura also publish Linux builds. The
Linux bridge remains useful for bounded inspection and artifact validation, but
Alloy's differentiator and active release target are Android phone-only slicing
and printer control. See `docs/LINUX_ALTERNATIVES.md` for the current source
links and product decision.

`tools/alloy_linux_gui.py` now provides a lightweight Tkinter window for
opening, orbiting and inspecting the bundled box/parts or imported STL/OBJ/3MF
files. `tools/alloy_linux.py` remains the bounded model and portable
`.alloy.zip` project inspection plus safe
Bambu Studio/OrcaSlicer CLI bridge that validates `.gcode.3mf` structure before
publication. Its contract and entry point are covered by
`.github/workflows/linux-cli.yml`. This is useful Linux support while Android
remains the only active product surface; it is not yet a production-grade
Linux desktop replacement because the Alloy-owned native desktop engine,
profiles, transport and packaging still need their own gates. No new Linux
feature work is planned until Android's native/profile/printer gates pass.

The follow-up focused ARM64 compile on 2026-09-22 confirms that the Bambu
TreeSupport overlay cannot yet be promoted: `TreeModelVolumes.cpp`,
`TreeSupport3D.cpp`, and `TreeSupport.cpp` fail against Alloy's surrounding
API at the support-parameter/config, support-layer enum, polygon-helper,
safe-offset/safe-union, incomplete-layer, and compile-time-scaling boundaries.
The disposable overlay was discarded; the main native tree and shipped build
remain unchanged. This is a dependency-closure task, not a production-ready
Bambu parity result.

The first implementation increment toward that dependency closure adds
`ExPolygon::split_expoly_with_holes` to Alloy's native geometry API. The
updated `ExPolygon.cpp` object compiles for ARM64. A clean full Gradle rebuild
was then started, but native compilation stopped producing progress and was
terminated before packaging; no APK success is claimed from that run.

The support configuration closure also now includes
`top_z_overrides_xy_distance` in the native `PrintConfig` schema, defaulting
to `false` as in the pinned Bambu source. The focused `PrintConfig.cpp`
ARM64 target compiles and is up to date; this still does not constitute full
Bambu TreeSupport parity or an end-to-end APK result.

The following support-parameter increment adds explicit soluble-interface and
aggregate interface-density values, derives solubility from the selected
interface filament, and applies the top-Z-over-XY gap policy. The affected
`TreeSupport3D.cpp` ARM64 translation unit compiles successfully. This is
still a compatibility increment, not a claim that the complete Bambu source
set or production APK has passed.

The shared Bambu safe-geometry increment is now present in
`SupportCommon.hpp/.cpp`, and Alloy's `TreeSupport3D.cpp` ARM64 target passes
with it. A disposable exact-Bambu probe resolves the prior safe-union and
safe-offset failures and now stops at the remaining scaling, legacy-layer, and
integer/`constexpr` API differences. This narrows the port boundary but does
not close the production parity gate.

The scaling/layer compatibility increment adds a runtime-safe integral
`scaled<T>` overload and typed overhang storage/propagation on `Layer`. The
affected native ARM64 targets compile and are up to date. Exact Bambu
`constexpr` scaling sites remain a source-port task; Alloy has not weakened
its runtime scaling contract.

The existing Alloy overhang classifier is now propagated into the Bambu-shaped
`Layer::loverhangs_with_type` vector after final filtering, including the
sharp-tail and enforced classifications. The ARM64 `TreeSupport.cpp` target
is up to date; exact Bambu source parity remains open.

The disposable exact-source probe was also advanced through the runtime-scale
boundary by converting its local `constexpr` values to runtime constants. It
now reaches only the `RichInterfacePlacer`/`InterfacePlacer` surrounding API
boundary plus one class-member declaration issue. These probe-only edits are
not counted as shipped Bambu parity or APK evidence.

The latest disposable compile narrows the exact Bambu source boundary to one
runtime member declaration and three `RichInterfacePlacer`/`InterfacePlacer`
inheritance calls. Scaling, typed overhang storage, and safe geometry are no
longer the first compiler failures; the source remains unpromoted pending a
coherent class-layout port.

The Android native build now vendors and wires the pinned Clipper2 geometry
library as a static C++17 target. `Clipper2` and Alloy's `Clipper2Utils.cpp`
ARM64 targets compile successfully, eliminating the prior missing-header
failure in the exact Bambu `TreeSupport.cpp` probe. This is dependency evidence
only; the full Bambu support route and production APK gate remain open.

The Clipper2-enabled exact-source probe now reaches support-material API
differences instead of failing on missing Clipper2 headers. The active boundary
is support ironing/cooling fields and roles, bridge-removal signatures,
geometry helper names, and bounding-box APIs. Clipper2 wiring is verified, but
this does not close Bambu TreeSupport parity.

The latest support compatibility increment adds Bambu ironing/base-pattern
aliases, unique support-debug include guards, and shared `ExPolygons`
`safe_union`/`safe_offset_inc` helpers. Alloy's focused ARM64 support targets
compile successfully. The exact pinned Bambu `TreeSupport.cpp` probe now gets
past those geometry and ironing boundaries and reports twelve remaining
surrounding-API diagnostics: scalar bounding-box comparison, bridge-removal
arity, support-ironing role, cooling metadata, overhang-degree setters,
soluble-interface state, moment/config access, and colinear-point cleanup.
This is measurable source-compatibility progress only; it does not establish
exact Bambu support parity, a complete slicer, or a production APK.

## 2026-09-24 local Android verification increment

The staged ARM64 JDK and Android 35 SDK were recovered from `/private/tmp`.
Gradle ran after its user/cache directory was pointed at the writable
temporary toolchain area and local worker sockets were allowed. The
Java-only `:app:assembleDebug -PalloyCiDebugSign=true` task completed
successfully, producing `app/build/outputs/apk/debug/app-debug.apk`. The
configured `:app:assembleReleaseAndroidTest -PalloyCiDebugSign=true` task also
completed successfully, compiling the instrumentation source set, including
`inventoryShowsUrgentNeedsBeforeRoutineStock`.

This is compile evidence only: ADB found no connected device, the staged SDK
has no emulator executable, and neither instrumentation execution nor visual
inspection of the updated A1 Mini study was possible. The debug artifact has
no native slicer because this build did not enable `alloyNativeEngine`; it is
not a release candidate or production-readiness evidence. Native/profile/
support parity, G-code parity, physical printer, lifecycle/recovery, visual
signoff and release-signing gates remain open.

The production packaging audit found a mismatch between the asset-license
documentation and Gradle: release variants defaulted to including private
reference meshes even though public redistribution rights are not documented.
Release inclusion now defaults off, private visual-review builds retain an
explicit opt-in, and the production build guard rejects that opt-in. The
release-wiring validator checks this policy. A fresh `:app:assembleRelease`
build succeeded, and direct APK listing confirmed that the A1 reference mesh
and private model catalog are absent while the ordinary bundled examples and
profile remain. A negative production-configuration check emitted the intended
redistribution-rights failure when private visuals were explicitly enabled.
Debug builds are unchanged so the owner can continue reviewing the supplied
scene locally.

## 2026-10-07 release instrumentation verification

The matching release and Android instrumentation APKs built from the current
checkout were installed on an API-35 ARM64 emulator. The complete
`AndroidJUnitRunner` suite completed successfully: **180 tests passed**. This
includes model import and multi-object 3MF handling, slicing and slice-job
recovery, profile/catalog validation, and printer lifecycle safety checks.
`ci/validate_android_workflows.py` and `git diff --check` also pass.

This is software instrumentation evidence, not printer hardware or Bambu Handy
acceptance. Hosted Android v1 Actions run
[`37554000114`](https://github.com/mbaliga/Alloy/actions/runs/37554000114)
completed successfully for commit `bddeeeac76eee895a9d3cc3a2f9c81cdce57a5a1`:
it built the CI-signed release APK, passed the Android pipeline acceptance
tests and uploaded the artifact. The exact Orca provenance and G-code parity
gates, Bambu TreeSupport3D parity, A1 Mini physical pilot, A1/P1S physical
qualification, Bambu Handy import, visual signoff and production-signing gates
remain open.

The Android v1 static validation set was also rerun locally: workflow/profile,
capability catalog, Android and model wiring, bundled model assets, multi-object
3MF, native/JNI contract, Bambu transport and release-wiring validators all
passed. The production readiness template correctly fails validation while it
contains placeholder evidence, and the physical-pilot build guard passes.
There is still no completed production-readiness or physical-acceptance record;
these checks confirm that software success does not promote the release gates.

### 2026-10-07 hosted native and Library verification

The hosted `alloy-native-build` run
[`37610818807`](https://github.com/mbaliga/Alloy/actions/runs/37610818807)
completed successfully for `30ae0f7360bb8abcdbe9aceee9cf9a7405d4477b`.
The native-enabled release and instrumentation pair built, the API-35 emulator
suite completed, and the opt-in G3 evidence export plus its validators passed.
The native release, instrumentation APK, instrumentation report and G3 evidence
artifact ZIP SHA-256 values are, respectively:
`4ec646b366ee4b35411e1122c2a45bd9f77b1f1e04bbf3d17c126452e306a661`,
`63f85c59f02587e87e556df282546fdd2e15fc35a0207e13935568eb845a1daa`,
`e3418252cd711a4ed8e49268fc169973423c15ecd6c07cbb87c7481c521d0820`, and
`5549ca287f317305988966e29d95a2db6fc8540efd2f96ac0c6f3bca14b24a67`.
These are CI QA artifacts, not production-signed builds.

The follow-on Android v1 run
[`37613376092`](https://github.com/mbaliga/Alloy/actions/runs/37613376092)
completed successfully for `4e6723c25e26a058fdc07ba01d335a74bea6ed65` and
produced `alloy-v1-release-ci-apk` with artifact ZIP SHA-256
`8a200c561073e49907012ae3fbb647da8001bc081cf64cbe28b507b55acc344b`.
This is the newest ordinary phone-workflow QA build and includes Library
search, live favourite counts, filters, sorting and shared arc navigation.
It is not the native-enabled build; use the matching native pair above for
native runtime testing.

The G3 evidence export passing means the evidence bundle was generated and
validated. The independent desktop parity comparison still reports
pass-with-warnings: transition counts and acceleration sequence differ, even
though geometry, time, filament and travel metrics are within recorded
tolerances. Therefore general G3 parity, support parity, physical A1 Mini
pilot, A1/P1S qualification, Bambu Handy acceptance, production signing and
visual sign-off all remain open. No direct-send claim is promoted by these CI
runs.


### 2026-10-07 Library and learning-artwork acceptance

The Library sorting correction passed Android v1 acceptance on commit
`a6c5781cae4175d6a4829800f01a9474a0b2f9a5` (run
[`37617745445`](https://github.com/mbaliga/Alloy/actions/runs/37617745445)).
The following commit adds a 512 px offline build-plate learning illustration
and references it from the placement/model articles. Android v1 run
[`37620435593`](https://github.com/mbaliga/Alloy/actions/runs/37620435593)
completed successfully on `2c88a069e9e67b343d3b82bdf9506d46626a131f`, with
artifact ZIP SHA-256
`0fca66c78d9478b46b5d2a3457a21b988aabc1e1ed7524b0412c89b656b50cd6`.
This confirms the learning catalog and all referenced artwork load in the
Android acceptance suite; it is not a visual sign-off on a physical phone.


### 2026-10-07 native and A1 Mini pilot CI completion

Commit `2dd169bf747aa3bebd121fa9de51adfde220fb39` passed the hosted native
workflow [run 37639120483](https://github.com/mbaliga/Alloy/actions/runs/37639120483)
and the Android v1 workflow
[run 37639120626](https://github.com/mbaliga/Alloy/actions/runs/37639120626).
The ordinary QA APK artifact ZIP SHA-256 is
`21dcbee5f12e89609fccca1cbc73b9252189b3b0d2cf1d826646349a126aff81`.
The native lane built the ARM64 native APK, passed 182 API-35 emulator
instrumentation tests (2 skipped, 0 failed), and structurally validated the
G3 evidence bundle. G3 is still not a parity pass: the earlier cross-check
retains transition-count and acceleration-command warnings.

The workflow also built an isolated `alloyPhysicalPilot` APK pair and passed
the five-fixture, software-only emulator preflight. The pilot artifact ZIP
SHA-256 is
`00a7d3da15f836c24028f59e22fe9fc146d9ab55825db62e40342694585f3e70`.
This enables a bounded on-device A1 Mini pilot attempt for the five bundled
unchanged no-support PLA fixtures; it does not claim that any physical print,
upload/start transport, or Bambu Handy handoff has passed. Physical
qualification, TreeSupport3D parity, production signing, release approval, and
A1/P1S physical qualification remain open. See
[`ON_DEVICE_MVP_CHECKLIST.md`](ON_DEVICE_MVP_CHECKLIST.md) and
[`NO_SUPPORT_PLA_PILOT.md`](NO_SUPPORT_PLA_PILOT.md) for the next checks.


### 2026-10-07 Java action deprecation cleanup and ZIP intake hardening

The deprecated `actions/setup-java@v4` references were upgraded to v6 across
five workflows; the validator rejects a regression to v4. The official
[setup-java documentation](https://github.com/actions/setup-java) recommends
v6 and marks v1–v4 deprecated. Android v1 run
[37647245782](https://github.com/mbaliga/Alloy/actions/runs/37647245782)
passed on commit `6e5d56a28b82383d44e14b760d68d6763a38c2e7`; logs confirm v6
executed and the former deprecation warning is absent. Its QA APK ZIP SHA-256
is `48eb0cc38ad8a37e36f3c0fa1c414c7ce42cda71091f33914b51eab7f4291648`.

A further import-hardening change on commit
`49288f95300f9a6967103352e1f54cfd57403084` bounds decompressed data across
all ZIP entries (including ignored previews/docs), streams model members to the
cache rather than buffering them wholesale, and removes newly-created partial
cache files if a later entry rejects the archive while preserving existing
content-addressed models. Android v1 run
[37650975522](https://github.com/mbaliga/Alloy/actions/runs/37650975522)
passed with 192 tests finished, 9 skipped and no failures. Native run
[37650975574](https://github.com/mbaliga/Alloy/actions/runs/37650975574)
passed with 185 instrumentation tests finished, 2 skipped and no failures;
the opt-in pilot test passed and the workflow validated the five-fixture
software-only manifest. Artifacts:
- Android QA APK ZIP SHA-256:
  `d11d3b6065ef6b0b2f3f2063402a89ce9afb14df6f637fdf76f76535052b53f5`
- Native APK ZIP SHA-256:
  `99b8dd8480cec51db743c400654f7baf1cb04a4b35c12f7a4b77dea4d50f832e`
- Native test APK ZIP SHA-256:
  `117b16363ebb86664a6e853fe2a4f4f9f45e8dd30795e007a5491a2ceb32d7d9`
- G3 evidence ZIP SHA-256:
  `9422907b6f27ff168f4470d545c9892c0cd79b4a8dd3caceac51ae285e245e1c`
- Isolated A1 Mini pilot APK pair and software evidence ZIP SHA-256:
  `d1426770017197fb690c20fcd9084eb36eedf584cdfb63b98bf16eef5ea61a12`

These results establish software/packaging evidence only. They do not establish
physical print quality, direct-send transport reliability, Bambu Handy
acceptance, support parity, desktop G3 parity, production signing, or A1/P1S
physical qualification. See the consolidated on-device checklist and narrow
pilot runbook for outstanding checks.
