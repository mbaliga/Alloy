# Alloy engine gate status

Date: 2026-09-21

Fresh verification on this checkpoint: the independent parity suite completed
65/65 tests successfully; Android, transport, release, model/profile/native
wiring validators all passed; and `connectedReleaseAndroidTest` completed 153
instrumentation executions with 0 failures and 2 intentional skips. The two
skips are the opt-in native G3 evidence export and owner-step assembly smoke
test; they remain excluded from the production claim until their engine gates
are closed.

## Delivery forecast and merge state

The current checkout is a **closed-alpha Android build**, not a production
replacement for Bambu Studio. The local `codex/alloy-phone-first-ui` checkout
contains the Android/native implementation and local branding checkpoint
`7915526`; the GitHub branch of the same name carries the updated logo and
marketing captures through `926b25859732c2400d790f2ad86365f0279ef2b4`. That branch has no
merged pull request yet, and the local implementation remains ahead of the
remote branding-only branch. The checkout's tracking ref is still
`origin/codex/alloy-v1` at the older base, so those refs must not be treated as
one synchronized release.

The live GitHub audit on 2026-09-21 found branches `main`, `codex/alloy-v1`,
`codex/alloy-phone-first-ui`, `stage1-foundation`, `stage1-gates-prep`,
`stage1-path-b-g1-lean`, `stage1-path-b-g4-prep`,
`stage1-path-b-prusa-runtime`, `stage1-runtime-parity`, and the Claude storage
cleanup branch. PRs #1, #2, #4, #5 and #6 remain open drafts; PRs #3 and #7 are
merged. Linux feature work is intentionally frozen at
the inspection/bridge milestone while Android is the primary product.

Forecast: a real A1 Mini beta is approximately **2–4 weeks after** a physical
printer and reachable LAN are available for upload/start/cancel and print
acceptance. A production-grade Android replacement remains realistically
**Q1 2027**; **December 2026** is an aggressive best case, not a committed
date. The profile/parity and physical-printer gates below are the critical path.

The latest safety increment tightened both the offline slicer and setup
preflight to reject layer heights below the documented 0.01 mm minimum. The
focused regression passed, followed by the full non-native release suite:
**158 executions, 0 failures, 8 intentional native-engine skips**. This is a
fail-closed input-validation improvement; it does not change the native
engine, profile-parity, support-parity, or physical-printer gate status.

The next artifact-safety increment hardens strict G-code validation for curved
motion: I/J arcs now check their cardinal extrema against the configured bed,
and radius-form arcs are rejected because their sweep cannot be bounded safely
without a full arc simulator. The focused regression passed, followed by the
full non-native release suite: **159 executions, 0 failures, 8 intentional
native-engine skips**. Native toolpaths were not changed by this safeguard.

The native `nativeArcOutputRemainsInspectableAndSafe` smoke test also passes on
the API-35 ARM64 emulator with the strict arc validator enabled. This confirms
the new envelope check accepts Alloy's actual I/J output while still rejecting
the synthetic unsafe cases; it is safety evidence, not native parity or
physical-printer acceptance evidence.

Fresh G3 evidence was exported manually from the rebuilt native APK on the
API-35 ARM64 emulator and retained in temporary evidence storage. The bundle
validator passes. Against the pinned v14 Orca reference, the cube still has
exact feature-transition and layer-count matches, 62/62 profile fields pass,
and extrusion moves are within 2.01%; the measured deltas remain 11.28% for
reported time, 2.09% for reported filament length, 2.03% for reported mass,
41.45% for travel moves, and 10.70% for positive-E distance. The first motion
signature mismatch remains desktop `M204 P500` versus native `M204 T500`.
This refresh confirms the parity failure is reproducible; it does not promote
the native engine.

The 2026-09-20 native checkpoint successfully linked the ARM64 slicer, packaged
the release and instrumentation APKs, and installed both on the API-35 ARM64
emulator. The rebuilt profile-import regression is **15/15** and the native
smoke class is **7/7**, with two intentional opt-in/private-asset skips. Fresh
full release instrumentation is now **150/150 executions, 0 failures** on the hardened APK. The
current G3 evidence carries the resolved `wipe_distance=2` project setting and
explicit support-policy defaults; profile identity is **62/62**, layer count is
**170/170**, and major feature classes pass. The controlled cube comparison
measures 1,581 s and 1,111.86 mm / 3.37 g versus Orca's 1,782 s and 1,135.62
mm / 3.44 g: timing still fails at **11.28%**, filament length at **2.09%**,
filament mass at **2.03%**, and travel moves differ by **41.45%**. G3 therefore remains fail-closed and is not
production promotion evidence. The latest transport hardening keeps
unauthenticated certificate inspection separate from authenticated MQTT/FTPS
traffic and requires an explicit leaf pin for the latter; the new regression
and full suite pass. Current rebuilt hashes are release APK
`1d274fee951123f2b3acccb9eedfc3d9ecdb6effd493ede439a8906113a9936f`, release
instrumentation `eed7c8fc9d6729ee97d82ac413d3358e5416f2acf8d208b5e8498f4e1cf698c0`,
and stripped ARM64 `libslic3r.so`
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

Latest native verification on the API-35 ARM64 emulator rebuilt the native
release from the current checkout, ran **150 executions with 0 failures and 2
intentional skips**, and exported the opt-in cube, overhang, and thin-wall G3
bundle. `ci/validate_g3_evidence.py` passed against that freshly installed
artifact. The current native APK hashes are release
`a33f2f3fc7835512f4dfd5652cf5c64263a20d177fe1d6b83178cdce49d3b023` and
instrumentation
`d281a3e0ccd7272dcaff0cefd130dec8dc1029efa0cdc43c5a958a42a27f7e2d`.

After the layer-height safety change, the native ARM64 release was rebuilt
successfully and the native smoke suite completed **9 executions, 0 failures,
2 intentional opt-in/private-asset skips**. The rebuilt artifact hashes are
APK `db6640d14390e83eb6cc978eb71e57c7b13eafa4b957a89ce70fff849459d709`, AAB
`7ddd6d86b712391c6b16ad17afc8969c2acb1a0505ed6c3814ef85bb6aea96dc`, and
stripped `libslic3r.so`
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

The controlled cube export now carries the reviewed machine-start
`M204 S6000` default acceleration before first-layer acceleration. The first
remaining normalized acceleration mismatch against Orca is the separate
`P500` versus `T500` encoding at command index 5; it remains diagnostic and
does not promote G3 or physical printing. The LAN start path also rejects an
explicit busy telemetry snapshot before publishing `project_file`.

The same checkpoint also reran the host parity suite (**65/65**), Linux bridge
tests (**19/19**), LAN probe/transport tests (**6/6**), and model-contract tests,
plus the Android workflow,
release-wiring, native-wiring, transport-wiring, model-asset, and whitespace
validators. One validator correction made the compact shipped profile's
import-only Bambu compatibility fields explicit; it did not widen the runtime
allowlist. Three source-backed diagnostics were measured and rejected for this
fixture: the tree-support safety-offset change, the organic-smoothing
reachability-clamp bypass, and Bambu's `auto` interface-pattern resolution.
None improved the G3 gate; all were removed and the artifacts above are the
restored baseline. No physical A1 Mini acceptance evidence exists yet, so
real-printer send remains disabled.

The pinned Bambu `v02.08.02.61` checkout was audited locally against the
current native tree. The corrected gate reports **PORT_REQUIRED**: 36 direct
include files, 89 transitive files, 35 unresolved quoted includes, 7 missing
transitive Alloy files, and all 7 required Bambu support source files differing
from Alloy. The existing Orca `TreeSupport3D` route is therefore not counted
as Bambu parity. A compile-only probe of the pinned `TreeSupport3D.cpp` against
Alloy's current headers also fails before code generation at the known API
boundary (`BBL_INTERNAL_TESTING`, `Point3`/`Polyline3`, and related native
types); no source was promoted from that probe.

The 2026-09-20 pinned Orca/SliceBeam G4 evidence was regenerated locally at
Orca `07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4` and SliceBeam
`12b370ce305acc2caa59b7e4e78e04069db2f7e3`. The static profile audit and
explicit unsupported-field scope both pass. The workflow now feeds the
template audit the exporter’s actual `resolved/machine.json` output. That
template audit remains **REVIEW_ONLY**: the upstream A1 Mini start/end
templates still contain unresolved placeholders, conditional blocks, and
Bambu firmware macros, so Alloy continues to emit its reviewed neutral
template and keeps physical printing fail-closed.

The opt-in native G3 export was also executed on the local ARM64 API-35
emulator after the template-policy hardening. The resulting cube,
support-enabled overhang, thin-wall, and three config artifacts pass
`ci/validate_g3_evidence.py`. This proves the packaged native path can emit a
structurally valid evidence bundle; it does not close G3, whose desktop
semantic comparison and physical-printer acceptance remain required.

No Bambu Studio/OrcaSlicer desktop executable or saved reference toolpath is
present on the current host. The pinned Orca checkout includes
`build_release_macos.sh`, but this host has no CMake/Ninja build toolchain, so
the desktop reference comparison remains an explicit external-input gate and
has not been synthesized from Alloy's own output.

The 2026-09-21 parity follow-up tested two source-backed hypotheses against the
same saved Orca cube reference and then restored the baseline. Mapping the A1
profile from `marlin2` to legacy `marlin` changed the acceleration vocabulary
but did not improve the gate (1,543 s, 1,111.86 mm, 1,438 travel moves). A
separate export with arc fitting disabled also did not improve it (1,581 s,
1,111.87 mm, 1,438 travel moves). Both experiments were rejected; the native
profile remains on `marlin2` with its explicit arc-fitting projection. The
remaining discrepancy is therefore in motion/travel planning rather than a
header-only flavor or arc-setting mismatch. The restored baseline rebuilt and
passed the full signed release instrumentation suite at 150 executions, 0
failures, with two intentional skips.

A third 2026-09-21 experiment forced `wipe=0` for the cube export because the
desktop config resolves that field differently. It worsened the result to
1,563 s and 942 travel moves without changing filament consumption, so it was
also rejected and removed.

That reference input is now available in temporary evidence storage: the
official OrcaSlicer `v2.4.2` macOS binary was hash-verified against
`e15e7bb1b66214ec6e96b169b388004179c4f5f705effcdaf8c80d4992ee0366` and used
through its CLI with the checked-in G3 project overlay. The independent cube
comparison has an exact feature-transition and layer-count match; filament
length is within **2.09%** and filament mass within **2.03%**. The corrected
reference uses explicit A1 Mini machine, process, and filament overlays; the
reference G-code SHA-256 is
`c0f359a56679b6f42a399f7bcab4b50340e175e185919591fbbca36c239f7037`. G3 still
fails: Orca reports 1,782 s versus Alloy 1,581 s (**11.28%**), travel moves
differ by **41.45%**, and positive-E distance differs by **10.70%**. The
verification passes all **62/62** source-backed profile fields after recording
the proven `retract_before_travel`/`retraction_minimum_travel` vocabulary alias
and making the build-plate support default explicit. The earlier comparison
that used Orca's default filament profile is superseded and must not be used
as parity evidence.

The same corrected invocation now covers the other two fixtures. The
support-enabled overhang reference
(`1de198f3037c05ca0b09618a2d082e4c83cc451607b5f88d5888ec561c430ab6`)
uses the complete checked-in G3 recipe, matches 170 layers and passes both
profile identity and feature-transition checks; timing differs by **12.54%**
and filament by **2.59%**. The thin-wall reference
(`3e9d9c63a8c6b015ac949cd692cd89a79e71b879a4e9dc0d6779ffa6a405a0e9`) matches
40 layers, feature sequence, and profile identity, but remains outside the
2% gate at **3.96%** time and **2.65%** filament. These are now independent,
like-for-like fail-closed measurements for all three G3 fixtures; the earlier
partial-support-overlay result is superseded.

The reproducible reference invocation is the pinned Orca CLI with the machine
profile `Bambu Lab A1 mini 0.4 nozzle.json`, the checked-in
`parity/g3-bambu-filament-overlay.json`, and the explicit G3 process overlay;
the native side is the emulator-exported `cube_20mm.gcode` plus its adjacent
`cube_20mm.config.ini`. Both sides therefore use the same 200°C PLA recipe,
2 mm³/s volumetric cap, 0.2 mm layers, and 100-layer cube before any G-code
comparison is made.

The parity verifier now accepts the desktop `.gcode` or packaged `.gcode.3mf`
itself as `--desktop-profile` and reads its resolved `CONFIG_BLOCK`; a separate
project JSON can still be supplied when auditing source intent, but it is no
longer the only profile-identity evidence.

The latest release instrumentation rerun completed successfully with **0
failures and 8 intentional skips** (Gradle reported 155 completed executions
from 147 scheduled tests). The first rerun attempt was rejected before tests by
the unsigned intermediate APK installation path; rerunning with the repository's
CI debug-signing flag produced the signed installable artifact.

The subsequent model-preparation increment added largest-face lay-flat
orientation to the phone workbench. Its release APK and instrumentation build
passed, and the API-35 ARM64 emulator completed **152 test cases: 144 passed
and 8 intentional native/physical skips**. This is preparation evidence,
not a native G3 or physical-printer promotion.

The optional-native GitHub workflow now installs the matching CI-signed
release/test pair on an arm64 API-35 emulator and runs
`connectedReleaseAndroidTest`; it no longer treats compilation of native APKs
as sufficient runtime evidence. The local equivalent has completed
**143/143**.

The native workflow now validates the exported G3 bundle itself: cube,
support-enabled overhang, and thin-wall G-code/config pairs must be present,
contain machine-safety markers and layer markers, and carry an explicit support
contract. The current emulator-exported bundle passes this packaging gate;
the desktop comparison remains fail-closed at the deltas recorded above.

The phone inspection surface now also exposes an accessible layer scrubber
alongside the existing layer step controls. It selects the same renderer layer
state used by the top-down toolpath view and is inspection-only: it cannot
modify the sliced artifact. The rebuilt native release suite completed **149
tests with 0 failures and 2 intentional skips** after this change.

The follow-up native support-style experiment was rejected rather than
promoted: selecting the legacy `tree` route produced 274 layers, 1,083.45 mm
of filament and 1,831 s on the current overhang fixture, versus the Bambu
reference's 170 layers, 881.81 mm and 2,024 s. It also failed the profile
identity check for `support_material_style` and `support_base_pattern`. The
shipped organic mapping remains the closer controlled baseline; parity still
requires the coherent Bambu `TreeSupport3D` route audited below.

The current G3 profile now also preserves Bambu's source-backed
`retraction_minimum_travel=1` in the native config, increasing the controlled
profile identity to **62 fields**. The fresh export confirms the key is emitted
and accepted by the native core, but the fixture remains outside G3 tolerance:
1,755 s, 910.79 mm and 2.76 g versus 2,024 s, 881.81 mm and 2.67 g. This is
kept as a fidelity correction, not presented as a parity win.

The managed geometry-repair path now closes bounded planar convex openings for
single-part meshes. An on-device open-box regression changed the repaired mesh
from open to watertight and reported one filled planar hole; the existing
degenerate/duplicate/winding repair regression also remains green. The repair
boundary is intentionally fail-closed for non-planar, concave, non-manifold and
multi-part openings.

Latest LAN safety increment: authenticated MQTT/FTPS operations now require
an explicitly pinned printer leaf certificate in the transport itself, not
only in the Activity's send button. The unpinned fallback can no longer probe,
read telemetry, upload, start, pause, resume, or cancel against a real printer;
certificate inspection remains a separate handshake-only pairing step. The
new fail-closed loopback regression passes, the release build compiles, and
the complete API-35 ARM64 emulator suite is **143/143**. This closes a
credential-transport safety gap; it does not provide physical A1 Mini
acceptance evidence or change the closed G3/native-promotion gates. Current
local artifact hashes are release APK
`1d274fee951123f2b3acccb9eedfc3d9ecdb6effd493ede439a8906113a9936f`, release
instrumentation `eed7c8fc9d6729ee97d82ac413d3358e5416f2acf8d208b5e8498f4e1cf698c0`,
and stripped ARM64 `libslic3r.so`
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

Latest G3 parity increment: the bounded Bambu project importer now preserves
the source-backed `wipe_distance` value and explicit support-policy defaults,
and the identity comparator checks them along with surface/solid patterns and
tree branch distance. The current fixture passes **61/61** source-backed
fields. These accounting additions do not change the generated toolpath;
the earlier `wipe_distance` projection reduced the Android-vs-Bambu travel mismatch from
40.91% to **20.57%** and the timing miss from 15.02% to **13.88%**, while
keeping all feature classes and layers present. Filament remains 3.18% apart,
so the native path still does not match Bambu closely enough for promotion;
G3 remains fail-closed. Host parity is **48/48** and the Android suite is
**143/143**. This checkpoint separates profile-accounting coverage from the
remaining native support/path-estimation mismatch; it is measurable progress,
not a production-readiness claim. Current APK hashes are release
`1d274fee951123f2b3acccb9eedfc3d9ecdb6effd493ede439a8906113a9936f` and
instrumentation `eed7c8fc9d6729ee97d82ac413d3358e5416f2acf8d208b5e8498f4e1cf698c0`.

Latest local parity increment: the checked-in G3 Bambu project fixture now
includes the resolved `brim_type=auto_brim`, `brim_width=5` and
`brim_object_gap=0.1` settings that are present in the desktop reference.
The G3 identity gate was expanded from 47 to **50/50** source-backed fields,
the Android importer regression remains **14/14**, and the rebuilt native
release/test pair completed the full emulator suite at **141/141**. The fresh
native export carries all three brim values into its config, but the measured
toolpath is unchanged because the native auto-brim decision emits no additional
brim for this fixture: 1,720 s / 910.79 mm / 2.76 g versus the Bambu reference
2,024 s / 881.81 mm / 2.67 g. G3 therefore remains fail-closed; this is a
profile-fidelity correction, not a parity pass. The updated instrumentation
APK hash is `8d7787caa5b62ad1ffc92a9b8a5d1babedc665c9f0574fc8a87892d5e3d5cd31`;
the release APK and stripped ARM64 library are unchanged at
`bbd1806f156eb06b4ea5df016c07381d1ea8481f2c583f807e52509da3f6159a` and
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

Latest safety increment: imported Bambu projects now preserve the selected
build-plate family (`Cool Plate`, `Engineering Plate`, `Textured PEI Plate`,
`SuperTack Plate`, or `Hot Plate`) and project its source-backed temperature
into the native bed-temperature fields. Older Alloy projects without plate
metadata keep their existing typed-temperature fallback. The new Android
regression passes, the focused profile importer is **15/15**, and the full
rebuilt release instrumentation suite is **142/142** on the API-35 ARM64
emulator. This closes a wrong-surface-heating class of profile-import bugs;
it does not change the fail-closed G3 parity or physical-printer gates.

Latest release-hardening increment: the production-tag signing helper now
compares the actual SHA-256 signer digest of the app and matching release
instrumentation APK before replacing the unsigned outputs. The release
workflow also hashes the instrumentation artifact separately. The rebuilt
debug-signed pair has a matching certificate digest and the full emulator
suite remains **142/142**; production tag signing still requires the protected
organization keystore and has not been claimed locally.

Latest recipe increment: the Android print-recipe editor now exposes the
selected Bambu build-plate family and writes the chosen plate plus its
first-layer/steady-state temperatures into the persisted native recipe. This
keeps a phone-edited job aligned with the wrong-surface-heating guard rather
than leaving plate choice implicit. The rebuilt release APK is
`ea5898fe3ffe8864ebc72c6f135431c9068e8f36b675f274ba4e9581fbda35e6`; focused
profile import is **15/15**, full release instrumentation is **142/142**, and
host parity is **48/48**. This is a production-safety improvement, not a G3 or
physical-printer promotion.

Latest local increment: the Android import boundary now keeps direct 3MF
packages on the MeshModel reader path instead of misclassifying them as
generic ZIP bundles; ordinary ZIP bundles still expand through the bounded
extractor. A Bambu 3MF's bounded `Metadata/project_settings.config` is also
carried into the allowlisted profile importer, so opening a project does not
silently discard its recipe. The profile boundary also accepts one resolved
Bambu `project_settings.config` document in addition to typed machine/process/
filament presets, with arbitrary templates still excluded by the native
allowlist. The importer now carries top/bottom shell thickness, thin-wall
detection, wall generator, infill direction and support interface spacing into
the native recipe, while explicit project acceleration limits are protected
from the stock compatibility acceleration rule. The importer now also carries
current Bambu quality vocabulary for infill/surface patterns, line widths,
bridge behavior, overhang speeds, cooling time, skirt count and layer-change
retraction, and the native writer projects those values into the current Orca
keys rather than leaving them only in the compatibility map. Filament,
object-level and per-role flow ratios now follow the same bounded path, with
`set_other_flow_ratios` enabled only when role overrides are present. The
source-built ARM64 release and matching instrumentation APKs compile successfully; the
emulator release suite is **141/141**, and the host parity suite remains
**48/48**. The latest source-backed projection also carries Bambu's sparse
infill anchoring values into the native recipe (`infill_anchor=400%`,
`infill_anchor_max=20`) and verifies them in the emitted INI/G-code comments.
Current artifact hashes are release APK
`bbd1806f156eb06b4ea5df016c07381d1ea8481f2c583f807e52509da3f6159a`, release
instrumentation `9835510415a1d4c7dc561746d45692bdcfaee51fd521be08c9e6ea2ca1ff2206`,
and ARM64 `libslic3r.so`
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.
This does not change the open G2/G3/G4 or physical-printer gates.

The 2026-09-15 parity increment adds a checked-in, bounded resolved Bambu
project recipe to the opt-in G3 evidence path. Its on-device import regression
passes, the full current release instrumentation suite is **140/140**, and
the recipe identity check passes **47/47** source-backed fields against the
effective Bambu project plus support override. The importer now also carries
the effective A1 Mini motion recipe: 60/60/100 mm/s role speeds, 30 mm/s
initial-layer speed, 0.4 mm widths, Bambu feed-rate/jerk limits, retraction,
Z-hop, acceleration ceilings and the small-overhang support flag. The native
INI regression confirms those values reach the current Orca keys instead of
falling back to stock profile values. This changes the generated toolpath, so
the earlier 1,774 s / 858.59 mm versus 1,693 s / 947.20 mm comparison is
superseded and must be rerun from the retained desktop reference before G3
can be evaluated again. Both parity tools still accept explicit ordered
desktop overlays, and the semantic identity gate remains closed-loop and
fail-closed.
This is a measured profile-fidelity improvement, not production promotion:
G3, NATIVE_ENGINE_VERIFIED, and physical A1 Mini send remain closed.
The current locally CI-signed release artifacts are 90,961,592-byte APK
`3bb415197ed7c61b484e83bff604a22e167d58e6cc043794a28f23b929bdb5f1`,
1,322,640-byte instrumentation APK
`66b3153fcae00e6e2e57d6e51d77f1b59ef2ff2769c6140c835bd4bc3084601a`, and
the unchanged 35,504,344-byte ARM64 native library
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

The next source-backed support increment adds Bambu's section-circle
self-intersection guard: sharply turning branch paths are split into valid
overlapping capsules before slicing, while Alloy's flat build-plate root is
preserved. The native release rebuilt and the emulator suite stayed at
**133/133**. On the controlled support fixture the output changed only from
9,814 to 9,818 extrusion moves and from 4,237 to 4,234 travel moves; the
measured result remains 170 layers, 1,126 seconds and 960.03 mm versus the
Bambu reference's 1,774 seconds and 858.59 mm. This is a correctness guard,
not a G3 parity improvement, so native verification and physical send remain
disabled. The resulting release APK is 92,181,746 bytes with SHA-256
`8ad58b8a204f2b479b6e81a3e9b12bdf9f9398499e37f91c795c9c696c10ed36`; the
stripped ARM64 library is 35,504,344 bytes with SHA-256
`e437f1035a384ccfcdfc84ea0f402e57a555bcd594d1b60c80b43ea8b6ba362f`.

The profile audit also separated stock-profile fidelity from project fidelity.
The controlled Bambu support fixture's resolved `project_settings.config`
overrides the stock A1 Mini chain (for example, 200 °C nozzle temperature,
2 mm³/s volumetric cap, 1,000/1,500 acceleration limits, Arachne walls, four
top layers, and a `tree_support_wall_count` of `-1`). Those values are not
present in the selected stock machine/process/filament JSON presets. Alloy
therefore keeps the shipped stock profile unchanged and relies on the new
bounded project-settings import to carry such user-project overrides; the
remaining G4 comparison is still open until the native engine consumes and
matches the complete resolved recipe.

The 2026-09-21 modeling milestone adds a bounded axis-aligned auto-orientation
action to the phone workbench. It evaluates principal-axis and quarter-turn
poses, chooses lower print height with footprint as the stability tie-breaker,
and leaves arbitrary support-aware orientation as an explicit future gate. The
focused emulator test passed, and the full signed non-native release suite
completed 157 executions with 0 failures and 8 intentional skips.

## Local verification snapshot

The current checkout now has a reproducible local Android packaging check for
the safe phone shell:

- `:app:compileDebugJavaWithJavac` passes with the repository-declared Android
  dependencies.
- The product viewport is now an Alloy-owned GLES 2.0 surface behind the
  stable `ViewportView` API. The local compile covers the perspective camera,
  touch orbit/zoom/pan, part picking, machine/build-volume presentation,
  out-of-volume shading, toolpath lines, bounded thumbnails and Activity
  pause/resume forwarding.
- A clean default `:app:assembleDebug` passes with native slicing disabled;
  the current native-enabled visual-review APK is 92,169,714 bytes and contains
  the bundled models/profile, the private supplied A1 reference mesh, the
  owner-provided Redmagic v0.4 catalog overlay, and the ARM64 native engine.
  Ordinary release packaging excludes both private visual overlays; the private
  review APK below opts in explicitly.
- `:app:testDebugUnitTest` and `:app:testReleaseUnitTest` have no JVM test
  sources; the repository parity suite is green at 47 tests and the Python
  tool suite is green at 24 tests.
- The Linux secondary surface remains covered by those tool checks: 17 model,
  project, artifact and batch-archive tests plus 4 LAN-probe tests, all with
  0 failures. This validates the bounded Linux bridge; it does not promote
  the Android native engine or replace physical-printer acceptance.
- `:app:assembleRelease -PalloyCiDebugSign=true` passes; its CI-signed release
  variant verifies with APK Signature Scheme v2.
- The explicit native build now passes locally for `arm64-v8a`, including the
  source-built GMP 6.2.1 / MPFR 4.2.2 inputs and the matching native release
  and release-instrumentation APKs. The public native release APK without
  private visual overlays is 90,933,160 bytes; the owner-only visual-review
  variant below is 92,154,098 bytes with private assets. Both contain the
  Orca/SliceBeam/OCCT dependency chain
  and still carry `NATIVE_ENGINE_VERIFIED=false`.
- Native-enabled prebuilt packaging is fail-closed: the
  `validateNativePrebuiltDirectory` task rejects a missing or incomplete ARM64
  bundle before any release APK is assembled, while the valid CMake path
  remains green.
- Latest local artifact hashes after the Orca-Mobile native integration,
  baked-normal, material-region,
  studio-light/annotation, CPU-thumbnail, deferred-notification, OBJ
  material-group, BYOK redirect-hardening, redirect-regression-test,
  `sparse_infill_acceleration` and `infill_direction` projections, large-OBJ deep signature detection,
  pre-start remote-artifact
  revalidation, ambiguous-MQTT start/control recovery, and native-artifact
  loopback transaction, headless-thumbnail handoff, and marker-bounded
  A1 Mini template preflight, known-dimension model-scale, parameterized
  chamfered-box, watertight open-top enclosure and mirrored-mesh increments:
  public native release
  `2e6ad67bc17b4f5226b16d89a42f0914603463c6582fa5361c4f6c339c80cb6c`;
  owner-only visual-review release
  `a33d7284dcafe3ca0797068ff92ec164ddae03f2af7d47d07f4ec4e301926b71`;
  private release instrumentation
  `58cee6df01435df4f15bc2769690c957d7d5ed0e31526fceb50f456367b01ac9`.
- Staging, crash recovery and the final LAN transport boundary now invoke the
  strict package validator with the A1 Mini build volume, so homing, heater
  shutdown and raw-motion bounds are rechecked immediately before an artifact
  can leave app-private storage or be sent. The new send-boundary regression
  is included in the on-device suite. Physical-print recovery and send now
  additionally require the package's own `engine_verified=true` and
  `support_parity_verified=true` metadata; support-enabled packages must name
  the verified Bambu `TreeSupport3D` engine. Ordinary offline/batch recovery
  remains available for inspection and rework, but an unverified artifact
  cannot regain a physical-print action after an app restart.
- The printer package writer now emits a Bambu-shaped 3MF relationship graph:
  the outer model references `3D/Objects/object_1.model`, the model-settings
  table describes the combined object and its parts, `cut_information.xml`
  and a single-filament assignment are present, and a supplied phone preview
  is exposed under the standard plate/no-light/top/pick thumbnail names. The
  validator requires the related object and relationship, while headless
  staging remains honest and omits thumbnail references until a surface-backed
  preview is available. This improves package compatibility but is still not
  physical A1 Mini acceptance evidence.
- A local API-35 ARM64 emulator accepted the current native release/test APK
  pair; the latest Gradle instrumentation run started and finished 131 tests
  with 0 failures and one intentionally skipped opt-in G3
  evidence test. The loopback fake-printer regression also verifies remote artifact
  size after upload and again immediately before a start request, and carries
  a native-generated `.gcode.3mf` through upload, start, telemetry and cancel.
  A direct opt-in rerun of `NativeEngineSmokeTest` then finished
  the seven-test class with 0 failures and pulled a fresh cube, support and
  thin-wall evidence set before teardown; the support regression also requires
  a `;TYPE:Support` section in the support-enabled output. No physical A1 Mini is attached,
  so LAN upload/start/telemetry behavior remains unverified.
- The offline visualization boundary is now exercised by the same owner build:
  the on-device provider renders a bounded PNG from the active thumbnail plus
  finish/environment presets without network access, and reports itself as a
  deterministic studio renderer rather than claiming a bundled generative
  model. The public native release rebuilt after this change has SHA-256
  `2e6ad67bc17b4f5226b16d89a42f0914603463c6582fa5361c4f6c339c80cb6c`;
  the owner visual-review APK remains
  `a33d7284dcafe3ca0797068ff92ec164ddae03f2af7d47d07f4ec4e301926b71`.
- The 2026-09-12 source-built ARM64 library includes a bounded port of Bambu
  v02.08.02.61's tree-level connected-component cleanup after sibling-branch
  merging. The source target linked successfully, the updated settings
  contract passed on-device, and the full 131-test release suite passed. A
  fresh opt-in export remained numerically unchanged at 715 s / 1,224.39 mm
  for the cube, 1,126 s / 960.03 mm for the support fixture (147 support and
  2 interface sections), and 281 s / 859.86 mm for thin-wall. This is useful
  negative parity evidence: the cleanup is source-valid but is not the missing
  Bambu support geometry, so `NATIVE_ENGINE_VERIFIED` and physical send remain
  disabled.
- The same source-built checkpoint now carries an isolated lightning-infill
  grounding pass adapted from Bambu `v02.08.02.61`. It reconstructs only
  newly opened, bounded internal support voids, calls the existing native
  `FillLightning` generator, clips the result against the A1 Mini collision
  volume, and emits supplemental rectilinear support paths only for organic
  trees. The standard cube, overhang and thin-wall fixtures did not contain a
  qualifying newly opened support void, so their metrics stayed at 713 s /
  1,224.39 mm, 1,126 s / 960.03 mm and 280 s / 859.86 mm respectively. The
  full owner suite remains **131/131**. This is a source-backed code-path
  increment with negative fixture evidence, not Bambu support parity; the
  physical-send gate remains closed. The current owner-only APK is
  92,163,826 bytes (SHA-256
  `504bbe6ab34a68a33e1252faba444934a4f4e46ae4561db7adb5c16c01b7b416`), the
  release instrumentation APK is 1,317,057 bytes (SHA-256
  `3955e963314ee30d01992643859ddd850e4e2e3bef365b98223e96dab9bbb322`), and
  the stripped ARM64 `libslic3r.so` is 35,486,424 bytes (SHA-256
  `b80bb219183096a0db7f6058f52b6ef03e568b2390f94470db674a8020367f71`).
- The current source-built checkpoint also carries the steep organic-branch
  cooling metadata route from Bambu's tree-support path. It preserves bounded
  per-layer regions, marks only the corresponding emitted support paths with
  overhang metadata, and leaves classic supports untouched. The existing fan
  policy now consumes that metadata: the organic support fixture gains
  support-local fan-on commands, while the cube and thin-wall fixtures remain
  unchanged apart from generated timestamps. The measured support output remains
  1,126 s / 960.03 mm and the ARM64 build and owner emulator suite are green at
  **131/131**. This is a bounded cooling-policy increment, not Bambu cooling
  parity. Current owner-only APK: 92,169,714 bytes,
  SHA-256 `0276c0153b46295abdf11dc2eaa7f91aeee61f0fd59aa44e5ae24ecea3094844`;
  instrumentation APK: 1,317,057 bytes, SHA-256
  `3955e963314ee30d01992643859ddd850e4e2e3bef365b98223e96dab9bbb322`;
  stripped ARM64 `libslic3r.so`: 35,492,056 bytes, SHA-256
  `8d5adf950eeb1afe38ce66663e9b7d721bfea7d635b2d134464f6c3721bb71fe`.
- The current Bambu/Orca preset importer now translates the provider's
  current support vocabulary (`support_style`, `support_base_pattern`,
  `support_speed`, interface keys, and tree-branch keys) into the bounded
  native recipe Alloy actually consumes. `support_style=default` plus
  `support_type=tree(auto)` is preserved as organic tree support instead of
  being silently dropped. The new on-device regression is green at 9/9 and
  the full release instrumentation run is green at **132/132**. An isolated
  `support-speed=80` export was also measured: it remains 1,123 s / 960.03 mm,
  proving speed is not the current G3 geometry blocker. The Bambu support
  comparison and physical-send gates therefore remain closed. Current owner
  visual-review APK: 92,169,714 bytes, SHA-256
  `9c1d70663c414c5420df1231132198730048b0d933890a030f39ed89daf23ce8`;
  instrumentation APK: 1,317,689 bytes, SHA-256
  `af53381ad5292f9c75d062a3e1172458645eff1b93a739b590153c42ca7da266`;
  stripped ARM64 `libslic3r.so`: 35,492,312 bytes, SHA-256
  `8d5adf950eeb1afe38ce66663e9b7d721bfea7d635b2d134464f6c3721bb71fe`.
- The native tree-support volume boundary now carries the current Bambu
  `TreeModelVolumes` machine-border construction into every support layer,
  preserving the printable-bed hole and optional wrapping-exclusion area in
  the older embedded geometry API. This prevents support legs from being
  treated as valid outside the reviewed BuildVolume polygon. The ARM64 native
  build passed and the full release instrumentation suite remains **132/132**.
  A fresh overhang export still reports 147 support and 2 interface sections;
  against the controlled Bambu baseline it remains outside G3 at 1,774 s /
  858.59 mm versus 1,126 s / 960.03 mm, so native verification and physical
  send remain disabled. Current owner visual-review APK: 92,172,274 bytes,
  SHA-256 `8b580f46ecdae9bedb17d4fa8b7075390ae37754dd81bd2d3d299f82bd5e6224`;
  instrumentation APK: 1,317,689 bytes, SHA-256
  `af53381ad5292f9c75d062a3e1172458645eff1b93a739b590153c42ca7da266`;
  stripped ARM64 `libslic3r.so`: 35,494,872 bytes, SHA-256
  `2bd4286c69f62b0519a500b1d1b9ff71fff74aa14a92f60ba43d5cddcc0c49c7`.
- The follow-on Bambu support-modifier ordering audit is now source-integrated:
  painted blockers participate before sharp-tail/cantilever classification,
  manual enforcers enter the same overhang pass as auto support, and small
  painted regions are retained. The embedded API requires local
  `Polygons`/`ExPolygons` conversion and a post-insertion type-map rebuild.
  The new release build is 92,177,906 bytes, SHA-256
  `dc90da334c868c0d4f569c4e2ac04d02ba77564c0c0bf91d703d269c0c6a20a6`;
  instrumentation remains 1,317,689 bytes, SHA-256
  `af53381ad5292f9c75d062a3e1172458645eff1b93a739b590153c42ca7da266`;
  stripped ARM64 `libslic3r.so` is 35,500,504 bytes, SHA-256
  `9454e93ba8c411a79d895149ea3ac3e287900f09918cc05b9624c3ebcb8b9ff9`.
  Full instrumentation remains **132/132**. The controlled export is still
  1,126 seconds / 960.03 mm / 2.91 g with 147 support and 2 interface
  sections, so G3 and physical send remain closed.
- The same source-built checkpoint now retains every disconnected overhang
  island when grouping support regions on a layer, matching the pinned Bambu
  `TreeSupport3D` collection shape instead of silently overwriting same-layer
  entries. The native release rebuilt successfully and the emulator suite
  remains green at 131/131; fixture metrics were unchanged, so this is a
  correctness hardening change rather than evidence of Bambu support parity.
- The same source-built checkpoint also normalizes Orca's current organic-tree
  `default` base-pattern vocabulary to Bambu's tree policy, with hybrid trees
  retaining a rectilinear base. This is a source-backed profile correctness
  fix; the controlled fixtures are intentionally unchanged, so the measured
  G3 deltas and the physical-send decision remain unchanged. Current local
  artifacts are the owner-only visual-review APK
  `a33d7284dcafe3ca0797068ff92ec164ddae03f2af7d47d07f4ec4e301926b71`
  (92,154,098 bytes), release instrumentation APK
  `58cee6df01435df4f15bc2769690c957d7d5ed0e31526fceb50f456367b01ac9`
  (1,316,797 bytes), and ARM64 `libslic3r.so`
  `e1f3958f176f62c860e904313a035d1e128f673e362aaca9cf06e29b9cb3e8ca`
  (52,659,264 bytes).
- The native profile boundary now accepts an explicit imported Bambu
  `brim_type` from a bounded allowlist (`no_brim`, outer/inner variants,
  `auto_brim`, ears and painted). The shipped snapshot retains its explicit
  no-brim compatibility policy, while imported choices are no longer silently
  discarded. The same boundary now maps Bambu's `brim_separation` to the
  native `brim_object_gap` key instead of forwarding an ignored setting. The
  owner visual-review release rebuilt at 92,154,098 bytes with SHA-256
  `062414148da673e592b116142d45b94dec8a0a7665846bfca40fe8d91aeb2e84`;
  its instrumentation APK is 1,317,057 bytes with SHA-256
  `3955e963314ee30d01992643859ddd850e4e2e3bef365b98223e96dab9bbb322`.
  The full owner suite remains green at **131/131**. This is profile-boundary
  hardening, not auto-brim parity evidence; the geometry-dependent resolver
  and physical A1 Mini acceptance gate remain open.
- An opt-in `g3-brim-type=auto_brim` export was run on the same API-35 ARM64
  emulator. The native artifact correctly records `brim_type=auto_brim` and
  `brim_object_gap=0.1`, and produces no skirt/brim feature on the cube,
  support or thin-wall fixtures. Its measured results were 713 s / 1,224.39
  mm, 1,126 s / 960.03 mm, and 280 s / 859.86 mm respectively. The Bambu
  reference artifacts resolve these same cases to `brim_width=0`, while the
  native config still records the inherited process width of 5 mm. This is
  useful evidence that the enum route is viable, but the width-resolution
  mismatch means auto-brim remains unpromoted and the shipped compatibility
  policy remains unchanged.
- The profile surface now accepts one to thirty-two user-selected Bambu JSON preset
  documents (machine, process and/or filament), resolves inheritance when the
  selected chain is present, and persists an Alloy-normalized copy in app-private
  storage. Only allowlisted scalar settings reach the native adapter; A1 Mini
  machine limits are not borrowed for a different imported printer. If a multi-file
  import contains more than one unrelated active preset of the same type, Alloy
  rejects it with an explicit ambiguity error instead of trusting provider order;
  unsupported preset types, duplicate IDs and inheritance cycles are rejected
  before any native setting is projected.
  Imported profiles are deliberately marked **unverified**, invalidate any prior slice,
  and require review before physical printing. The new phone flow and security
  boundary are covered by the matching native-enabled release run: **131 tests,
  0 failures**, with the opt-in G3 export still skipped.
- The Bambu import projection now preserves the source-backed current Orca
  spellings for first-layer infill/travel and per-feature acceleration, in
  addition to Alloy's legacy compatibility names. The profile snapshot checker,
  importer regression, and native config contract verify these aliases against
  the pinned source values; the current release run remains **131/131**.
- Offline visualization now runs through the same provider seam as BYOK: the
  built-in renderer returns a bounded PNG with finish and environment cues
  without uploading geometry. The UI labels it as a deterministic studio
  renderer; a generative on-device model is not claimed until a signed mobile
  runtime and model are actually installed.
- The phone profile workflow now has explicit review, normalized JSON export, and
  reset-to-bundled actions. Reset removes only Alloy's private imported-profile
  override, restores the bundled A1 Mini / PLA profile, invalidates the current
  slice, and preserves saved models, plates, project archives, and inventory.
- Saved-model restore now fails soft when a document-provider URI is stale or
  contains an unsupported archive: the saved project checkpoint is retained for
  retry, while the phone loads the local 3D showcase so the workspace never
  becomes blank. The exact owner APK was launched after the final build and
  visually checked in both the preparation workspace and the immersive A1 Mini
  study.
- Foreground slices may be staged headlessly. The release handoff now detects
  that state and re-stages with a validated Bambu thumbnail before export or
  upload; instrumentation covers both the thumbnail-present and headless
  package cases.
- STEP/STP sources are now retained through the same boundary in batch request
  validation, active-job cache protection and portable `.alloy.zip` entries;
  the release suite includes a STEP archive round-trip regression. This keeps
  CAD imports usable offline rather than only previewable immediately after
  intake.
- The native A1 Mini machine-template boundary is now explicit. The Android
  artifact carries the `alloy-safe-a1-mini-v1` identity and rejects missing
  identity markers, reordered/out-of-section startup or shutdown commands, or
  unresolved template tokens before durable result/package writes. Escaped
  `end_gcode` text in the native diagnostic header cannot impersonate the real
  shutdown marker. The parity audit records the pinned upstream template hashes: start
  `ff13bf5a597336787df534926dfb3510491e0c21f945bf7436c118097dfa2d8a`
  (10,423 bytes) and end
  `cae467596f44df75473889ec5d8d99e9d2e1a99ea7b05e46653939b0a7256d24`
  (2,910 bytes). Both are **review-only** because they contain Bambu-specific
  commands and unresolved/conditional template language. This is a safety and
  provenance increment, not evidence that Alloy has reproduced Bambu's full
  startup calibration sequence.

## Primary candidate: OrcaSlicer-Mobile

Pinned candidate: `CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`

### G1 reproducible Android source build

**Status: HOSTED FAIL / LOCAL CLEAN-REPLAY PASS; NO PROMOTION.**

Path B G1 lean run `32660939450` reached the dependency-bootstrap preparation
step, patched the Android SDK/NDK paths successfully, then stopped because its
temporary Stage 1 STEP-removal script expected an older upstream
`load_step` dispatch shape (`got 0`). The checkout never reached dependency
compilation or APK assembly. Alloy now carries a fail-closed replacement
patcher in `ci/patch_slicebeam_stage1_no_step.py`. On 2026-09-06 it was replayed
from a clean archive of the exact pinned SliceBeam checkout, after the pinned
OrcaSlicer-Mobile bootstrap patch, and both patches completed successfully.
The workflow and parity validators are green locally. This is not hosted-build
evidence: the containing change must still be pushed and the hosted source
build re-run before G1 can advance.

### G2 exact OrcaSlicer engine provenance

**Status: FAIL / NO-GO.**

Hosted evidence from Alloy Actions run `32658450073`:

- mapped engine files shared with official OrcaSlicer: 488
- sampled files with an exact historical blob interval: 67 / 72
- best official OrcaSlicer candidate: `ff9ce434a24873c18fd3c996b48d7f4fc0ee06e6`
- exact common-tree matches at that candidate: **250 / 488 (51.23%)**
- required high-confidence threshold: 85%
- the closest-tree diff contains large divergences in core files including `GCode.cpp`, `GCodeProcessor.cpp`, `PrintConfig.cpp`, `Print.cpp`, `Preset.cpp`, `PresetBundle.cpp`, Arachne, support, wipe tower and model/config code

The bundled mobile `BBL.ini` Git blob was also **not found as an exact blob** in official OrcaSlicer history, so it cannot be used as a direct profile provenance pin.

This is not merely a small Android portability patch set around one identifiable Orca revision. Alloy therefore cannot truthfully claim the primary candidate tracks an exact official OrcaSlicer engine version under the adopted G2 criterion.

**Decision for the exact official-Orca provenance gate: primary candidate rejected.**

The subsequent Android-source investigation identified and integrated the
current experimental OrcaSlicer-Mobile candidate
`CodeMasterCody3D/OrcaSlicer-Mobile@d996a9cadb65b354997f2d5d8734b46bb9ea4efd`.
Its full `libslic3r` tree, including Orca `TreeSupport3D`, now builds and runs
through Alloy's Android JNI boundary. This closes the earlier "candidate not
integrated" gap, but does not change the exact official-Orca provenance result
or the Bambu `tree(auto)` support-parity gate.

## Path B: Orca-Mobile / SliceBeam / PrusaSlicer core

The current production-candidate native runtime is the pinned Orca-Mobile
source above. The earlier SliceBeam pin remains in the tree as its JNI/source
lineage and compatibility provenance; the complete runtime source now follows
the newer Orca support and geometry pipeline.

Pinned Android candidate:
`utkabobr/SliceBeam@12b370ce305acc2caa59b7e4e78e04069db2f7e3`

Path B must now pass the same discipline:

1. **G1:** reproducible arm64 Android source build.
2. **G2:** identify the closest exact PrusaSlicer engine revision by Git-blob provenance.
3. **G3:** slice the 20 mm cube, overhang/support fixture and thin-wall keyboard-case-like fixture; compare semantic toolpaths plus time/filament estimates.
4. **G4:** prove A1 Mini profile import/resolution fidelity. Because SliceBeam's native profile format is Prusa-oriented and Orca import is experimental, this is likely the hardest Path B gate.

Prepared automation on `stage1-gates-prep`:

- `.github/workflows/path-b-g1-slicebeam-source-build.yml`
- `.github/workflows/path-b-g2-prusa-provenance.yml`
- `ci/identify_slicebeam_prusa_engine.py`
- `parity/generate_fixtures.py`
- `parity/compare_gcode.py`
- `parity/profile_resolver.py`
- `parity/export_resolved_orca_profiles.py`
- `parity/build_path_b_prusa_config.py`

The local Path B G2 audit is now executable against the current official
PrusaSlicer tree, including both its legacy and nested `libslic3r` history
layouts. It finds 320 common mapped source files, but only 2/72 sampled files
have an exact historical blob interval. The closest tree is
`26e594cfb7e7e7e70ceb34d8262ab5e62d45f978` with only 25/320 exact matches
(7.81%), so high-confidence Prusa provenance remains **FAIL / NO-GO**. This
result is stronger than the earlier zero-file report, which was caused by the
auditor's outdated path assumption.

The release-candidate workflow now repeats the pinned native dependency
bootstrap, copies generated native inputs into a clean Alloy checkout, and
assembles the release APK with `alloyNativeEngine=true`. It still forces
`alloyNativeEngineVerified=false` until the runtime/parity/hardware gates pass.

The packaged Android A1 Mini/PLA recipe now carries the resolved scalar fields
needed by the native adapter (layer/shell/support settings, temperatures,
 motion, flow and cooling) plus an explicit `native_settings` map of 123 safe
scalar values and blob hashes for the three selected upstream profile files.
`ProfileCatalog` rejects non-scalar values, unsafe keys and oversized values;
`Profile.applyTo` projects the validated map into the native config while
retaining Alloy-owned start/end/layer G-code and conditional safety fallbacks.
Single-plate and batch foreground request snapshots now serialize and restore
that same bounded map, so the Android service receives the selected native
profile projection after Activity recreation instead of silently falling back
to core defaults.
Both request formats now also carry a deterministic recipe identity covering
the typed recipe and native map; the service-side readers reject a changed
recipe before slicing while retaining backwards readability for older
single-plate requests.
Four source-profile namespace values are normalized for this pinned core:
`no ironing` becomes the native `ironing=0` switch, `crosshatch` becomes
`grid`, `tree(auto)` becomes the supported `organic` support style, and
`enable_arc_fitting=1` becomes `arc_fitting=emit_center`. The asset remains
explicitly `verified: false` until the G4 audit and native runtime parity are
complete.

The latest local ARM64 API-35 emulator run completed the native CMake
compile/link and ran the full matching release instrumentation suite:
  **123 test cases, 0 failures**, with the optional G3 export skipped because
it was not requested. The suite also runs a loopback fake-printer transcript
through the real Android
LAN adapter, covering artifact upload, passive FTP transfer, remote-size
verification, MQTT project-file start, PREPARE/RUNNING/PAUSED/resumed/FINISH
telemetry, pause/resume acknowledgements, accepted-job cancellation back
to idle, MQTT reconnect/resubscribe without resending the start command,
malformed telemetry recovery, and bounded reconnect exhaustion to
`RECOVERY_REQUIRED` without resending the start command. It also exercises
ambiguous pause/resume/stop publish failures as recoverable outcomes and
passes a native-generated `.gcode.3mf` through the same fake-printer
transaction.
The Orca-Mobile native engine loaded the complete OCCT dependency chain, sliced
the bundled cube, box-and-lid assembly, mounting block, overhang/support
fixture and thin-wall frame fixture, validated the generated G-code, staged
the `.gcode.3mf` artifacts, released JNI handles cleanly, and verified the
profile-authority contract after correcting a supported spacing-field
overwrite. The same run also covered deterministic MQTT/FTP transport,
project/archive, inventory, lifecycle recovery, modeling and visualization
regressions. This is strong local runtime evidence, but not a passed G1
promotion: the hosted clean source build, exact PrusaSlicer provenance,
semantic fixture parity, full profile parity and physical A1 Mini gates remain
open.

The release APK and matching release instrumentation APK were then installed
fresh on the local API-35 ARM64 emulator under the explicit CI-only debug
signing switch, and **140 test cases completed with 0 failures** against those
exact artifacts. The opt-in G3 export remains a separate invocation. The run covered the cube,
box-and-lid assembly, mounting block, overhang/support fixture and thin-wall
fixture. Ordinary CI now validates this installable release variant; the
tag/manual release-candidate workflow remains unsigned until production
signing secrets are configured.

### G3 local evidence: still FAIL / NO-GO

The latest 2026-09-15 controlled recheck uses the effective Bambu project
recipe rather than the older stock-profile snapshot. The 47-field profile
identity preflight passes, and the native export now includes the effective
motion recipe instead of baseline role speeds and machine limits. The prior
G-code comparison was run before this change and is no longer authoritative;
the desktop reference must be regenerated or restored and compared against
this exact export. G3 therefore remains **FAIL / NO-GO** and no physical
printer action is enabled.

The optional native evidence export now runs on the same release APK and
passes its instrumentation test (1 / 1), including a genuinely
support-enabled overhang fixture. The semantic comparator was corrected to
read Bambu's `total estimated time`, `total filament length` and declared
layer-count headers, to normalize SliceBeam's numbered layer markers, and to
report documented cross-slicer feature-role aliases separately.

The fresh measured results are not parity-grade yet. A 2026-09-09 recheck
used the installed Bambu Studio `02.08.02.61` CLI, fully resolved machine /
process / filament snapshots, and an explicit support-enabled process variant
for the overhang fixture. The earlier 20% infill / 2 mm³/s workstation drift
is therefore no longer part of the current baseline:

| Fixture | Bambu model time / filament | Android model time / filament | Result |
| --- | --- | --- | --- |
| cube, 100 layers | 728 s / 1,227.14 mm | 718 s / 1,213.18 mm | time +1.37%, filament +1.14% |
| support-enabled overhang, 170 layers | 1,047 s / 933.39 mm | 1,228 s / 899.44 mm | time +14.74%, filament −3.64% |
| thin-wall frame, 40 layers | 295 s / 905.48 mm | 274 s / 876.32 mm | time −7.12%, filament −3.22% |

The cube is inside the 2% estimate gate, but the support and thin-wall cases
are not. The overhang feature-transition sequence and all 46 profile-identity
fields now match after the explicit per-fixture support policy, while the
engines still emit materially different support paths and move counts. G3 is
still **FAIL / NO-GO**; native engine verification remains disabled.

Each fixture comparison now carries a profile-identity preflight:
`parity/verify_g3_profile.py` reads Bambu's `Metadata/project_settings.config`
and the exact native `config.ini`, normalizes only the documented field and
vocabulary aliases, and fails on missing or changed source-backed values. The
current cube, support and thin-wall evidence pairs all pass this 46-field
identity check, and `compare_gcode.py` can now embed that result as a hard
check in the same JSON report. This prevents a future parity run from turning
workstation profile-registry drift into an engine claim; it does not relax the
G-code or physical-printer gates below.

That table is the last completed comparison from the older SliceBeam-shaped
runtime. After the Orca-Mobile source integration, a fresh Android evidence
export on 2026-09-10 established this new native baseline:

| Fixture | Observed layers | Native time | Native filament |
| --- | ---: | ---: | ---: |
| cube | 100 | 11m 55s | 1,224.39 mm / 3.71 g |
| support-enabled overhang | 170 | 18m 46s | 960.03 mm / 2.91 g |
| thin-wall frame | 40 | 4m 41s | 859.86 mm / 2.61 g |

The current Orca output preserves safe A1 Mini motion envelopes and valid
`G2`/`G3` moves. A like-for-like comparator rerun against installed Bambu
Studio `02.08.02.61` now gives a controlled cube pass: 719 s versus 715 s
(0.56%), 1,237.32 mm versus 1,224.39 mm (1.05%), and 3.75 g versus 3.71 g
(1.07%). The bridge naming difference is reported explicitly as Bambu
`Bridge` versus native `Internal bridge`; it is not silently discarded.

The controlled support artifact uses the same explicit tree wall-count value
(`0`) and passes all 46 profile-identity fields. After the current-vocabulary
projection corrected the source-backed organic base pattern and interface
layer counts, Android reports 1,126 s versus 1,047 s (7.02%), 960.03 mm versus
933.39 mm (2.77%), and 2.91 g versus 2.83 g (2.75%). Its feature counts now
match the Bambu section counts for support (147) and interfaces (2); positive
extrusion accounting is within 1.04%, but path segmentation and motion timing
remain outside the gate. The thin-wall control remains outside the gate at
281 s versus 295 s (4.75%) and 859.86 mm versus 905.48 mm (5.04%). G3
therefore remains **FAIL / NO-GO**; the cube result is evidence of successful
mapping corrections, not a promotion.

A controlled 2026-09-11 source experiment also enabled the Bambu-shaped generic
overhang-area generator already present in the port and ran the same release
evidence export. It compiled and passed the 123-test suite, but produced the
same 715 s / 1,224.39 mm cube, 1,126 s / 960.03 mm support, and 281 s /
859.86 mm thin-wall metrics while taking roughly 32 minutes on the emulator.
The faster smart-overhang route is therefore retained; the Bambu source route
is not promoted based on name similarity or compile success alone.

The follow-up export after translating Alloy's legacy support settings into
Orca's current vocabulary now emits explicit support toolpaths: the overhang
fixture contains 147 `Support` and 2 `Support interface` sections across its
170 layers, while the cube and thin-wall controls remain support-free. This
confirms that the integrated TreeSupport3D path and the source-backed
interface-layer policy are selected; it does not yet prove Bambu's support
geometry, motion ordering, time estimate or filament accounting, so the
physical-send gate remains closed.

The native parser keeps valid `G2`/`G3` center-format moves visible in the phone
toolpath preview by discretizing them into bounded display segments; it does
not rewrite the printer artifact. The pinned A1 Mini process enables arc
fitting, so `enable_arc_fitting=1` is now projected losslessly to the native
`arc_fitting=emit_center` enum alongside the exact `ironing_flowrate` and
`spiral_vase` projections. This is source-backed configuration fidelity, not a
parity claim: the controlled cube now passes the measured time/material checks,
while support path/material accounting and thin-wall timing/material accounting
remain outside the G3 tolerance.

The latest increment preserves Bambu's per-filament volumetric-cap semantics
(`filament_max_volumetric_speed=21`, global cap `0`) and hardens BYOK HTTPS
redirect handling. The fresh native export confirms the redirect/security
change and the cap-scope correction do not materially change the measured G3
deltas. The remaining tuning target is native motion/cooling and support
semantics, not evidence freshness.

A bounded cooling-buffer sweep on the thin-wall fixture isolated that timing
gap without changing geometry or material accounting: native
`slowdown_below_layer_time` values of 0, 6, 7 and 8 seconds produced 186 s,
275 s, 312 s and 349 s respectively, while the Bambu reference reports 320 s.
The pinned source value is 6 seconds, so the 7-second near-match is not
promoted as a magic compatibility override; it would change the source
profile semantics and still leaves the filament accounting outside the gate.
The experiment is retained only in the on-device evidence harness so future
work can address the native cooling/travel estimator with a source-backed
mapping.
The profile-fidelity increment also projects Bambu's
`sparse_infill_acceleration=100%` into the native engine's resolved
`infill_acceleration=6000` value (100% of the pinned 6,000 mm/s² default).
The packaged asset and typed-recipe contract test both verify that value, and
the fresh native export shows no material G3 change; this is an accounting and
packaging correction, not a parity improvement.
The follow-up current-vocabulary projection also carries the source-backed
`gap_fill_speed` into Orca's `gap_infill_speed`, and mirrors the pinned line
width, per-feature acceleration and zero-skirt values. On the fresh export this
removed the native default skirt and moved the cube from a 7.82% time miss to
the controlled 0.56% pass; the thin-wall and support fixtures still require
engine-level work.
Two additional on-device support experiments were run against the same
support-enabled overhang fixture: `support_material_pattern=rectilinear-grid`
and `support_material_pattern=honeycomb`, with the source-backed
`support_material_style=organic` held constant. Both produced the same
170-layer, 1,227-second, 899.15 mm result as `rectilinear`; neither changed
the 3.63% filament delta or the feature-class result. This confirms that
Bambu's resolved `support_base_pattern=default` normalization to native
`rectilinear` is not the current mismatch, and no experimental pattern is
promoted into the product default.
The follow-up native experiment also allow-listed SliceBeam's
`support_tree_branch_distance` and injected the Bambu-reference value `5`.
The serialized config confirmed the setting and the G-code hash changed, but
the measured result remained 170 layers, 1,227 seconds and 899.15 mm, so the
3.63% filament delta did not improve. Because the pinned Orca process snapshot
does not contain this source field, it remains an experiment rather than a
profile default or a claim of Bambu equivalence.
The next controlled experiment also allow-listed SliceBeam's
`support_tree_branch_diameter_angle` and injected the Bambu-reference value
`5`. The serialized config confirmed both tree settings, but the resulting
G-code metrics and move counts were unchanged: 170 layers, 1,227 seconds and
899.15 mm versus Bambu's 1,047 seconds and 933.39 mm. The angle setting is
therefore retained only as an explicit review-harness experiment and is not
promoted into the shipped profile.
For completeness, the harness also exercised SliceBeam's legacy `tree` style
against the same Bambu `tree(auto)` reference. It produced 170 layers,
1,225 seconds and 911.68 mm: closer in filament than the Prusa `organic`
mapping, but still outside the time and material gates and semantically a
different support mode. The exact Bambu Studio 2.8.2.61 source routes
`tree(auto)` through its separate `TreeSupport3D` implementation
([TreeSupport.cpp](https://github.com/bambulab/BambuStudio/blob/v02.08.02.61/src/libslic3r/Support/TreeSupport.cpp),
[TreeSupport3D.cpp](https://github.com/bambulab/BambuStudio/blob/v02.08.02.61/src/libslic3r/Support/TreeSupport3D.cpp));
the older pinned SliceBeam tree does not contain that implementation, while
the integrated Orca-Mobile tree contains Orca's distinct implementation. The
next parity milestone is therefore an isolated, license-complete Bambu
TreeSupport3D port or an exact compatible upstream engine import, followed by
the same fixture and physical-printer gates. No legacy-style workaround is
promoted into the product profile.

The first reproducible port-preparation audit is now checked in as
`ci/audit_bambu_treesupport3d.py`. Against the pinned official checkout
`v02.08.02.61` / `926a7192574bcb9b3a732e1ec59a46d79cb45466`, it records the
seven source-file hashes and line counts, the 36-file direct quoted-include
surface, and now follows that surface transitively through 89 quoted-header
dependencies: 19 are absent from the current Alloy tree and 69 differ at the
same path. It returns `PORT_REQUIRED` until an actual coherent Bambu
TreeSupport3D implementation is present and routed by the native engine. This
prevents a future profile change from silently treating the legacy `tree`
experiment as Bambu parity.
The latest bounded support calibration sweep found one promising but
unpromoted candidate: `support_material_extrusion_width=0.5` produced 170
layers, 1,228 seconds and 937.01 mm on the same fixture, versus Bambu's 1,204
seconds and 933.04 mm (1.95% time and 0.42% filament-length deltas). The
candidate is intentionally not shipped because the pinned source profile says
`0.42`, the wider value is an engine calibration rather than a source-backed
profile mapping, and no physical A1 Mini result has established that it is
safe or behaviorally equivalent. This narrows the remaining G3 work to
calibration policy plus physical validation instead of silently changing the
user's recipe.
The profile increment projects Bambu's `minimum_sparse_infill_area=15` into
SliceBeam's `solid_infill_below_area=15`, and projects
`tree_support_wall_count=0` into
`support_tree_branch_diameter_double_wall=0`. Those source-backed mappings
reduced the support delta substantially and brought overhang time within the
gate, but they do not close the remaining filament/path differences.
The Android physical-send checklist now has a separate support-parity gate:
automatic supports cannot become printable merely because the general native
engine is marked verified. Until the result comes from a verified Bambu
`TreeSupport3D` engine, the legacy SliceBeam organic/tree result is exportable
for inspection but remains blocked at the physical-send boundary and carries
its support-engine provenance in package metadata.
The earlier 20% versus 15% discrepancy was a workstation profile-registry
drift; it is now isolated by generating the desktop reference from the pinned,
fully resolved snapshots rather than silently changing Alloy's recipe.

The native adapter now explicitly normalizes Bambu's `auto_brim` policy to
SliceBeam's `no_brim` enum. Without that translation, the pinned core silently
used its default `outer_only` mode and emitted a fixed 5 mm brim. This removes
an unintended toolpath feature from the comparison. The adapter now also
zeroes the paired legacy `brim_width`; otherwise the classic generator reverses
the first-layer perimeter order even when `brim_type=no_brim`. The latest
The previous 94/94 release run confirmed the corrected order, but a geometry-dependent
auto-brim resolver is still required before the profile can be called fully
equivalent.

The native artifact now also emits the profile-backed A1 Mini motion envelope
(`M201`, `M203` and `M205`) instead of leaving those limits implicit in the
engine configuration. The adapter now selects SliceBeam's `marlin2` mode as
well, so the emitted `M204 P/R/T` fields retain Bambu's separate print,
retract and travel acceleration semantics; the former legacy `marlin` mode
would encode `T` as retract acceleration. This is a
printer-safety/interoperability increment; it does not change the native
engine's measured G3 parity result or promote the physical-send gate.

Until these deltas are resolved against the same pinned profile and a
support-enabled reference, the native engine remains `NATIVE_ENGINE_VERIFIED=false`
and Alloy must not be presented as a production Bambu Studio replacement.

The latest source audit ported Bambu's painted support-modifier ordering into
the embedded tree-support path: blockers now participate before sharp-tail
and cantilever classification, manual enforcers enter the same overhang pass
as auto support, and small painted regions are retained instead of being
discarded as ordinary small overhangs. Alloy's older geometry API required
`Polygons`/`ExPolygons` conversion and a post-insertion type-map rebuild; the
native release compiles and the full emulator suite remains **132/132**. The
fresh controlled support export is unchanged at 170 layers, 1,126 seconds,
960.03 mm and 2.91 g versus the pinned Bambu reference's 1,774 seconds,
858.59 mm and 0 g in the comparator, so this correctness increment does not
close G3. The support parity and physical-send gates remain closed.

The release and optional-native workflows now clone the pinned official Orca
profile revision and run `ci/verify_profile_snapshot.py`. That check verifies
all 11 listed source blobs, the 26 typed projected values and 123 source-backed
native scalar projections after resolving the machine, process and filament
inheritance chains. It makes profile drift a CI failure, but it does not
substitute for native G-code parity or a physical printer test.

A local static Path B G4 audit against the pinned SliceBeam checkout also
passes its declared critical A1 Mini mappings. The audit parses 593 native
configuration keys and resolves 164 supported profile fields; **211 source
profile fields remain explicitly unsupported** by the current adapter and are
not silently treated as equivalent. The latest increment adds an explicit,
commit-bound review scope in `parity/a1mini_g4_scope.json` and
`ci/validate_g4_scope.py`; the validator now fails if any unsupported resolved
field is missing from that scope, if a supported field is excluded, or if a
required critical field is not supported. This closes the static accounting
gap, but it does not turn omissions into parity: native runtime, semantic
toolpath/estimate comparison and physical-printer validation remain required,
so G4 remains open and the native engine remains unverified.

## Product/UI gate

### Android modeling and visualization increment

The phone shell now includes a bounded parametric workbench for box, chamfered
box, open-top enclosure, cylinder, sphere, wedge and tube primitives, convex sketch extrusion, additive
assemblies, native OCCT union/subtract/intersect editing against a bounded
primitive tool in the optional native build, and a durable per-plate undo/redo
snapshot timeline for printable edits. That timeline is also portable in the
project archive, including its cursor and historical model bytes. It includes
deterministic STL materialization and instrumentation coverage for
mesh/STL/storage round trips.
The same workbench now offers X/Y footprint-centre mirroring as a validated
geometry edit with corrected triangle winding and named-part preservation.
The existing viewport and slicer paths consume generated geometry through the
same validation boundary as imported files.
Boolean inputs are restricted to one watertight solid each and invalid or
empty results are rejected. History snapshots are bounded, validated and
retain referenced cache files across process reload; they deliberately do not
claim a full desktop-CAD feature tree.

The visualization boundary is also present. The on-device renderer can show a
private studio preview with bounded finish and environment presets, while a user-configured BYOK HTTPS image-edit provider
can receive only a bounded rendered thumbnail and prompt. API keys are stored
with a separate Android Keystore key. A generative on-device model is **not
bundled yet** and is explicitly reported unavailable; it must not be implied by
the local preview. Broader CAD operations, sketch constraints, a richer
feature-tree history and a signed local model runtime remain future gates.

The Android-native phone-first shell is now present as a Views implementation
with import, multi-object 3MF assembly view, preparation, persisted per-part
scale/rotation/X/Y transforms, durable per-plate undo/redo, offline fallback
slicing, inspection, export, inventory, encrypted pairing storage and an
opt-in native-engine/transport boundary. The current build also supports bounded global and per-part X/Y
orientation, conservative multi-part auto-arrangement, and persists up to eight independent plate
snapshots with their model sources and transforms, allowing separate boxes,
lids and parts to be prepared in one phone project. This is still not a release
acceptance: native CI, profile parity, package compatibility, and physical
A1 Mini upload/start/cancel tests remain open.

The print-recipe surface now exposes the allowlisted support controls directly
on the phone: organic/slim-tree or grid style, top and bottom interface layers,
independent top and bottom contact gaps, XY spacing, support/interface speeds,
and tree branch angle, distance, diameter, thickening angle and wall mode.
Values persist with the project recipe and are still subject to the same native
profile and physical-send gates.

The same recipe surface now exposes bounded Bambu/Orca path controls for top and
bottom surface patterns, seam placement, ironing, bridge speed and default
acceleration. These values are written through the existing allowlist and native
projection, so they remain part of the portable project recipe rather than UI-only
preferences.

### Alloy-owned 3D viewport increment

**Status: IMPLEMENTED / LOCAL EMULATOR VISUAL QA PASSED / PHYSICAL-DEVICE QA OPEN.**

The former Canvas-only inspection surface has been replaced by an Alloy-owned
GLES 2.0 renderer hosted behind `ViewportView`. It renders sampled mesh
triangles with perspective lighting, a neutral 180 × 180 × 180 mm machine
presentation envelope, active-layer toolpaths, selected-part focus and a
clear grey out-of-volume state. One-finger orbit, pinch zoom and two-finger
pan remain available on the phone; the host forwards lifecycle pause/resume to
the GL surface. Thumbnails are generated through a bounded CPU path so archive
and printer artifacts do not depend on a frame callback.

The immersive study surface now has explicit Hero and Machine framing modes.
Hero keeps the selected part large and centered for the supplied configurator
style, using an Alloy-owned light studio plinth, differentiated materials and a
bounded contact shadow; Machine uses the supplied 16,054-triangle mesh in
visual-review debug builds and falls back to an Alloy-owned, presentation-only
A1 Mini frame, gantry, carriage, toolhead/nozzle, filament spool and
control-panel detail in release builds. This keeps the unlicensed reference
mesh out of a public release while letting visual-review builds show the
supplied model. Ordinary builds use the seven-part authored assembly as the
first-run showcase; owner visual-review builds surface the supplied Redmagic
editable STEP assembly first when the native OCCT importer is present (falling
back to the main chassis STL otherwise) and keep the complete owner print set,
A1 reference study and authored assembly available from the model workspace.
The Model
Atlas exposes the bundled models through a live selectable 3D
preview with provenance and checksum status rather than a text-only picker.
The main action rail now calls this surface **Model atlas**, and the header
**3D** action opens the current model's Hero view (falling back to the atlas
when no model exists). Choosing **Use & open 3D** from the atlas loads the
selected bundled model and lands directly in that Hero view.

The owner visual-review catalog now also carries the exact supplied Bambu Lab
A1 Mini v5 3MF as a private, checksum-verified model-library item. Selecting
it follows the normal content-addressed import and live 3D path, so the machine
reference is discoverable in the same gallery as the Redmagic parts rather than
only through the standalone presentation study. Public/ordinary artifacts still
omit this owner-supplied file.

The project menu now also exposes a standalone **A1 Mini 3D Study**. It renders the
printer reference without requiring an imported print model, labels whether the
debug build is using the supplied reference mesh or the release-safe Alloy
study model, and keeps the presentation-only boundary visible in the footer.
The immersive Hero footer now exposes the same **A1 study** route, and the
standalone study exposes **Import supplied 3MF**, making the supplied machine
reference reachable directly from the phone.
The updated debug APK was installed and exercised on the API-35 ARM64 emulator;
the dedicated study now presents the reference mesh with stronger opaque
contrast while the combined print-in-machine view remains translucent.

On 2026-09-07 the updated debug APK was installed on the API-35 ARM64
emulator and the immersive study was inspected in both Hero and Machine modes,
including the supplied reference mesh and its translucent machine presentation.
This confirms the visual path and shader branches on Android, but it is not a
substitute for physical-device GPU coverage.

The presentation pass also adds a release-safe GPU studio field: Hero mode no
longer renders a full-size technical slab, and both the model study and
standalone printer study expose bounded finish and light/dark-stage controls.
The supplied-machine study also carries procedural PEI grain and small
Alloy-authored can/ball/key scale props, matching the handoff's dimensional
presentation without making those objects part of a print. These controls are
renderer-only and are deliberately excluded from model, recipe, slicing and
printer-command state.

The debug reference loader now preserves the handoff's baked per-corner
normals (including its 42-degree crease policy) after converting the source
scene axes to Alloy's print-volume axes. Generic meshes receive the same
bounded crease-aware display-normal fallback, and the A1 study applies
presentation-only PEI, frame, carriage/nozzle and spool material regions plus
a restrained key/fill/rim studio-light pass for the product-study view.
The bounded CPU thumbnail path used by project archives and BYOK references
now mirrors that presentation direction with perspective depth ordering,
per-part material separation, a contact shadow and a three-point light pass;
it remains presentation-only and never substitutes for the live GLES scene.
The updated debug APK was reinstalled on the API-35 ARM64 emulator and the
standalone study was visually rechecked; this improves the supplied study's
readability but is not a licensed vendor-CAD replacement or a physical-device
GPU acceptance.

On 2026-09-08 a real supplied Redmagic keyboard chassis STEP source was
imported through Android's document picker. Native OCCT conversion produced a
non-empty watertight mesh (6,428 triangles), and the new-import handoff opened
the object-first Hero view automatically. Long CAD filenames are shortened to
friendly presentation names so the title does not collide with the floating
plate callout. This is visual/import evidence only; it does not promote the
native slicer or physical A1 Mini print path.

The same day, the Hero renderer was corrected so centred CAD coordinates (for
example X=-84..84 on the chassis) no longer trigger the technical
out-of-volume warning material in the presentation-only view. Prepare and
Machine views continue to evaluate the actual leveled placement and retain the
warning when a print is genuinely outside the build volume. A fresh private
release APK was installed on the API-35 ARM64 emulator and visually confirmed
with the real chassis showing its material palette and shaded geometry.

Preparation now includes a bounded **Scale to known dimension** helper for
unitless STL/OBJ sources. Plate-level scale accepts 10%–10,000%, persists
through plate snapshots, batch requests, portable project archives and
restart recovery, and still blocks any result that exceeds the configured A1
Mini build volume. This is user-confirmed unit calibration; Alloy does not
guess whether arbitrary STL/OBJ coordinates represent inches, centimetres or
millimetres.

The latest visual-review pass adds a renderer-only **Explode / Assemble**
control for multi-part assemblies. It spaces logical parts just for visual
inspection, preserves the original model coordinates for preparation and
slicing, and was exercised on the Redmagic STEP assembly on the API-35
emulator. The phone layout also moves the plate badge into its own lane so the
assembly title remains readable on a narrow portrait screen.

Preparation now adds a bounded broad-phase review for multi-part plates. It
flags positive 3D axis-aligned bounding-box overlap between distinct logical
parts, while treating face contact as non-overlap and keeping the result a
review warning rather than pretending an AABB is exact solid-collision proof.
This catches assembled CAD that has not yet been separated for printing;
Explode remains presentation-only and Auto-arrange/manual placement remain the
printable resolution paths.

This is a renderer milestone, not slicer or printer evidence. Exact collision
geometry, foldable/desktop hover and resize behavior, full-resolution mesh
performance and physical-device visual QA remain open. The machine envelope
is presentation-only and cannot authorize a print.

The next preparation increment replaces the earlier simple row walk with a
bounded, deterministic best-fit shelf packer. **Arrange** now considers both
0° and 90° XY placements, preserves the user's scale and tilt values, keeps a
nozzle-derived clearance, and applies placements back in source-part order.
Automatic Z-up/tilt changes remain deliberately explicit because they can
change supports and first-layer behavior. The planner has connected release
regression coverage for fit, clearance, deterministic ordering and oversized
part rejection; this improves preparation but does not close native parity or
physical-printer gates.

The ordinary phone workflow now has an API-35 emulator acceptance test
(`AndroidPipelineTest`) covering model import, fallback slicing, `.gcode.3mf`
staging/validation, project checkpoint reload, and inventory service reload.
That test proves the local workflow only; it does not promote fallback output
or remove the native/profile/hardware gates above.

The printer surface now exposes a deterministic **Print readiness** report in
the action rail and A1 Mini status dialog. It lists the native-engine and
profile promotion gates, verified slice and package, support parity when
supports are requested, pairing, leaf-certificate pin, and recovery lock
independently, and re-evaluates the same facts at the send boundary. This turns
a hidden disabled action into an actionable phone workflow; it does not weaken
any gate or claim that the physical A1 Mini test has passed.

The same boundary now persists the Bambu discovery model code with the
encrypted printer credentials and requires `N1` (A1 Mini) before any physical
upload or control operation. Manual pairing may still enter a model code, but
an empty or different code remains visibly blocked; older credentials decode
without error and require explicit model confirmation on the next pairing.

The credential round-trip is covered on the API-35 emulator. The AES-GCM
implementation lets Android Keystore generate the encryption IV and persists
that generated IV with the ciphertext; caller-supplied IVs are rejected by
the platform and are no longer used.

The Bambu telemetry monitor now delegates packet decisions to a pure
`PrinterTelemetryReducer`. The API-35 release suite replays matching and
unrelated PREPARE/RUNNING/PAUSED/FINISH/IDLE reports, verifies the pause/resume
acknowledgements, requires prior job identity before accepting cancellation,
and exercises bounded reconnect/resubscribe after an accepted job's MQTT
socket drop, malformed telemetry recovery, and reconnect exhaustion to
`RECOVERY_REQUIRED`. The latest native-enabled suite ran 123 test cases with 0 failures; only the optional
G3 evidence export was skipped. Earlier native-disabled runs skipped the
native-only smoke tests. Replay coverage improves the transport seam but does
not replace a real A1 Mini LAN session.

Printer transactions now have a durable `PrinterJobStore` checkpoint. A
process restart promotes any in-flight upload/start/run/cancel record to
`RECOVERY_REQUIRED`; the UI blocks a new send until the user reviews and
explicitly dismisses the unconfirmed record. The successfully revalidated
staged artifact is kept separate from the currently loaded model, and the
review action remains visible before a new slice is loaded. This improves
interruption behavior but still requires a device test with real printer
telemetry.

The paired-printer screen can also request one read-only MQTT telemetry
snapshot, showing bounded state, progress, nozzle/bed temperatures and
remaining minutes without changing printer state. It remains informational
until the physical transport gate confirms the target firmware's report
vocabulary.

Physical upload and print start now require a paired SHA-256 leaf-certificate
pin at both the Activity and foreground-service boundaries. Read-only pairing
and telemetry probing may still use the platform trust store while a user is
discovering the printer, but a Bambu self-signed LAN certificate cannot reach
the physical-print path without an explicit pin.

The LAN boundary now also applies one canonical host grammar across discovery,
pairing, durable credentials and transport targets. IPv4, IPv6 (including a
bounded link-local zone identifier) and DNS/mDNS names are accepted; URL
schemes, ports, paths, credentials, malformed IPv4 and bracketed non-IPv6
values are rejected before socket/MQTT use. The release instrumentation suite
covers these cases. This is a transport-hardening increment, not physical
printer evidence.

The recent model shelf has a capped reserve of one quarter of the model-cache
budget during pruning. Newest validated imports can therefore be reopened
offline without allowing the shelf alone to consume the whole cache budget;
stale shelf records are evicted when their cache files disappear. Active
plates, in-flight batch requests and undo/history sources retain stronger
protection when the cache is under pressure.

Before `START_REQUESTED` is persisted or MQTT `project_file` is published,
the foreground service now revalidates the local artifact identity and the
printer's remote file size over the pinned FTPS channel. If the remote file is
missing or replaced, the start is blocked and the durable checkpoint moves to
`RECOVERY_REQUIRED`, requiring a fresh upload. The loopback fake-printer test
asserts both the upload-time and pre-start remote-size checks.

Once the MQTT `project_file` packet is attempted, any subsequent write or
telemetry failure is also reported as `RECOVERY_REQUIRED`, because delivery is
ambiguous and an automatic retry could duplicate a physical print. The
loopback suite simulates a failed start-packet flush and verifies that Alloy
does not expose it as a retryable `FAILED` state.

The same rule now applies to pause, resume and stop packets: once a control
publish is attempted, a write failure becomes `RECOVERY_REQUIRED` and is not
silently retried. The release loopback suite covers all three ambiguous
control-command paths. Stop now shares the transport control lock with
pause/resume, so two physical commands cannot interleave or race while the
pending-control state is being updated.

The active printer session also has pause/resume controls. Alloy records
`PAUSE_REQUESTED` or `RESUME_REQUESTED` first and changes the durable state to
`PAUSED` or `RUNNING` only after matching job telemetry confirms the transition;
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
