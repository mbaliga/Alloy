#!/usr/bin/env python3
"""Exercise the official-Orca libnoise CMake adapter in a tiny configure."""

from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "ci/fixtures/official-orca-libnoise-adapter"
ADAPTER = ROOT / "app/cmake/official-orca"


@unittest.skipUnless(shutil.which("cmake"), "CMake is required for adapter configure tests")
class OfficialOrcaLibnoiseAdapterTests(unittest.TestCase):
    def configure(self, supplies_target: bool) -> subprocess.CompletedProcess[str]:
        with tempfile.TemporaryDirectory() as output:
            return subprocess.run(
                [
                    "cmake",
                    "-S", str(FIXTURE),
                    "-B", output,
                    f"-DADAPTER_DIR={ADAPTER}",
                    f"-DSUPPLY_NOISE_TARGET={'ON' if supplies_target else 'OFF'}",
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

    def test_source_target_satisfies_official_find_package(self) -> None:
        result = self.configure(supplies_target=True)
        self.assertEqual(0, result.returncode, result.stdout)
        self.assertIn("Found libnoise", result.stdout)

    def test_missing_source_target_fails_closed(self) -> None:
        result = self.configure(supplies_target=False)
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn("pinned source-built noise::noise target", result.stdout)


if __name__ == "__main__":
    unittest.main()
