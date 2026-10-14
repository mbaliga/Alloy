#!/usr/bin/env python3
"""Compare native G3 slices with the successful desktop reference for a PR SHA.

The native job runs long enough for the desktop reference job to finish. This
gate fetches only a successful reference artifact from the exact same PR head,
verifies its artifact digest, and compares every shared fixture. Missing or
stale evidence fails closed.
"""

from __future__ import annotations

import argparse
import hashlib
from io import BytesIO
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
import time
from typing import Callable, Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
from zipfile import BadZipFile, ZipFile


REFERENCE_WORKFLOW = "desktop-orca-reference.yml"
REFERENCE_ARTIFACT = "a1mini-orca-2.4.2-reference-slices"
FIXTURES = ("cube_20mm", "overhang_support", "thin_wall_frame", "travel_obstacle")
REPO_ROOT = Path(__file__).resolve().parents[1]


def select_reference_run(runs: list[dict[str, Any]], head_sha: str) -> dict[str, Any]:
    candidates = [
        run for run in runs
        if run.get("head_sha") == head_sha
        and run.get("event") == "pull_request"
        and run.get("status") == "completed"
        and run.get("conclusion") == "success"
    ]
    if not candidates:
        raise RuntimeError(f"no successful desktop reference run for exact PR head {head_sha}")
    return max(candidates, key=lambda run: run.get("created_at", ""))


def select_reference_artifact(artifacts: list[dict[str, Any]]) -> dict[str, Any]:
    candidates = [
        artifact for artifact in artifacts
        if artifact.get("name") == REFERENCE_ARTIFACT
        and not artifact.get("expired", False)
        and artifact.get("archive_download_url")
    ]
    if not candidates:
        raise RuntimeError(f"successful desktop run has no unexpired {REFERENCE_ARTIFACT} artifact")
    return max(candidates, key=lambda artifact: artifact.get("created_at", ""))


def github_get_json(url: str, token: str) -> dict[str, Any]:
    request = Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urlopen(request, timeout=60) as response:
            payload = response.read()
    except (HTTPError, URLError, TimeoutError) as error:
        status = getattr(error, "code", "network error")
        raise RuntimeError(f"GitHub Actions evidence request failed ({status})") from None
    try:
        return json.loads(payload.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        raise RuntimeError("GitHub Actions API returned invalid JSON") from None


class _NoRedirect(HTTPRedirectHandler):
    """Keep the GitHub token away from the signed external artifact URL."""

    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def download_artifact(url: str, token: str) -> bytes:
    request = Request(
        url,
        headers={
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with build_opener(_NoRedirect()).open(request, timeout=60) as response:
            return response.read()
    except HTTPError as response:
        if response.code not in (301, 302, 303, 307, 308):
            raise RuntimeError(f"GitHub artifact download failed ({response.code})") from None
        signed_url = response.headers.get("Location")
        parsed = urlparse(signed_url or "")
        if parsed.scheme != "https" or not parsed.netloc:
            raise RuntimeError("GitHub returned an invalid artifact download redirect") from None
        # The Location is a short-lived signed URL; do not forward the GitHub
        # bearer token to its host or any subsequent redirect target.
        try:
            with urlopen(Request(signed_url, headers={"Accept": "application/octet-stream"}), timeout=60) as signed:
                return signed.read()
        except (HTTPError, URLError, TimeoutError) as error:
            status = getattr(error, "code", "network error")
            raise RuntimeError(f"signed desktop artifact download failed ({status})") from None
    except (URLError, TimeoutError) as error:
        raise RuntimeError("GitHub artifact download failed (network error)") from None


def wait_for_reference_run(
    repository: str,
    head_sha: str,
    token: str,
    *,
    attempts: int = 5,
    delay_seconds: int = 15,
    get_json: Callable[[str, str], dict[str, Any]] | None = None,
    sleep: Callable[[float], None] = time.sleep,
) -> dict[str, Any]:
    get_json = get_json or github_get_json
    query = urlencode({"head_sha": head_sha, "event": "pull_request", "per_page": 100})
    url = (
        f"https://api.github.com/repos/{repository}/actions/workflows/"
        f"{REFERENCE_WORKFLOW}/runs?{query}"
    )
    last_error: RuntimeError | None = None
    for attempt in range(attempts):
        result = get_json(url, token)
        try:
            return select_reference_run(result.get("workflow_runs", []), head_sha)
        except RuntimeError as error:
            last_error = error
        if attempt + 1 < attempts:
            sleep(delay_seconds)
    raise last_error or RuntimeError(f"no successful desktop reference run for {head_sha}")


def safe_extract(archive_bytes: bytes, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    try:
        with ZipFile(BytesIO(archive_bytes)) as archive:
            for member in archive.infolist():
                relative = PurePosixPath(member.filename)
                if relative.is_absolute() or ".." in relative.parts:
                    raise RuntimeError(f"artifact contains unsafe path: {member.filename}")
                target = destination.joinpath(*relative.parts)
                if member.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(member))
    except BadZipFile:
        raise RuntimeError("desktop reference artifact is not a valid ZIP") from None


def compare_fixtures(
    native_dir: Path,
    reference_dir: Path,
    report_dir: Path,
    tolerance: float,
) -> dict[str, Any]:
    report_dir.mkdir(parents=True, exist_ok=True)
    reports: list[dict[str, Any]] = []
    comparator = REPO_ROOT / "parity/compare_gcode.py"
    for fixture in FIXTURES:
        desktop = reference_dir / "orca-reference" / fixture / f"{fixture}.gcode.3mf"
        native = native_dir / f"{fixture}.gcode"
        config = native_dir / f"{fixture}.config.ini"
        for path in (desktop, native, config):
            if not path.is_file() or path.stat().st_size == 0:
                raise RuntimeError(f"G3 parity input is missing or empty: {path.name}")
        output = report_dir / f"{fixture}.json"
        completed = subprocess.run(
            [
                sys.executable, str(comparator), str(desktop), str(native),
                "--desktop-profile", str(desktop), "--native-config", str(config),
                "--tolerance", str(tolerance), "--json-out", str(output),
            ],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        if not output.is_file():
            raise RuntimeError(f"G3 comparator did not write a report for {fixture}")
        detail = json.loads(output.read_text(encoding="utf-8"))
        comparison = detail["comparison"]
        reports.append({"fixture": fixture, **comparison})
        print(f"{fixture}: {comparison['result']}")
        for check in comparison["checks"]:
            if check["status"] == "fail":
                print(f"  FAIL {check['name']}: {check['detail']}")
        if completed.returncode not in (0, 2):
            stderr = completed.stderr[-2000:]
            raise RuntimeError(f"G3 comparator crashed for {fixture}: {stderr}")

    result = "pass" if all(item["result"] == "pass" for item in reports) else "fail"
    summary = {"result": result, "tolerance": tolerance, "fixtures": reports}
    (report_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--native-dir", type=Path, required=True)
    parser.add_argument("--report-dir", type=Path, required=True)
    parser.add_argument("--repository", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--head-sha", default=os.environ.get("ALLOY_PR_HEAD_SHA"))
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"))
    parser.add_argument("--tolerance", type=float, default=0.02)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.repository or not args.head_sha or not args.token:
        raise SystemExit("G3 parity requires repository, exact PR head SHA, and GitHub token")
    if not 0 < args.tolerance <= 0.02:
        raise SystemExit("G3 estimate tolerance must be greater than zero and no greater than 2%")
    try:
        run = wait_for_reference_run(args.repository, args.head_sha, args.token)
        run_id = run.get("id")
        if not isinstance(run_id, int):
            raise RuntimeError("matching desktop reference run has no numeric ID")
        artifacts_url = f"https://api.github.com/repos/{args.repository}/actions/runs/{run_id}/artifacts?per_page=100"
        artifact_listing = github_get_json(artifacts_url, args.token)
        artifact = select_reference_artifact(artifact_listing.get("artifacts", []))
        artifact_bytes = download_artifact(artifact["archive_download_url"], args.token)
        digest = hashlib.sha256(artifact_bytes).hexdigest()
        expected_digest = str(artifact.get("digest", "")).removeprefix("sha256:")
        if expected_digest and digest != expected_digest:
            raise RuntimeError("downloaded desktop reference ZIP digest does not match GitHub metadata")
        with tempfile.TemporaryDirectory(prefix="alloy-g3-reference-") as temporary:
            reference_dir = Path(temporary) / "reference"
            safe_extract(artifact_bytes, reference_dir)
            print(f"Desktop reference run {run_id} ({args.head_sha}); artifact SHA-256 {digest}")
            summary = compare_fixtures(args.native_dir, reference_dir, args.report_dir, args.tolerance)
        return 0 if summary["result"] == "pass" else 2
    except (RuntimeError, OSError, KeyError, ValueError, json.JSONDecodeError) as error:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "error.txt").write_text(f"{error}\n", encoding="utf-8")
        print(f"G3 parity failed closed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
