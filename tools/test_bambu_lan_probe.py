from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from tools.bambu_lan_probe import (
    build_curl_config,
    build_start_payload,
    telemetry_matches_job,
    validate_access_code,
    validate_host,
    validate_remote,
)


class BambuLanProbeTests(unittest.TestCase):
    def test_remote_path_is_bounded_and_relative(self) -> None:
        self.assertEqual(validate_remote("cache/box.gcode.3mf"), "cache/box.gcode.3mf")
        for value in ("/box.gcode.3mf", "cache/../box.gcode.3mf", "cache/bad name.gcode.3mf", "box.stl"):
            with self.assertRaises(ValueError):
                validate_remote(value)

    def test_payload_is_explicit_no_ams_project_file(self) -> None:
        payload = build_start_payload("01S123", "cache/box.gcode.3mf", "box.gcode.3mf", 1)
        self.assertEqual(payload["print"]["command"], "project_file")
        self.assertEqual(payload["print"]["param"], "Metadata/plate_1.gcode")
        self.assertEqual(payload["print"]["url"], "ftp:///cache/box.gcode.3mf")
        self.assertEqual(payload["print"]["subtask_name"], "box")
        self.assertEqual(payload["print"]["file"], "")
        self.assertEqual(payload["print"]["bed_type"], "auto")
        self.assertTrue(payload["print"]["bed_leveling"])
        self.assertTrue(payload["print"]["bed_levelling"])
        self.assertFalse(payload["print"]["use_ams"])
        self.assertNotIn("ams_mapping", payload["print"])
        json.dumps(payload)

    def test_matching_telemetry_requires_structured_job_identity(self) -> None:
        payload = json.dumps({
            "print": {
                "gcode_state": "RUNNING",
                "subtask_name": "box",
                "url": "ftp:///cache/box.gcode.3mf",
            }
        })
        self.assertEqual((True, "RUNNING"), telemetry_matches_job(payload, "cache/box.gcode.3mf"))

    def test_unrelated_or_malformed_telemetry_is_not_acceptance(self) -> None:
        unrelated = json.dumps({"print": {"gcode_state": "RUNNING", "subtask_name": "other"}})
        self.assertEqual((False, ""), telemetry_matches_job(unrelated, "cache/box.gcode.3mf"))
        self.assertEqual((False, ""), telemetry_matches_job(b"not-json", "cache/box.gcode.3mf"))

    def test_curl_config_escapes_secret_and_file_values(self) -> None:
        config = build_curl_config("192.168.1.2", '12"34', Path('/tmp/box "one".gcode.3mf'), "cache/box.gcode.3mf")
        self.assertIn('user = "bblp:12\\"34"', config)
        self.assertIn('upload-file = "/tmp/box \\"one\\".gcode.3mf"', config)
        self.assertNotIn('user = "bblp:12"34"', config)
        self.assertNotIn('upload-file = "/tmp/box "one".gcode.3mf"', config)

    def test_protocol_tokens_reject_controls_and_unbounded_values(self) -> None:
        self.assertEqual(validate_host("192.0.2.1"), "192.0.2.1")
        self.assertEqual(validate_access_code("12 34"), "12 34")
        for value in ("bad host", "bad\nhost"):
            with self.assertRaises(ValueError):
                validate_host(value)
        for value in ("bad\ncode", "x" * 129):
            with self.assertRaises(ValueError):
                validate_access_code(value)


if __name__ == "__main__":
    unittest.main()
