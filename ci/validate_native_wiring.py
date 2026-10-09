#!/usr/bin/env python3
"""Static guardrails for the optional native engine boundary.

The workspace cannot run an Android/JNI build locally, so this check catches
the most damaging wiring regressions before CI gets to the expensive native
compile job.
"""

import json
import os
import re
from pathlib import Path
from typing import List, Optional


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, token: str, path: Path) -> None:
    if token not in text:
        raise SystemExit(f"{path}: missing required native wiring: {token}")


def casefolded_path(root: Path, relative: str) -> Optional[Path]:
    """Return a case-insensitive in-tree match without accepting it as valid.

    Linux and the Android toolchain require a byte-for-byte filename match,
    while a developer's default macOS volume does not.  We use this only to
    identify a stale spelling: includes that are genuinely supplied by an
    external dependency are deliberately left to CMake rather than reported as
    local source mistakes.
    """
    current = root
    for segment in Path(relative).parts:
        if segment in {".", ""}:
            continue
        if segment == "..":
            current = current.parent
            continue
        if not current.is_dir():
            return None
        matches = [child for child in current.iterdir() if child.name.casefold() == segment.casefold()]
        if len(matches) != 1:
            return None
        current = matches[0]
    return current


def local_include_case_mismatches(libslic3r_root: Path) -> List[str]:
    """Find quoted includes which resolve in-tree only with different casing."""
    quoted_include = re.compile(r'^\s*#\s*include\s+"([^"\\n]+)"', re.MULTILINE)
    mismatches: List[str] = []
    for candidate in libslic3r_root.rglob("*"):
        if not candidate.is_file() or candidate.suffix.lower() not in {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp"}:
            continue
        source = candidate.read_text(encoding="utf-8", errors="replace")
        for relative in quoted_include.findall(source):
            # The compiler first searches beside the including source and then
            # the libslic3r include root.  Test both in that order.
            for base in (candidate.parent, libslic3r_root):
                # Collapse `..` lexically before comparing.  An include such
                # as `../libslic3r.h` is perfectly valid and should not be
                # mistaken for a spelling defect merely because the resolved
                # path has a different textual form.
                exact = Path(os.path.normpath(str(base / relative)))
                insensitive = casefolded_path(base, relative)
                if insensitive is None or str(insensitive) == str(exact):
                    continue
                # On a case-insensitive macOS volume exact.is_file() may be
                # true even for the wrong spelling.  The lexical path mismatch
                # is the evidence we need; Linux will not resolve it.
                if insensitive.is_file():
                    mismatches.append(
                        f"{candidate.relative_to(libslic3r_root)} -> {relative} "
                        f"(actual {os.path.relpath(insensitive, base)})"
                    )
                break
    return sorted(set(mismatches))


def main() -> None:
    adapter_path = ROOT / "app/src/main/java/com/mbaliga/alloy/NativeSlicerEngine.java"
    native_path = ROOT / "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r/Native.java"
    occt_loader_path = ROOT / "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r/OCCTLoader.java"
    shader_path = ROOT / "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r/GLShadersManager.java"
    activity_path = ROOT / "app/src/main/java/com/mbaliga/alloy/MainActivity.java"
    gradle_path = ROOT / "app/build.gradle"
    cmake_entry_path = ROOT / "app/CMakeLists.txt"
    workflow_path = ROOT / ".github/workflows/alloy-native-build.yml"
    smoke_path = ROOT / "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java"
    safety_path = ROOT / "app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java"
    prebuilts_path = ROOT / "ci/validate_native_prebuilts.py"
    dependency_inputs_path = ROOT / "ci/validate_native_dependency_inputs.py"
    dependency_build_path = ROOT / "ci/build_gmp_mpfr_android.sh"
    profile_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ProfileCatalog.java"
    native_settings_path = ROOT / "app/src/main/java/com/mbaliga/alloy/NativeSettings.java"
    profile_asset_path = ROOT / "app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json"
    jni_contract_path = ROOT / "ci/validate_native_jni_contract.py"
    gcode_path = ROOT / "app/src/main/jni/libslic3r/GCode.cpp"
    svg_path = ROOT / "app/src/main/jni/libslic3r/Format/SVG.cpp"
    fuzzy_skin_path = ROOT / "app/src/main/jni/libslic3r/Feature/FuzzySkin/FuzzySkin.cpp"
    legacy_libnoise_stub = ROOT / "app/src/main/jni/libnoise/noise.h"

    adapter = adapter_path.read_text()
    native = native_path.read_text()
    occt_loader = occt_loader_path.read_text()
    shader = shader_path.read_text()
    activity = activity_path.read_text()
    gradle = gradle_path.read_text()
    cmake_entry = cmake_entry_path.read_text()
    workflow = workflow_path.read_text()
    smoke = smoke_path.read_text()
    safety = safety_path.read_text()
    prebuilts = prebuilts_path.read_text()
    dependency_inputs = dependency_inputs_path.read_text()
    dependency_build = dependency_build_path.read_text()
    profile = profile_path.read_text()
    native_settings = native_settings_path.read_text()
    profile_asset = profile_asset_path.read_text()
    jni_contract = jni_contract_path.read_text()
    gcode = gcode_path.read_text()
    svg = svg_path.read_text()
    fuzzy_skin = fuzzy_skin_path.read_text()

    for token in (
        "implements SlicerEngine",
        "Native.model_read_from_file",
        "Native.model_slice",
        "Native.model_cancel",
        "Native.model_release",
        "Native.gcoderesult_release",
        "writeAsciiStl",
        "writeConfig",
        "support_material_auto",
        "support_material_threshold",
        "support_material_angle",
        "support_material_contact_distance",
        "support_material_interface_layers",
        "nativeSettings",
        "hasApprovedNativeSetting",
        "entrySet",
        "first_layer_height",
        "first_layer_temperature",
        "first_layer_bed_temperature",
        "extrusion_multiplier",
        "max_volumetric_speed",
        "arc_fitting",
        "gcode_comments",
        "start_gcode",
        "end_gcode",
        "layer_gcode",
        "cooling",
        "fan_below_layer_time",
        "slowdown_below_layer_time",
        "MAX_PREVIEW_LAYERS",
        "MAX_PREVIEW_SEGMENTS",
        "MAX_GCODE_LINE_CHARS",
        "BoundedLineReader",
        "Native G-code exceeds the 128 MB limit",
        "parseGcode",
        "toolpathRole",
        "perimeterSegments",
        "supportSegments",
        "GcodeSafetyValidator.requireSafe",
        "engineVerified",
        "Printer and filament names are required",
        "NativeSettings.isApprovedKey",
    ):
        require(adapter, token, adapter_path)
    for token in ("EXPECTED_DIGESTS", "require_elf", "6.2.1", "4.2.1", "4.2.2"):
        require(prebuilts, token, prebuilts_path)
    for token in ("actual CMake contract", "BOOST_LIBS", "OCCT_LIBS", "oneAPI TBB compatibility headers", "portable OCCT source headers"):
        require(dependency_inputs, token, dependency_inputs_path)
    for token in ("sha256_file()", "command -v sha256sum", "shasum -a 256", "--enable-shared", "--disable-static", "llvm-readelf", "SOURCE_BUILD_METADATA.txt", "GMP_SO_SHA256="):
        require(dependency_build, token, dependency_build_path)
    for token in (
        "model_create",
        "model_read_from_file",
        "model_slice",
        "model_cancel",
        "model_release",
        "gcoderesult_release",
        "OCCTLoader.load",
    ):
        require(native, token, native_path)
    for token in ("TKDESTEP", "TKXCAF", "TKernel", "System.loadLibrary"):
        require(occt_loader, token, occt_loader_path)
    for token in (
        "NewGlobalRef(localSliceListenerClass)",
        "AttachCurrentThread(&e, &args)",
        "cancel_requested",
        "DeleteGlobalRef(listenerGlobal)",
        "JNI_OnUnload",
    ):
        require((ROOT / "app/src/main/jni/slicebeam/beam_native.cpp").read_text(), token,
                ROOT / "app/src/main/jni/slicebeam/beam_native.cpp")
    for token in ("jni_escape", "Java_ru_ytkab0bp_slicebeam_slic3r_Native_", "missing JNI exports", "CMake OCCT libraries are not loaded"):
        require(jni_contract, token, jni_contract_path)
    # Alloy previously carried a small bridge-local G-code pipeline with these
    # helper names. The full OrcaSlicer-Mobile libslic3r source now owns that
    # pipeline, so validate the stable layer-processing surface as well as the
    # legacy helper names for older engine snapshots.
    if "auto pipeline_to_layerresult" not in gcode and not ("LayerResult" in gcode and "process_layer" in gcode):
        raise SystemExit(f"{gcode_path}: missing native G-code pipeline")
    if "auto pipeline_to_string" not in gcode and not ("GCodeProcessor" in gcode and "process_layer" in gcode):
        raise SystemExit(f"{gcode_path}: missing native G-code serialization pipeline")
    # GitHub's Linux runner is case-sensitive. Keep this source/header pairing
    # explicit because macOS filesystems otherwise hide this build break.
    require(svg, '#include "SVG.hpp"', svg_path)
    require(fuzzy_skin, '#include "noise.h"', fuzzy_skin_path)
    if '#include "libnoise/noise.h"' in fuzzy_skin or legacy_libnoise_stub.exists():
        raise SystemExit(f"{fuzzy_skin_path}: legacy handwritten libnoise stub must not be selectable")
    # Check every direct libslic3r Format include against the checkout rather
    # than relying on one translation unit. The Android compiler includes the
    # libslic3r root directly, so a stale spelling can pass on macOS yet fail
    # only after the expensive Linux source-dependency bootstrap.
    libslic3r_root = ROOT / "app/src/main/jni/libslic3r"
    format_include = re.compile(r'^\s*#\s*include\s+"(Format/[A-Za-z0-9_./-]+\.(?:h|hpp))"', re.MULTILINE)
    missing_format_headers = []
    for candidate in libslic3r_root.rglob("*"):
        if not candidate.is_file() or candidate.suffix.lower() not in {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp"}:
            continue
        for relative in format_include.findall(candidate.read_text(encoding="utf-8", errors="replace")):
            if not (libslic3r_root / relative).is_file():
                missing_format_headers.append(f"{candidate.relative_to(libslic3r_root)} -> {relative}")
    if missing_format_headers:
        raise SystemExit(f"{libslic3r_root}: missing case-exact Format include(s): "
                         + "; ".join(sorted(missing_format_headers)))
    # The SVG failures were only symptoms of a broader
    # case-insensitive-development-volume risk. Audit all quoted headers that
    # can be resolved inside our complete JNI source checkout, including the
    # retained legacy slicer tree. Do not guess at external headers such as
    # Boost, OCCT or generated dependency inputs.
    jni_root = ROOT / "app/src/main/jni"
    case_mismatches = local_include_case_mismatches(jni_root)
    if case_mismatches:
        raise SystemExit(f"{jni_root}: case-mismatched local include(s): "
                         + "; ".join(case_mismatches))
    require(shader, "getCurrentShaderPointer", shader_path)
    require(activity, "BuildConfig.NATIVE_ENGINE_ENABLED", activity_path)
    require(activity, "BuildConfig.NATIVE_ENGINE_VERIFIED", activity_path)
    for token in ("MAX_PROFILE_BYTES", "Printer profile exceeds the 256 KB limit", "Profile provenance revision must be a commit SHA",
                  "requireRange", "Double.isNaN", "Profile minimum fan exceeds maximum fan"):
        require(profile, token, profile_path)
    for token in ("APPROVED_KEYS", "isApprovedKey", "Native setting is not approved", "MAX_VALUE_CHARS"):
        require(native_settings, token, native_settings_path)
    for token in ('"native_settings"', '"perimeter_generator"', '"fill_pattern"', '"seam_position"'):
        require(profile_asset, token, profile_asset_path)
    allowlist_block = native_settings.split("APPROVED_KEYS", 1)[1].split(")));", 1)[0]
    approved_keys = set(re.findall(r'"([A-Za-z0-9_]+)"', allowlist_block))
    profile_keys = set(json.loads(profile_asset)["native_settings"])
    # These keys are intentionally approved for isolated G3 experiments, but
    # are not source-backed by the pinned Orca profile and therefore must not
    # appear in the shipped profile asset. Keep this exception explicit so a
    # future experimental key cannot silently widen the runtime boundary.
    experimental_keys = {
        # Imported Bambu profiles may provide the native alias below. The
        # shipped profile intentionally keeps brim_type outside its compact
        # source projection until auto-brim is promoted.
        "brim_object_gap",
        "brim_type",
        # G3 parity fixtures may explicitly select the desktop flavor. The
        # shipped A1 profile keeps the runtime default on marlin2.
        "gcode_flavor",
        # Current Bambu projects carry this Orca-native pattern key. The
        # compact shipped profile predates the projection, so it remains an
        # import-only exception until the pinned profile snapshot is refreshed.
        "internal_solid_infill_pattern",
        "set_other_flow_ratios",
        "print_flow_ratio",
        "first_layer_flow_ratio",
        "outer_wall_flow_ratio",
        "inner_wall_flow_ratio",
        "overhang_flow_ratio",
        "sparse_infill_flow_ratio",
        "internal_solid_infill_flow_ratio",
        "gap_fill_flow_ratio",
        "top_solid_infill_flow_ratio",
        "bottom_solid_infill_flow_ratio",
        # These native fields are accepted only when an imported project
        # supplies them. The pinned compact A1 Mini profile does not resolve
        # them, so they must never be mistaken for source-backed defaults.
        "infill_anchor",
        "infill_anchor_max",
        "outer_wall_jerk",
        "inner_wall_jerk",
        "infill_jerk",
        "top_surface_jerk",
        "initial_layer_jerk",
        "travel_jerk",
        "support_remove_small_overhang",
        "internal_solid_infill_acceleration",
        "support_interface_spacing",
        "support_bottom_interface_spacing",
        "support_tree_branch_distance",
        "support_tree_branch_diameter_angle",
        # These two fields are imported from Bambu projects and used by the
        # dedicated motion-parity fixtures. They are intentionally not part
        # of the compact shipped profile snapshot until the native profile
        # projection is promoted beyond fixture-level evidence.
        "reduce_infill_retraction",
        "travel_slope",
    }
    # These source-backed compatibility fields are accepted for imported
    # Bambu projects, while the compact shipped profile represents them via
    # typed bed/filament fields instead of duplicating every plate variant.
    # Keep this list explicit: a newly approved native key must still be
    # added to the shipped profile or consciously documented here.
    import_only_compatibility_keys = {
        "cool_plate_temp",
        "cool_plate_temp_initial_layer",
        "curr_bed_type",
        "eng_plate_temp",
        "eng_plate_temp_initial_layer",
        "hot_plate_temp",
        "hot_plate_temp_initial_layer",
        "supertack_plate_temp",
        "supertack_plate_temp_initial_layer",
        "textured_plate_temp",
        "textured_plate_temp_initial_layer",
        "wipe_distance",
        "support_object_first_layer_gap",
        "support_interface_not_for_body",
    }
    compatibility_keys = experimental_keys | import_only_compatibility_keys
    if (approved_keys - compatibility_keys) != profile_keys or not compatibility_keys.issubset(approved_keys):
        missing = sorted(profile_keys - approved_keys)
        extra = sorted(approved_keys - profile_keys - compatibility_keys)
        raise SystemExit(f"{native_settings_path}: approved native settings drift from {profile_asset_path}: missing={missing}, extra={extra}")
    require(gradle, "buildConfigField 'boolean', 'NATIVE_ENGINE_ENABLED'", gradle_path)
    require(gradle, "buildConfigField 'boolean', 'NATIVE_ENGINE_VERIFIED'", gradle_path)
    require(gradle, "alloyNativeEngineVerified=true requires alloyNativeEngine=true", gradle_path)
    require(gradle, "abiFilters 'arm64-v8a'", gradle_path)
    require(gradle, "jniLibs.srcDirs = []", gradle_path)
    require(gradle, "assets.srcDirs += file('../third_party/licenses')", gradle_path)
    require(gradle, "path file('CMakeLists.txt')", gradle_path)
    for token in ("cmake_minimum_required", "project(SliceBeam)", "CMakeLists.native.txt"):
        require(cmake_entry, token, cmake_entry_path)
    cmake_path = ROOT / "app/CMakeLists.native.txt"
    cmake = cmake_path.read_text()
    # Android NDK Clang is not clang-cl: overriding CMake's system-include
    # flag with -imsvc breaks every translation unit once an imported
    # dependency (such as OpenCV) contributes an include directory.
    if re.search(r"^\\s*set\\(CMAKE_INCLUDE_SYSTEM_FLAG_CXX", cmake, re.MULTILINE):
        raise SystemExit(f"{cmake_path}: clang-cl system include flags are invalid for Android NDK Clang")
    for token in ("function(require_native_input", "Missing native build input", "require_native_input(", "test_exec_monitor", "Boost ${NAME}", "OCCT ${NAME}", '"oneTBB"', '"GMP"', '"MPFR"', "ORCA_LIBNOISE_SOURCE_DIR", "pinned Orca libnoise source", "add_subdirectory(\"${ORCA_LIBNOISE_SOURCE_DIR}\"", "noise::noise"):
        require(cmake, token, cmake_path)
    # CMake accepts paths on case-insensitive developer volumes that fail
    # later on Linux/Android builders. Validate every explicit JNI source and
    # header reference before the expensive source-built native job starts.
    source_pattern = re.compile(r"\bsrc/main/jni/[A-Za-z0-9_./-]+\.(?:c|cc|cpp|cxx|h|hpp)\b")
    missing_sources = []
    for line in cmake.splitlines():
        if line.lstrip().startswith("#"):
            continue
        for relative in source_pattern.findall(line):
            if not (ROOT / "app" / relative).is_file():
                missing_sources.append(relative)
    if missing_sources:
        raise SystemExit(f"{cmake_path}: missing explicit native source(s): "
                         + ", ".join(sorted(set(missing_sources))))
    require(workflow, "-PalloyNativeEngine=true", workflow_path)
    require(workflow, "ci/build_gmp_mpfr_android.sh app/src/main arm64-v8a 26", workflow_path)
    require(workflow, "ci/validate_native_dependency_inputs.py arm64-v8a", workflow_path)
    require(workflow, "ci/validate_native_prebuilts.py arm64-v8a --source-built", workflow_path)
    for token in ("Initialize pinned official libnoise source", "git submodule update --init --depth 1 third_party/orca-deps-libnoise", "ci/validate_official_orca_snapshot.py", "--libnoise-source-root third_party/orca-deps-libnoise"):
        require(workflow, token, workflow_path)
    # GitHub's default Linux runner is x86_64 and cannot boot an arm64 Android
    # system image. API 35's Google APIs image supplies ARM binary translation;
    # retain an arm64-only APK while using that bootable host for instrumentation.
    for token in (
        "API-35 ARM translation",
        "target: google_apis",
        "arch: x86_64",
        "disable-linux-hw-accel: ${{ steps.emulator-acceleration.outputs.disable-hw-accel }}",
        "Configure Android emulator acceleration",
        'echo "disable-hw-accel=true" >> "$GITHUB_OUTPUT"',
        'echo "disable-hw-accel=false" >> "$GITHUB_OUTPUT"',
        "99-kvm4all.rules",
        'MODE="0666"',
        "sudo udevadm control --reload-rules",
        "sudo udevadm trigger --name-match=kvm",
    ):
        require(workflow, token, workflow_path)
    for token in (
        "@RunWith(AndroidJUnit4.class)",
        "BuildConfig.NATIVE_ENGINE_ENABLED",
        "NativeSlicerEngine",
        "ArtifactStore.stage",
        "GcodePackageValidator.validate",
        "GcodeSafetyValidator.inspect",
    ):
        require(smoke, token, smoke_path)
    for token in ("MAX_GCODE_CHARS", "MAX_LINE_CHARS", "removeCommentsAndChecksum", "hasPrintableMotion", "class Stream"):
        require(safety, token, safety_path)
    for token in ("testInstrumentationRunner", "androidTestImplementation 'androidx.test:runner:",
                  "androidTestImplementation 'androidx.test.ext:junit:"):
        require(gradle, token, gradle_path)
    print("validated optional native engine wiring")


if __name__ == "__main__":
    main()
