#!/usr/bin/env python3
"""Identify the official OrcaSlicer revision closest to OrcaSlicer-Mobile's engine tree.

The script uses exact Git blob identities, not timestamps or version strings. It:
1. maps mobile app/src/main/jni/libslic3r/* to Orca src/libslic3r/*;
2. finds the official-history interval in which sampled files had the exact mobile blob;
3. scores official commits in the intersected interval against the complete common tree;
4. writes machine-readable and Markdown evidence.

Exit 0 means a high-confidence engine baseline was found. Exit 2 means the evidence
is insufficient for G2 and the candidate remains unpinned.
"""

from __future__ import annotations

import json
from pathlib import Path
import statistics
import subprocess
import sys
from typing import Iterable

SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}
SAMPLE_LIMIT = 72
MIN_EXACT_SAMPLE_INTERVALS = 12
MIN_COMMON_FILES = 100
MIN_EXACT_RATIO = 0.85


def git(repo: Path, *args: str, check: bool = True) -> str:
    p = subprocess.run(
        ["git", "-C", str(repo), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if check and p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)} failed: {p.stderr.strip()}")
    return p.stdout.strip()


def blob_hash(path: Path) -> str:
    p = subprocess.run(
        ["git", "hash-object", str(path)],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return p.stdout.strip()


def commit_blob(repo: Path, commit: str, rel: str) -> str | None:
    p = subprocess.run(
        ["git", "-C", str(repo), "rev-parse", f"{commit}:{rel}"],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    return p.stdout.strip() if p.returncode == 0 else None


def commit_time(repo: Path, commit: str) -> int:
    return int(git(repo, "show", "-s", "--format=%ct", commit))


def mapped_files(mobile: Path, orca: Path) -> list[str]:
    mobile_root = mobile / "app/src/main/jni/libslic3r"
    orca_root = orca / "src/libslic3r"
    out: list[str] = []
    for p in mobile_root.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in SUFFIXES:
            continue
        rel = p.relative_to(mobile_root).as_posix()
        if (orca_root / rel).is_file():
            out.append(rel)
    return sorted(out)


def choose_sample(paths: list[str], mobile: Path) -> list[str]:
    root = mobile / "app/src/main/jni/libslic3r"
    # Prefer substantive files, then spread deterministically across the sorted list.
    substantial = [p for p in paths if (root / p).stat().st_size >= 4096]
    source = substantial if len(substantial) >= SAMPLE_LIMIT else paths
    if len(source) <= SAMPLE_LIMIT:
        return source
    step = len(source) / SAMPLE_LIMIT
    return [source[min(int(i * step), len(source) - 1)] for i in range(SAMPLE_LIMIT)]


def exact_blob_interval(
    mobile: Path, orca: Path, rel: str
) -> tuple[int, int | None, str] | None:
    mobile_blob = blob_hash(mobile / "app/src/main/jni/libslic3r" / rel)
    official_path = f"src/libslic3r/{rel}"
    commits = [
        c
        for c in git(orca, "log", "--format=%H", "--", official_path).splitlines()
        if c
    ]
    for i, commit in enumerate(commits):
        if commit_blob(orca, commit, official_path) != mobile_blob:
            continue
        start = commit_time(orca, commit)
        end = None if i == 0 else commit_time(orca, commits[i - 1]) - 1
        return start, end, commit
    return None


def candidate_commits(orca: Path, intervals: list[tuple[int, int | None, str]]) -> list[str]:
    lower = max(i[0] for i in intervals)
    finite_ends = [i[1] for i in intervals if i[1] is not None]
    upper = min(finite_ends) if finite_ends else 2**63 - 1

    all_commits = []
    for line in git(orca, "rev-list", "--timestamp", "main").splitlines():
        if not line:
            continue
        ts_s, sha = line.split(maxsplit=1)
        ts = int(ts_s)
        if lower <= ts <= upper:
            all_commits.append(sha)

    if all_commits:
        return all_commits

    # If Android-specific edits mean exact intervals do not intersect, use a bounded
    # fallback window around the median introduction time and score candidates there.
    center = int(statistics.median(i[0] for i in intervals))
    radius = 45 * 24 * 60 * 60
    for line in git(orca, "rev-list", "--timestamp", "main").splitlines():
        ts_s, sha = line.split(maxsplit=1)
        if abs(int(ts_s) - center) <= radius:
            all_commits.append(sha)
    return all_commits


def score_commit(mobile: Path, orca: Path, commit: str, paths: Iterable[str]) -> tuple[int, int]:
    exact = 0
    total = 0
    mobile_root = mobile / "app/src/main/jni/libslic3r"
    for rel in paths:
        total += 1
        official_blob = commit_blob(orca, commit, f"src/libslic3r/{rel}")
        if official_blob and official_blob == blob_hash(mobile_root / rel):
            exact += 1
    return exact, total


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: identify_orca_engine.py <mobile-repo> <orca-repo> <out-dir>")

    mobile = Path(sys.argv[1]).resolve()
    orca = Path(sys.argv[2]).resolve()
    out = Path(sys.argv[3]).resolve()
    out.mkdir(parents=True, exist_ok=True)

    paths = mapped_files(mobile, orca)
    sample = choose_sample(paths, mobile)

    intervals = []
    interval_rows = []
    for rel in sample:
        hit = exact_blob_interval(mobile, orca, rel)
        if hit:
            intervals.append(hit)
            interval_rows.append({
                "path": rel,
                "introduced_at": hit[0],
                "valid_until": hit[1],
                "introducing_commit": hit[2],
            })

    candidates = candidate_commits(orca, intervals) if intervals else []

    # Bound scoring work in pathological broad windows while retaining temporal spread.
    if len(candidates) > 400:
        stride = max(1, len(candidates) // 350)
        candidates = candidates[::stride]

    scored = []
    for commit in candidates:
        exact, total = score_commit(mobile, orca, commit, paths)
        scored.append((exact, total, commit, commit_time(orca, commit)))
    scored.sort(reverse=True)

    best = scored[0] if scored else (0, len(paths), "", 0)
    exact, total, best_commit, best_time = best
    ratio = (exact / total) if total else 0.0

    # Capture the closest full-tree diff stat without mutating the official checkout.
    diff_stat = ""
    if best_commit:
        temp = out / "orca-baseline"
        subprocess.run(["git", "-C", str(orca), "worktree", "add", "--detach", str(temp), best_commit], check=True)
        p = subprocess.run(
            [
                "git", "diff", "--no-index", "--stat", "--",
                str(mobile / "app/src/main/jni/libslic3r"),
                str(temp / "src/libslic3r"),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        diff_stat = p.stdout
        subprocess.run(["git", "-C", str(orca), "worktree", "remove", "--force", str(temp)], check=False)

    high_confidence = (
        len(intervals) >= MIN_EXACT_SAMPLE_INTERVALS
        and total >= MIN_COMMON_FILES
        and ratio >= MIN_EXACT_RATIO
        and bool(best_commit)
    )

    data = {
        "mobile_commit": git(mobile, "rev-parse", "HEAD"),
        "orca_head": git(orca, "rev-parse", "main"),
        "common_engine_files": len(paths),
        "sample_size": len(sample),
        "sample_files_with_exact_history_interval": len(intervals),
        "best_orca_commit": best_commit,
        "best_orca_commit_time": best_time,
        "exact_common_files": exact,
        "exact_ratio": ratio,
        "high_confidence": high_confidence,
        "top_candidates": [
            {"commit": c, "exact": e, "total": t, "ratio": (e / t if t else 0), "time": ts}
            for e, t, c, ts in scored[:10]
        ],
        "sample_intervals": interval_rows,
    }
    (out / "g2-provenance.json").write_text(json.dumps(data, indent=2) + "\n")

    md = [
        "# G2 Orca engine provenance evidence",
        "",
        f"- OrcaSlicer-Mobile commit: `{data['mobile_commit']}`",
        f"- official OrcaSlicer HEAD examined: `{data['orca_head']}`",
        f"- common mapped engine files: {len(paths)}",
        f"- sampled files with exact-history intervals: {len(intervals)} / {len(sample)}",
        f"- best matching official commit: `{best_commit or 'NONE'}`",
        f"- exact full common-tree matches: {exact} / {total} ({ratio:.2%})",
        f"- G2 high-confidence result: **{'PASS CANDIDATE' if high_confidence else 'INSUFFICIENT / FAIL'}**",
        "",
        "## Top candidates",
        "",
        "| commit | exact files | ratio |",
        "|---|---:|---:|",
    ]
    for e, t, c, _ in scored[:10]:
        md.append(f"| `{c}` | {e}/{t} | {(e/t if t else 0):.2%} |")
    md += ["", "## Closest-tree diff stat", "", "```text", diff_stat.rstrip(), "```", ""]
    (out / "G2_PROVENANCE.md").write_text("\n".join(md))

    print("\n".join(md[:12]))
    raise SystemExit(0 if high_confidence else 2)


if __name__ == "__main__":
    main()
