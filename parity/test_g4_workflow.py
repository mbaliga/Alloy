import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class G4WorkflowTests(unittest.TestCase):
    def test_template_audit_consumes_exported_machine_profile(self):
        workflow = (ROOT / ".github/workflows/path-b-g4-a1mini-profile-audit.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("path-b-g4-evidence/resolved/machine.json", workflow)
        self.assertNotIn("path-b-g4-evidence/resolved/resolved-printer.json", workflow)


if __name__ == "__main__":
    unittest.main()
