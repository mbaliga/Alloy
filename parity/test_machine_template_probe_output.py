import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from validate_machine_template_probe_output import (
    FIXTURES, G3_PROFILE_SHA256, PROFILE, SOURCE, expected_template_digests,
    validate, validate_end_only, validate_counterfactual_bbl,
)
from machine_template_probe_attempt import record


class MachineTemplateProbeOutputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = {
            "schema_version": 1,
            "source_commit": SOURCE,
            "scope": "template-rendering-only",
            "default_bbl_printer_mode": False,
            "counterfactual": False,
            "non_gating": True,
            "profile_name": PROFILE,
            "profile_sha256": G3_PROFILE_SHA256,
            "qualified": False,
            "template_sha256": expected_template_digests(),
            "fixtures": [],
        }
        for name in sorted(FIXTURES):
            data = f"; offline unqualified probe: {name}\nG1 X10 Y10 E1\n".encode()
            filename = f"{name}.gcode"
            (self.directory / filename).write_bytes(data)
            config_name = f"{name}.config.ini"
            config_data = f"config for {name}\n".encode()
            (self.directory / config_name).write_bytes(config_data)
            self.manifest["fixtures"].append({
                "fixture": name, "file": filename, "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False, "counterfactual": False,
                "bbl_printer_mode": False, "config_file": config_name,
                "config_sha256": hashlib.sha256(config_data).hexdigest(),
            })
        self.write_manifest()
        self.end_only = self.directory / "end-only"
        self.end_only.mkdir()
        self.end_manifest = {
            "schema_version": 1,
            "source_commit": SOURCE,
            "scope": "machine-end-template-only-render-diagnostic",
            "qualified": False,
            "template_sha256": expected_template_digests()["machine_end_gcode"],
            "fixtures": [],
        }
        for name in sorted(FIXTURES):
            data = f"; end-only unqualified probe: {name}\nG1 X10 Y10 E1 F600\n".encode()
            filename = f"{name}.gcode"
            (self.end_only / filename).write_bytes(data)
            self.end_manifest["fixtures"].append({
                "fixture": name, "file": filename, "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False,
            })
        self.write_end_manifest()
        self.counterfactual_bbl = self.directory / "counterfactual-bbl"
        self.counterfactual_bbl.mkdir()
        self.counterfactual_manifest = {
            "schema_version": 1, "source_commit": SOURCE,
            "scope": "counterfactual-bbl-identity-template-render-diagnostic",
            "counterfactual": True, "bbl_printer_mode": True,
            "production_runtime_changed": False, "non_gating": True, "qualified": False,
            "profile_name": PROFILE, "profile_sha256": G3_PROFILE_SHA256,
            "template_sha256": expected_template_digests(), "fixtures": [],
        }
        for name in sorted(FIXTURES):
            data = f"; counterfactual BBL unqualified probe: {name}\nG1 X10 Y10 E1 F600\n".encode()
            filename = f"{name}.gcode"
            (self.counterfactual_bbl / filename).write_bytes(data)
            config_name = f"{name}.config.ini"
            config_data = f"config for {name}\n".encode()
            (self.counterfactual_bbl / config_name).write_bytes(config_data)
            self.counterfactual_manifest["fixtures"].append({
                "fixture": name, "file": filename, "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False, "counterfactual": True,
                "bbl_printer_mode": True, "config_file": config_name,
                "config_sha256": hashlib.sha256(config_data).hexdigest(),
            })
        self.write_counterfactual_manifest()

    def write_manifest(self):
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def write_end_manifest(self):
        (self.directory / "end-only-manifest.json").write_text(
            json.dumps(self.end_manifest), encoding="utf-8"
        )

    def write_counterfactual_manifest(self):
        (self.counterfactual_bbl / "manifest.json").write_text(
            json.dumps(self.counterfactual_manifest), encoding="utf-8"
        )

    def test_valid_probe_output_and_hashes(self):
        self.assertEqual(SOURCE, validate(self.directory)["source_commit"])
        self.assertEqual(SOURCE, validate_end_only(self.end_only)["source_commit"])
        manifest = validate_counterfactual_bbl(self.counterfactual_bbl)
        self.assertTrue(manifest["counterfactual"])
        self.assertFalse(manifest["qualified"])

    def test_counterfactual_bbl_rejects_missing_mode_or_unpinned_identity(self):
        for field, value in (("counterfactual", False), ("bbl_printer_mode", False),
                             ("non_gating", False), ("qualified", True),
                             ("profile_sha256", "0" * 64),
                             ("template_sha256", {"machine_start_gcode": "a" * 64})):
            with self.subTest(field=field):
                old = self.counterfactual_manifest[field]
                self.counterfactual_manifest[field] = value
                self.write_counterfactual_manifest()
                with self.assertRaisesRegex(ValueError, "counterfactual BBL manifest"):
                    validate_counterfactual_bbl(self.counterfactual_bbl)
                self.counterfactual_manifest[field] = old
        self.write_counterfactual_manifest()
        original = self.counterfactual_bbl / self.counterfactual_manifest["fixtures"][0]["file"]
        data = original.read_bytes()
        original.write_bytes(data + b"G28\n")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate_counterfactual_bbl(self.counterfactual_bbl)
        original.write_bytes(data)

    def test_end_only_probe_rejects_wrong_scope_digest_and_outputs(self):
        for field, value in (("scope", "template-rendering-only"), ("qualified", True),
                             ("template_sha256", "0" * 64)):
            with self.subTest(field=field):
                old = self.end_manifest[field]
                self.end_manifest[field] = value
                self.write_end_manifest()
                with self.assertRaises(ValueError):
                    validate_end_only(self.end_only)
                self.end_manifest[field] = old
        self.write_end_manifest()
        output = self.end_only / self.end_manifest["fixtures"][0]["file"]
        data = output.read_bytes()
        output.write_bytes(data + b"G28\n")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate_end_only(self.end_only)
        output.write_bytes(data)

    def test_rejects_wrong_identity_scope_and_missing_hooks(self):
        for field, value in (("source_commit", "0" * 40), ("qualified", True),
                             ("scope", "production"), ("default_bbl_printer_mode", True),
                             ("profile_sha256", "0" * 64)):
            with self.subTest(field=field):
                old = self.manifest[field]
                self.manifest[field] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    validate(self.directory)
                self.manifest[field] = old
        del self.manifest["template_sha256"]["time_lapse_gcode"]
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "all four"):
            validate(self.directory)

    def test_rejects_well_formed_but_unpinned_template_digests(self):
        self.manifest["template_sha256"]["machine_start_gcode"] = "a" * 64
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, "pinned source bundle"):
            validate(self.directory)

    def test_rejects_duplicate_unexpected_and_path_traversal_fixture(self):
        original = list(self.manifest["fixtures"])
        for records in (original[:-1] + [dict(original[0])],
                        original[:-1] + [{**original[-1], "fixture": "other"}],
                        original[:-1] + [{**original[-1], "file": "../escape.gcode"}]):
            self.manifest["fixtures"] = records
            self.write_manifest()
            with self.assertRaises(ValueError):
                validate(self.directory)

    def test_rejects_output_tampering_and_extra_output(self):
        path = self.directory / self.manifest["fixtures"][0]["file"]
        original = path.read_bytes()
        path.write_bytes(original + b"G28\n")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate(self.directory)
        path.write_bytes(original)
        (self.directory / "unexpected.gcode").write_text("G1\n")
        with self.assertRaisesRegex(ValueError, "unexpected G-code"):
            validate(self.directory)

    def test_attempt_artifact_is_created_for_emulator_boot_or_test_failure(self):
        directory = self.directory / "attempt-only"
        record(directory, "initialized")
        record(directory, "failed", 1)
        payload = json.loads((directory / "attempt.json").read_text())
        self.assertEqual("failed", payload["state"])
        self.assertEqual(1, payload["exit_code"])
        self.assertFalse(payload["qualified"])
        self.assertEqual("template-rendering-only", payload["scope"])


if __name__ == "__main__":
    unittest.main()
