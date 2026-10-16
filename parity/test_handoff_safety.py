"""Guard the Android export/share UI boundary against print-ready bypasses."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class HandoffSafetyTests(unittest.TestCase):
    def test_every_external_share_route_checks_print_readiness_first(self) -> None:
        source = (ROOT / "app/src/main/java/com/mbaliga/alloy/MainActivity.java").read_text(
            encoding="utf-8"
        )
        start = source.index("private void sharePackage(boolean preferBambuHandy)")
        end = source.index("private void openViewExport()", start)
        share_method = source[start:end]

        gate = share_method.index("BambuHandyHandoff.validationBlockReason(")
        blocked_return = share_method.index("if (handoffBlock != null)", gate)
        staging = share_method.index("ArtifactStore.stage(", blocked_return)
        branch = share_method.index("if (preferBambuHandy)", staging)

        self.assertLess(gate, blocked_return)
        self.assertLess(blocked_return, staging)
        self.assertLess(staging, branch)
        self.assertIn("return;", share_method[blocked_return:staging])
        self.assertIn("sharePackage(false)", source)
        self.assertIn("sharePackage(true)", source)


if __name__ == "__main__":
    unittest.main()
