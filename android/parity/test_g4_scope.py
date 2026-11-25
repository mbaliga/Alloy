import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "ci/validate_g4_scope.py"


class G4ScopeTests(unittest.TestCase):
    def _fixture(self):
        commit = "a" * 40
        scope = {
            "schema_version": 1,
            "engine_commit": commit,
            "required": {kind: ["required"] for kind in ("printer", "process", "filament")},
            "rationales": {"unsupported_by_pinned_core": "explicit test rationale"},
            "excluded": {
                kind: {"unsupported_by_pinned_core": ["omitted"]}
                for kind in ("printer", "process", "filament")
            },
        }
        audit = {
            "slicebeam_commit": commit,
            "audit": {
                kind: {
                    "keys": [
                        {"orca_key": "required", "status": "supported"},
                        {"orca_key": "omitted", "status": "unsupported"},
                    ]
                }
                for kind in ("printer", "process", "filament")
            },
        }
        return audit, scope

    def _run(self, audit, scope):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            audit_path = root / "audit.json"
            scope_path = root / "scope.json"
            audit_path.write_text(json.dumps(audit), encoding="utf-8")
            scope_path.write_text(json.dumps(scope), encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(VALIDATOR), str(audit_path), str(scope_path)],
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
            )

    def test_accepts_complete_explicit_scope(self):
        result = self._run(*self._fixture())
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("every unsupported resolved field is reviewed", result.stdout)

    def test_rejects_unreviewed_field(self):
        audit, scope = self._fixture()
        audit["audit"]["printer"]["keys"].append({"orca_key": "new_field", "status": "unsupported"})
        result = self._run(audit, scope)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unreviewed unsupported field", result.stderr or result.stdout)

    def test_rejects_required_field_in_exclusion(self):
        audit, scope = self._fixture()
        scope["excluded"]["process"]["unsupported_by_pinned_core"].append("required")
        result = self._run(audit, scope)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("supported field is incorrectly excluded", result.stderr or result.stdout)


if __name__ == "__main__":
    unittest.main()
