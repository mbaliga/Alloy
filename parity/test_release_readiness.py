import json
import tempfile
import unittest
from pathlib import Path

from ci.validate_release_readiness import REQUIRED_GATES, validate


class ReleaseReadinessTests(unittest.TestCase):
    def _write(self, value):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "readiness.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return directory, path

    def test_all_required_gates_are_explicit(self):
        value = {
            "schema_version": 1,
            "evidence_id": "a1-mini-acceptance-2027-01-01",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        value["physical_acceptance_evidence"] = "physical.json"
        directory, path = self._write(value)
        try:
            (path.parent / "physical.json").write_text(json.dumps({
                "schema_version": 1,
                "evidence_id": "a1-mini-2027-01-01",
                "verified_at": "2027-01-01T00:00:00Z",
                "reviewer": "release-owner",
                "device_scope": "Bambu Lab A1 Mini / N1 / firmware 01.00",
                "apk_sha256": "a" * 64,
                "instrumentation_sha256": "b" * 64,
                "signer_sha256": "c" * 64,
                "printer_certificate_sha256": "d" * 64,
                "cases": {
                    "discovery": "PASS", "upload": "PASS", "start": "PASS",
                    "running": "PASS", "completion": "PASS", "pause_resume": "PASS",
                    "cancel": "PASS", "recovery": "PASS", "offline": "PASS",
                },
            }))
            self.assertEqual("a1-mini-acceptance-2027-01-01", validate(path)["evidence_id"])
        finally:
            directory.cleanup()

    def test_physical_gate_cannot_be_omitted_or_softened(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "emulator",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        value["gates"]["physical_a1_mini_transport"] = "PASS_WITH_WARNINGS"
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()

    def test_physical_pass_requires_valid_acceptance_record(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()

    def test_placeholder_identity_is_rejected_even_when_gates_are_passed(self):
        value = {
            "schema_version": 1,
            "evidence_id": "REPLACE-WITH-REVIEWED-ID",
            "verified_at": "REPLACE-WITH-UTC-TIMESTAMP",
            "reviewer": "REPLACE-WITH-REVIEWER",
            "device_scope": "Bambu Lab A1 Mini / N1 / firmware REPLACE",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        value["physical_acceptance_evidence"] = "physical.json"
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()


if __name__ == "__main__":
    unittest.main()
