"""Deterministic, fail-closed tests for native build provenance inventories."""

import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from native_build_provenance import build_manifest, validate_against_root, validate_manifest


PIN_BLOCK = """env:
  SLICEBEAM_REPO: https://example.invalid/SliceBeam.git
  SLICEBEAM_SHA: 1111111111111111111111111111111111111111
  ORCA_MOBILE_REPO: https://example.invalid/OrcaSlicer-Mobile.git
  ORCA_MOBILE_SHA: 2222222222222222222222222222222222222222
  ORCA_PROFILE_REPO: https://example.invalid/OrcaSlicer.git
  ORCA_PROFILE_SHA: 3333333333333333333333333333333333333333
  BAMBU_PROFILE_REPO: https://example.invalid/BambuStudio.git
  BAMBU_PROFILE_SHA: 4444444444444444444444444444444444444444
  ANDROID_API: '35'
  NDK_VERSION: 23.1.7779620
  OPENCV_VERSION: 4.6.0
  OPENCV_SHA256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa

jobs:
  native-build:
"""


class NativeBuildProvenanceTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        (self.root / ".github/workflows").mkdir(parents=True)
        (self.root / ".github/workflows/alloy-native-build.yml").write_text(PIN_BLOCK, encoding="utf-8")
        (self.root / "app/src/main/jni/libslic3r").mkdir(parents=True)
        (self.root / "app/src/main/jni/openvdb").mkdir(parents=True)
        (self.root / "app/src/main/jni/openvdb/LICENSE").write_text("MPL-2.0 notice\n", encoding="utf-8")
        (self.root / "app/src/main/jni/libslic3r/GCode.cpp").write_text("// pinned native source\n", encoding="utf-8")
        (self.root / "app/CMakeLists.native.txt").write_text(
            'set(BOOST_VERSION "1_85")\n'
            'set(OpenVDB_MAJOR_VERSION 8)\nset(OpenVDB_MINOR_VERSION 2)\nset(OpenVDB_PATCH_VERSION 0)\n',
            encoding="utf-8"
        )
        (self.root / "third_party/licenses").mkdir(parents=True)
        (self.root / "third_party/licenses/SLICEBEAM_AGPL-3.0.txt").write_text("AGPL text\n", encoding="utf-8")
        (self.root / "third_party/licenses/LIBNOISE_LGPL-2.1.txt").write_text("LGPL text\n", encoding="utf-8")
        for relative in (
            "ci/patch_orca_mobile_bootstrap.py",
            "ci/patch_boost_android_locale.py",
            "ci/patch_slicebeam_stage1_no_step.py",
            "ci/normalize_occt_headers.py",
        ):
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"# {relative}\n", encoding="utf-8")
        (self.root / "ci/build_gmp_mpfr_android.sh").write_text(
            'GMP_URL="https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2"\n'
            'GMP_SHA256="eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c"\n'
            'MPFR_URL="https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2"\n'
            'MPFR_SHA256="9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b"\n',
            encoding="utf-8",
        )
        self.apk = "app/build/outputs/apk/release/app-release.apk"
        apk_path = self.root / self.apk
        apk_path.parent.mkdir(parents=True)
        with zipfile.ZipFile(apk_path, "w") as archive:
            archive.writestr("lib/arm64-v8a/libslic3r.so", b"native engine bytes")
            archive.writestr("lib/arm64-v8a/libc++_shared.so", b"runtime bytes")
            archive.writestr("assets/LICENSE-LIBNOISE_LGPL-2.1.txt", b"LGPL text\n")

    def test_manifest_is_deterministic_inventory_only_and_records_apk_libraries(self):
        first = build_manifest(self.root, apk_paths=(self.apk,))
        second = build_manifest(self.root, apk_paths=(self.apk,))
        self.assertEqual(json.dumps(first, sort_keys=True), json.dumps(second, sort_keys=True))
        validate_manifest(first)
        self.assertFalse(first["qualification"]["gate_eligible"])
        self.assertEqual("PENDING", first["qualification"]["native_engine_source_provenance_and_license"])
        self.assertEqual("FAIL_OR_UNRESOLVED", first["qualification"]["g2_source_lineage"])
        self.assertEqual("a" * 64, first["pinned_inputs"]["OPENCV_SHA256"])
        self.assertEqual("1.85", first["toolchain"]["boost_version"])
        self.assertEqual("8.2.0", first["toolchain"]["openvdb_version"])
        self.assertEqual(2, len(first["build_artifacts"][0]["packaged_shared_libraries"]))
        self.assertTrue(any(
            entry["path"] == "lib/arm64-v8a/libslic3r.so"
            for entry in first["build_artifacts"][0]["packaged_shared_libraries"]
        ))
        self.assertEqual("assets/LICENSE-LIBNOISE_LGPL-2.1.txt",
                         first["build_artifacts"][0]["packaged_license_assets"][0]["path"])
        self.assertEqual(["OpenCV", "GMP", "MPFR"], [
            archive["component"] for archive in first["declared_downloaded_archives"]
        ])
        self.assertEqual("6.2.1", first["declared_downloaded_archives"][1]["version"])
        self.assertEqual("4.2.2", first["declared_downloaded_archives"][2]["version"])
        self.assertIn("third_party/licenses/SLICEBEAM_AGPL-3.0.txt", {
            item["path"] for item in first["notice_files_in_repository"]
        })
        validate_against_root(first, self.root)

    def test_missing_apk_is_recorded_as_unavailable_without_qualification(self):
        document = build_manifest(self.root, apk_paths=("missing.apk",))
        self.assertFalse(document["build_artifacts"][0]["available"])
        self.assertFalse(document["qualification"]["gate_eligible"])
        validate_manifest(document)

    def test_source_or_apk_tampering_invalidates_record(self):
        document = build_manifest(self.root, apk_paths=(self.apk,))
        source = self.root / "app/src/main/jni/libslic3r/GCode.cpp"
        source.write_text("// changed\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "source digest mismatch"):
            validate_against_root(document, self.root)
        source.write_text("// pinned native source\n", encoding="utf-8")
        with (self.root / self.apk).open("ab") as stream:
            stream.write(b"tampered")
        with self.assertRaisesRegex(ValueError, "build artifact digest mismatch"):
            validate_against_root(document, self.root)

    def test_validator_rejects_attempt_to_mark_inventory_as_gate_evidence(self):
        document = build_manifest(self.root, apk_paths=(self.apk,))
        document["qualification"]["gate_eligible"] = True
        with self.assertRaisesRegex(ValueError, "not be marked as qualification"):
            validate_manifest(document)


if __name__ == "__main__":
    unittest.main()
