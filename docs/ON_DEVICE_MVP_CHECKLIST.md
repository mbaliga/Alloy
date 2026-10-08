# Alloy on-device MVP verification

Use the exact build lane named in each section. The latest Android v1
artifact is the ordinary CI-signed QA build from run 37744453665, app-source
commit 8bc416109d2b73c1a7ea737bffe88d841ae9853d. It is suitable for phone
workflow and non-native slicer smoke checks, but it does not qualify the native
slicer, printer transport or production release. Native verification for the
current capability-parser change is pending in run 37745898443. The previous
native run built from pre-parser-fix commit 849df7eac15ad07d4ef31bfdde39c6b6d0b08b93
but failed instrumentation because the runtime catalog contract rejected the
new A1 mini support-filament entries. No current-source isolated
`alloyPhysicalPilot` artifact is available yet. None of these builds by itself
authorizes general direct printing, Bambu Handy compatibility, or production
release promotion.

## 1. Install the matching artifacts

1. For the current UI/software smoke pass, download
   [`alloy-v1-release-ci-apk` from Android v1 run 37744453665](https://github.com/mbaliga/Alloy/actions/runs/37744453665).
   The app-source commit is `8bc416109d2b73c1a7ea737bffe88d841ae9853d`;
   the Android pipeline acceptance tests passed. Artifact ZIP digest:
   `sha256:5bcb064b7bb21e438df6985c951504bcec73b98e29d80629f3f80102043aeb99`
   (expires **2027-01-06**). After extracting, install `app-release.apk` on
   an ARM64 Android phone; compute and record the APK's own SHA-256 separately.
   This QA build does not qualify native slicing, printer transport, or release.
2. Do not use an older native APK as verification for the current source. Native
   verification for the A1 mini capability-parser update is running in
   [run 37745898443](https://github.com/mbaliga/Alloy/actions/runs/37745898443),
   triggered from workflow commit `066ab57ebf77c7287f734dbbf7f82c4e14011d24`.
   Wait for that run to pass and use only its matching artifacts. The earlier
   run 37742656049 built the engine but failed native instrumentation against
   pre-parser-fix commit `849df7eac15ad07d4ef31bfdde39c6b6d0b08b93`.
   Native CI artifacts are not production-signed. Fresh G2 provenance remains
   **INSUFFICIENT / FAIL** (66/72 exact-history samples; 314/479 exact common
   files, 65.55%; [run 37741025557](https://github.com/mbaliga/Alloy/actions/runs/37741025557)).
   The separate Bambu Studio lineage diagnostic did not establish a baseline
   (101/445 historical best; [run 37741025606](https://github.com/mbaliga/Alloy/actions/runs/37741025606)).
   No physical printer route has been qualified.
3. No current-source isolated pilot APK pair is available yet. Do not use the
   older pilot build from run 37728381335 for physical acceptance; it targets
   commit `462ad6ca49c049444960e36f83845a15093a740c`. Wait for the current
   native build lane to complete and confirm a matching pilot artifact before
   beginning the five-fixture, no-support PLA procedure.

## 1a. Phone navigation and visual system