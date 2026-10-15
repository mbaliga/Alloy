import unittest
from pathlib import Path

from parity.audit_path_b_a1mini_profile import PRINTER_PROFILES


ROOT = Path(__file__).resolve().parents[1]


class G4WorkflowTests(unittest.TestCase):
    def test_static_profile_compatibility_matrix_covers_initial_printer_cohort(self):
        self.assertEqual({"a1-mini", "a1", "p1s"}, set(PRINTER_PROFILES))
        self.assertEqual("Bambu Lab A1 mini 0.4 nozzle", PRINTER_PROFILES["a1-mini"]["printer"])
        self.assertEqual("Bambu Lab A1 0.4 nozzle", PRINTER_PROFILES["a1"]["printer"])
        self.assertEqual("Bambu Lab P1S 0.4 nozzle", PRINTER_PROFILES["p1s"]["printer"])

    def test_template_audit_consumes_exported_machine_profile(self):
        workflow = (ROOT / ".github/workflows/path-b-g4-a1mini-profile-audit.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("path-b-g4-evidence/resolved/machine.json", workflow)
        self.assertNotIn("path-b-g4-evidence/resolved/resolved-printer.json", workflow)

    def test_matrix_workflow_keeps_audit_as_pre_gate_for_all_printers(self):
        workflow = (ROOT / ".github/workflows/bambu-profile-compatibility.yml").read_text(
            encoding="utf-8"
        )
        self.assertIn("printer: [a1-mini, a1, p1s]", workflow)
        self.assertNotIn("branches:", workflow.split("paths:", 1)[0])
        self.assertIn("--printer \"${{ matrix.printer }}\"", workflow)
        self.assertIn("BAMBU_PROFILE_SHA: da8b44ee34dd349f2ae0df3f1cbae366df482354", workflow)
        self.assertIn("github.com/bambulab/BambuStudio.git", workflow)
        self.assertIn("static compatibility pre-gate", workflow)
        self.assertIn("static compatibility pre-gate; it is not runtime parity", workflow)


if __name__ == "__main__":
    unittest.main()
