import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from validate_machine_template_probe_output import (
    FIXTURES, G3_PROFILE_SHA256, PROFILE, ROOT, SOURCE, expected_template_digests,
)


SCRIPT = ROOT / "ci/run_machine_template_probe.sh"


FAKE_ADB = r'''#!/usr/bin/env python3
import os
from pathlib import Path
import shutil
import sys

args = sys.argv[1:]
mode = os.environ.get("ALLOY_PROBE_MODE", "success")
if args and args[0] == "install":
    raise SystemExit(0)
if args[:2] == ["shell", "am"] and "instrument" in args:
    if mode == "instrument-failure":
        print("INSTRUMENTATION_STATUS: shortMsg=Process crashed")
        print("FAILURES!!!")
        raise SystemExit(17)
    print("OK (1 test)")
    raise SystemExit(0)
if args and args[0] == "logcat":
    print("fake-logcat-tail")
    raise SystemExit(0)
if args and args[0] == "pull":
    count_file = Path(os.environ["ALLOY_PROBE_PULL_COUNT"])
    count = int(count_file.read_text()) + 1 if count_file.exists() else 1
    count_file.write_text(str(count))
    if mode == "pull-failure-once" and count == 1:
        print("simulated transient pull failure")
        raise SystemExit(44)
    shutil.copytree(os.environ["ALLOY_PROBE_DEVICE_DIR"], args[-1], dirs_exist_ok=True)
    raise SystemExit(0)
print("unexpected adb command:", args, file=sys.stderr)
raise SystemExit(90)
'''


class MachineTemplateProbeScriptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        adb = self.bin / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o755)
        self.device = self.root / "device"
        self.device.mkdir()
        self.output = self.root / "probe-output"
        self.pull_count = self.root / "pull-count"
        self._write_device_evidence()

    def _write_device_evidence(self, invalid=False):
        fixtures = []
        for name in sorted(FIXTURES):
            filename = f"{name}.gcode"
            data = f"; partial offline rendering: {name}\nG1 X10 Y10 E1\n".encode()
            (self.device / filename).write_bytes(data)
            config_name = f"{name}.config.ini"
            config_data = f"config for {name}\n".encode()
            (self.device / config_name).write_bytes(config_data)
            fixtures.append({
                "fixture": name, "file": filename,
                "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False,
                "counterfactual": False, "bbl_printer_mode": False,
                "config_file": config_name,
                "config_sha256": hashlib.sha256(config_data).hexdigest(),
            })
        digests = expected_template_digests()
        if invalid:
            digests["machine_start_gcode"] = "a" * 64
        manifest = {
            "schema_version": 1, "source_commit": SOURCE,
            "scope": "template-rendering-only", "default_bbl_printer_mode": False,
            "counterfactual": False, "non_gating": True, "profile_name": PROFILE,
            "profile_sha256": G3_PROFILE_SHA256,
            "qualified": False,
            "template_sha256": digests, "fixtures": fixtures,
        }
        (self.device / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        end_only_dir = self.device / "end-only"
        end_only_dir.mkdir(exist_ok=True)
        end_only_fixtures = []
        for name in sorted(FIXTURES):
            filename = f"{name}.gcode"
            data = f"; end-only offline rendering: {name}\nG1 X10 Y10 E1 F600\n".encode()
            (end_only_dir / filename).write_bytes(data)
            end_only_fixtures.append({
                "fixture": name, "file": filename,
                "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False,
            })
        end_only_manifest = {
            "schema_version": 1, "source_commit": SOURCE,
            "scope": "machine-end-template-only-render-diagnostic", "qualified": False,
            "template_sha256": expected_template_digests()["machine_end_gcode"],
            "fixtures": end_only_fixtures,
        }
        (self.device / "end-only-manifest.json").write_text(
            json.dumps(end_only_manifest), encoding="utf-8"
        )
        counterfactual_dir = self.device / "counterfactual-bbl"
        counterfactual_dir.mkdir(exist_ok=True)
        counterfactual_fixtures = []
        for name in sorted(FIXTURES):
            filename = f"{name}.gcode"
            data = f"; counterfactual BBL rendering: {name}\nG1 X10 Y10 E1 F600\n".encode()
            (counterfactual_dir / filename).write_bytes(data)
            config_name = f"{name}.config.ini"
            config_data = f"config for {name}\n".encode()
            (counterfactual_dir / config_name).write_bytes(config_data)
            counterfactual_fixtures.append({
                "fixture": name, "file": filename,
                "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False,
                "counterfactual": True, "bbl_printer_mode": True,
                "config_file": config_name,
                "config_sha256": hashlib.sha256(config_data).hexdigest(),
            })
        counterfactual_manifest = {
            "schema_version": 1, "source_commit": SOURCE,
            "scope": "counterfactual-bbl-identity-template-render-diagnostic",
            "counterfactual": True, "bbl_printer_mode": True,
            "production_runtime_changed": False, "non_gating": True, "qualified": False,
            "profile_name": PROFILE, "profile_sha256": G3_PROFILE_SHA256,
            "template_sha256": expected_template_digests(), "fixtures": counterfactual_fixtures,
        }
        (counterfactual_dir / "manifest.json").write_text(
            json.dumps(counterfactual_manifest), encoding="utf-8"
        )

    def run_probe(self, mode):
        env = os.environ.copy()
        env.update({
            "PATH": str(self.bin) + os.pathsep + env["PATH"],
            "ALLOY_TEMPLATE_PROBE_OUTPUT_DIR": str(self.output),
            "ALLOY_PROBE_DEVICE_DIR": str(self.device),
            "ALLOY_PROBE_PULL_COUNT": str(self.pull_count),
            "ALLOY_PROBE_MODE": mode,
        })
        return subprocess.run(
            ["bash", str(SCRIPT)], cwd=ROOT, env=env,
            capture_output=True, text=True, check=False,
        )

    def assert_failed_attempt(self, result, expected_code):
        attempt = json.loads((self.output / "attempt.json").read_text(encoding="utf-8"))
        self.assertEqual("failed", attempt["state"])
        self.assertEqual(expected_code, attempt["exit_code"])
        self.assertFalse(attempt["qualified"])
        self.assertIn("fake-logcat-tail", (self.output / "logcat-tail.txt").read_text(encoding="utf-8"))

    def test_instrumentation_failure_pulls_partial_rendered_files_and_preserves_exit(self):
        result = self.run_probe("instrument-failure")
        self.assertEqual(17, result.returncode, result.stderr)
        self.assert_failed_attempt(result, 17)
        self.assertTrue((self.output / "instrumentation.txt").is_file())
        self.assertIn("partial offline rendering", (self.output / "rendered/cube_20mm.gcode").read_text())
        self.assertIn("counterfactual BBL rendering",
                      (self.output / "rendered/counterfactual-bbl/cube_20mm.gcode").read_text())

    def test_transient_pull_failure_is_recorded_and_diagnostics_retry_pull(self):
        result = self.run_probe("pull-failure-once")
        self.assertEqual(44, result.returncode, result.stderr)
        self.assert_failed_attempt(result, 44)
        self.assertEqual("2", self.pull_count.read_text())
        self.assertIn("simulated transient pull failure",
                      (self.output / "adb-pull.txt").read_text(encoding="utf-8"))
        self.assertTrue((self.output / "rendered/cube_20mm.gcode").is_file())
        self.assertTrue((self.output / "rendered/counterfactual-bbl/cube_20mm.gcode").is_file())

    def test_validator_failure_keeps_outputs_and_original_failure_state(self):
        self._write_device_evidence(invalid=True)
        result = self.run_probe("success")
        self.assertNotEqual(0, result.returncode)
        self.assert_failed_attempt(result, result.returncode)
        self.assertTrue((self.output / "rendered/cube_20mm.gcode").is_file())


if __name__ == "__main__":
    unittest.main()
