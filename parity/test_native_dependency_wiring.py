from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[1]


class NativeDependencyWiringTests(unittest.TestCase):
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
