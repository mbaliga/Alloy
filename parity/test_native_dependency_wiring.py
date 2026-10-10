from pathlib import Path
import re
import tempfile
import unittest

from ci.validate_native_wiring import casefolded_path, local_include_case_mismatches


ROOT = Path(__file__).resolve().parents[1]


class NativeDependencyWiringTests(unittest.TestCase):
    def test_official_orca_probe_uses_its_own_libnest2d_headers_first(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        probe = cmake.split("target_include_directories(official_orca_libslic3r BEFORE PRIVATE", 1)[1]
        official_libnest = '"${OFFICIAL_ORCA_SOURCE_DIR}/src/libnest2d/include"'
        legacy_libnest = "${_legacy_slic3r_include_dirs}"
        self.assertIn(official_libnest, probe)
        self.assertLess(probe.index(official_libnest), probe.index(legacy_libnest))

    def test_official_orca_step_source_includes_occt_label_sequence_alias(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        step_options = cmake.split("set_source_files_properties(\n            \"${OFFICIAL_ORCA_SOURCE_DIR}/src/libslic3r/Format/STEP.cpp\"", 1)[1]
        self.assertIn('COMPILE_OPTIONS "-include;TDF_LabelSequence.hxx"', step_options)

    def test_official_orca_locales_source_includes_stringstream_header(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        locale_options = cmake.split("set_source_files_properties(\n            \"${OFFICIAL_ORCA_SOURCE_DIR}/src/libslic3r/LocalesUtils.cpp\"", 1)[1]
        self.assertIn('COMPILE_OPTIONS "-include;sstream"', locale_options)

    def test_official_orca_text_shape_maps_removed_occt_7_aliases(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        text_options = cmake.split(
            'set_source_files_properties(\n            "${OFFICIAL_ORCA_SOURCE_DIR}/src/libslic3r/Shape/TextShape.cpp"',
            1,
        )[1]
        self.assertIn('COMPILE_OPTIONS "-include;occt_legacy_text_api.hxx"', text_options)
        compat = (ROOT / "app/src/main/jni/official_orca_compat/occt_legacy_text_api.hxx").read_text(encoding="utf-8")
        self.assertIn("using NCollection_Utf8Iter = NCollection_UtfIterator<char>", compat)
        self.assertIn("using TColStd_SequenceOfHAsciiString", compat)

    def test_official_orca_probe_uses_the_pinned_libnoise_public_target(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        official_probe = cmake.split("add_library(official_orca_libslic3r STATIC", 1)[1]
        self.assertIn('"${CMAKE_CURRENT_SOURCE_DIR}/src/main/jni/official_orca_compat"', official_probe)
        self.assertIn("target_link_libraries(official_orca_libslic3r PRIVATE ${OpenCV_LIBS} noise::noise)", official_probe)
        wrapper = ROOT / "app/src/main/jni/official_orca_compat/libnoise/noise.h"
        self.assertIn('#include <noise.h>', wrapper.read_text(encoding="utf-8"))

    def test_explicit_cmake_sources_exist_with_exact_case(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        pattern = re.compile(r"\bsrc/main/jni/[A-Za-z0-9_./-]+\.(?:c|cc|cpp|cxx|h|hpp)\b")
        sources = []
        for line in cmake.splitlines():
            if not line.lstrip().startswith("#"):
                sources.extend(pattern.findall(line))
        missing = sorted({source for source in sources if not (ROOT / "app" / source).is_file()})
        self.assertEqual([], missing)
        self.assertIn("src/main/jni/libslic3r/Format/SVG.cpp", sources)

    def test_svg_translation_unit_includes_case_matched_header(self) -> None:
        source = (ROOT / "app/src/main/jni/libslic3r/Format/SVG.cpp").read_text(encoding="utf-8")
        self.assertIn('#include "SVG.hpp"', source)
        self.assertNotIn('#include "svg.hpp"', source)

    def test_model_translation_unit_includes_case_matched_svg_header(self) -> None:
        source = (ROOT / "app/src/main/jni/libslic3r/Model.cpp").read_text(encoding="utf-8")
        self.assertIn('#include "Format/SVG.hpp"', source)
        self.assertNotIn('#include "Format/svg.hpp"', source)

    def test_local_quoted_headers_have_exact_case(self) -> None:
        root = ROOT / "app/src/main/jni"
        self.assertEqual([], local_include_case_mismatches(root))

    def test_casefolded_path_finds_but_does_not_accept_a_wrong_spelling(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Format").mkdir()
            (root / "Format" / "SVG.hpp").write_text("// test\n", encoding="utf-8")
            self.assertEqual(root / "Format" / "SVG.hpp", casefolded_path(root, "format/svg.hpp"))
            self.assertNotEqual(root / "format" / "svg.hpp", casefolded_path(root, "format/svg.hpp"))

    def test_case_audit_rejects_a_stale_local_include(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Format").mkdir()
            (root / "Format" / "SVG.hpp").write_text("// header\n", encoding="utf-8")
            (root / "Model.cpp").write_text('#include "format/svg.hpp"\n', encoding="utf-8")
            self.assertEqual(
                ["Model.cpp -> format/svg.hpp (actual Format/SVG.hpp)"],
                local_include_case_mismatches(root),
            )

    def test_source_build_is_pinned_and_cross_compiled(self) -> None:
        script = (ROOT / "ci/build_gmp_mpfr_android.sh").read_text(encoding="utf-8")
        for token in (
            "https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2",
            "https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2",
            "sha256_file()",
            "command -v sha256sum",
            "shasum -a 256",
            "SOURCE_BUILD_MODE=official-source",
            "GMP_SO_SHA256=",
            "--host=\"$TARGET\"",
            "--enable-shared",
            "--disable-static",
            "--with-gmp=\"$WORK_ROOT/prefix\"",
            "jniLibs/$ABI",
            "jniImports/gmp/include/$ABI",
            "llvm-readelf",
            "SONAME",
            "SOURCE_BUILD_METADATA.txt",
        ):
            self.assertIn(token, script)
        for value in re.findall(r'"([0-9a-f]{64})"', script):
            self.assertEqual(len(value), 64)

    def test_native_workflows_require_source_built_inputs(self) -> None:
        validator = (ROOT / "ci/validate_native_prebuilts.py").read_text(encoding="utf-8")
        self.assertIn('expected_mpfr = "4.2.2" if abi == "arm64-v8a" and source_built_arm64 else "4.2.1"', validator)
        self.assertIn("GMP_SOURCE_SHA256", validator)
        for workflow in (
            ".github/workflows/alloy-native-build.yml",
            ".github/workflows/android-release-candidate.yml",
        ):
            text = (ROOT / workflow).read_text(encoding="utf-8")
            self.assertIn("ci/build_gmp_mpfr_android.sh app/src/main arm64-v8a 26", text)
            self.assertIn("ci/validate_native_dependency_inputs.py arm64-v8a", text)
            self.assertIn("ci/validate_native_prebuilts.py arm64-v8a --source-built", text)
            self.assertNotIn("check-native-prebuilts.py arm64-v8a", text)

    def test_native_workflow_uses_bootable_api35_arm_translation_host(self) -> None:
        workflow = (ROOT / ".github/workflows/alloy-native-build.yml").read_text(encoding="utf-8")
        self.assertIn("Run native release instrumentation through API-35 ARM translation", workflow)
        self.assertIn("Export native G3 evidence through API-35 ARM translation", workflow)
        # Instrumentation, G3 export, and the isolated five-fixture pilot each
        # boot a fresh API-35 ARM-translation emulator.
        self.assertEqual(3, workflow.count("target: google_apis"))
        self.assertEqual(3, workflow.count("arch: x86_64"))
        self.assertEqual(
            3,
            workflow.count(
                "disable-linux-hw-accel: ${{ steps.emulator-acceleration.outputs.disable-hw-accel }}"
            ),
        )
        self.assertIn("Use hosted KVM where available", workflow)
        self.assertIn("99-kvm4all.rules", workflow)
        self.assertIn('MODE="0666"', workflow)
        self.assertIn("sudo udevadm control --reload-rules", workflow)
        self.assertEqual(1, workflow.count("sudo udevadm trigger --name-match=kvm"))
        self.assertIn("arm64-v8a JNI library", workflow)

    def test_native_target_uses_pinned_libnoise_not_the_handwritten_stub(self) -> None:
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        fuzzy = (ROOT / "app/src/main/jni/libslic3r/Feature/FuzzySkin/FuzzySkin.cpp").read_text(encoding="utf-8")
        workflow = (ROOT / ".github/workflows/alloy-native-build.yml").read_text(encoding="utf-8")
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn("ORCA_LIBNOISE_SOURCE_DIR", cmake)
        self.assertIn('add_subdirectory("${ORCA_LIBNOISE_SOURCE_DIR}"', cmake)
        self.assertIn("noise::noise", cmake)
        self.assertIn('#include "noise.h"', fuzzy)
        self.assertNotIn('#include "libnoise/noise.h"', fuzzy)
        self.assertFalse((ROOT / "app/src/main/jni/libnoise/noise.h").exists())
        self.assertIn("Initialize pinned official libnoise source", workflow)
        self.assertIn("git submodule update --init --depth 1 third_party/orca-deps-libnoise", workflow)
        self.assertIn("assets.srcDirs += file('../third_party/licenses')", gradle)

    def test_occt_headers_are_normalized_and_source_is_copied(self) -> None:
        normalizer = (ROOT / "ci/normalize_occt_headers.py").read_text(encoding="utf-8")
        self.assertIn("/OCCT/src/", normalizer)
        self.assertIn("src/", normalizer)
        cmake = (ROOT / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
        self.assertIn("src/main/occt)", cmake)
        bootstrap = (ROOT / "ci/patch_orca_mobile_bootstrap.py").read_text(encoding="utf-8")
        self.assertIn('"$WORK_DIR/OCCT/src/."', bootstrap)
        for workflow in (
            ".github/workflows/alloy-native-build.yml",
            ".github/workflows/android-release-candidate.yml",
        ):
            text = (ROOT / workflow).read_text(encoding="utf-8")
            self.assertIn("ci/normalize_occt_headers.py app/src/main/occt", text)


if __name__ == "__main__":
    unittest.main()
