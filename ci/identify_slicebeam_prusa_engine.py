#!/usr/bin/env python3
"""Pin SliceBeam's libslic3r tree to the closest official PrusaSlicer revision.

Uses exact Git blob identities and source history. Android-specific edits are
expected, so this records both exact-match coverage and a diff for the best
candidate instead of trusting SliceBeam's application version string.
"""

from __future__ import annotations

import json
from pathlib import Path
import statistics
import subprocess
import sys

SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hh", ".hpp", ".hxx"}
SAMPLE_LIMIT = 72
MIN_INTERVALS = 12
MIN_COMMON = 100
MIN_RATIO = 0.85
MAX_CANDIDATES = 400


def git(repo: Path, *args: str, check: bool = True) -> str:
    p = subprocess.run(["git", "-C", str(repo), *args], text=True,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if check and p.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {p.stderr.strip()}")
    return p.stdout.strip()


def hash_file(path: Path) -> str:
    return subprocess.run(["git", "hash-object", str(path)], text=True,
                          stdout=subprocess.PIPE, check=True).stdout.strip()


def commit_time(repo: Path, sha: str) -> int:
    return int(git(repo, "show", "-s", "--format=%ct", sha))


def commit_blob(repo: Path, sha: str, path: str) -> str | None:
    p = subprocess.run(["git", "-C", str(repo), "rev-parse", f"{sha}:{path}"],
                       text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    return p.stdout.strip() if p.returncode == 0 else None


def map_blobs(slice_repo: Path, prusa_repo: Path) -> dict[str, str]:
    src = slice_repo / "app/src/main/jni/libslic3r"
    official = prusa_repo / "src/libslic3r"
    out = {}
    for path in src.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in SUFFIXES:
            continue
        rel = path.relative_to(src).as_posix()
        if (official / rel).is_file():
            out[rel] = hash_file(path)
    return dict(sorted(out.items()))


def sample_paths(paths: list[str], slice_repo: Path) -> list[str]:
    root = slice_repo / "app/src/main/jni/libslic3r"
    substantial = [p for p in paths if (root / p).stat().st_size >= 4096]
    src = substantial if len(substantial) >= SAMPLE_LIMIT else paths
    if len(src) <= SAMPLE_LIMIT:
        return src
    step = len(src) / SAMPLE_LIMIT
    return [src[min(int(i * step), len(src) - 1)] for i in range(SAMPLE_LIMIT)]


def interval(prusa: Path, rel: str, wanted: str):
    path = f"src/libslic3r/{rel}"
    commits = [x for x in git(prusa, "log", "--format=%H", "--", path).splitlines() if x]
    for i, sha in enumerate(commits):
        if commit_blob(prusa, sha, path) != wanted:
            continue
        start = commit_time(prusa, sha)
        end = None if i == 0 else commit_time(prusa, commits[i - 1]) - 1
        return start, end, sha
    return None


def history(prusa: Path) -> list[tuple[int, str]]:
    out = []
    for line in git(prusa, "rev-list", "--timestamp", "master").splitlines():
        ts, sha = line.split(maxsplit=1)
        out.append((int(ts), sha))
    return out


def candidates(hist, intervals):
    lo = max(x[0] for x in intervals)
    ends = [x[1] for x in intervals if x[1] is not None]
    hi = min(ends) if ends else 2**63 - 1
    found = [(ts, sha) for ts, sha in hist if lo <= ts <= hi]
    if found:
        return found
    center = int(statistics.median(x[0] for x in intervals))
    radius = 60 * 24 * 3600
    return [(ts, sha) for ts, sha in hist if abs(ts - center) <= radius]


def spread(items):
    if len(items) <= MAX_CANDIDATES:
        return items
    idx = {0, len(items) - 1}
    for i in range(MAX_CANDIDATES):
        idx.add(min(int(i * len(items) / MAX_CANDIDATES), len(items) - 1))
    return [items[i] for i in sorted(idx)]


def tree(prusa: Path, sha: str) -> dict[str, str]:
    prefix = "src/libslic3r/"
    out = {}
    for line in git(prusa, "ls-tree", "-r", sha, "--", "src/libslic3r").splitlines():
        if "\t" not in line:
            continue
        meta, path = line.split("\t", 1)
        parts = meta.split()
        if len(parts) >= 3 and parts[1] == "blob" and path.startswith(prefix):
            rel = path[len(prefix):]
            if Path(rel).suffix.lower() in SUFFIXES:
                out[rel] = parts[2]
    return out


def main():
    if len(sys.argv) != 4:
        raise SystemExit("usage: identify_slicebeam_prusa_engine.py <slicebeam> <prusa> <out>")
    sb, prusa, out = map(Path, sys.argv[1:])
    sb, prusa, out = sb.resolve(), prusa.resolve(), out.resolve()
    out.mkdir(parents=True, exist_ok=True)

    blobs = map_blobs(sb, prusa)
    paths = list(blobs)
    samples = sample_paths(paths, sb)
    ivals = []
    rows = []
    for rel in samples:
        hit = interval(prusa, rel, blobs[rel])
        if hit:
            ivals.append(hit)
            rows.append({"path": rel, "from": hit[0], "until": hit[1], "commit": hit[2]})

    cand = spread(candidates(history(prusa), ivals)) if ivals else []
    scored = []
    for ts, sha in cand:
        official = tree(prusa, sha)
        exact = sum(official.get(rel) == blob for rel, blob in blobs.items())
        scored.append((exact, len(blobs), sha, ts))
    scored.sort(reverse=True)
    best = scored[0] if scored else (0, len(blobs), "", 0)
    exact, total, best_sha, best_ts = best
    ratio = exact / total if total else 0.0
    high = len(ivals) >= MIN_INTERVALS and total >= MIN_COMMON and ratio >= MIN_RATIO and bool(best_sha)

    diff_stat = ""
    if best_sha:
        wt = out / "prusa-baseline"
        subprocess.run(["git", "-C", str(prusa), "worktree", "add", "--detach", str(wt), best_sha], check=True)
        p = subprocess.run(["git", "diff", "--no-index", "--stat", "--",
                            str(sb / "app/src/main/jni/libslic3r"), str(wt / "src/libslic3r")],
                           text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        diff_stat = p.stdout
        subprocess.run(["git", "-C", str(prusa), "worktree", "remove", "--force", str(wt)], check=False)

    data = {
        "slicebeam_commit": git(sb, "rev-parse", "HEAD"),
        "prusa_head": git(prusa, "rev-parse", "master"),
        "common_files": total,
        "sample_exact_intervals": len(ivals),
        "sample_size": len(samples),
        "candidates_scored": len(cand),
        "best_prusa_commit": best_sha,
        "best_prusa_commit_time": best_ts,
        "exact_files": exact,
        "exact_ratio": ratio,
        "high_confidence": high,
        "top_candidates": [
            {"commit": sha, "exact": e, "total": t, "ratio": e / t if t else 0, "time": ts}
            for e, t, sha, ts in scored[:10]
        ],
        "sample_intervals": rows,
    }
    (out / "path-b-g2.json").write_text(json.dumps(data, indent=2) + "\n")
    md = [
        "# Path B G2: SliceBeam → PrusaSlicer provenance",
        "",
        f"- SliceBeam: `{data['slicebeam_commit']}`",
        f"- PrusaSlicer HEAD examined: `{data['prusa_head']}`",
        f"- common mapped files: {total}",
        f"- sampled exact-history intervals: {len(ivals)} / {len(samples)}",
        f"- best matching PrusaSlicer commit: `{best_sha or 'NONE'}`",
        f"- exact common-tree matches: {exact}/{total} ({ratio:.2%})",
        f"- high-confidence provenance: **{'PASS CANDIDATE' if high else 'INSUFFICIENT / FAIL'}**",
        "", "## Top candidates", "", "| commit | exact | ratio |", "|---|---:|---:|",
    ]
    for e, t, sha, _ in scored[:10]:
        md.append(f"| `{sha}` | {e}/{t} | {(e/t if t else 0):.2%} |")
    md += ["", "## Closest-tree diff stat", "", "```text", diff_stat.rstrip(), "```", ""]
    (out / "PATH_B_G2.md").write_text("\n".join(md))
    print("\n".join(md[:14]))
    raise SystemExit(0 if high else 2)


if __name__ == "__main__":
    main()
