#!/usr/bin/env python3
"""Identify the official OrcaSlicer revision closest to OrcaSlicer-Mobile's engine tree.

The script uses exact Git blob identities, not timestamps or version strings. It:
1. maps mobile app/src/main/jni/libslic3r/* to Orca src/libslic3r/*;
2. finds the official-history interval in which sampled files had the exact mobile blob;
3. scores official commits in that interval against the complete common tree;
4. writes machine-readable and Markdown evidence.

Full-tree scoring uses one `git ls-tree` per candidate commit rather than one
`git rev-parse` per file. This matters for Orca's large engine tree and keeps G2
bounded enough for hosted CI.

Exit 0 means a high-confidence engine baseline was found. Exit 2 means the evidence
is insufficient for G2 and the candidate remains unpinned.
"""

from __future__ import annotations

import json
from pathlib import Path
import statistics
import subprocess
import sys

SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}
SAMPLE_LIMIT = 72
MIN_EXACT_SAMPLE_INTERVALS = 12
MIN_COMMON_FILES = 100
MIN_EXACT_RATIO = 0.85
MAX_CANDIDATES_TO_SCORE = 400


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


def mobile_blob_map(mobile: Path, orca: Path) -> dict[str, str]:
    mobile_root = mobile / "app/src/main/jni/libslic3r"
    orca_root = orca / "src/libslic3r"
    out: dict[str, str] = {}
    for p in mobile_root.rglob("*"):
        if not p.is_file() or p.suffix.lower() not in SUFFIXES:
            continue
        rel = p.relative_to(mobile_root).as_posix()
        if (orca_root / rel).is_file():
            out[rel] = blob_hash(p)
    return dict(sorted(out.items()))


def choose_sample(paths: list[str], mobile: Path) -> list[str]:
    root = mobile / "app/src/main/jni/libslic3r"
    substantial = [p for p in paths if (root / p).stat().st_size >= 4096]
    source = substantial if len(substantial) >= SAMPLE_LIMIT else paths
    if len(source) <= SAMPLE_LIMIT:
        return source
    step = len(source) / SAMPLE_LIMIT
    return [source[min(int(i * step), len(source) - 1)] for i in range(SAMPLE_LIMIT)]


def exact_blob_interval(
    orca: Path, rel: str, wanted_blob: str
) -> tuple[int, int | None, str] | None:
    official_path = f"src/libslic3r/{rel}"
    commits = [
        c
        for c in git(orca, "log", "--format=%H", "--", official_path).splitlines()
        if c
    ]
    for i, commit in enumerate(commits):
        if commit_blob(orca, commit, official_path) != wanted_blob:
            continue
        start = commit_time(orca, commit)
        end = None if i == 0 else commit_time(orca, commits[i - 1]) - 1
        return start, end, commit
    return None


def rev_list_with_times(orca: Path) -> list[tuple[int, str]]:
    out: list[tuple[int, str]] = []
    for line in git(orca, "rev-list", "--timestamp", "main").splitlines():
        if not line:
            continue
        ts_s, sha = line.split(maxsplit=1)
        out.append((int(ts_s), sha))
    return out


def candidate_commits(
    history: list[tuple[int, str]], intervals: list[tuple[int, int | None, str]]
) -> list[tuple[int, str]]:
    lower = max(i[0] for i in intervals)
    finite_ends = [i[1] for i in intervals if i[1] is not None]
    upper = min(finite_ends) if finite_ends else 2**63 - 1

    matches = [(ts, sha) for ts, sha in history if lower <= ts <= upper]
    if matches:
        return matches

    # Android-specific edits may make exact per-file intervals fail to intersect.
    # Fall back to a bounded temporal window around the median exact introduction.
    center = int(statistics.median(i[0] for i in intervals))
    radius = 45 * 24 * 60 * 60
    return [(ts, sha) for ts, sha in history if abs(ts - center) <= radius]


def tree_blob_map(orca: Path, commit: str) -> dict[str, str]:
    """Return {libslic3r-relative-path: blob_sha} with one Git process."""
    prefix = "src/libslic3r/"
    text = git(orca, "ls-tree", "-r", commit, "--", "src/libslic3r")
    out: dict[str, str] = {}
    for line in text.splitlines():
        if not line or "\t" not in line:
            continue
        meta, path = line.split("\t", 1)
        parts = meta.split()
        if len(parts) < 3 or parts[1] != "blob" or not path.startswith(prefix):
            continue
        rel = path[len(prefix):]
        if Path(rel).suffix.lower() in SUFFIXES:
            out[rel] = parts[2]
    return out


def score_commit(
    orca: Path, commit: str, mobile_blobs: dict[str, str]
) -> tuple[int, int]:
    official = tree_blob_map(orca, commit)
    exact = sum(official.get(rel) == sha for rel, sha in mobile_blobs.items())
    return exact, len(mobile_blobs)


def spread_limit(candidates: list[tuple[int, str]], limit: int) -> list[tuple[int, str]]:
    if len(candidates) <= limit:
        return candidates
    # Preserve the ends of the candidate interval and spread samples through it.
    indices = {0, len(candidates) - 1}
    for i in range(limit):
        indices.add(min(int(i * len(candidates) / limit), len(candidates) - 1))
    return [candidates[i] for i in sorted(indices)]


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: identify_orca_engine.py <mobile-repo> <orca-repo> <out-dir>")

    mobile = Path(sys.argv[1]).resolve()
    orca = Path(sys.argv[2]).resolve()
    out = Path(sys.argv[3]).resolve()
    out.mkdir(parents=True, exist_ok=True)

    mobile_blobs = mobile_blob_map(mobile, orca)
    paths = list(mobile_blobs)
    sample = choose_sample(paths, mobile)

    intervals: list[tuple[int, int | None, str]] = []
    interval_rows = []
    for rel in sample:
        hit = exact_blob_interval(orca, rel, mobile_blobs[rel])
        if hit:
            intervals.append(hit)
            interval_rows.append({
                "path": rel,
                "introduced_at": hit[0],
                "valid_until": hit[1],
                "introducing_commit": hit[2],
            })

    history = rev_list_with_times(orca)
    candidates = candidate_commits(history, intervals) if intervals else []
    candidates = spread_limit(candidates, MAX_CANDIDATES_TO_SCORE)

    scored: list[tuple[int, int, str, int]] = []
    for ts, commit in candidates:
        exact, total = score_commit(orca, commit, mobile_blobs)
        scored.append((exact, total, commit, ts))
    scored.sort(reverse=True)

    best = scored[0] if scored else (0, len(paths), "", 0)
    exact, total, best_commit, best_time = best
    ratio = (exact / total) if total else 0.0

    diff_stat = ""
    if best_commit:
        temp = out / "orca-baseline"
        subprocess.run(
            ["git", "-C", str(orca), "worktree", "add", "--detach", str(temp), best_commit],
            check=True,
        )
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
        subprocess.run(
            ["git", "-C", str(orca), "worktree", "remove", "--force", str(temp)],
            check=False,
        )

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
        "candidate_commits_scored": len(candidates),
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
        f"- candidate commits scored: {len(candidates)}",
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

    print("\n".join(md[:13]))
    raise SystemExit(0 if high_confidence else 2)


if __name__ == "__main__":
    main()
