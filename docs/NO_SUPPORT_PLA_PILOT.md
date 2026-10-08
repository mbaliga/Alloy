# A1 Mini no-support PLA pilot

This is Alloy's first physical-print qualification milestone. It is deliberately
smaller than production approval: **A1 Mini (model N1), 0.4 mm nozzle, PLA
Basic, textured PEI, one colour, no AMS and no supports.** It does not promote
the native engine, profile, printer transport or production release flags.

## Why the pilot excludes supports

Bambu TreeSupport3D parity remains an independent open gate. A print without
supports must not inherit that unresolved risk. Do not enable supports for any
fixture in this pilot, and do not mark this evidence as support evidence.

## Required build and on-phone checks

1. Download and extract the latest successful
   [native-build artifact from run 37749868229](https://github.com/mbaliga/Alloy/actions/runs/37749868229),
   `alloy-a1-mini-physical-pilot-ci` (ZIP SHA-256
   `47c9795fafe8205ae6cf3198d2634eb7fa5492cd9feee015ec3d38f75b82129f`;
   expires **2027-01-06**). On the Android phone, install only
   `app-release.apk` from the extracted folder. The matching
   `app-release-androidTest.apk` is for optional instrumentation runs and is
   not required for the in-app physical-pilot flow. The pilot uses a separate Android application ID, so it installs alongside
   the regular Alloy app and avoids overwriting its projects, inventory, and
   printer credentials. Keep the regular app installed. The pilot remains
   CI-debug-signed and is not a production release. The artifact
   also includes
   `evidence/run-*/software-pilot-manifest.json`; keep it as the CI software
   record. It includes package/profile/build hashes and explicitly says
   `software_only: true`, `physical_qualification: PENDING`, and
   `direct_send_qualification: PENDING`. It never authorizes a printer job.
   CI passed the five-fixture software checks for this exact source. That does
   not prove this phone's runtime or a physical print. Do not use the older
   pilot artifact from run 37728381335.
2. Confirm the app visibly labels itself **PILOT BUILD**. On the phone, open
   Library and select an untouched bundled fixture. Use only the pinned
   A1 Mini / N1 profile, 0.4 mm nozzle, PLA Basic, textured PEI, one colour,
   external/direct feed, no AMS, and supports off. Do not transform the fixture
   or change its recipe. Slice and inspect the toolpath and preflight result.
   Repeat the in-app slice/preflight for each of the five fixtures listed below.
   Export the `.gcode.3mf` to Files if you need a phone-accessible copy.
3. Pair the printer from Alloy, confirm the discovered model code is N1, and
   save the printer's leaf-certificate SHA-256 pin. For each fixture, use
   **Send to printer** only after its unchanged pilot checks pass. Confirm the
   separate “Upload controlled pilot package?” prompt, then the separate Start
   prompt. Confirm fresh authenticated upload and PREPARE/RUNNING telemetry.
   Print each fixture in turn; a successful in-app slice or upload is not a
   completed physical acceptance check.
4. For every physical fixture, retain its artifact hash, first-layer and
   completed-part photos, caliper measurements where applicable, and redacted
   printer telemetry. Record each check in
   `release/no-support-pla-pilot.json`. Keep evidence outside Git if it
   includes printer identifiers or local-network data. If motion, temperature,
   material routing, adhesion or printer state is unexpected, stop; do not
   retry commands automatically.
5. A desktop with ADB is optional for rerunning the Android instrumentation
   export below; it is not required to install the pilot app, slice, or run its
   guarded physical flow from the phone. The export remains software-only and
   does not replace the five physical checks. If you do rerun it, install the
   matching instrumentation APK and use:

   ```sh
   adb shell am instrument -w -r \
     -e class com.mbaliga.alloy.NoSupportPlaPilotTest \
     -e export-no-support-pilot true \
     com.mbaliga.alloy.pilot.test/androidx.test.runner.AndroidJUnitRunner

   adb pull /sdcard/Android/data/com.mbaliga.alloy.pilot/files/alloy-no-support-pilot \
     ./alloy-no-support-pilot-evidence
   ```

Run the record validator only after human review:

```sh
python3 ci/validate_no_support_pla_pilot.py \
  release/no-support-pla-pilot.json
```

Start from `release/no-support-pla-pilot.template.json`. It intentionally
cannot pass until every fixture and check is recorded as `PASS`.

## Fixture intent

| Fixture | What it qualifies |
| --- | --- |
| `box-20mm` | dimensional baseline and first-layer calibration |
| `mounting-block` | ordinary functional walls and corners |
| `thin-wall-frame` | thin-wall toolpath integrity |
| `travel_obstacle` | travel planning around separated geometry |
| `box-and-lid` | multi-part geometry and plate preparation |

Any unexpected motion, collision risk, package rejection, loss of telemetry,
or material/result mismatch fails the pilot. Do not retry a printer command
automatically; capture the state and investigate first.
