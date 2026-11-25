# Linux slicer landscape

Verified 2026-09-24 against the projects' current release pages. Linux already
has production-grade desktop slicers for Bambu printers, including Bambu Lab's
own Bambu Studio. Alloy should complement them with a phone-native workflow,
not duplicate the desktop target.

| Option | Linux status | Best fit | Alloy implication |
| --- | --- | --- | --- |
| [Bambu Studio releases](https://github.com/bambulab/BambuStudio/releases) | Bambu's release notes link to the [Flathub package](https://flathub.org/apps/com.bambulab.BambuStudio). At verification, upstream's latest stable was **2.8.2.61** and latest beta **2.8.4**; Flathub identifies the package as “by Bambu Lab,” lists **2.8.2.61**, and provides x86_64 and aarch64 builds. The Flatpak manifest is maintained outside Bambu's desktop repository. | Bambu's own full desktop slicer and the closest compatibility baseline. | Linux desktop coverage already exists; don't spend Alloy's Android-first effort on cloning it. |
| [OrcaSlicer releases](https://github.com/OrcaSlicer/OrcaSlicer/releases) | **2.4.2** was the latest official release at verification. The project documents Flathub and Linux AppImage packages, including x86_64 and aarch64 AppImages. | Strong open-source Bambu-oriented alternative with broad printer support, calibration and network workflows. | Compatibility/reference baseline; Alloy's audited mobile engine lineage has not met the exact-engine provenance gate. |
| [PrusaSlicer releases](https://github.com/prusa3d/PrusaSlicer/releases) | **2.9.6** was the latest stable release; **3.0.0-alpha12** was the latest pre-release. Upstream now identifies Flathub as its only official Linux distribution channel. | General-purpose slicer and automation reference. | Useful for engine/profile comparison, but not a Bambu-specific replacement by itself. |
| [UltiMaker Cura](https://ultimaker.com/ultimaker-software/) | UltiMaker offers Cura as a free, open-source slicer with Linux packages; use its download page for the current build. | Broad, mature FDM slicer for many non-Bambu printers. | A viable general Linux alternative, but not our A1 Mini parity target. |
| [SliceBeam](https://github.com/utkabobr/SliceBeam) | Android FFF slicer based on PrusaSlicer core; not a Linux desktop product. | Touch-first mobile reference. | Useful as an Android UX/build reference, not a Linux target. |

## Product decision

Linux support is intentionally secondary, not the current phone-first release
gate. Do not pursue full Linux desktop feature parity while the Android phone
workflow is incomplete. Keep Linux utilities and buildability healthy where
they provide low-cost value, and revisit a native desktop shell only after the
Android release gates are met. The shared engine and transport architecture
may eventually support:

`validated native engine → Android shell + Linux shell → Bambu LAN transport`

For Alloy's current native work, Path B uses the pinned SliceBeam/PrusaSlicer
lineage; Orca remains a compatibility reference and profile comparison source.

The current Linux utility milestones are the dependency-free headless bridge in
`tools/alloy_linux.py` (model inspection, safe artifact validation, and
controlled invocation of an installed slicer) and the resizable Tkinter model
workspace in `tools/alloy_linux_gui.py`. They are supplementary tools, not a
finished Linux Bambu Studio replacement: Alloy-owned native desktop slicing,
printer-control parity, and production packaging remain open. The Android phone
workflow is the active product gate.

This confirms the product decision behind the current roadmap: Linux has
usable, actively maintained desktop options, and Bambu Studio itself is
available there. Current upstream notes distinguish the stable release from
the beta channel; use stable builds for production work. Alloy's differentiator
is phone-only preparation and printer control, not another desktop slicer
distribution. Re-check versions and package channels before any future Linux
release because these values change over time.

Alloy is not production-grade until its engine/profile output is compared with
known-good Bambu/Orca slices, the generated `.gcode.3mf` is structurally and
semantically verified, LAN upload/start/telemetry are tested against a real A1
Mini, and Android/Linux release builds pass hardware and interruption testing.
