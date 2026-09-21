# Linux slicer landscape

As of 2026-09-22, Linux already has several credible ways to prepare Bambu
prints. Alloy should complement them with a phone-native workflow rather than
pretend the Linux ecosystem is empty.

| Option | Linux status | Best fit | Alloy implication |
| --- | --- | --- | --- |
| [Bambu Studio releases](https://github.com/bambulab/BambuStudio/releases) | Bambu’s upstream release notes explicitly point Linux users to a Flathub build. Treat the exact tag and package provenance as release-time data; the Flathub package is maintained outside the upstream desktop repository. | The closest Bambu-specific desktop solution because it is Bambu’s own slicer. | Do not prioritize a Linux desktop clone; use Bambu Studio as the compatibility baseline. |
| [OrcaSlicer releases](https://github.com/OrcaSlicer/OrcaSlicer/releases) | The official project currently publishes Linux AppImage and Flatpak builds, including x86_64 and ARM64 variants. | Strong open-source Bambu-oriented alternative with broad printer support, calibration and network workflows. | Compatibility/reference baseline; Alloy’s audited mobile engine lineage did not meet the exact-engine provenance gate. |
| [PrusaSlicer releases](https://github.com/prusa3d/PrusaSlicer/releases) | The upstream release notes currently identify Flathub as the official Linux distribution channel; stable and beta/alpha channels are separate. | General-purpose slicer and automation reference. | Useful for engine/profile comparison, but not a Bambu-specific replacement by itself. |
| [UltiMaker Cura](https://ultimaker.com/ultimaker-software/) | UltiMaker continues to publish Cura as a free cross-platform slicer with Linux support; use the vendor download page for the current package. | Broad, mature FDM slicer for many non-Bambu printers. | A viable general Linux alternative, but not our A1 Mini parity target. |
| [SliceBeam](https://github.com/utkabobr/SliceBeam) | Android FFF slicer based on PrusaSlicer core; not a Linux desktop product. | Touch-first mobile reference. | Useful as an Android UX/build reference, not a Linux target. |

## Product decision

Linux support is intentionally frozen as an appreciated secondary platform, not
the current phone-first release gate. The shared architecture should remain:

`validated native engine → Android shell + Linux shell → Bambu LAN transport`

For Alloy's current native work, Path B uses the pinned SliceBeam/PrusaSlicer
lineage; Orca remains a compatibility reference and profile comparison source.

The completed Linux milestone is the dependency-free headless bridge in
`tools/alloy_linux.py`. It makes model inspection and safe artifact validation
available on Linux now, and can invoke an installed slicer without giving the
shell arbitrary command text. `tools/alloy_linux_gui.py` adds a resizable
Tkinter model workspace for the bundled box/parts and imported STL/OBJ/3MF files;
the Alloy-owned native desktop engine and production packaging remain later
gates. The Android phone workflow is the only active product gate for now.

This confirms the product decision behind the current roadmap: Linux already
has usable desktop alternatives, including Bambu Studio itself, OrcaSlicer and
PrusaSlicer. Alloy’s differentiator is phone-only preparation and printer
control, not another desktop slicer distribution.

Alloy is not production-grade until its engine/profile output is compared with
known-good Bambu/Orca slices, the generated `.gcode.3mf` is structurally and
semantically verified, LAN upload/start/telemetry are tested against a real A1
Mini, and Android/Linux release builds pass hardware and interruption testing.
