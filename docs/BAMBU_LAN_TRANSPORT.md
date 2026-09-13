# Bambu A1 Mini LAN transport proof

Status: protocol tool committed; **physical-printer gate not yet executed**.

Alloy must not treat an MQTT publish as proof that a print actually started. The Android implementation will subscribe to printer telemetry and model upload/start/accepted/running/error as distinct states.

## Current Developer Mode contract

For the A1 Mini LAN path being targeted:

- implicit FTPS: TCP 990
- MQTT over TLS: TCP 8883
- username: `bblp`
- password: printer LAN/Developer access code
- command topic: `device/<serial>/request`
- report topic: `device/<serial>/report`
- print start command: `print.project_file`

The recommended artifact is `.gcode.3mf`, not plain `.gcode`.

## FTPS session reuse

The printer's FTPS server may require TLS session reuse between the FTP control and data connections. Generic FTP clients can authenticate successfully and then fail data transfer with a 522 error.

For the standalone proof, curl/OpenSSL remains the known-working path. For Alloy Android, the FTPS client must explicitly support implicit TLS and session reuse. Apache Commons Net `FTPSClient` with a data-socket session-reuse hook is a candidate, but it is not accepted until tested on the physical A1 Mini.

## Remote path and project URL

Two A1-family patterns exist in current implementations:

1. upload to `/cache/<name>.gcode.3mf` and start with `file:///sdcard/cache/<name>.gcode.3mf`
2. upload to FTP root and start with `ftp:///<name>.gcode.3mf`

For Alloy, prefer the simpler **root upload + `ftp:///...`** path first because it directly couples the uploaded FTP name to the print URL. The exact accepted path is a physical-firmware gate and must be recorded with the printer firmware version.

## A1 Mini no-AMS defaults

Initial target: non-Combo A1 Mini using an external spool.

- `use_ams`: false
- `ams_mapping`: **do not guess**

The mapping field is the least certain part of the payload for an external-spool/no-AMS A1 Mini. First hardware test should try omission/empty mapping and record printer telemetry. Alloy must not expose AMS mapping UI until that is validated.

## Print-start fields

The `project_file` command should carry only job-start values that are actually start-time controls, including:

- `sequence_id`
- `command: project_file`
- `param: Metadata/plate_1.gcode` for plate 1
- `subtask_name`
- `url`
- local IDs (`project_id`, `profile_id`, `task_id`, `subtask_id`) as required by accepted local payload
- `timelapse`
- `bed_type`
- `bed_leveling` / firmware-compatible spelling if required
- `flow_cali`
- `vibration_cali`
- `layer_inspect`
- `use_ams: false`
- verified no-AMS mapping behavior

Layer height, temperature, infill, wall generation, and other slicer settings are baked into the `.gcode.3mf` and are not start-time overrides.

## Verification sequence per job

1. FTPS upload completes successfully (`STOR`/transfer returns success).
2. When possible, LIST the destination and confirm filename + size.
3. Publish `project_file`.
4. Subscribe to `device/<serial>/report`.
5. Wait for telemetry showing the expected `subtask_name` and a transition to PREPARE/RUNNING.
6. A successful MQTT publish without status confirmation is **not** print acceptance.
7. Cancellation/control is tested separately before transport is considered complete.

## Safe physical validation order

1. Keep the printer in its normal cloud configuration during slicer/profile work.
2. Produce a known-good tiny `.gcode.3mf` using desktop Bambu Studio/OrcaSlicer for A1 Mini, PLA, external spool.
3. Enable LAN/Developer Mode for the transport gate.
4. Record IP, serial, firmware, and access code locally; never commit credentials.
5. Run upload-only first.
6. Verify remote file presence.
7. Run the start command only with a tiny, physically safe fixture loaded and the correct plate/filament installed.
8. Confirm printer screen + MQTT report show the intended job and RUNNING state.
9. Test stop/cancel.
10. Record exact URL form, mapping behavior, accepted payload, and firmware in this document.
11. Only after this end-to-end proof, wire the same transport semantics into Alloy.

## Existing probe

```bash
python -m pip install -r tools/requirements.txt

python tools/bambu_lan_probe.py \
  --host <printer-ip> \
  --serial <printer-serial> \
  --access-code <lan-access-code> \
  --file ./known-good.gcode.3mf \
  --upload-only
```

The probe is a bench tool, not the shipping Android transport. It must be updated to the physically accepted path/payload after hardware validation and should wait for report telemetry before declaring a start successful.

## Remaining transport work

- verify exact path/URL on target A1 Mini firmware
- verify external-spool `ams_mapping` omission/empty behavior
- subscribe to and parse status/report MQTT telemetry
- implement cancellation, disconnect, timeout and retry semantics
- implement Android implicit-FTPS session reuse
- Keystore-backed credential storage
- printer discovery/pairing UX
- remote file verification where protocol permits it
- ensure no credential leakage through Android logs/crash reporting
- add fake-printer integration tests before real-printer manual gates

## Non-goal

Alloy will not reproduce or ship Bambu's proprietary networking plugin. LAN transport stays behind `PrinterTransport`, allowing future open-networking adapters without coupling them to slicing.
