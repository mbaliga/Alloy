# Physical A1 Mini acceptance gate

This checklist is intentionally fail-closed. Emulator tests and the fake-printer
transport suite prove the Android-side state machine; they do not prove that a
real A1 Mini accepts and executes a `.gcode.3mf` produced by Alloy.

## Required setup

- A real Bambu Lab A1 Mini in Developer/LAN mode.
- Android phone and printer on the same reachable network.
- Printer serial, access code, and the exact leaf-certificate fingerprint
  captured during explicit pairing.
- A disposable test model and the matched A1 Mini/PLA profile.
- The release APK/AAB hash and Alloy commit recorded with the run.

## Required evidence

Record timestamps, screenshots or screen recordings, printer telemetry, and the
local artifact SHA-256 for every step. A run is not accepted if any command's
outcome is inferred only from a socket write.

1. Pairing discovers or accepts the printer, inspects the certificate, and saves
   the explicit fingerprint without transmitting MQTT/FTPS commands first.
2. Read-only telemetry returns the expected serial/model and a non-busy state.
3. Alloy exports a valid `.gcode.3mf`; its local package validator passes.
4. FTPS upload completes; remote size and artifact identity are revalidated
   immediately before start.
5. Start produces telemetry for the matching remote job and reaches RUNNING.
6. Pause reaches PAUSED, resume reaches RUNNING again, and neither command is
   sent twice after a lost telemetry connection.
7. Cancel reaches the printer's idle/cancelled state and records completion.
8. Force-close/relaunch during upload, start, and monitoring. Alloy must restore
   an explicit recovery state, never silently retry a physical side effect.
9. A mismatched certificate, busy printer, wrong remote size, malformed
   telemetry packet, and disconnected printer each fail closed with a
   user-actionable recovery state.

## Promotion rule

All nine steps must pass on the same physical printer and release artifact.
Until then, Alloy remains a closed alpha and the physical-send gate stays open.

Suggested evidence directory:

`evidence/physical-a1/<date>-<printer-serial>/`

Keep credentials and certificate private; store only redacted logs, hashes,
telemetry summaries, and screenshots in a shareable evidence bundle.
