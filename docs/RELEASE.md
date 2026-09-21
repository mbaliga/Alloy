# Alloy release path

The repository has two Android build paths:

- `Alloy Android v1` builds a CI-debug-signed release APK and runs matching
  release instrumentation for ordinary branch and pull-request verification.
  The signing key is the standard Android debug key and the artifact is not a
  production release.
- `Alloy Android release candidate` runs the same structural/profile/model/
  transport gates, source-builds the pinned native engine and assembles a
  release APK, Android App Bundle, plus its matching release instrumentation
  APK with that engine enabled, then publishes SHA-256 manifests as workflow
  artifacts. On either a `v*` tag or manual dispatch it requires
  organization-owned signing secrets and signs the APK, AAB, and matching
  instrumentation APK.

The production promotion contract is explicit and fail-closed. A production
APK/AAB build must pass `-PalloyProductionRelease=true` together with
`-PalloyNativeEngine=true -PalloyNativeEngineVerified=true` and must use the
organization signing configuration rather than `-PalloyCiDebugSign=true`.
The Gradle guard also requires the reviewed `release/production-readiness.json`
evidence record to exist before it will configure that promotion build.
Start a reviewed record from `release/production-readiness.template.json`;
the template intentionally contains only `PENDING` gates and cannot satisfy
the production validator on its own.
The ordinary CI-debug and emulator commands remain available for development
and test verification, but they cannot be mistaken for a production build.

Release-candidate dispatch requires the organization-owned Android signing
key, protected CI secrets (`ANDROID_KEYSTORE_B64`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEYSTORE_PASSWORD`, and `ANDROID_KEY_PASSWORD`), Play App Signing or
an equivalent signing service, and a tested upgrade path. A tag or manual
dispatch fails closed when those secrets are absent. The signing key must never be
committed to Alloy or stored in a workflow artifact. The signing helper also
compares the SHA-256 signer digest of the app and its matching instrumentation
APK before replacing the unsigned workflow outputs; the pair cannot be
promoted as an accidentally mismatched test harness.

Before any pushed `v*` tag can reach the signing step, CI also requires a
reviewed `release/production-readiness.json` record. The record must mark every
required gate `PASS`, including Bambu TreeSupport3D parity and a physical A1
Mini transport run; emulator/build success alone cannot satisfy those fields.
When physical transport is marked `PASS`, the record must additionally point to
the reviewed `physical-a1-mini-acceptance.json` beside it. That nested record
is validated for the A1 Mini `N1` model code, APK/signer/certificate hashes,
and every upload/start/telemetry/recovery case. The schemas are enforced by
`ci/validate_release_readiness.py` and
`ci/validate_physical_a1_acceptance.py`.

`alloyNativeEngineVerified` is explicitly forced to `false` in the release
candidate workflow; setting it to `true` is an
explicit promotion switch only after the native engine gates, profile parity,
and physical-printer acceptance are complete. Gradle rejects the inconsistent
case where that flag is enabled without the native engine itself. The profile asset's own
`verified` flag must also be promoted as part of that same reviewed change.

Before calling a build a production release, all of these gates must pass:

1. the optional native engine builds for `arm64-v8a`;
2. `NativeEngineSmokeTest` passes on a supported arm64 Android device;
3. the pinned A1 Mini profile passes the automated Orca snapshot/blob check and
   the remaining G1-G4 source, runtime and parity checks;
4. exported `.gcode.3mf` packages pass structural and semantic comparison,
   including the bounded comment-aware G-code safety preflight;
5. a real A1 Mini confirms upload, start, running, completion and cancellation
   telemetry over the LAN transport, including the FTPS control/data TLS
   session-reuse requirement; the start path must also refuse an explicit
   already-busy telemetry state before publishing `project_file`; and
6. signed Android installation, upgrade, interruption, offline recovery and
   release-artifact checks pass on supported devices.

The release workflow's instrumentation APK is a compiled test harness, not
proof that the native runtime passes. It must be installed with the matching
release APK on an ARM64 device and run through the native smoke and pipeline
tests before signing or promotion.

The interruption check must include killing/restarting Alloy during upload,
start-request and running states. The durable checkpoint and app-private
staged artifact must survive that restart; recovery must revalidate the
artifact's package structure, size and SHA-256 before exposing it. The durable
checkpoint must surface
`RECOVERY_REQUIRED`; Alloy must not automatically retry, resume or report a
terminal printer state without fresh telemetry or explicit user review.
Malformed or partially written printer checkpoints must also remain visible as
`RECOVERY_REQUIRED` and keep sending disabled until the record is dismissed.
The same test must recreate the Activity while the foreground
`PrinterJobService` owns a live transaction and confirm that the service lease
keeps the upload/monitoring session alive; only an actual process loss should
promote the checkpoint to recovery.

The multi-plate convenience path must also be covered: every populated plate
is sliced and staged independently by `BatchSliceJobService`, the immutable
request survives Activity recreation, and the exported `.alloy-batch.zip` is
validated as a bundle of nested `.gcode.3mf` files. A process loss must surface
`RECOVERY_REQUIRED`; partial plate results must not be presented as a complete
batch. That archive is not a multi-plate Bambu LAN transaction; the user still
selects and confirms one plate for upload.

The normal single-plate Slice action must also be tested while the Activity is
rotated and backgrounded. `SliceJobService` must retain the immutable request,
continue under the `dataSync` foreground-service type, and deliver a result
only after the raw G-code, metadata, package structure and artifact identity
have been revalidated. Killing the process during slicing must leave an
explicit `RECOVERY_REQUIRED` checkpoint and must not silently retry or expose
an unverified artifact.

Linux packaging is intentionally a frozen secondary target and should reuse the
validated native engine/profile/transport contracts rather than fork slicing
behavior. The current Linux deliverable has a dedicated contract workflow
covering model inspection, safe non-shell slicer invocation, and `.gcode.3mf`
validation. A resizable Linux GUI and Alloy-owned native desktop engine remain
separate future release gates; Android is the only active product release
target for now.
