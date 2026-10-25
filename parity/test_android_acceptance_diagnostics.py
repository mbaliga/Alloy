"""Ensure Android acceptance failures retain Gradle diagnostics and exit status."""

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "ci/run_android_acceptance_with_diagnostics.sh"


class AndroidAcceptanceDiagnosticsTests(unittest.TestCase):
    def run_gradle(self, exit_code):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        bindir = root / "bin"
        bindir.mkdir()
        gradle = bindir / "gradle"
        gradle.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$*\" > \"$ALLOY_TEST_GRADLE_ARGS\"\n"
            "printf 'synthetic compile diagnostic\\n'\n"
            "printf 'synthetic stderr diagnostic\\n' >&2\n"
            f"exit {exit_code}\n",
            encoding="utf-8",
        )
        gradle.chmod(0o755)
        report_dir = root / "reports"
        args_path = root / "gradle-args.txt"
        env = os.environ.copy()
        env.update({
            "PATH": f"{bindir}{os.pathsep}{env.get('PATH', '')}",
            "ALLOY_ANDROID_ACCEPTANCE_REPORT_DIR": str(report_dir),
            "ALLOY_TEST_GRADLE_ARGS": str(args_path),
        })
        result = subprocess.run(
            ["bash", str(SCRIPT)], text=True, capture_output=True, env=env, check=False
        )
        log = report_dir / "gradle-acceptance.log"
        self.assertTrue(log.is_file())
        contents = log.read_text(encoding="utf-8")
        self.assertIn("synthetic compile diagnostic", contents)
        self.assertIn("synthetic stderr diagnostic", contents)
        self.assertEqual(
            ":app:connectedReleaseAndroidTest -PalloyCiDebugSign=true --no-daemon --stacktrace",
            args_path.read_text(encoding="utf-8").strip(),
        )
        self.assertIn(contents, result.stdout)
        return result

    def test_successful_acceptance_preserves_log_and_zero_exit(self):
        self.assertEqual(0, self.run_gradle(0).returncode)

    def test_compile_failure_preserves_log_and_original_exit_code(self):
        self.assertEqual(29, self.run_gradle(29).returncode)


if __name__ == "__main__":
    unittest.main()
