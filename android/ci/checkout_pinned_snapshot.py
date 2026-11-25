#!/usr/bin/env python3
"""Fetch and verify a pinned Git source snapshot with bounded retries."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Callable


def checkout_snapshot(
    repository: str,
    revision: str,
    destination: Path,
    *,
    attempts: int = 3,
    retry_delay: float = 5.0,
    runner: Callable[..., subprocess.CompletedProcess] = subprocess.run,
    sleep: Callable[[float], None] = time.sleep,
) -> None:
    if attempts < 1:
        raise ValueError("attempts must be at least one")
    if not revision or any(char.isspace() for char in revision):
        raise ValueError("revision must be a non-empty Git ref")
    if destination.exists():
        raise FileExistsError(f"destination already exists: {destination}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    errors: list[str] = []
    for attempt in range(1, attempts + 1):
        try:
            with tempfile.TemporaryDirectory(prefix=f".{destination.name}-fetch-", dir=destination.parent) as temp:
                checkout = Path(temp) / "checkout"
                clone = runner(
                    ["git", "clone", "--filter=blob:none", "--no-checkout", repository, str(checkout)],
                    check=False, capture_output=True, text=True,
                )
                if clone.returncode != 0:
                    raise RuntimeError(f"clone failed: {(clone.stderr or clone.stdout).strip()}")
                fetch = runner(
                    ["git", "-C", str(checkout), "-c", "http.lowSpeedLimit=1000", "checkout", "--detach", revision],
                    check=False, capture_output=True, text=True,
                )
                if fetch.returncode != 0:
                    raise RuntimeError(f"checkout failed: {(fetch.stderr or fetch.stdout).strip()}")
                resolved = runner(
                    ["git", "-C", str(checkout), "rev-parse", "HEAD"],
                    check=False, capture_output=True, text=True,
                )
                if resolved.returncode != 0 or resolved.stdout.strip() != revision:
                    raise RuntimeError(
                        f"resolved revision mismatch: expected {revision}, got {resolved.stdout.strip()}"
                    )
                shutil.move(str(checkout), str(destination))
                return
        except (OSError, RuntimeError) as error:
            errors.append(f"attempt {attempt}/{attempts}: {error}")
            if attempt < attempts:
                sleep(retry_delay * attempt)
    raise RuntimeError("pinned snapshot checkout exhausted retries:\n" + "\n".join(errors))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository")
    parser.add_argument("revision")
    parser.add_argument("destination", type=Path)
    parser.add_argument("--attempts", type=int, default=3)
    parser.add_argument("--retry-delay", type=float, default=5.0)
    args = parser.parse_args()
    checkout_snapshot(args.repository, args.revision, args.destination,
                      attempts=args.attempts, retry_delay=args.retry_delay)
    print(f"Verified {args.repository} at {args.revision} -> {args.destination}")


if __name__ == "__main__":
    main()
