"""Unit tests for matching, downloading, and validating same-commit G3 evidence."""

from io import BytesIO
from email.message import Message
from urllib.error import HTTPError
from pathlib import Path
import tempfile
import unittest
from zipfile import ZipFile
from unittest.mock import patch

from ci.compare_g3_artifacts import (
    REFERENCE_ARTIFACT,
    download_artifact,
    safe_extract,
    select_reference_artifact,
    select_reference_run,
    wait_for_reference_run,
)


class G3ArtifactParityTests(unittest.TestCase):
    def test_reference_run_requires_exact_successful_pr_head(self):
        head = "a" * 40
        runs = [
            {"id": 1, "head_sha": "b" * 40, "event": "pull_request", "status": "completed", "conclusion": "success"},
            {"id": 2, "head_sha": head, "event": "pull_request", "status": "completed", "conclusion": "failure"},
            {"id": 3, "head_sha": head, "event": "pull_request", "status": "completed", "conclusion": "success", "created_at": "2026-10-14T01:00:00Z"},
            {"id": 4, "head_sha": head, "event": "pull_request", "status": "completed", "conclusion": "success", "created_at": "2026-10-14T02:00:00Z"},
            {"id": 5, "head_sha": head, "event": "push", "status": "completed", "conclusion": "success", "created_at": "2026-10-14T03:00:00Z"},
        ]
        self.assertEqual(4, select_reference_run(runs, head)["id"])

    def test_reference_run_fails_when_head_has_no_successful_reference(self):
        with self.assertRaisesRegex(RuntimeError, "exact PR head"):
            select_reference_run([], "a" * 40)

    def test_wait_retries_until_exact_head_reference_is_successful(self):
        head = "c" * 40
        responses = iter([
            {"workflow_runs": []},
            {"workflow_runs": [{"id": 9, "head_sha": head, "event": "pull_request", "status": "completed", "conclusion": "success"}]},
        ])
        sleeps = []

        def fake_get(url, token):
            self.assertIn(head, url)
            self.assertEqual("token", token)
            return next(responses)

        result = wait_for_reference_run("mbaliga/Alloy", head, "token", attempts=2, sleep=sleeps.append, get_json=fake_get)
        self.assertEqual(9, result["id"])
        self.assertEqual([15], sleeps)

    def test_reference_artifact_must_be_named_and_unexpired(self):
        artifacts = [
            {"name": REFERENCE_ARTIFACT, "expired": True, "archive_download_url": "expired"},
            {"name": "other", "expired": False, "archive_download_url": "wrong"},
            {"name": REFERENCE_ARTIFACT, "expired": False, "archive_download_url": "valid"},
        ]
        self.assertEqual("valid", select_reference_artifact(artifacts)["archive_download_url"])

    def test_artifact_extraction_rejects_path_traversal(self):
        archive_buffer = BytesIO()
        with ZipFile(archive_buffer, "w") as archive:
            archive.writestr("../../outside.txt", "bad")
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaisesRegex(RuntimeError, "unsafe path"):
                safe_extract(archive_buffer.getvalue(), Path(temporary) / "reference")
            self.assertFalse((Path(temporary) / "outside.txt").exists())

    def test_github_token_is_not_forwarded_to_signed_artifact_host(self):
        response_headers = Message()
        response_headers["Location"] = "https://artifact-storage.example/signed.zip"

        class RedirectingOpener:
            def open(self, request, timeout):
                self_request["authorization"] = request.get_header("Authorization")
                raise HTTPError(request.full_url, 302, "Found", response_headers, None)

        self_request = {}
        with patch("ci.compare_g3_artifacts.build_opener", return_value=RedirectingOpener()):
            with patch("ci.compare_g3_artifacts.urlopen", return_value=BytesIO(b"zip")) as signed_open:
                self.assertEqual(b"zip", download_artifact("https://api.github.com/artifact", "secret-token"))

        signed_request = signed_open.call_args.args[0]
        self.assertEqual("Bearer secret-token", self_request["authorization"])
        self.assertIsNone(signed_request.get_header("Authorization"))


if __name__ == "__main__":
    unittest.main()
