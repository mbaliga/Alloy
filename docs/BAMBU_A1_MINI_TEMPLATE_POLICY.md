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

## Exact-source timing investigation, 2026-10-17

Application source `05981d75e70026cdb011f5d6ea9cf8a33609db52` passes Android
acceptance and native instrumentation, but native run `38979999591` fails G3.
The supported-overhang support path is within 0.18% of desktop Orca and its
positive support extrusion within 0.07%; this supersedes the older large
support discrepancy as the next debugging target. It does not certify spatial
toolpath equivalence. Total filament still differs by 2.31% (length) and 2.12%
(mass), and all four fixtures fail print-time parity.

The exact desktop target is OrcaSlicer v2.4.2, not a newer Bambu Studio source
snapshot. Comparing its `GCodeProcessor.cpp` with Alloy shows the same
reverse-then-forward motion-planning order. The main differences concern
buffered additional/tool-change time. A speculative port of Bambu Studio's
different planner order is therefore not justified by the current evidence.

The desktop cube G-code explicitly includes `SKIPTYPE: timelapse` and
`SKIPTYPE: head_wrap_detect` blocks between model features. They include
travel to X0/X-13 and a 300 ms shutter wait. Alloy's baseline has no equivalent
per-layer machine template. Firmware flag conditions are not evidence that
these motions actually execute on the user's printer. The aggregate timing
comparison currently includes these differing emitted programs.

Next implementation work:

1. Attribute machine-template motion/waits separately from deposited model
   and support motion, including the return trip after a template. Retain raw
   full-program metrics and the existing strict G3 failures.
2. Reconcile filament accounting (deposited extrusion, retraction recovery,
   priming, support and waste) against the exact desktop output. Do not apply
   an empirical correction factor to force header totals to match.
3. Resolve the pinned Bambu machine-template adapter and its firmware scope,
   with tests for every placeholder, conditional and command. Diagnostic
   template exclusions are not permission to drop printer safeguards or to
   label the neutral baseline production-equivalent.
4. Rerun exact-source slicing comparisons, then perform the separately gated
   hardware qualification. Do not loosen tolerances or enable sending just
   because deposited support totals are close.

Evidence: [G3 report artifact](https://github.com/mbaliga/Alloy/actions/runs/38979999591/artifacts/12082788519),
SHA-256 `31c38e917fff960ddd7ded384392c0a8540f60673293117ccbe87eaa5ae568a3`;
[native G-code evidence](https://github.com/mbaliga/Alloy/actions/runs/38979999591/artifacts/12083501725),
SHA-256 `cb1df21deb7dcd2af6c8ab7e21cd3e7d68f2ba46c2712d096b3363a53b1f377d`.
