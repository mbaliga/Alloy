import json
import tempfile
import unittest
from pathlib import Path

from ci.validate_physical_a1_acceptance import REQUIRED_CASES, validate


class PhysicalA1MiniAcceptanceTests(unittest.TestCase):
    def _record(self):
        return {
            "schema_version": 1,
            "evidence_id": "a1-mini-2026-09-20-test",
            "verified_at": "2026-09-20T00:00:00Z",
            "reviewer": "release-owner",
            "device_scope": "Bambu Lab A1 Mini / N1 / firmware 01.02.00.00",
            "apk_sha256": "a" * 64,
            "instrumentation_sha256": "b" * 64,
            "signer_sha256": "c" * 64,
            "printer_certificate_sha256": "d" * 64,
            "cases": {case: "PASS" for case in REQUIRED_CASES},
        }

    def _write(self, value):
        directory = tempfile.TemporaryDirectory()
        path = Path(directory.name) / "acceptance.json"
        path.write_text(json.dumps(value), encoding="utf-8")
        return directory, path

    def test_complete_hardware_record_passes(self):
        directory, path = self._write(self._record())
        try:
            self.assertEqual("a1-mini-2026-09-20-test", validate(path)["evidence_id"])
        finally:
            directory.cleanup()

    def test_missing_case_fails_closed(self):
        value = self._record()
        value["cases"]["recovery"] = "NOT_RUN"
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()

    def test_wrong_printer_model_and_hash_fail_closed(self):
        value = self._record()
        value["device_scope"] = "Bambu Lab P1P / P1P"
        value["apk_sha256"] = "not-a-hash"
        directory, path = self._write(value)
        try:
            with self.assertRaises(SystemExit):
                validate(path)
        finally:
            directory.cleanup()


if __name__ == "__main__":
    unittest.main()
