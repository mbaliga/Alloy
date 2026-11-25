import tempfile
import unittest
from pathlib import Path

from ci.validate_g3_evidence import REQUIRED, validate


class G3EvidenceTests(unittest.TestCase):
    def test_complete_bundle_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in REQUIRED:
                root.joinpath(name).write_text(
                    "enable_support = 1\nsupport_type = tree(auto)\n"
                    if name == "overhang_support.config.ini"
                    else (";LAYER:0\nG90\nM104 S0\n" if name.endswith(".gcode") else "config\n"),
                    encoding="utf-8",
                )
            validate(root)

    def test_missing_support_contract_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in REQUIRED:
                root.joinpath(name).write_text(
                    ";LAYER:0\nG90\nM104 S0\n" if name.endswith(".gcode") else "config\n",
                    encoding="utf-8",
                )
            with self.assertRaises(SystemExit):
                validate(root)

    def test_slice_beam_layer_change_marker_is_accepted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for name in REQUIRED:
                root.joinpath(name).write_text(
                    "enable_support = 1\nsupport_type = tree(auto)\n"
                    if name == "overhang_support.config.ini"
                    else (";LAYER_CHANGE\nG90\nM104 S0\n" if name.endswith(".gcode") else "config\n"),
                    encoding="utf-8",
                )
            validate(root)


if __name__ == "__main__":
    unittest.main()
