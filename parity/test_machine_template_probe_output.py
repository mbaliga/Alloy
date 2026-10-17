import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from validate_machine_template_probe_output import FIXTURES, SOURCE, validate


class MachineTemplateProbeOutputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.manifest = {
            "schema_version": 1,
            "source_commit": SOURCE,
            "scope": "template-rendering-only",
            "qualified": False,
            "template_sha256": {key: "a" * 64 for key in (
                "machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"
            )},
            "fixtures": [],
        }
        for name in sorted(FIXTURES):
            data = f"; offline unqualified probe: {name}\nG1 X10 Y10 E1\n".encode()
            filename = f"{name}.gcode"
            (self.directory / filename).write_bytes(data)
            self.manifest["fixtures"].append({
                "fixture": name, "file": filename, "sha256": hashlib.sha256(data).hexdigest(),
                "rendered": True, "qualified": False,
            })
        self.write_manifest()

    def write_manifest(self):
        (self.directory / "manifest.json").write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_valid_probe_output_and_hashes(self):
        self.assertEqual(SOURCE, validate(self.directory)["source_commit"])

    def test_rejects_wrong_identity_scope_and_missing_hooks(self):
        for field, value in (("source_commit", "0" * 40), ("qualified", True), ("scope", "production")):
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


if __name__ == "__main__":
    unittest.main()
