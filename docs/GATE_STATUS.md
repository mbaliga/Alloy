Warning: truncated output (original token count: 61902)
Total output lines: 3624

# Latest exact-head status — 2026-10-22

## Corrected exact-head run — d763b1c — native instrumentation PASS, strict G3 FAIL / NO-GO

The latest tested code candidate is [d763b1c](https://github.com/mbaliga/Alloy/commit/d763b1cf87326edd0e2042717d05df1efc30c24e). PR heads [c79397b](https://github.com/mbaliga/Alloy/commit/c79397bf8aa6c2d466ea0b594c593070fc0fe2ef) and [2e96802](https://github.com/mbaliga/Alloy/commit/2e96802c884ac015cffb2d9e5e5d1232b7280d43) only update documentation; CI is rerunning on 2e96802. App/runtime remains at `411bc3499d4ef864347e68e6b5f0df14a9544cc9`; all changes since are verification/documentation only, with no production slicing, G-code, printer-send, or threshold change.

Android acceptance [39736852494](https://github.com/mbaliga/Alloy/actions/runs/39736852494) passed; software-QA APK [12408246894](https://github.com/mbaliga/Alloy/actions/runs/39736852494/artifacts/12408246894), archive SHA-256 `7f5f1e3e799aa789b82af6dc90354b3c4ca778cf5f08d63470c8529aef0a5232`. Native build and API-35 instrumentation, G3 export, and evidence validation completed in [39736852448](https://github.com/mbaliga/Alloy/actions/runs/39736852448). The exact-head strict comparator **failed** at the unchanged 2% limit; report bundle [12411345286](https://github.com/mbaliga/Alloy/actions/runs/39736852448/artifacts/12411345286). All 63 profile identity fields pass on all fixtures. Time deltas remain cube 14.31% (1775/1521 s), supported overhang 17.77% (2048/1684 s), thin wall 13.23% (1330/1154 s), and travel obstacle 9.43% (3393/3073 s). Filament length deltas: 2.09%, 2.59%, 2.65%, and 1.17%; corresponding mass deltas: 2.03%, 2.47%, 2.58%, and 1.29%. Therefore **no slicing artifact from this candidate is qualified for print, direct send, or Bambu Handy**.

The correction resolves the 3MF row-vector transform convention and makes the retained desktop mesh equal the checked-in STL after transform/plate-translation normalization. Focused tests pass 9/9; full parity tooling passes 203/203. Deposited path totals on the travel-obstacle fixture now match by feature, but travel routing and estimate still fail. Diagnostic report indicates 83,655 vs 113,666 mm total XYZ motion and 70.8 vs 188.2 s nominal travel time; these are G-code diagnostics, not firmware execution measurements. A hook-neutralized counterfactual removes large conditional desktop travel but is explicitly non-production and does not establish printer behavior. The default BBL-mode toggle and template-only probes are also diagnostic, not fixes. Do not copy Bambu macros or adjust planner/estimator/tolerance based on these probes alone.

Latest software QA package is safe for UI/import/planning workflow verification only. Physical A1 Mini pilot, Bambu Handy acceptance, A1/P1S runtime qualification, full source/license provenance review, production signing, and release approval remain open. See the checklist below; it must not be used to print or share a job.

---

## Native G3 follow-up — exact parent head e6d0dd1

The tested parent was [e6d0dd16ca9f2009d8e2b4694ff18e4469c93ffc](https://github.com/mbaliga/Alloy/commit/e6d0dd16ca9f2009d8e2b4694ff18e4469c93ffc). Android acceptance passed in [run 39725057362](https://github.com/mbaliga/Alloy/actions/runs/39725057362); native API-35 instrumentation and all four native G3 exports completed in [run 39725057371](https://github.com/mbaliga/Alloy/actions/runs/39725057371). The exact-head strict comparator **failed**; the 2% tolerance is unchanged. Its report is [artifact 12404627846](https://github.com/mbaliga/Alloy/actions/runs/39725057371/artifacts/12404627846), ZIP SHA-256 `706093c3184343bbe9bd5d91f5c594871fb99340e7163b4e833655eb50b005f5`.

All four fixtures pass the 63-field profile identity check, but fail time: cube 1,775/1,520 s (14.37%), supported overhang 2,048/1,680 s (17.97%), thin wall 1,330/1,154 s (13.23%), and travel obstacle 3,393/3,072 s (9.46%). Filament length misses 2% on the first three (2.10%, 2.75%, 2.65%); travel obstacle is within tolerance at 1.04%. This remains **NO-GO** for print/share.

Correction to the earlier transform note: its local validator treated the 3MF 12-value transform as a column-vector matrix, so the reported +90° conclusion was wrong. The 3MF Core Specification defines a row-major 4×3 affine transform with translation in the final row; interpreted with row-vector points, the retained desktop reference encodes −90°. The test-only contract and geometry validator are corrected to that convention and now include a nested-transform regression. See the [3MF Core Specification](https://github.com/3MFConsortium/spec_core/blob/master/3MF%20Core%20Specification.md). The desktop mesh is still checked against the test STL with plate translation removed; do not use the earlier +90° comparison or the ordered-anchor count as evidence of geometry loss.

This follow-up adds a fail-closed automated 3MF-vs-STL geometry guard to the exact-head G3 comparator, including external 3MF component resolution and spec-conformant transform composition. It changes no production slicer, G-code, printer-send behavior, or tolerance. Focused transform/geometry tests pass locally; the full parity suite and hosted validation are pending on this corrected contract. Next: re-run exact-head native G3, then add translation/rotation-independent per-layer toolpath comparison and investigate remaining output differences; do not modify production path planning until that isolates causality.

Native G3 artifact [12404722578](https://github.com/mbaliga/Alloy/actions/runs/39725057371/artifacts/12404722578), native instrumentation report [12405236655](https://github.com/mbaliga/Alloy/actions/runs/39725057371/artifacts/12405236655), and Android QA APK [12402729529](https://github.com/mbaliga/Alloy/actions/runs/39725057362/artifacts/12402729529) are software evidence only. Direct send, Bambu Handy, physical A1 Mini acceptance, A1/P1S qualification, production signing, and release approval remain open.

---

PR head at the time of this G3 run: [e6d0dd16ca9f2009d8e2b4694ff18e4469c93ffc](https://github.com/mbaliga/Alloy/commit/e6d0dd16ca9f2009d8e2b4694ff18e4469c93ffc). The application/runtime source checkpoint remains [411bc3499d4ef864347e68e6b5f0df14a9544cc9](https://github.com/mbaliga/Alloy/commit/411bc3499d4ef864347e68e6b5f0df14a9544cc9); recent changes are test, diagnostics, and workflow only.

Current PR head: [26153a7ec757681098bd78ff9f8dff18a52f1189](https://github.com/mbaliga/Alloy/commit/26153a7ec757681098bd78ff9f8dff18a52f1189). The application/runtime source checkpoint remains [411bc3499d4ef864347e68e6b5f0df14a9544cc9](https://github.com/mbaliga/Alloy/commit/411bc3499d4ef864347e68e6b5f0df14a9544cc9); recent changes are test, diagnostics, and workflow only.

- Android acceptance on predecessor head [fe7100117d44a225352d396e76b6344ec8d1d04d](https://github.com/mbaliga/Alloy/commit/fe7100117d44a225352d396e76b6344ec8d1d04d) passed in [run 39716316437](https://github.com/mbaliga/Alloy/actions/runs/39716316437). QA APK artifact [12399842993](https://github.com/mbaliga/Alloy/actions/runs/39716316437/artifacts/12399842993), archive SHA-256 `2af46d5d7261624bbd1aa7a13d0744f67fdb34dfe48b038da70da23670b43278`; software-QA only.
- The native engine built on [run 39716316487](https://github.com/mbaliga/Alloy/actions/runs/39716316487), but its API-35 emulator failed to install the app because Android reported `Can't find service: package`. The instrumentation XML records **0 tests executed**; this is infrastructure failure, not an assertion from the test-only transform change. Report artifact [12401729423](https://github.com/mbaliga/Alloy/actions/runs/39716316487/artifacts/12401729423), archive SHA-256 `a7d81523443d93f544820b0532f767d2e0f28f73cdafcdda49e6a85ad377bbea`.
- Consequently, exact-head G3 export and strict comparison did not run on `fe71001`. The previous valid G3 evidence remains the strict **FAIL** recorded below; no print/Handy qualification is granted.
- Commit `26153a7` makes G3 export and comparison execute with `always()` after earlier emulator failures, without changing the 2% gate. Exact-head CI for this commit is in progress; until new G3 evidence is produced, G3 remains **unverified at this head** and print/share remain **NO-GO**.
- The earlier travel-obstacle orientation note on this predecessor is superseded: it used the wrong 3MF matrix convention. The corrected test-only contract uses the spec-conformant row-vector interpretation (−90°), and does not alter production model transforms.

Direct send, Bambu Handy handoff, physical A1 Mini acceptance, A1/P1S runtime qualification, production signing, and release approval remain open.

# Exact-head Android/native result — 2026-10-22

Verified PR head before this documentation/tooling update: [ecab2f405f9cf9282787b371eac246a3f0cf9fb3](https://github.com/mbaliga/Alloy/commit/ecab2f405f9cf9282787b371eac246a3f0cf9fb3). Application runtime is unchanged from [411bc3499d4ef864347e68e6b5f0df14a9544cc9](https://github.com/mbaliga/Alloy/commit/411bc3499d4ef864347e68e6b5f0df14a9544cc9).

## Exact-head CI results

- Android acceptance [39594704892](https://github.com/mbaliga/Alloy/actions/runs/39594704892) passed. QA APK artifact [12345620998](https://github.com/mbaliga/Alloy/actions/runs/39594704892/artifacts/12345620998), archive SHA-256 `bd881c31c5b60a69f1e1f44735b27b8944de37a76d327ff3a8c477428f48276b`. This is software-QA only.
- Profile compatibility [39594704034](https://github.com/mbaliga/Alloy/actions/runs/39594704034), parity tooling [39594704073](https://github.com/mbaliga/Alloy/actions/runs/39594704073), desktop references [39594704025](https://github.com/mbaliga/Alloy/actions/runs/39594704025), and official Orca snapshot [39594704035](https://github.com/mbaliga/Alloy/actions/runs/39594704035) passed.
- Native run [39594704028](https://github.com/mbaliga/Alloy/actions/runs/39594704028) passed native API-35 instrumentation; instrumentation report artifact [12345754697](https://github.com/mbaliga/Alloy/actions/runs/39594704028/artifacts/12345754697), archive SHA-256 `754bb94b31c1bff2f2b033a00eb97e6e9f744e9d9578ab65113b8f5acbd3b95d`. Native QA APK artifact [12344962629](https://github.com/mbaliga/Alloy/actions/runs/39594704028/artifacts/12344962629), archive SHA-256 `04ebfc7c3e7469bc1b7c7441d8a6905d3ca31de8d411e5e4fae7def5c78aa6ee`; not print-qualified.
- Exact-head G3 **failed** with the strict 2% tolerance unchanged. Comparator bundle [12345669519](https://github.com/mbaliga/Alloy/actions/runs/39594704028/artifacts/12345669519), SHA-256 `a2f6d304bb130ef8ec383a94da7eebe02f4b9cb012d5166776fca625cb6a1c38`. All four time checks failed: cube 14.31%, overhang/support 17.77%, thin wall 13.23%, travel obstacle 9.40%. Filament-length deltas were 2.09%, 2.59%, 2.65%, and 1.06%; first three remain over tolerance. Filament mass deltas were 2.03%, 2.47%, 2.58%, and 1.13%. Do not relax the gate.
- Toolpath diagnostics continue to show close deposited path totals for most roles but substantially shorter travel in Alloy: travel-move count deltas range from 33.36% to 56.02% lower; nominal total XYZ path is 15.04%–47.00% lower depending on fixture. First-layer startup/prime accounting remains a separate gap, not an explanation for all timing/travel failures.
- The API-35 native QA APK is not a print/share artifact. Direct-send, Bambu Handy, physical pilot, and release gates remain closed.

## Ordered deposition-anchor analysis

The diagnostic-only ordered-anchor comparator added in this update was run locally against the exact desktop/native G3 artifact pairs above. It aligns feature-labelled positive-E XYZ segments by layer, canonical role, rounded XY endpoints, E, and conditional label; it compares travel only between immediately adjacent matched deposition anchors and reports unlabelled travel separately. This does not prove physical deposition, identify a planner defect, or affect G3 pass/fail.

For the cube, it found 3,841/3,801 labelled segments, 3,195 matching in sequence, 3,160 adjacent matched spans, and 86 spans with different travel. The first broad ordering divergence is layer-0 bottom-surface infill: desktop and Alloy traverse the same area in opposite orders/directions. The supported-overhang and thin-wall fixtures also have substantial unmatched/reshaped segment sequences; the travel-obstacle fixture has zero exact matched anchors, with the first paths traversing the same square in different orientations. This is evidence to investigate feature/path ordering and travel generation—not a basis for enabling wall avoidance or changing slicing policy. The comparator keeps repeated anchors by disabling difflib autojunk and includes regression coverage.

This update adds diagnostics/tests only; it does not change production slicing or qualification. The next production milestone is to resolve deposition ordering and travel parity against pinned Orca behavior, then rerun the same fixtures and unchanged limits. See the prior checkpoints below for the remaining G2, printer, Handy, and release work.

---

# Current exact-head gate update — 2026-10-21

PR head: [b0261a88c28317e69cffa235a3608559f2646361](https://github.com/mbaliga/Alloy/commit/b0261a88c28317e69cffa235a3608559f2646361). Runtime/app source checkpoint remains [411bc3499d4ef864347e68e6b5f0df14a9544cc9](https://github.com/mbaliga/Alloy/commit/411bc3499d4ef864347e68e6b5f0df14a9544cc9); the intervening changes are verification/documentation, not slicer behavior.

## Exact-head verification

- Android acceptance run [39587772168](https://github.com/mbaliga/Alloy/actions/runs/39587772168): passed, 0 failures, 15 native-dependent skips. QA APK artifact [12341606245](https://github.com/mbaliga/Alloy/actions/runs/39587772168/artifacts/12341606245), ZIP SHA-256 `1c56101d669f61ae3955987e9391248627eb163beb59d7f5fbee0bef37e6f89b`. Software QA only.
- Profile compatibility [39587772205](https://github.com/mbaliga/Alloy/actions/runs/39587772205), parity suite [39587772109](https://github.com/mbaliga/Alloy/actions/runs/39587772109), Orca source snapshot [39587772106](https://github.com/mbaliga/Alloy/actions/runs/39587772106), and desktop reference slices [39587772194](https://github.com/mbaliga/Alloy/actions/runs/39587772194): passed.
- Native run [39587772192](https://github.com/mbaliga/Alloy/actions/runs/39587772192): native instrumentation passed on API 35 (0 failures); exact-head G3 comparison failed. QA APK artifact [12341173326](https://github.com/mbaliga/Alloy/actions/runs/39587772192/artifacts/12341173326), ZIP SHA-256 `bae1da64997fbf615247d13636baae6d31fad174a31798293416b1b3a7167480`. Not print-qualified.
- G3 comparator artifact [12343320626](https://github.com/mbaliga/Alloy/actions/runs/39587772192/artifacts/12343320626), SHA-256 `b6b4418ed43c64d5420ba5db3392a3cc58e2ae3fc65a2cba0fdbec01a3c04ae2`; tolerance remains 2%, unchanged. Cube, supported overhang, thin wall, and travel obstacle all fail. Time deltas: 14.31%, 17.77%, 13.23%, 9.46%. Filament-length deltas: 2.09%, 2.59%, 2.65%, and within 2%; mass deltas: 2.03%, 2.47%, 2.58%, and within 2%. Do not relax tolerances.
- The separate pinned-template probe completed diagnostically: artifact [12343590257](https://github.com/mbaliga/Alloy/actions/runs/39587772192/artifacts/12343590257), SHA-256 `e512f939797e8e09e78175dd73c095cb998a2ea3ad824ba44e733d4620369438`. It confirms template/startup accounting materially affects filament totals, but its output is unqualified and must not be enabled as production output.
- Diagnostic report: desktop startup includes 23.742 mm of positive first-layer purge/prime extrusion that Alloy does not model, explaining the first three filament deltas in part, not all G3 failures. Toolpath/travel differences are also material (e.g. cube total XYZ path is 35.15% lower in Alloy; supported-overhang path is about 47% lower). Desktop and Alloy time metrics use different fields, but their observed deltas still fail the unchanged gate. This is an implementation/parity gap, not a header-only discrepancy.

## Decision and next work

Slicer output remains **NO-GO for print/share**. Keep direct send, Bambu Handy handoff, physical-pilot, and release gates fail-closed. The next engineering work is to diagnose and close the real motion/travel/toolpath parity gap and model startup purge from the pinned, verified machine sequence (never add a fixed filament constant); then rerun the same four fixtures and unchanged thresholds. Physical A1 Mini acceptance, A1/P1S qualification, G2 lineage review, Bambu Handy import/print acceptance, and production signing remain open.

---

# Alloy engine gate status

Date: 2026-10-21 (current PR branch checkpoint)

## Exact PR branch head 6500ef8 — Android/native instrumentation PASS; strict G3 FAIL; print/release NO-GO

The current PR branch head is [6500ef85c6bf9c52dc139ecc5ff65e9d2a945373](https://github.com/mbaliga/Alloy/commit/6500ef85c6bf9c52dc139ecc5ff65e9d2a945373). Since app/test source checkpoint [3e2947d1cc29e3f72ae1866565cb1b114ed34b30](https://github.com/mbaliga/Alloy/commit/3e2947d1cc29e3f72ae1866565cb1b114ed34b30), changes only fix the release-candidate test import path, guard that workflow contract, make the README parity command reproducible, and refresh QA evidence. No app, slicer, print/send, or qualification behavior changed.

At this exact head, parity tooling [39562063868](https://github.com/mbaliga/Alloy/actions/runs/39562063868), official Orca source snapshot [39562063881](https://github.com/mbaliga/Alloy/actions/runs/39562063881), desktop A1 Mini reference slices [39562063875](https://github.com/mbaliga/Alloy/actions/runs/39562063875), and the three-printer profile compatibility pre-gate [39562063839](https://github.com/mbaliga/Alloy/actions/runs/39562063839) passed. Android acceptance [39562063866](https://github.com/mbaliga/Alloy/actions/runs/39562063866) passed. Its retained JUnit XML reports 210 tests, 0 failures/errors and 15 native-dependent skips (195 passed). The CI QA APK is artifact [12328868647](https://github.com/mbaliga/Alloy/actions/runs/39562063866/artifacts/12328868647); its ZIP SHA-256 is `ed5403265c15becb60b3afe3ca04775ef01ff1ce58ab2c9986e777beb00c0b26`, and extracted APK SHA-256 is `9355ba2b40ec29366e3d860938e9def8e60e9c2aec745d06b7d3b29cefe1729a`. It is software-QA only, not a production or print-qualified build.

Native workflow [39562063843](https://github.com/mbaliga/Alloy/actions/runs/39562063843) built the optional engine, passed API-35 ARM-translation instrumentation (210 scheduled; 208 passed, 2 skipped, 0 failed), rendered the pinned Bambu templates, and exported all four G3 fixtures. The strict G3 comparator then **failed**; the dedicated physical-pilot APK and device-pilot stages were correctly skipped.

The exact-head parity report is artifact [12332679109](https://github.com/mbaliga/Alloy/actions/runs/39562063843/artifacts/12332679109), ZIP SHA-256 `683559f99d4c99d428f19c4109d74ced6dd842c33b690723b3db9650df17bb40`. At the unchanged 2% tolerance:

| Fixture | Print time (desktop / Alloy) | Time delta | Filament length delta | Filament mass delta |
| --- | ---: | ---: | ---: | ---: |
| Cube | 1775 / 1521 s | 14.31% | 2.09% | 2.03% |
| Supported overhang | 2048 / 1684 s | 17.77% | 2.59% | 2.47% |
| Thin wall | 1330 / 1154 s | 13.23% | 2.65% | 2.58% |
| Travel obstacle | 3393 / 3072 s | 9.46% | within tolerance | within tolerance |

The comparator also warns that desktop time uses `model_printing_time` while Alloy uses `estimated_printing_time`; the time figures therefore need a source-matched interpretation, not just a tolerance change. Travel moves/path remain materially different. The instrumentation-only Bambu-identity/template counterfactual is explicitly non-gating and still does not establish strict G3 or firmware behavior. Preserve the hard gate and do not ship its settings into production. The native instrumentation report is artifact [12332902600](https://github.com/mbaliga/Alloy/actions/runs/39562063843/artifacts/12332902600), ZIP SHA-256 `1c585158f180220368671e6c9e00401818ba9926e63081a39dad6f33f154f0b7`.

No job is cleared for Bambu Handy or physical printing. Physical A1 Mini acceptance, A1/P1S runtime qualification, Handy acceptance, and production signing remain open.

Date: 2026-10-21 (latest exact-head evidence)

## Exact PR head 60e1784 — native instrumentation PASS; strict G3 FAIL / NO-GO

The current PR head is [60e178429e75da31de06596871e2d9c9089efea3](https://github.com/mbaliga/Alloy/commit/60e178429e75da31de06596871e2d9c9089efea3). The latest native workflow [39527385446](https://github.com/mbaliga/Alloy/actions/runs/39527385446) built the optional engine and passed all **207 Android instrumentation tests** on API-35 ARM translation, including the real native ordinary-tree overhang fixture. The native build and template probes also completed. The strict G3 gate failed, so physical-pilot APK build/test stages were skipped.

At the unchanged 2% tolerance, the exact-head G3 evidence [artifact 12316833654](https://github.com/mbaliga/Alloy/actions/runs/39527385446/artifacts/12316833654) fails all four fixtures on print-time and travel-path diagnostics; filament is close but still outside tolerance on three fixtures. Desktop/Alloy time deltas are cube **14.31%**, supported overhang **17.77%**, thin-wall **13.23%**, and travel obstacle **9.40%**. Profile identity and layer counts match. Feature-transition sequences match. Filament length deltas are 2.09%, 2.59%, 2.65%, and within tolerance, respectively. Travel-path/move differences remain large. The separate hook-neutralized report is diagnostic only and shows Bambu conditional motion contributes substantially to the raw desktop travel metrics; it does not establish runtime equivalence or permit relaxing the strict gate.

The strict comparator remains unchanged at 2%; no generated job is qualified for Bambu Handy or physical printing. The new tree-support code has emulator and source-contract coverage, not a real-print support-quality validation. Do not use either QA APK to send or print. A1 Mini physical-pilot qualification, A1/P1S runtime qualification, Bambu Handy acceptance, and production signing remain open.

Artifacts from this run: native G3 evidence [12316883334](https://github.com/mbaliga/Alloy/actions/runs/39527385446/artifacts/12316883334), instrumentation report [12316873317](https://github.com/mbaliga/Alloy/actions/runs/39527385446/artifacts/12316873317), and hook-neutralized diagnostic [12316733704](https://github.com/mbaliga/Alloy/actions/runs/39527385446/artifacts/12316733704).

Date: 2026-10-20 (current PR-head checkpoint)

## Exact PR head 853f222 — native instrumentation PASS; strict G3 FAIL / NO-GO

The current PR head is [853f2221b20c76e6ef0b6eff286b6a9ab04573f1](https://github.com/mbaliga/Alloy/commit/853f2221b20c76e6ef0b6eff286b6a9ab04573f1). Production app source remains `964fbd2df7d28eb99e35e277c4c4bc80412adc96`; the added Bambu identity entry point is called only by instrumentation. The production slice entry point continues to use the non-BBL default. No production slicing/send behavior or hard tolerance changed.

The exact-head Android acceptance run [39398116342](https://github.com/mbaliga/Alloy/actions/runs/39398116342) passed: 220 reported tests, 0 failures, 14 skipped. Those skips are native-engine-dependent cases. The native run [39398116512](https://github.com/mbaliga/Alloy/actions/runs/39398116512) successfully built the engine, passed API-35 ARM-translation instrumentation, rendered and validated the pinned templates, and completed the test-only A/B probe. Its strict G3 comparator failed, so both physical-pilot steps were skipped.

The strict G3 report is [artifact 12258296482](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12258296482) (ZIP SHA-256 `cfd5540c5a657c073d90c518e73aef4fcd228bfc7c0496f29b926f47cbf8b5e3`). At the unchanged 2% tolerance, 63/63 profile-identity fields pass per fixture, but all four fail print-time comparison:

| Fixture | Time (desktop / Alloy) | Time delta | Filament length delta | Filament mass delta |
| --- | ---: | ---: | ---: | ---: |
| Cube | 1775 / 1521 s | 14.31% | 2.09% | 2.03% |
| Supported overhang | 2048 / 1684 s | 17.77% | 2.59% | 2.47% |
| Thin-wall frame | 1330 / 1154 s | 13.23% | 2.65% | 2.58% |
| Travel obstacle | 3393 / 3074 s | 9.40% | within 2% | within 2% |

The A/B Bambu template diagnostic is [artifact 12258785816](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12258785816) (SHA-256 `901f1a99669998044352a11edabcb53235f2a00ea6c03d0101acabc7775ec7aa`); its rendered G-code bundle is [artifact 12258256554](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12258256554) (SHA-256 `a70ef4aaff8cef0437fb851af4afba02fed4819e5b0cacc4cdb21de9929b9cda`). This is an explicitly non-gating, unqualified, instrumentation-only comparison using identical configs while toggling internal BBL-printer identity; it does not change production runtime. Across the four fixtures it lowers the engine-reported estimate by 427–440 seconds, but leaves it 11.32% high for the cube, 18.36% high for the supported overhang, and 11.41% high for the travel obstacle. Thin-wall time lands within 0.46% of desktop. Filament length remains within 0.21% for all four; layer and extrusion-move counts are unchanged. This points to a time-estimation/template-policy effect, not a validated production fix or firmware parity. Keep the default unchanged.

The native instrumentation report is [artifact 12259135616](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12259135616) (SHA-256 `de61b32e761950985c99fb17c2b7572d5ab95a5412d4528b5d0c2e3682bbe154`). The ordinary Android software-QA APK is [artifact 12253604604](https://github.com/mbaliga/Alloy/actions/runs/39398116342/artifacts/12253604604), archive SHA-256 `e11816fc146700f02d1bfb1d42d85f32ab60e3e30cafbf842969b6f77b50ce47`, extracted APK SHA-256 `68adb2f0eaf0e60e2a7fb00c1be56c6ac3a4645036f0877f7887f499d2bbec67`. The native-enabled APK pair is available as artifacts [12257885728](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12257885728) and [12257800815](https://github.com/mbaliga/Alloy/actions/runs/39398116512/artifacts/12257800815), but the engine is not G3-qualified. These are QA artifacts, not release packages. Do not send/share/print generated jobs or import them into Bambu Handy for printing. Direct send, physical A1 Mini pilot, A1/P1S runtime qualification, Bambu Handy acceptance, production signing, and release remain open.

## Historical 1a1932d checkpoint — software checks pass; strict G3 FAIL / NO-GO

The current PR head is [1a1932db903b6adc47cfeec47922f8b243261d56](https://github.com/mbaliga/Alloy/commit/1a1932db903b6adc47cfeec47922f8b243261d56). The production app source remains `964fbd2df7d28eb99e35e277c4c4bc80412adc96`; subsequent changes are diagnostic and documentation only and do not modify production slicing, printer-send behavior, or qualification thresholds.

At this head, parity tooling passed ([39365705084](https://github.com/mbaliga/Alloy/actions/runs/39365705084), 178 tests), as did all three profile audits ([39365705077](https://github.com/mbaliga/Alloy/actions/runs/39365705077)), the official Orca source snapshot ([39365705086](https://github.com/mbaliga/Alloy/actions/runs/39365705086)), desktop A1 Mini reference slices ([39365705109](https://github.com/mbaliga/Alloy/actions/runs/39365705109)), and Android acceptance ([39365705397](https://github.com/mbaliga/Alloy/actions/runs/39365705397)). The Android workflow built its CI-signed QA APK and passed the acceptance suite. Native build and API-35 ARM64 instrumentation also succeeded, but the strict native-vs-Orca G3 comparison failed in [39365705522](https://github.com/mbaliga/Alloy/actions/runs/39365705522); the isolated physical pilot was skipped.

The G3 report is [artifact 12242487066](https://github.com/mbaliga/Alloy/actions/runs/39365705522/artifacts/12242487066), ZIP SHA-256 `688cea2cf151d138c37abbf33f4b9a9723b62f821c5270a291b1ffa5a701b38c`. At the unchanged 2% tolerance, 63/63 profile-identity fields pass on every fixture, but all four fixtures fail time:
- Cube: 1,775 s desktop vs 1,520 s Alloy (14.37%); filament 1,135.62 vs 1,111.81 mm (2.10%), 3.44 vs 3.37 g (2.03%).
- Supported overhang: 2,048 vs 1,680 s (17.97%); filament 934.59 vs 908.93 mm (2.75%), 2.83 vs 2.75 g (2.83%).
- Thin-wall frame: 1,330 vs 1,154 s (13.23%); filament 895.41 vs 871.69 mm (2.65%), 2.71 vs 2.64 g (2.58%).
- Travel obstacle: 3,393 vs 3,073 s (9.43%); its filament delta is within 2%.

These values do not qualify printer-ready output. The report warns that the desktop and Android print-time estimate fields differ in source; the hard numeric check remains in force. A diagnostic-only hook-neutralized counterfactual (artifact [12242596579](https://github.com/mbaliga/Alloy/actions/runs/39365705522/artifacts/12242596579), SHA-256 `78954b68cebe7174d48ebe6b2822ad6a9c5c0e0d59d74450182ad29d4e765d70`) shows that clearing optional desktop hooks removes much of the travel-path delta, but leaves measurable residuals across fixtures. It does not model firmware execution or disable every Bambu policy. The end-template-only probe (artifact [12242089356](https://github.com/mbaliga/Alloy/actions/runs/39365705522/artifacts/12242089356), SHA-256 `150b6d1c99cecc1eeffbb1843604a9169f395779c03b87332d8526e842113a44`) retains the native baseline for other hooks while desktop has its full hook set; it does not isolate end-template causality or establish parity. Do not copy macros into production, change planner behavior, or loosen tolerances from these measurements alone.

For phone-only software QA, the fresh Android artifact is [12239627731](https://github.com/mbaliga/Alloy/actions/runs/39365705397/artifacts/12239627731), archive SHA-256 `56c7eccfe331f61972e69a2a8c6942cb12881784271732b5825ceb8ead5f97f9`; extracted `app-release.apk` SHA-256 `85bc8844b004d6450af58cc9b9ae50b4361d48cc9b70f2c731d6f6e2daa1b55e`. This is a software-QA build, not a production release or qualified native slicing path. It may be used for the software checklist only. Do not send generated packages, try Bambu Handy import for printing, or run a physical job. A1/P1S runtime qualification, Bambu Handy acceptance, physical printing, provenance/license review, production signing, and release approval remain open. Keep send/print/release fail-closed.

## 2026-10-19 exact-head native G3 result — FAIL / NO-GO

PR source [`0a1d52530ae73ef92baa72fb62ccaf2207892b03`](https://github.com/mbaliga/Alloy/commit/0a1d52530ae73ef92baa72fb62ccaf2207892b03)
passed parity tooling ([39278128589](https://github.com/mbaliga/Alloy/actions/runs/39278128589)),
the official Orca source snapshot
([39278128743](https://github.com/mbaliga/Alloy/actions/runs/39278128743)),
desktop A1 Mini reference slices
([39278128700](https://github.com/mbaliga/Alloy/actions/runs/39278128700)),
the three-printer static profile pre-gate
([39278128784](https://github.com/mbaliga/Alloy/actions/runs/39278128784)),
and ordinary Android acceptance
([39278128756](https://github.com/mbaliga/Alloy/actions/runs/39278128756)).
Android acceptance reported 216 tests, 0 failures, 12 skipped (204 executed).
The native workflow built the engine and ran API-35 ARM64 instrumentation
(203 passed, 2 intentionally ignored), rendered the template diagnostic, and
exported G3 evidence. Its strict comparator failed; the isolated physical
pilot was skipped. The native run is
[39278128752](https://github.com/mbaliga/Alloy/actions/runs/39278128752).

The exact-head G3 comparison is artifact
[12202999737](https://github.com/mbaliga/Alloy/actions/runs/39278128752/artifacts/12202999737)
(SHA-256 `20c1604e02222fbe44232f61e35f304a30d1fcf9b05e0dcb99f5993d4028b88e`);
raw native G-code and configs are artifact
[12204021145](https://github.com/mbaliga/Alloy/actions/runs/39278128752/artifacts/12204021145)
(SHA-256 `71e7089f3e002bc089c3d05a38df6433990d1ebf9c69fb0c6417d5731238b67b`).
All fixtures pass 63 checked profile-identity fields and retain the 2%
hard tolerance. Results:

| Fixture | Time delta (desktop / Alloy) | Filament length delta | Travel-move delta |
| --- | ---: | ---: | ---: |
| Cube | 14.37% (1775 / 1520 s) | 2.10% | 56.51% |
| Supported overhang | 17.87% (2048 / 1682 s) | 2.61% | 33.72% |
| Thin-wall frame | 13.23% (1330 / 1154 s) | 2.65% | 37.96% |
| Travel obstacle | 9.52% (3393 / 3070 s) | 1.07% | 37.23% |

For the cube, extrusion-motion time is effectively identical (1337.18 s
desktop vs 1337.09 s Alloy), while estimated travel time is 111.27 s vs 20.97 s
and travel moves are 2435 vs 1059. Travel-path distance differs 35.16%.
The obstacle's time source also differs
(`model_printing_time` vs `estimated_printing_time`); the comparator warns but
keeps the hard numeric tolerance unchanged. The larger travel-path and move
deltas show a genuine motion-planning mismatch as well, so estimate-source
semantics alone cannot explain or waive the failure. Do not add a blanket time
multiplier or relax tolerances. No fixture result qualifies production output.

### Follow-up: conditional-motion sensitivity (diagnostic only)

Inspection of the retained raw cube G-code found a second possible source of
the travel discrepancy, not a justified planner fix. The desktop program emits
12,636 mm of motion inside `SKIPTYPE` blocks (`timelapse` and
`head_wrap_detect`); Alloy's neutral output has no such tagged moves. The
desktop block ends near X0 after visiting X0/Y90 and X-13, then the next
unconditional move targets X97.692/Y99.064. If firmware skips that entire
conditional block, the unconditional move's assumed starting point may differ
from the printer's actual position. Alloy instead moves from its nearby wipe
endpoint (about 0.81 mm to that target). This is a conditional-state ambiguity:
G-code alone does not establish whether, when, or how the A1 mini firmware
executes the tagged blocks.

The bounded raw travel diagnostic reports 2,435 desktop vs 1,059 Alloy travel
moves and approximately 27,194 mm vs 4,678 mm of travel. Subtracting tagged
desktop motion leaves roughly 9,880 mm of additional travel unexplained; these
are program-level distances, not firmware-executed paths. The resolved
`reduce_crossing_wall` value is false in both configs, so the inspected
`GCode::travel_to` planner branch is not evidence for changing that setting or
the planner. Next evidence step: create a separate, non-gating matched re-slice
with timelapse/head-wrap hooks disabled on both sides, verify both effective
configs, then compare per-layer travel. Independently determine the firmware
conditional behavior before drawing a runtime conclusion. Strict G3 results,
tolerances, and print/send eligibility remain unchanged.

The exact-head CI-signed QA APK is artifact
[12199639681](https://github.com/mbaliga/Alloy/actions/runs/39278128756/artifacts/12199639681),
archive SHA-256 `6544bd3a60690c5495c3a3dba4b409a275f069f3b0c92d96483f5183aa996ff2`,
extracted APK SHA-256
`0d538621b9632d77e2781db7405e9602ffe761ba52045f3a3fa8962afbcca6ab`.
It is for software/device QA, not production signing. The ordinary Android
run's native-dependent cases were skipped; use native instrumentation results
only for the runtime behavior they actually cover. Direct send, Bambu Handy
acceptance, physical print, and production release remain unqualified. The
updated device protocol is in
[`ON_DEVICE_MVP_CHECKLIST.md`](ON_DEVICE_MVP_CHECKLIST.md).


### Follow-up: preliminary three-way travel decomposition (cross-run diagnostic)

A bounded local analysis compared the exact-head desktop references for
`76f0be33` with a retained native G3 artifact from the earlier `0a1d525` run.
The slicer/native source was unchanged between those app revisions, but this
is **not a same-head hosted comparison** and is not parity evidence. With
desktop time-lapse and wrapping-detection hooks disabled and verified, the
very large cube travel delta fell from 22,516 mm (strict desktop vs native)
to about 1,283 mm. The other three fixtures similarly retain about 0.52–1.88 m
of travel-path difference after hook neutralization. This indicates that the
optional hooks explain much of the extreme strict difference, but do not
explain the remaining planner/template mismatch.

Across all four fixtures, the largest residual cluster is on the final model
layer: approximately 0.55–0.60 m more desktop travel than native in each case.
Inspection of the cube tails shows the desktop's Bambu machine-end template
emitting park/unload motions absent from Alloy's current end template. This is
a plausible attribution for much of the repeatable tail delta, **not** proof
of firmware execution, safety, exact per-layer accounting (the report groups
post-model moves into its terminal layer bucket), or a qualified Bambu end
sequence. The native template probe now has a separate test-only
end-template-only render, alongside its existing all-hooks render. It asserts
that the start, layer-change, and time-lapse hooks are not imported into that
isolated case. Its outputs remain explicitly unqualified and are validated
separately; exact-head native CI must still render and compare them. Do not
ship upstream macros, alter production templates, or change G3 thresholds/send
gates based on this lead. Firmware conditional behavior and physical pilot
remain open.

## 2026-10-18 exact-head native G3 result — FAIL / NO-GO

Application source `d7f5d330f01d21f8f08821a780c16aae6cebe18c` passed the
ordinary Android, profile pre-gate, desktop-reference, parity-tooling and
official-source snapshot workflows listed in
[`ON_DEVICE_MVP_CHECKLIST.md`](ON_DEVICE_MVP_CHECKLIST.md). Exact-head native
run [39132914352](https://github.com/mbaliga/Alloy/actions/runs/39132914352)
completed its source/dependency checks, native build, API-35 ARM-translation
instrumentation, Bambu template render/probe, and native G3 evidence export.
It then failed the strict comparison against the exact-PR-head Orca reference;
physical pilot steps were skipped. The report is artifact
[12140872241](https://github.com/mbaliga/Alloy/actions/runs/39132914352/artifacts/12140872241)
(SHA-256 `b9a4911168cf17df3e5b251a33f5a9bc58da22b3063f920452ac27709c384d18`).

The four fixtures fail print-time tolerance by 9.43–17.87%; cube, supported
overhang, and thin-wall also exceed the 2% filament length/mass tolerance by
2.03–2.65%. For the cube, nominal extrusion time is nearly equal (1337.09 s
Android vs 1337.18 s desktop), but nominal travel time is 20.97 s vs 111.26 s
and travel moves are 1,059 vs 2,435. Profile identity passes for 63 checked
fields per fixture. These diagnostics indicate an engine/toolpath/time-model
gap, not a reason to add a blanket time correction or relax the gate. Native
build and instrumentation are verified; G3, physical A1 Mini printing, Handy
handoff, A1/P1S runtime parity, production signing and release remain
unqualified. Keep send/print/release controls fail-closed.

The same run's separate, **non-gating** Bambu template probe
[diagnostic artifact 12140727585](https://github.com/mbaliga/Alloy/actions/runs/39132914352/artifacts/12140727585)
(SHA-256 `691b641f079c941bc3ac7d92fe83efd10e1842ae61eb4a11a8db523d8fd490d5`)
successfully rendered the four pinned template hooks through the native test
harness. The diagnostic's template-probe estimates are worse—not better—than
desktop Orca:

| Fixture | Desktop (s) | Template probe (s) | Delta |
| --- | ---: | ---: | ---: |
| Cube | 1,775 | 2,406 | +35.55% |
| Supported overhang | 2,048 | 2,851 | +39.21% |
| Thin-wall frame | 1,330 | 1,764 | +32.63% |
| Travel obstacle | 3,393 | 4,211 | +24.11% |

The cube's filament length is within 0.01% and its total XYZ path within 2.63%,
but nominal motion time is 20.44% higher; other fixtures show different
motion/time behavior. This is a rendered-template diagnostic, not printer
firmware execution. It proves that copying the raw Bambu hooks into the
current engine is not a validated fix; retain the neutral production policy
until conditionals, firmware semantics, and actual device behavior are
resolved. The strict G3 thresholds and send gate are unchanged.

The comparator now records `print_time_source` (`model_printing_time`,
`estimated_printing_time`, or `total_estimated_time`) and emits a warning when
the two slicers report different estimate kinds. This diagnostic does not
change the hard numeric check; the current G3 comparisons still fail their
unchanged tolerance.

## 2026-10-18 exact-head CI and native provenance checkpoint

On PR source head `07c9dc0c4bfe1bbaa5ab8c1a91828fecf950ee30`, parity tooling
([39088667769](https://github.com/mbaliga/Alloy/actions/runs/39088667769)),
the A1 Mini/A1/P1S profile pre-gate
([39088667762](https://github.com/mbaliga/Alloy/actions/runs/39088667762)),
official Orca source snapshot
([39088667772](https://github.com/mbaliga/Alloy/actions/runs/39088667772)),
and desktop reference slices
([39088667804](https://github.com/mbaliga/Alloy/actions/runs/39088667804))
passed. Android acceptance ([39088667774](https://github.com/mbaliga/Alloy/actions/runs/39088667774))
passed with 203 tests, 0 failures, 0 errors, and 12 skipped. The skipped tests
include native-engine and template-probe cases, so this result does not show
native G-code rendering. The CI APK is `com.mbaliga.alloy` v1.0, target SDK
35, SHA-256
`5fb8b00d91e19f9743d1836867df597792074179a164bd3d3e6e934e113ac439`.
This is software acceptance evidence only.

At this checkpoint the optional-native run
([39088667780](https://github.com/mbaliga/Alloy/actions/runs/39088667780))
and the immediately preceding native run
([39087573937](https://github.com/mbaliga/Alloy/actions/runs/39087573937))
were still in native dependency compilation; neither had produced native
instrumentation, template-render, G3, or physical-pilot evidence. The older
native run [39086220594](https://github.com/mbaliga/Alloy/actions/runs/39086220594)
retained only an `initialized` template-probe attempt and a diagnostic saying
the rendered manifest was missing. It is **unavailable**, not a measurement.
The source-level Android compile failure was the invalid
`Material.alloyDirectSend` reference, corrected to `directSendState`; that is
the likely same-source cause for native instrumentation compilation, but the
native job log itself was not retrievable. Treat this native failure cause as
inferred, not directly confirmed.

The native build workflow now records a deterministic, hash-bound inventory
of declared source/dependency pins, Alloy patch scripts and available patched
checkout diffs, local native input files, repository license/notice files,
APK/test-APK hashes, packaged license assets, and every `.so` hash inside the
APK. This is audit evidence only: the manifest is explicitly…41902 tokens truncated…RY_REQUIRED`. The latest native-enabled suite ran 123 test cases with 0 failures; only the optional
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

### 2026-10-16 exact-head native G3 result and software-only APKs

The tested application/CI source was
`4dc532d6ad71ec7ab6f5a524bf68bee647290947`. Parity tooling
[38802544918](https://github.com/mbaliga/Alloy/actions/runs/38802544918),
three-printer static profile pre-gate
[38802545030](https://github.com/mbaliga/Alloy/actions/runs/38802545030),
desktop A1 Mini references
[38802544908](https://github.com/mbaliga/Alloy/actions/runs/38802544908),
official Orca snapshot
[38802544906](https://github.com/mbaliga/Alloy/actions/runs/38802544906),
and Android QA build/tests
[38802544927](https://github.com/mbaliga/Alloy/actions/runs/38802544927)
passed. Android artifact `12006353162` has ZIP SHA-256
`1f469a0170ea2522ce7d11761d066583445f7921ed17b63f6374b46fe7b24ee3`; the
extracted APK SHA-256 is
`8394676538274415e94266f99d8bf4a7d7fe55bd26ed2ce1195e66f8af519759`.

The native PR run
[38802544902](https://github.com/mbaliga/Alloy/actions/runs/38802544902)
completed its ARM64 build, instrumentation, and G3 evidence export, then
**failed** the desktop comparison. The four print-time deltas at a 2% tolerance
are: cube 14.08% (1,525 vs 1,775 s), supported overhang 18.02% (1,679 vs
2,048 s), thin-wall frame 12.48% (1,164 vs 1,330 s), and travel obstacle
9.14% (3,083 vs 3,393 s). On supported overhang, filament length differs by
8.90% (1,017.81 vs 934.59 mm), mass by 8.83% (3.08 vs 2.83 g), and positive-E
by 6.04% (1,509.81 vs 1,606.85 mm, against a 5% allowance). Profile identity
matches 62 fields; that does not override the output failures. This native
result is a concrete G3 **NO-GO**, not a reason to loosen tolerances or apply a
global timing correction.

Separate push-triggered native run
[38802536896](https://github.com/mbaliga/Alloy/actions/runs/38802536896)
produced software-only artifact `12010340940`: a CI QA APK pair and five
preflighted sample packages. Its manifest says `software_only: true`,
`engine_verified: false`, and physical/direct-send qualification pending. It
can support app and file-inspection QA only; **do not send these packages to a
printer or import them into Bambu Handy for printing**.

The Bambu TreeSupport3D source audit was also repeated against pinned Bambu
Studio `v02.08.02.61` (`926a7192574bcb9b3a732e1ec59a46d79cb45466`) and remains
`PORT_REQUIRED`: seven required implementation files differ, 30/36 direct
support includes differ, with 94 transitive includes, 12 unresolved quoted
includes, 9 absent transitive dependencies, and 68 differing same-path files.
The next slicer milestone is to close that coherent source/dependency/API port
and rerun the same strict four-fixture comparison. Until then, G3 and every
physical-send/release gate remain closed.


### 2026-10-18 native template probe failure and correction

On application commit `5f4d3529ddc495bacd22ef914bdb77c1824014ef`, regular
Android acceptance run
[39091992055](https://github.com/mbaliga/Alloy/actions/runs/39091992055)
passed with 204 tests, 0 failures/errors, and 12 skips. Its QA APK artifact
`12123651141` has ZIP SHA-256
`5fea57b5660339e7e3a1424b0e6aa658b0afe19724918d36f2f5a5c6170f2e12`.

Native run
[39091992048](https://github.com/mbaliga/Alloy/actions/runs/39091992048)
successfully built the ARM64 engine and both native APKs, but native
instrumentation and the standalone template probe failed before any G3
comparison. The retained diagnostic identifies the common setup error:
the test overlaid the exact pinned Bambu layer-progress hook on Alloy's
relative-extrusion recipe, replacing the required per-layer `G92 E0` and
making the slicer reject the fixture before G-code rendering. The probe now
adds that reset as a test-only compatibility shim after validating the
untouched upstream template digest (`399963be081f2df51a5f83a68ac4d9d8875f7b79`;
comment clarification `28c52690a39d85bd579de05b1fc5cfda9f170021`). This only
repairs the diagnostic harness; it neither changes production output nor
qualifies imported Bambu templates.

The failure artifacts are retained: native instrumentation report
`12124269192` (SHA-256
`558c793f747748cc4f7be3953a19c116eefb4092469d2a45cdd159d782515326`),
template render probe `12125445310` (SHA-256
`8006b754a09fc2b2f1ba52a35f3fe45a1bc8dee7c544f6bdd56918a152df7fc5`),
and native build provenance `12124657511` (SHA-256
`8e90421a9e75f1065fb90b1126406f55bab77c0bbb5b590e1fa756d15a2be649`).
G3 export and the software-only physical-pilot build were skipped because
native instrumentation failed. The new harness fix still needs an exact-head
native CI run; desktop parity, Bambu Handy acceptance, physical printing,
legal/source-lineage, production signing, and release gates remain open.

### 2026-10-20 diagnostic correction and current Android QA checkpoint

Application-source checkpoint `63f53724775d4f5e7a2641f7a95b3dadbf379cf1` changes only CI diagnostics relative to the preceding application build; production slicing behavior and the strict G3 tolerance remain unchanged. The template-probe hook-order parser now tracks `M82`/`M83` mode and `G92 E` resets, so an absolute-E retraction is not misidentified as positive extrusion. Its regression and all 184 parity tests pass locally.

The ordinary Android workflow [39420155791](https://github.com/mbaliga/Alloy/actions/runs/39420155791) passed with 220 reported tests, zero failures, and 14 native-dependent skips. The CI QA APK is artifact [12264346365](https://github.com/mbaliga/Alloy/actions/runs/39420155791/artifacts/12264346365), archive SHA-256 `eb5b0cc70919e11784324bc44fa4dcfbf858860632f18ccc7d6dc8b7c4c40c48`. It is for software QA only, not a production release or print artifact.

At the last check, exact-source native run [39420155810](https://github.com/mbaliga/Alloy/actions/runs/39420155810) had passed native dependency compilation and was building the optional native engine. No fresh G3 comparison or physical-pilot result was available yet. The latest completed strict G3 comparison remains **NO-GO** at the existing tolerance; direct-send, Bambu Handy, physical-print, and production-release qualification remain closed. Subsequent PR commits update documentation only; the application/runtime source is unchanged from checkpoint `63f5372`.


### 2026-10-22 exact-head G3 result and native test diagnosis

At PR head `4a99f8f555efd601cd20b5190bfac97cd6011dc2`, desktop Orca reference
run [39778592065](https://github.com/mbaliga/Alloy/actions/runs/39778592065),
Android acceptance
[39778592231](https://github.com/mbaliga/Alloy/actions/runs/39778592231),
Bambu profile compatibility
[39778592204](https://github.com/mbaliga/Alloy/actions/runs/39778592204),
official source snapshot
[39778592290](https://github.com/mbaliga/Alloy/actions/runs/39778592290),
and parity tooling
[39778592332](https://github.com/mbaliga/Alloy/actions/runs/39778592332)
passed. The software-QA APK is artifact
[12425954059](https://github.com/mbaliga/Alloy/actions/runs/39778592231/artifacts/12425954059),
archive SHA-256
`5c957ade52596529cfe62aa039079526319f240414a31d3bda86eabe70807c83`.
It is not a native-slicer qualification or a print artifact.

Native run
[39778592179](https://github.com/mbaliga/Alloy/actions/runs/39778592179)
built the native engine and exported exact-head G3 evidence, but its overall
result is **FAIL**. Strict G3 report artifact
[12441006405](https://github.com/mbaliga/Alloy/actions/runs/39778592179/artifacts/12441006405)
kept the 2% tolerance unchanged. Print-time deltas: cube 14.31% (1,521 vs
1,775 s), supported overhang 17.58% (1,688 vs 2,048 s), thin wall 13.23%
(1,154 vs 1,330 s), and travel obstacle 9.43% (3,073 vs 3,393 s). Filament
length and mass also fail for the first three fixtures (2.03–2.65%); travel
obstacle is within those two limits. The detailed native G-code evidence is
artifact [12441475071](https://github.com/mbaliga/Alloy/actions/runs/39778592179/artifacts/12441475071).
Profile identity passed; toolpath/time parity did not. No native output from
this run is qualified to print or share to Bambu Handy.

Native instrumentation also failed: 8 tests timed out, several while slicing.
The independent Bambu-template rendering checks timed out; their diagnostic
pull also reported Android external-storage permission denied. A separate
counterfactual identity assertion exposed a stale expected digest: the exact
pinned project-settings fixture hashes to
`6ea072f761a3a50dd33caafe5a6c44b0da4011698c97d34e20011849b231fd18`, not the
old `d638086d...` value. Commit
[be3feaf](https://github.com/mbaliga/Alloy/commit/be3feaf9a93889554fd5bc19cfca7bf7fdd7afab)
updates that test constant. Follow-up commit
[0c0f17c](https://github.com/mbaliga/Alloy/commit/0c0f17c5c72656eda7ad5fec616af5e628f2960e)
moves probe artifacts from Android/data to the shared Android/media directory
so CI can retrieve them on Android 11+; its focused harness test and all 208
parity tests pass locally. Neither fix has an exact-head native CI rerun yet. The corrected bed-temperature identity mapping remains
a comparator fix only; it does not resolve the actual G3 path/time differences.

Direct send, Handy handoff, physical printing, and production release therefore
remain locked. Do not use these artifacts to print or send jobs.
