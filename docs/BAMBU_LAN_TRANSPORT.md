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
- print start command: `print.project_file`

The probe uploads a known-good `.gcode.3mf` to `cache/<filename>` and uses a `file:///sdcard/cache/<filename>` project URL.

## Why curl is used for the proof

Some Bambu FTPS firmware requires TLS session reuse between the FTP control and data connections. Generic FTP client implementations can authenticate successfully and then fail file transfer with a 522 TLS error. The standalone probe therefore uses curl/OpenSSL for the upload rather than making Python's FTP stack part of the protocol proof.

This does **not** dictate Alloy's final Android FTPS implementation. The Android transport must explicitly support the printer's implicit-TLS/session-reuse behavior and be tested against the physical A1 Mini.

## Safe test order

1. Keep the printer in its normal cloud configuration during slicer/profile work.
2. Produce a known-good tiny `.gcode.3mf` using desktop Bambu Studio/OrcaSlicer.
3. Enable the printer's LAN Developer Mode only for this gate.
4. Record IP, serial and access code locally; never commit them.
5. Run the probe with `--upload-only`.
6. Verify the file is present on printer storage.
7. Run again without `--upload-only` only with a tiny, physically safe fixture loaded and the correct plate/filament installed.
8. Confirm the MQTT command is accepted and the printer transitions to a print state.
9. Record firmware version and exact accepted payload.
10. Capture telemetry needed to distinguish upload success, start acceptance, running and error.
11. Disable Developer Mode again if the user wants to return to Bambu cloud/Handy operation.

## Commands

```bash
python -m pip install -r tools/requirements.txt

python tools/bambu_lan_probe.py \
  --host <printer-ip> \
  --serial <printer-serial> \
  --access-code <lan-access-code> \
  --file ./known-good.gcode.3mf \
  --upload-only
```

The tool requires typing the literal word `PRINT` before it sends a physical print-start command.

## Remaining transport work

- verify exact payload on the target A1 Mini firmware
- subscribe to and parse status/report MQTT telemetry
- test cancellation, disconnect and retry semantics
- choose/implement Android implicit-FTPS client behavior
- Keystore-backed credential storage
- printer discovery/pairing UX
- checksum/remote-file verification where the printer protocol permits it
- ensure no credential leakage through Android logs/crash reporting
- add fake-printer integration tests before real-printer CI/manual gates

## Non-goal

Alloy will not reproduce or ship Bambu's proprietary networking plugin. LAN transport stays behind `PrinterTransport`, allowing a future Bambuddy/open-networking adapter without coupling it to slicing.
