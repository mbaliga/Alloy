# Bambu A1 Mini LAN transport proof

Status: Android transport adapter and protocol tool committed; **physical-printer gate not yet executed**.

Alloy must not treat an MQTT publish as proof that a print actually started. The Android implementation subscribes to printer telemetry and models upload/start/accepted/running/error as distinct states. Immediately before publishing `project_file`, it also blocks explicit `PREPARE`, `RUNNING`, `PAUSE`, `PAUSED`, and `SLICING` snapshots so a phone cannot replace a visibly active job; firmware that provides no immediate snapshot remains compatible and is still subject to post-start telemetry confirmation.

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

When the phone has a rendered preview, Alloy includes bounded PNG copies at
`Metadata/plate_1.png`, `Metadata/plate_1_small.png`,
`Metadata/plate_no_light_1.png`, `Metadata/top_1.png`, `Metadata/pick_1.png`
and `Metadata/bbl_thumbnail.png`, matching the thumbnail roles used by the
open BambuStudio archive writer. The 3MF model is also emitted as a related
`3D/Objects/object_1.model` with a relationship from the package wrapper;
`model_settings.config`, `cut_information.xml` and the single-filament
assignment are included. This improves inspection and package
interoperability, but is not yet proof that Alloy's package is accepted by
every Bambu firmware version.

The export also includes Bambu-shaped `slice_info.config` XML, model/project
settings snapshots, a comment-based `print_profile.config`, a filament
sequence record, and `Metadata/plate_1.gcode.md5`. Alloy validates the digest
and the canonical metadata shape before staging. These fields improve
interoperability but do not replace a physical-printer acceptance test.

Physical upload/control is restricted to the confirmed target model. Discovery
records `N1` for an A1 Mini alongside the encrypted host, serial and access-code
credentials; manual pairing may enter a model code, but an empty or different
code is rejected by both the Activity and foreground service before upload.

## FTPS session reuse

The printer's FTPS server may require TLS session reuse between the FTP control and data connections. Generic FTP clients can authenticate successfully and then fail data transfer with a 522 error. Alloy's read-only probe and pinned physical-print path both use Bouncy Castle JSSE (BCJSSE); the probe retains Android's system trust store, while the send path additionally pins the printer leaf. The transport uses the explicit `BCSSLSocket.setBCSessionToResume` API, forces TLS 1.2 for this service, and compares the resumed data-session ID with the control-session ID before `STOR`.

The standalone proof may use curl/OpenSSL. Alloy Android now has an explicit
session-reuse implementation, but the physical A1 Mini gate remains open until
the implementation is exercised against the printer and a successful upload is
observed. A code path is not hardware evidence.

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
2. Alloy issues `SIZE` and confirms the printer stored the expected artifact
   byte count before reporting upload success.
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

Alloy's cancellation monitor requires an earlier telemetry packet to identify
the requested artifact and an accepted PREPARE/RUNNING state before accepting
the final idle state. Completion and firmware-cancelled states use the same
accepted-job requirement. This preserves support for firmware that omits
filename fields after a stop without treating stale or unrelated telemetry as
job evidence.

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

The probe is a bench tool, not the shipping Android transport. It now subscribes
to the report topic before publishing and exits successfully only after a
matching structured PREPARE/RUNNING report; it still must be updated to the
physically accepted path/payload after hardware validation.

## Implemented in the Android boundary

BambuLanTransport now provides:

- implicit-TLS FTPS upload on port 990 with binary mode, passive data transfer,
  progress callbacks, staged-file and remote `SIZE` verification, and local
  SHA-256 verification before upload (the printer's FTP surface does not expose
  a portable remote SHA-256 operation);
- MQTT 3.1.1 over TLS on port 8883 using bblp credentials;
- project_file start payload generation using the conservative local-print
  fields (`bed_type: auto`, both leveling spellings, empty optional `file` and
  `md5`, and no guessed AMS mapping);
- report-topic monitoring that parses the structured `print` object and requires
  job-specific PREPARE/RUNNING evidence; malformed JSON and matching text in
  unrelated report fields are ignored;
- a read-only status snapshot that waits for one bounded report and exposes only
  structured state, progress, temperatures and remaining minutes to the phone UI;
- terminal completion/error handling and a stop request that waits for idle
  telemetry; and
- pause and resume requests that wait for matching job telemetry before
  changing the durable printer state. Once a stop/pause/resume publish is
  attempted, a write failure becomes `RECOVERY_REQUIRED` rather than an
  automatically retryable failure; and
- default certificate validation plus explicit SHA-256 certificate pinning for
  a paired self-signed printer. The pairing dialog can perform a handshake-only
  leaf-certificate inspection to populate the digest; that helper captures the
  certificate and closes immediately, sends no MQTT/FTP command or credential,
  and never authorizes a print session. There is no trust-all data path.
- canonical printer-host validation shared by discovery, pairing, durable
  credentials and every transport target: IPv4, IPv6 (including a bounded
  link-local zone identifier), and DNS/mDNS names are accepted; URL schemes,
  ports, paths, credentials, malformed IPv4, and bracketed non-IPv6 values are
  rejected before socket or MQTT code runs. Bracketed IPv6 is normalized to its
  raw host form before it is persisted.

ArtifactStore stages the package to durable app-private storage, flushes it to
disk before returning it to the transport, and exposes the digest through the
typed PrinterTransport.Artifact object. It retains a bounded history of staged
jobs and can recover a checkpointed artifact only after revalidating its size,
package structure and SHA-256 digest. The recovered artifact is kept separate
from the currently loaded model/slice, so importing another model cannot
silently erase an unconfirmed printer transaction.
Each send attempt is assigned a fresh persisted transaction ID. Transport
callbacks must present that ID, the paired printer identity, and the immutable
artifact digest before changing the checkpoint; a late callback from an older
attempt therefore cannot overwrite a later attempt using the same artifact.
The checkpoint store also permits only forward transitions within one job and
promotes in-flight records to explicit recovery after restart. It persists the
slice's material estimate with the transaction; the inventory ledger consumes
that estimate only after matching `COMPLETED` telemetry and ignores a repeated
completion for the same transaction ID.

`PrinterJobService` owns the live Android upload/start/telemetry/cancel session
and posts a low-noise foreground notification. Activity recreation reattaches
to the durable checkpoint through the service's persisted lease; a process
restart has no live in-memory owner and still requires explicit recovery review.

The protocol boundary is also fail-closed on malformed input: artifact
constructors require a 64-character SHA-256 digest and matching source size,
MQTT remaining lengths are limited to the four-byte protocol encoding and
bounded packet size, QoS 1/2 PUBLISH packets account for their packet
identifier, FTP multiline replies are bounded, and PASV octets are range
checked. Paired serials are restricted to literal MQTT topic tokens and
access codes are bounded to one protocol-safe line before they can reach the
FTP `PASS` command. These checks improve resilience but are not a substitute
for the physical-printer acceptance run.

## Remaining transport work

- verify exact path/URL on target A1 Mini firmware
- verify external-spool `ams_mapping` omission/empty behavior
- confirm the report payload/state vocabulary on the target firmware
- confirm cancellation completion semantics and retry policy on the target
- verify the Android implicit-FTPS session-reuse implementation on the
  physical printer
- verify the Keystore-backed pairing flow, including the handshake-only
  certificate inspection, against a real printer and ensure
  credentials never reach logs/crash reporting
- physical-device discovery acceptance; the Android pairing flow now performs
  a bounded, read-only SSDP scan on UDP 2021 and can pre-fill the discovered
  host/serial/model, while manual host/serial entry remains available for
  VLANs and networks that block discovery
- physical verification that the remote `SIZE` result matches the staged
  artifact on the target firmware
- replace the deterministic fake-printer transcript with a captured, reviewed
  real A1 Mini transcript covering malformed-server, reconnect-exhaustion and
  ambiguous-control-command behavior
  behavior before physical release gates

Physical upload/start is fail-closed on a paired SHA-256 leaf-certificate pin
in the Android Activity and foreground service. The read-only probe may still
use the platform trust store through BCJSSE to support setup on printers with
a trusted certificate; the Bambu self-signed case must be pinned before any
physical job can be sent.

## Non-goal

Alloy will not reproduce or ship Bambu's proprietary networking plugin. LAN transport stays behind `PrinterTransport`, allowing future open-networking adapters without coupling them to slicing.
