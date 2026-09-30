# Physical A1 Mini acceptance runbook

This runbook is the final external validation step before Alloy can enable
physical sending. Emulator tests, fake-printer tests, and a successful MQTT
publish do not satisfy this runbook.

## Preconditions

- Use an actual Bambu Lab A1 Mini on a private, reachable LAN.
- Record the printer model code and firmware version from authenticated
  telemetry. The A1 Mini model code must be `N1`.
- Pair the printer in Alloy and verify the printer leaf certificate SHA-256
  pin. Do not disable certificate pinning or use a wildcard pin.
- Use a release APK and matching release instrumentation APK signed by the
  same certificate. Record both APK SHA-256 values and the signer digest.
- Start with a clean build plate, a known PLA spool, and a documented nozzle
  and bed condition.

## Controlled no-support pilot APK

Before production qualification, a deliberately restricted CI-debug pilot APK
may collect the first real A1 Mini transport evidence. Build it only with
`-PalloyNativeEngine=true -PalloyPhysicalPilot=true -PalloyCiDebugSign=true`.
Gradle rejects that property combination when production or native-engine
verification is requested. The app labels the route **PILOT BUILD** and allows
only the five immutable bundled fixtures under the pinned A1 Mini / 0.4 mm /
PLA Basic / supports-off recipe. It still requires an N1 discovery result and
leaf SHA-256 certificate pin, then presents distinct upload and start
confirmations. Treat all results as acceptance evidence, never as a general
direct-print authorization.

## Required evidence matrix

| Case | Action | Required evidence | Failure condition |
| --- | --- | --- | --- |
| Discovery | Pair and refresh telemetry | model `N1`, serial, firmware, temperatures, state | model is unknown or telemetry is stale |
| Upload | Send a verified `.gcode.3mf` | authenticated FTPS upload and remote filename | upload succeeds without a pinned leaf |
| Start | Confirm the print in Alloy | MQTT start request and fresh PREPARE/RUNNING telemetry | publish succeeds but state is unconfirmed |
| Running | Background/recreate Activity | foreground service keeps lease and telemetry continues | UI recreation cancels or duplicates the job |
| Completion | Let the cube complete | DONE/COMPLETED telemetry and one inventory consumption record | duplicate material deduction or no completion evidence |
| Pause/resume | Pause, observe, resume | state transitions and fresh telemetry for each transition | local UI state is treated as printer truth |
| Cancel | Start a second small job and cancel | cancellation request plus terminal canceled state | cancellation is reported without confirmation |
| Recovery | Kill Alloy during upload/start/running | `RECOVERY_REQUIRED`; no automatic retry or send action | stale checkpoint exposes Print/Resume |
| Offline | Disable network before send | send remains unavailable and no artifact is lost | app silently queues an unreviewed physical job |

## Evidence record

Save one reviewed JSON record at `release/physical-a1-mini-acceptance.json`.
The record is intentionally separate from the app database and must not
contain the printer password, private key, or API key. Redact SSIDs and public
IP addresses before sharing it.

Validate the reviewed record before attaching it to a release candidate:

```sh
python3 ci/validate_physical_a1_acceptance.py \
  release/physical-a1-mini-acceptance.json
```

```json
{
  "schema_version": 1,
  "evidence_id": "a1-mini-<date>-<operator>",
  "verified_at": "<UTC ISO-8601 timestamp>",
  "reviewer": "<operator>",
  "device_scope": "Bambu Lab A1 Mini / N1 / firmware <version>",
  "apk_sha256": "<release APK hash>",
  "instrumentation_sha256": "<matching test APK hash>",
  "signer_sha256": "<shared signer digest>",
  "printer_certificate_sha256": "<leaf certificate hash>",
  "cases": {
    "discovery": "PASS",
    "upload": "PASS",
    "start": "PASS",
    "running": "PASS",
    "completion": "PASS",
    "pause_resume": "PASS",
    "cancel": "PASS",
    "recovery": "PASS",
    "offline": "PASS"
  },
  "notes": "<operator notes; no secrets>"
}
```

The production-readiness record may mark `physical_a1_mini_transport` as
`PASS` only after this matrix is complete and the evidence has been reviewed.
Until then Alloy must keep `NATIVE_ENGINE_VERIFIED=false` and the physical
send action fail-closed.
