#!/usr/bin/env python3
"""Locate OrcaSlicer-Mobile's bundled BBL.ini snapshot in official Orca history."""

from pathlib import Path
import json
import subprocess
import sys


def run(*args: str) -> str:
    return subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout.strip()


def git(repo: Path, *args: str) -> str:
    return run("git", "-C", str(repo), *args)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit("usage: find_orca_profile_anchor.py <mobile> <orca> <out-dir>")

    mobile = Path(sys.argv[1]).resolve()
    orca = Path(sys.argv[2]).resolve()
    out = Path(sys.argv[3]).resolve()
    out.mkdir(parents=True, exist_ok=True)

    mobile_file = mobile / "app/src/main/assets/orca_profiles/BBL.ini"
    official_path = "resources/profiles/BBL.ini"
    mobile_blob = run("git", "hash-object", str(mobile_file))

    commits = [c for c in git(orca, "log", "--format=%H", "--", official_path).splitlines() if c]
    match = None
    for i, commit in enumerate(commits):
        p = subprocess.run(
            ["git", "-C", str(orca), "rev-parse", f"{commit}:{official_path}"],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
        )
        if p.returncode != 0 or p.stdout.strip() != mobile_blob:
            continue
        start = int(git(orca, "show", "-s", "--format=%ct", commit))
        next_change = commits[i - 1] if i > 0 else None
        end = int(git(orca, "show", "-s", "--format=%ct", next_change)) - 1 if next_change else None
        match = {
            "mobile_blob": mobile_blob,
            "introducing_commit": commit,
            "valid_from": start,
            "next_profile_change_commit": next_change,
            "valid_until": end,
        }
        break

    if match:
        candidates = []
        for line in git(orca, "rev-list", "--timestamp", "main").splitlines():
            ts_s, sha = line.split(maxsplit=1)
            ts = int(ts_s)
            if ts >= match["valid_from"] and (match["valid_until"] is None or ts <= match["valid_until"]):
                candidates.append(sha)
        match["candidate_commits_in_profile_interval"] = candidates
        match["candidate_count"] = len(candidates)
    else:
        match = {
            "mobile_blob": mobile_blob,
            "introducing_commit": None,
            "candidate_count": 0,
            "candidate_commits_in_profile_interval": [],
        }

    (out / "g2-bbl-profile-anchor.json").write_text(json.dumps(match, indent=2) + "\n")
    md = [
        "# BBL.ini provenance anchor",
        "",
        f"- mobile BBL.ini Git blob: `{mobile_blob}`",
        f"- exact official introduction: `{match.get('introducing_commit') or 'NOT FOUND'}`",
        f"- next official BBL.ini change: `{match.get('next_profile_change_commit') or 'NONE'}`",
        f"- official commits in exact-profile interval: {match.get('candidate_count', 0)}",
        "",
    ]
    if match.get("candidate_commits_in_profile_interval"):
        md += ["## Candidate commits", ""]
        md += [f"- `{c}`" for c in match["candidate_commits_in_profile_interval"][:100]]
    (out / "G2_BBL_PROFILE_ANCHOR.md").write_text("\n".join(md) + "\n")
    print("\n".join(md[:7]))

    raise SystemExit(0 if match.get("introducing_commit") else 2)


if __name__ == "__main__":
    main()
