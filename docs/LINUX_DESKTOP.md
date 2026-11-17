# Linux desktop path

Alloy is Android-first. The Linux deliverable now includes a small Tkinter
windowed model workspace plus the dependency-free headless bridge. The windowed
surface is deliberately thin and uses the same bounded model/artifact safety
boundary; it is a model-preparation companion while an Alloy-owned native
desktop engine is being proven. Install the platform's `python3-tk` package if
Tkinter is not already present.

## Open models in the Linux workspace

From the repository root:

```bash
python3 tools/alloy_linux_gui.py
python3 tools/alloy_linux_gui.py --model ./my-model.3mf
```

The window opens the bundled box-and-lid assembly when no model is supplied.
Drag the canvas to orbit, scroll to zoom, and use **Model atlas** to inspect the
box, lid and mounting block. The details panel shows dimensions, solids,
triangle count and mesh-health counters. **Slice** invokes the explicit Bambu
Studio or OrcaSlicer bridge; it never publishes an artifact until the
`.gcode.3mf` validator passes.

## Inspect a model

The command reads STL, OBJ or 3MF, reports dimensions, triangle count, disconnected
solids, and mesh health, and refuses oversized, malformed, or unsafe input:

```bash
python3 tools/alloy_linux.py inspect --json app/src/main/assets/models/box-and-lid.stl
python3 tools/alloy_linux.py inspect --json ./my-model.3mf
```

The OBJ reader preserves object/group names, supports ordinary texture/normal
slash-qualified face references and negative vertex indices, and triangulates
polygons. The 3MF reader follows the package root relationship, applies build/component
transforms, honors the declared unit, and reports each build item as a part.
This keeps box assemblies and imported multi-part designs inspectable before a
slicer is started.

Inspect a portable phone project without unpacking it into the working tree:

```bash
python3 tools/alloy_linux.py inspect-project ./Box\ project.alloy.zip
```

The project command validates the bounded ZIP, manifest version, plate/index
references, model byte counts and SHA-256 records, then inspects each referenced
STL/OBJ/3MF source and reports its dimensions, parts and mesh-health counters.
It rejects unexpected entries, traversal names, unreferenced v2 model files,
oversized compressed/decompressed data, and invalid thumbnails.

## Slice through an installed Linux engine

Bambu Studio and OrcaSlicer can be selected explicitly for Bambu-compatible
`.gcode.3mf` output. PrusaSlicer remains a useful Linux engine/configuration
reference, but it is not advertised here as a Bambu package producer. The
bridge passes arguments as an argv list with `shell=False`, writes to a
temporary directory, refuses accidental overwrites, and validates the final
`.gcode.3mf` before moving it to the requested destination:

```bash
python3 tools/alloy_linux.py slice ./my-model.3mf \
  --engine orca \
  --executable /opt/OrcaSlicer.AppImage \
  --machine runtime/profiles/machine.json \
  --process runtime/profiles/process.json \
  --filament runtime/profiles/filament.json \
  --output ./out/my-model.gcode.3mf
```

`--executable` is optional when the selected binary is on `PATH` as
`orca-slicer` or `bambu-studio`. The machine/process/filament
files are resolved JSON profiles produced by
`parity/export_resolved_orca_profiles.py`; omit them only when using the
engine's own defaults.

Validate an artifact independently before any transport step:

```bash
python3 tools/alloy_linux.py validate ./out/my-model.gcode.3mf
```

The package gate checks required model, metadata, relationship, G-code and
typed recipe fields, including `G90`, an extrusion mode (`M82`/`M83`) and a
temperature stop (`M104 S0`). It does not grant physical-print approval: the
artifact still needs desktop parity, printer acceptance, and hardware testing.

Validate the multi-plate archive exported from the Android app:

```bash
python3 tools/alloy_linux.py validate-batch ./Box-plates.alloy-batch.zip
```

This checks the batch manifest's plate indexes, byte counts and SHA-256
digests, then validates every nested `.gcode.3mf`. The result is a portable
bundle of independent plate jobs, not a direct multi-plate Bambu upload.

## Run the Linux bridge checks

The documented test files are executable from the repository root; they add
the repository root to Python's import path so the direct commands work on a
clean Linux checkout:

```bash
python3 tools/test_alloy_linux.py
python3 tools/test_bambu_lan_probe.py
```

These checks cover model/project inspection, bounded artifact validation,
portable batch archives and the fail-closed Bambu LAN probe payload rules.

## Linux alternatives today

Linux already has credible slicers (checked against upstream release pages on
2026-09-22):

- [Bambu Studio](https://github.com/bambulab/BambuStudio) itself publishes
  Ubuntu AppImages through its upstream releases and points Linux users to
  its [Flathub build](https://flathub.org/apps/com.bambulab.BambuStudio).
  The upstream releases page currently lists **v02.08.02.61** as public;
  the locally pinned checkout is an audit reference, not a claim that Alloy
  embeds or redistributes Bambu Studio.
- [OrcaSlicer](https://www.orcaslicer.com/download/) 2.4.2 is the strongest
  open-source Bambu-oriented alternative for profiles, calibration, and LAN
  workflows, with official Linux AppImage and Flatpak packages.
- [PrusaSlicer](https://www.prusa3d.com/p/prusaslicer/) is the mature general
  purpose engine and CLI reference used by Alloy's current Path B native work;
  Prusa's current page lists stable **2.9.6** and links Linux downloads from
  the official release channel. It also advertises EasyPrint/Prusa Connect,
  but those are Prusa services rather than Bambu transport.
- [UltiMaker Cura](https://ultimaker.com/software/ultimaker-cura/) remains a
  broad general-purpose option, but is less relevant to Bambu-specific parity.

Conclusion: Linux is not the reason to delay Alloy. A capable desktop path
already exists today; Alloy's differentiator is the Android-first, phone-only
workflow, richer visual inspection, inventory/maintenance surface, and a
controlled Bambu A1 Mini transport path. The Linux companion remains useful
for fallback slicing and recovery, but Android should receive the primary
product investment.

Alloy's differentiator remains a phone-native workflow, local project recovery,
visual part inspection, and a controlled future printer transport. The Linux
bridge complements those tools; it does not pretend the current Android shell
or fallback slicer is already production-equivalent to them.
