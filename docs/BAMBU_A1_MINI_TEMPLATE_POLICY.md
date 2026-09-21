# Bambu A1 Mini machine-template policy

Alloy keeps the resolved Orca/Bambu A1 Mini machine profile as compatibility
evidence, but it does not copy the upstream `machine_start_gcode` or
`machine_end_gcode` into the Android send path. Those templates contain
Bambu-firmware macros (`M1002`, `M620`, `M970`, and others), indexed
multi-material expressions, and conditional template language. A generic
SliceBeam placeholder pass cannot prove that those commands and expressions
are correct for a single-nozzle A1 Mini job.

`parity/audit_bambu_a1mini_templates.py` records the exact UTF-8 byte length
and SHA-256 of both upstream templates, then reports unsupported placeholders,
conditional blocks, and firmware-specific commands. It exits non-zero while a
template still requires an adapter. This makes the open work visible in CI
without making an unsafe template executable by accident.

The current Android native path uses the Alloy-authored
`alloy-safe-a1-mini-v1` baseline. It is deterministic and has explicit
temperature, homing, coordinate-mode, extrusion-mode, heater-shutdown, fan,
and motor-off commands, but it is not claimed to be equivalent to Bambu's
full startup calibration sequence. The result is rejected if the identity
markers are missing, if startup commands are moved outside the startup
section or reordered, if shutdown commands are missing/reordered, or if any
unresolved `{...}` / `[...]` token appears in a command line. The preflight
recognizes only standalone marker lines; the escaped `end_gcode` copy in the
native diagnostic header cannot impersonate the real shutdown section.

The preflight also applies an explicit command allowlist to the marker-defined
startup and shutdown sections. It accepts only commands emitted by Alloy's
reviewed neutral baseline, including millimeter units (`G21`), fan and progress
reporting (`M106`, `M73`), heaters, homing, motion, extrusion mode, and safe
shutdown. Firmware-specific commands such as `M620` are rejected even when
they contain no unresolved placeholder.

Promotion to physical A1 Mini printing still requires all of the following:

- resolve or deliberately replace every upstream placeholder and conditional;
- define a firmware-specific command allowlist and document each Bambu macro;
- capture a real A1 Mini startup, print, pause/resume, cancel, and shutdown
  transcript against the exact firmware family;
- verify the output on a real printer with a small sacrificial test print; and
- keep the engine/profile/parity and LAN certificate-pinning gates green.

Until then, native output remains inspectable and exportable, while the app
continues to mark the engine as unverified for physical printing.
