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

1. Download the matching pilot app and instrumentation APK pair from
   [native-build run 37745898443](https://github.com/mbaliga/Alloy/actions/runs/37745898443),
   artifact `alloy-a1-mini-physical-pilot-ci` (ZIP SHA-256
   `6c0821a9fd4135523b189d6424ce13b37c64910fbcf5694af2956b35a95d935b`;
   expires **2027-01-06**). This is the latest successful isolated pilot pair
   available; CI passed its five-fixture emulator software checks. That does
   not qualify a physical printer. Do not use the older pilot artifact from run
   37728381335. If building locally instead, use the exact isolated command
   below; the build is rejected if a production flag or native verification
   claim is supplied:

   ```sh
   gradle :app:assembleRelease :app:assembleReleaseAndroidTest \
     -PalloyNativeEngine=true \
     -PalloyPhysicalPilot=true \
     -PalloyCiDebugSign=true
   ```

   The app must visibly describe the path as a **PILOT BUILD**. A normal APK
   never exposes this route.
2. Install both APKs on the physical Android phone. Run the deliberately
   opt-in evidence export below. It slices the five fixtures with the native
   engine, rejects any support toolpath, validates the A1 template, and keeps
   each `.gcode.3mf` plus a software-only hash manifest in Alloy's app-external
   storage. The normal regression test deletes its temporary artifacts; only
   this explicit command retains them.

   ```sh
   adb shell am instrument -w -r \
     -e class com.mbaliga.alloy.NoSupportPlaPilotTest \
     -e export-no-support-pilot true \
     com.mbaliga.alloy.test/androidx.test.runner.AndroidJUnitRunner

   adb pull /sdcard/Android/data/com.mbaliga.alloy/files/alloy-no-support-pilot \
     ./alloy-no-support-pilot-evidence
   ```

   Open `software-pilot-manifest.json` in the pulled run directory and retain
   its five package hashes. It explicitly says `software_only: true` and
   `physical_qualification: PENDING`; it is evidence for package inspection,
   not permission to upload or start a printer job.
3. Record the release APK, instrumentation APK, signer and profile hashes.
4. For every fixture, retain the artifact hash, a photograph of the first
   layer and completed part, caliper measurements where applicable, and
   redacted printer telemetry. Keep source evidence outside Git if it includes
   printer identifiers or local-network data.
5. In that pilot build only, open exactly one of the five bundled fixtures
   from Library, make no geometry or recipe changes, slice it, pair the N1
   printer with its leaf SHA-256 certificate pin, and use **Send to printer**.
   Alloy shows a second explicit “Upload controlled pilot package?” consent
   before it uploads; it shows a separate explicit Start consent afterward.
   Record the resulting authenticated upload and fresh PREPARE/RUNNING
   telemetry. Any other model, profile, material, support setting or ordinary
   build stays blocked.
6. Complete the physical transport acceptance runbook separately. This pilot
   collects its evidence but does not authorize a general direct-print claim,
   native-engine promotion, support parity, A1/P1S qualification or a
   production release.

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
