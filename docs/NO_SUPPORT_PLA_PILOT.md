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

1. **Do not install an older pilot APK.** The previously published pilot
   artifact from [run 37749868229](https://github.com/mbaliga/Alloy/actions/runs/37749868229)
   predates the separate pilot application ID and is not safe to describe as
   side-by-side with an existing Alloy install. Do not use it for a new trial,
   and do not uninstall an existing app to make it fit. The replacement build
   is being verified in [native run 37768795482](https://github.com/mbaliga/Alloy/actions/runs/37768795482)
   for source commit
   [4c41928](https://github.com/mbaliga/Alloy/commit/4c41928faa645af59185f97966e2a3c6cbc7f76e).
   Wait for that run to finish successfully and confirm its
   `alloy-a1-mini-physical-pilot-ci` artifact is present before downloading
   or installing it. CI now asserts the app/test package IDs are
   `com.mbaliga.alloy.pilot` and `com.mbaliga.alloy.pilot.test`. Only that
   verified replacement build is intended to install alongside regular Alloy.
   It remains CI-debug-signed, is not a production release, and is not a
   physical-print qualification. The matching `app-release-androidTest.apk`
   is optional for the phone-only flow. The artifact's
   `evidence/run-*/software-pilot-manifest.json` is software evidence only;
   it must say `software_only: true` and leave
   `physical_qualification: PENDING` and
   `direct_send_qualification: PENDING`.
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
