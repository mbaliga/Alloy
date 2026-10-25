from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from export_resolved_orca_profiles import (
    PRINTER_PROFILES,
    resolve,
    validate_printer_compatibility,
)


ROOT = Path(__file__).parents[1]
ORCA = ROOT / "third_party/orca-official"


class ResolvedOrcaProfileMatrixTests(unittest.TestCase):
    def test_three_initial_printers_have_explicit_source_profile_mappings(self):
        self.assertEqual(set(PRINTER_PROFILES), {"a1mini", "a1", "p1s"})
        self.assertEqual(PRINTER_PROFILES["a1"]["machine"], "Bambu Lab A1 0.4 nozzle")
        self.assertEqual(PRINTER_PROFILES["p1s"]["machine"], "Bambu Lab P1S 0.4 nozzle")
        self.assertEqual(PRINTER_PROFILES["p1s"]["process"], "0.20mm Standard @BBL X1C")
        self.assertEqual(PRINTER_PROFILES["p1s"]["filament"], "Bambu PLA Basic @BBL X1C")

    def test_every_mapping_resolves_in_the_pinned_orca_snapshot(self):
        from export_resolved_orca_profiles import load_kind

        for printer, selected in PRINTER_PROFILES.items():
            with self.subTest(printer=printer):
                for kind, name in selected.items():
                    profiles, sources = load_kind(ORCA, kind)
                    resolved = resolve(name, profiles)
                    self.assertEqual(resolved["name"], name)
                    self.assertTrue(sources[name].is_file())

    def test_process_and_filament_leaves_explicitly_support_the_selected_printer(self):
        from export_resolved_orca_profiles import load_kind

        for printer, selected in PRINTER_PROFILES.items():
            with self.subTest(printer=printer):
                resolved = {
                    kind: resolve(selected[kind], load_kind(ORCA, kind)[0])
                    for kind in selected
                }
                self.assertEqual(
                    selected["machine"],
                    validate_printer_compatibility(printer, selected, resolved),
                )

    def test_compatibility_validation_rejects_missing_or_wrong_machine(self):
        selected = PRINTER_PROFILES["p1s"]
        resolved = {
            "machine": {"name": selected["machine"]},
            "process": {"compatible_printers": ["Bambu Lab X1 Carbon 0.4 nozzle"]},
            "filament": {"compatible_printers": [selected["machine"]]},
        }
        with self.assertRaisesRegex(ValueError, "p1s process profile"):
            validate_printer_compatibility("p1s", selected, resolved)

        resolved["process"]["compatible_printers"] = [selected["machine"]]
        resolved["filament"].pop("compatible_printers")
        with self.assertRaisesRegex(ValueError, "p1s filament profile"):
            validate_printer_compatibility("p1s", selected, resolved)

    def test_multi_printer_export_records_resolved_leaf_provenance(self):
        with tempfile.TemporaryDirectory(prefix="alloy-profile-matrix-") as temp:
            output = Path(temp) / "profiles"
            subprocess.run(
                [sys.executable, str(ROOT / "parity/export_resolved_orca_profiles.py"),
                 str(ORCA), str(output), "--printer", "a1", "--printer", "p1s"],
                check=True, capture_output=True, text=True,
            )
            manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
            self.assertEqual(set(manifest["printers"]), {"a1", "p1s"})
            for printer in ("a1", "p1s"):
                self.assertEqual(
                    {
                        "machine": PRINTER_PROFILES[printer]["machine"],
                        "process_and_filament_explicitly_compatible": True,
                    },
                    manifest["printers"][printer]["compatibility"],
                )
                for kind in ("machine", "process", "filament"):
                    entry = manifest["printers"][printer][kind]
                    self.assertTrue((output / entry["output"]).is_file())
                    self.assertIn("resources/profiles/BBL/", entry["leaf_source"])
                    if kind != "machine":
                        self.assertIn(
                            PRINTER_PROFILES[printer]["machine"],
                            entry["compatible_printers"],
                        )


if __name__ == "__main__":
    unittest.main()
