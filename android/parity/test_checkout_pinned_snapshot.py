from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "ci"))
from checkout_pinned_snapshot import checkout_snapshot


class PinnedSnapshotCheckoutTests(unittest.TestCase):
    def test_transient_checkout_failure_retries_then_verifies_exact_revision(self):
        calls = {"clone": 0, "checkout": 0}
        delays = []
        revision = "0123456789abcdef"

        def runner(command, **kwargs):
            if command[1] == "clone":
                calls["clone"] += 1
                Path(command[-1]).mkdir(parents=True)
                return subprocess.CompletedProcess(command, 0, "", "")
            if "checkout" in command:
                calls["checkout"] += 1
                if calls["checkout"] == 1:
                    return subprocess.CompletedProcess(command, 128, "", "temporary HTTP 408")
                return subprocess.CompletedProcess(command, 0, "", "")
            return subprocess.CompletedProcess(command, 0, revision + "\n", "")

        with tempfile.TemporaryDirectory(prefix="alloy-checkout-test-") as temp:
            destination = Path(temp) / "snapshot"
            checkout_snapshot("https://example.invalid/repo.git", revision, destination,
                              attempts=3, retry_delay=2, runner=runner, sleep=delays.append)
            self.assertTrue(destination.is_dir())
            self.assertEqual(2, calls["clone"])
            self.assertEqual(2, calls["checkout"])
            self.assertEqual([2], delays)

    def test_exhausted_retries_leave_no_destination_tree(self):
        def runner(command, **kwargs):
            if command[1] == "clone":
                Path(command[-1]).mkdir(parents=True)
            return subprocess.CompletedProcess(command, 128, "", "network timeout")

        with tempfile.TemporaryDirectory(prefix="alloy-checkout-test-") as temp:
            destination = Path(temp) / "snapshot"
            with self.assertRaisesRegex(RuntimeError, "exhausted retries"):
                checkout_snapshot("https://example.invalid/repo.git", "deadbeef", destination,
                                  attempts=2, retry_delay=0, runner=runner, sleep=lambda _: None)
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()
