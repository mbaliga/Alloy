import json
import hashlib
import tempfile
import unittest
from pathlib import Path

from ci.validate_release_readiness import PRINTER_SPECIFIC_GATES, REQUIRED_GATES, validate


class ReleaseReadinessTests(unittest.TestCase):
    def _physical_record(self, printer, scope):
        return {
            "schema_version": 1,
            "evidence_id": f"{printer}-2027-01-01",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": scope,
            "apk_sha256": "a" * 64,
            "instrumentation_sha256": "b" * 64,
            "signer_sha256": "c" * 64,
            "printer_certificate_sha256": "d" * 64,
            "cases": {case: "PASS" for case in (
                "discovery", "upload", "start", "running", "completion",
                "pause_resume", "cancel", "recovery", "offline",
            )},
        }

    def _write(self, value):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "readiness.json"
        evidence = {}
        for gate, status in value.get("gates", {}).items():
            if status != "PASS":
                continue
            name = f"evidence-{gate}.txt"
            content = f"reviewed evidence for {gate}\n".encode("utf-8")
            (path.parent / name).write_bytes(content)
            evidence[gate] = {"path": name, "sha256": hashlib.sha256(content).hexdigest()}
            printer_id = PRINTER_SPECIFIC_GATES.get(gate)
            if printer_id:
                evidence[gate]["printer_id"] = printer_id
        value.setdefault("gate_evidence", evidence)
        path.write_text(json.dumps(value), encoding="utf-8")
        return directory, path

    def test_all_required_gates_are_explicit(self):
        value = {
            "schema_version": 1,
            "evidence_id": "a1-mini-acceptance-2027-01-01",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini (N1), A1, and P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        value["physical_acceptance_evidence"] = {
            "a1-mini": "physical-a1-mini.json",
            "a1": "physical-a1.json",
            "p1s": "physical-p1s.json",
        }
        directory, path = self._write(value)
        try:
            scopes = {
                "a1-mini": "Bambu Lab A1 Mini / N1 / firmware 01.00",
                "a1": "Bambu Lab A1 / firmware 01.00",
                "p1s": "Bambu Lab P1S / firmware 01.00",
            }
            for printer, filename in value["physical_acceptance_evidence"].items():
                record = self._physical_record(printer, scopes[printer])
                (path.parent / filename).write_text(json.dumps(record), encoding="utf-8")
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
            "device_scope": "Android arm64 + Bambu Lab A1 Mini (N1), A1, and P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()

    def test_each_physical_printer_pass_requires_its_own_model_matched_record(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini / N1, A1, P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
            "physical_acceptance_evidence": {
                "a1-mini": "mini.json", "a1": "a1.json", "p1s": "p1s.json",
            },
        }
        directory, path = self._write(value)
        try:
            (path.parent / "mini.json").write_text(json.dumps(self._physical_record(
                "a1-mini", "Bambu Lab A1 Mini / N1 / firmware 01.00")), encoding="utf-8")
            (path.parent / "a1.json").write_text(json.dumps(self._physical_record(
                "a1", "Bambu Lab A1 / firmware 01.00")), encoding="utf-8")
            # Deliberately provide A1 evidence for the P1S gate: model substitution must fail.
            (path.parent / "p1s.json").write_text(json.dumps(self._physical_record(
                "p1s", "Bambu Lab A1 / firmware 01.00")), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "physical P1S acceptance"):
                validate(path)
        finally:
            directory.cleanup()

    def test_placeholder_identity_is_rejected_even_when_gates_are_passed(self):
        value = {
            "schema_version": 1,
            "evidence_id": "REPLACE-WITH-REVIEWED-ID",
            "verified_at": "REPLACE-WITH-UTC-TIMESTAMP",
            "reviewer": "REPLACE-WITH-REVIEWER",
            "device_scope": "Bambu Lab A1 Mini (N1), A1, and P1S / firmware REPLACE",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        value["physical_acceptance_evidence"] = {"a1-mini": "physical.json"}
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()

    def test_gate_evidence_must_match_retained_file_digest(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini (N1), A1, and P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        directory, path = self._write(value)
        try:
            evidence_path = path.parent / value["gate_evidence"]["a1_mini_g3_toolpath_parity"]["path"]
            evidence_path.write_text("altered after review\n", encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "SHA-256 does not match"):
                validate(path)
        finally:
            directory.cleanup()

    def test_per_printer_gate_evidence_cannot_be_relabelled_for_another_model(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini / N1, A1, P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        directory, path = self._write(value)
        try:
            value["gate_evidence"]["p1s_g3_toolpath_parity"]["printer_id"] = "a1"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "printer_id must be p1s"):
                validate(path)
        finally:
            directory.cleanup()

    def test_gate_evidence_cannot_escape_readiness_directory(self):
        value = {
            "schema_version": 1,
            "evidence_id": "candidate",
            "verified_at": "2027-01-01T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Android arm64 + Bambu Lab A1 Mini (N1), A1, and P1S",
            "gates": {gate: "PASS" for gate in REQUIRED_GATES},
        }
        directory, path = self._write(value)
        try:
            value["gate_evidence"]["native_engine_source_build"]["path"] = "../outside.txt"
            path.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaisesRegex(SystemExit, "must stay beside"):
                validate(path)
        finally:
            directory.cleanup()

    def test_checked_in_template_remains_unpromotable(self):
        template = Path(__file__).resolve().parents[1] / "release/production-readiness.template.json"
        value = json.loads(template.read_text(encoding="utf-8"))
        self.assertEqual(set(REQUIRED_GATES), set(value["gates"]))
        self.assertTrue(all(status == "PENDING" for status in value["gates"].values()))
        with self.assertRaisesRegex(SystemExit, "still contains a placeholder"):
            validate(template)


if __name__ == "__main__":
    unittest.main()
