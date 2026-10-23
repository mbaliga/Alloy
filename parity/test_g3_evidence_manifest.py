"""Tests for source/run-bound native G3 artifact manifests."""

import json
from pathlib import Path
import tempfile
import unittest

from ci.g3_evidence_manifest import validate_manifest, write_manifest


class G3EvidenceManifestTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for fixture in ("cube_20mm", "overhang_support", "thin_wall_frame", "travel_obstacle"):
            (self.root / f"{fixture}.gcode").write_text(f"G1 X{len(fixture)}\n", encoding="utf-8")
            (self.root / f"{fixture}.config.ini").write_text(f"fixture={fixture}\n", encoding="utf-8")
        self.identity = {
            "head_sha": "a" * 40,
            "base_sha": "b" * 40,
            "merge_sha": "c" * 40,
            "run_id": "123456",
            "run_attempt": "2",
        }

    def test_manifest_binds_every_file_and_pr_run_context(self):
        written = write_manifest(self.root, **self.identity)
        validated = validate_manifest(
            self.root,
            head_sha=self.identity["head_sha"],
            base_sha=self.identity["base_sha"],
            merge_sha=self.identity["merge_sha"],
        )
        self.assertEqual(written, validated)
        self.assertEqual(8, len(validated["files_sha256"]))
        self.assertEqual("123456", validated["workflow_run_id"])
        self.assertIs(validated["qualified_for_print"], False)

    def test_manifest_rejects_a_different_pr_tree(self):
        write_manifest(self.root, **self.identity)
        with self.assertRaisesRegex(ValueError, "identity mismatch"):
            validate_manifest(
                self.root,
                head_sha="d" * 40,
                base_sha=self.identity["base_sha"],
                merge_sha=self.identity["merge_sha"],
            )

    def test_manifest_rejects_changed_or_added_inputs(self):
        write_manifest(self.root, **self.identity)
        (self.root / "cube_20mm.gcode").write_text("G1 X999\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            validate_manifest(
                self.root,
                head_sha=self.identity["head_sha"],
                base_sha=self.identity["base_sha"],
                merge_sha=self.identity["merge_sha"],
            )
        write_manifest(self.root, **self.identity)
        (self.root / "untracked.gcode").write_text("G1 X0\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "file set differs"):
            validate_manifest(
                self.root,
                head_sha=self.identity["head_sha"],
                base_sha=self.identity["base_sha"],
                merge_sha=self.identity["merge_sha"],
            )

    def test_manifest_rejects_invalid_digest_and_identity(self):
        with self.assertRaisesRegex(ValueError, "invalid PR head SHA"):
            write_manifest(self.root, **{**self.identity, "head_sha": "not-a-sha"})
        with self.assertRaisesRegex(ValueError, "invalid GitHub Actions"):
            write_manifest(self.root, **{**self.identity, "run_attempt": "latest"})
        write_manifest(self.root, **self.identity)
        manifest_path = self.root / "g3-evidence-manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["files_sha256"]["../outside"] = "0" * 64
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unsafe path or digest"):
            validate_manifest(
                self.root,
                head_sha=self.identity["head_sha"],
                base_sha=self.identity["base_sha"],
                merge_sha=self.identity["merge_sha"],
            )


if __name__ == "__main__":
    unittest.main()
