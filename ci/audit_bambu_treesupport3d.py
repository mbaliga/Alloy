#!/usr/bin/env python3
"""Audit the pinned Bambu TreeSupport3D source against Alloy's native tree.

This is deliberately an audit, not a source copier. Bambu Studio and Alloy
are both AGPL projects, but a safe Android port still needs an explicit
source/provenance record and a compatibility pass across the surrounding
libslic3r APIs. The command therefore reports the exact Bambu checkout, the
source hashes, the direct include surface, and same-path files that differ in
the current Alloy tree.

Usage:
    audit_bambu_treesupport3d.py <bambu-checkout> <alloy-root> <output-dir>

The pinned checkout is expected to be Bambu Studio v02.08.02.61. The Alloy
root is the repository root containing app/src/main/jni/libslic3r.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from typing import Iterable


PINNED_TAG = "v02.08.02.61"
PINNED_COMMIT = "926a7192574bcb9b3a732e1ec59a46d79cb45466"
SOURCE_FILES = (
    "Support/TreeSupport3D.cpp",
    "Support/TreeSupport3D.hpp",
    "Support/TreeSupport.cpp",
    "Support/TreeSupport.hpp",
    "Support/TreeSupportCommon.hpp",
    "Support/TreeModelVolumes.cpp",
    "Support/TreeModelVolumes.hpp",
)
INCLUDE_RE = re.compile(r'^\s*#include\s+"([^"]+)"\s*$', re.MULTILINE)


def git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", "-C", str(root), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if result.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {result.stderr.strip()}")
    return result.stdout.strip()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_root(checkout: Path) -> Path:
    root = checkout / "src/libslic3r"
    if not root.is_dir():
        raise RuntimeError(f"Bambu libslic3r source directory is missing: {root}")
    return root


def alloy_source_root(repository: Path) -> Path:
    root = repository / "app/src/main/jni/libslic3r"
    if not root.is_dir():
        raise RuntimeError(f"Alloy native source directory is missing: {root}")
    return root


def source_files(root: Path) -> list[Path]:
    result = []
    for relative in SOURCE_FILES:
        path = root / relative
        if not path.is_file():
            raise RuntimeError(f"required Bambu source is missing: {path}")
        result.append(path)
    return result


def include_names(paths: Iterable[Path]) -> list[str]:
    names = set()
    for path in paths:
        names.update(INCLUDE_RE.findall(path.read_text(encoding="utf-8", errors="replace")))
    return sorted(names)


def resolve_include(root: Path, including: Path, include: str) -> Path | None:
    """Resolve a quoted Bambu-style include within a libslic3r checkout."""
    candidates = (
        including.parent / include,
        root / include,
    )
    for candidate in candidates:
        candidate = candidate.resolve()
        if candidate.is_file() and candidate.is_relative_to(root.resolve()):
            return candidate
    return None


def relative_include_surface(root: Path, paths: Iterable[Path]) -> dict[str, str]:
    """Return direct quoted includes as repo-relative paths and SHA-256 values."""
    resolved_root = root.resolve()
    result: dict[str, str] = {}
    for path in paths:
        for include in INCLUDE_RE.findall(path.read_text(encoding="utf-8", errors="replace")):
            resolved = resolve_include(root, path, include)
            if resolved is None:
                continue
            try:
                relative = resolved.relative_to(resolved_root).as_posix()
            except ValueError:
                continue
            result[relative] = sha256(resolved)
    return dict(sorted(result.items()))


def transitive_include_surface(root: Path, entry_paths: Iterable[Path]) -> dict[str, str]:
    """Return every resolvable quoted include reachable from the support files."""
    resolved_root = root.resolve()
    entry_set = {path.resolve() for path in entry_paths}
    pending = list(entry_set)
    visited: set[Path] = set()
    result: dict[str, str] = {}
    while pending:
        including = pending.pop()
        if including in visited or not including.is_file():
            continue
        visited.add(including)
        for include in INCLUDE_RE.findall(including.read_text(encoding="utf-8", errors="replace")):
            resolved = resolve_include(root, including, include)
            if resolved is None or resolved in visited:
                continue
            pending.append(resolved)
            if resolved in entry_set:
                continue
            try:
                relative = resolved.relative_to(resolved_root).as_posix()
            except ValueError:
                continue
            result[relative] = sha256(resolved)
    return dict(sorted(result.items()))


def unresolved_quoted_includes(root: Path, entry_paths: Iterable[Path]) -> dict[str, list[str]]:
    """Return quoted includes that cannot be resolved inside the Bambu tree."""
    resolved_root = root.resolve()
    pending = [path.resolve() for path in entry_paths]
    visited: set[Path] = set()
    unresolved: dict[str, set[str]] = {}
    while pending:
        including = pending.pop()
        if including in visited or not including.is_file():
            continue
        visited.add(including)
        missing: set[str] = set()
        for include in INCLUDE_RE.findall(including.read_text(encoding="utf-8", errors="replace")):
            resolved = resolve_include(root, including, include)
            if resolved is None:
                missing.add(include)
            elif resolved not in visited:
                pending.append(resolved)
        if missing:
            unresolved[including.relative_to(resolved_root).as_posix()] = missing
    return {path: sorted(values) for path, values in sorted(unresolved.items())}


def audit_sources(bambu_root: Path, alloy_root: Path) -> dict:
    """Build a deterministic report without requiring either directory to be Git."""
    bambu_files = source_files(bambu_root)
    alloy_files = {path.relative_to(alloy_root).as_posix(): path for path in alloy_root.rglob("*") if path.is_file()}
    required_source_mismatches = sorted(
        relative for relative in SOURCE_FILES
        if relative not in alloy_files or sha256(alloy_files[relative]) != sha256(bambu_root / relative)
    )
    surface = relative_include_surface(bambu_root, bambu_files)
    transitive_surface = transitive_include_surface(bambu_root, bambu_files)
    unresolved = unresolved_quoted_includes(bambu_root, bambu_files)
    missing_surface = sorted(path for path in surface if path not in alloy_files)
    differing_surface = sorted(
        path for path, digest in surface.items()
        if path in alloy_files and sha256(alloy_files[path]) != digest
    )
    missing_transitive_surface = sorted(path for path in transitive_surface if path not in alloy_files)
    differing_transitive_surface = sorted(
        path for path, digest in transitive_surface.items()
        if path in alloy_files and sha256(alloy_files[path]) != digest
    )
    current_support = sorted(
        path.relative_to(alloy_root).as_posix()
        for path in alloy_root.glob("Support/TreeSupport3D.*")
        if path.is_file()
    )
    bambu_text = (bambu_root / "Support/TreeSupport.cpp").read_text(encoding="utf-8", errors="replace")
    alloy_tree = alloy_root / "Support/TreeSupport.cpp"
    alloy_text = alloy_tree.read_text(encoding="utf-8", errors="replace") if alloy_tree.is_file() else ""
    exact_route_markers = {
        "bambu_routes_tree_organic_to_3d": "generate_tree_support_3D" in bambu_text
        and "TreeSupport3D" in bambu_text,
        "alloy_has_tree_support3d_source": bool(current_support),
        "alloy_routes_tree_organic_to_3d": "generate_tree_support_3D" in alloy_text
        and "TreeSupport3D" in alloy_text,
    }
    return {
        "bambu_tag": PINNED_TAG,
        "bambu_commit_expected": PINNED_COMMIT,
        "required_sources": {
            relative: {
                "sha256": sha256(bambu_root / relative),
                "lines": len((bambu_root / relative).read_text(encoding="utf-8", errors="replace").splitlines()),
            }
            for relative in SOURCE_FILES
        },
        "required_source_mismatches_in_alloy": required_source_mismatches,
        "direct_include_surface": surface,
        "missing_direct_includes_in_alloy": missing_surface,
        "differing_direct_includes_in_alloy": differing_surface,
        "transitive_include_surface": transitive_surface,
        "unresolved_quoted_includes": unresolved,
        "missing_transitive_includes_in_alloy": missing_transitive_surface,
        "differing_transitive_includes_in_alloy": differing_transitive_surface,
        "alloy_tree_support3d_files": current_support,
        "route_markers": exact_route_markers,
        # An existing TreeSupport3D route is not evidence of a Bambu port:
        # Alloy currently routes Orca's implementation through the same name.
        # Exact pinned source hashes are required before the route can promote.
        "status": "PORT_READY" if exact_route_markers["alloy_routes_tree_organic_to_3d"]
        and not required_source_mismatches else "PORT_REQUIRED",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("bambu_checkout", type=Path)
    parser.add_argument("alloy_root", type=Path)
    parser.add_argument("output_dir", type=Path)
    args = parser.parse_args()

    bambu_checkout = args.bambu_checkout.resolve()
    alloy_repository = args.alloy_root.resolve()
    output_dir = args.output_dir.resolve()
    actual_commit = git(bambu_checkout, "rev-parse", "HEAD")
    tag_commit = git(bambu_checkout, "rev-parse", f"{PINNED_TAG}^{{commit}}")
    if actual_commit != PINNED_COMMIT or tag_commit != PINNED_COMMIT:
        raise SystemExit(
            f"Bambu checkout is not pinned to {PINNED_TAG}/{PINNED_COMMIT}: "
            f"HEAD={actual_commit}, tag={tag_commit}"
        )

    report = audit_sources(source_root(bambu_checkout), alloy_source_root(alloy_repository))
    report["bambu_checkout_commit"] = actual_commit
    report["alloy_repository"] = str(alloy_repository)
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "bambu-treesupport3d-audit.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8"
    )

    markdown = [
        "# Bambu TreeSupport3D port audit",
        "",
        f"- Bambu tag: `{report['bambu_tag']}`",
        f"- Bambu commit: `{report['bambu_checkout_commit']}`",
        f"- route status: **{report['status']}**",
        f"- differing required Bambu source files in Alloy: {len(report['required_source_mismatches_in_alloy'])}",
        f"- direct include surface: {len(report['direct_include_surface'])} files",
        f"- missing direct includes in Alloy: {len(report['missing_direct_includes_in_alloy'])}",
        f"- differing direct includes in Alloy: {len(report['differing_direct_includes_in_alloy'])}",
        f"- transitive include surface: {len(report['transitive_include_surface'])} files",
        f"- unresolved quoted includes: {sum(len(values) for values in report['unresolved_quoted_includes'].values())}",
        f"- missing transitive includes in Alloy: {len(report['missing_transitive_includes_in_alloy'])}",
        f"- differing transitive includes in Alloy: {len(report['differing_transitive_includes_in_alloy'])}",
        f"- Alloy TreeSupport3D files present: {', '.join(report['alloy_tree_support3d_files']) or 'none'}",
        "",
        "This report identifies the source and compatibility surface; it does not "
        "claim that a legacy Prusa organic/tree implementation is equivalent to Bambu's "
        "TreeSupport3D.",
        "",
        "## Missing direct include files",
        "",
    ]
    markdown.extend(f"- `{path}`" for path in report["missing_direct_includes_in_alloy"])
    markdown.extend(["", "## Differing required Bambu source files", ""])
    markdown.extend(f"- `{path}`" for path in report["required_source_mismatches_in_alloy"])
    markdown.extend(["", "## Differing same-path files", ""])
    markdown.extend(f"- `{path}`" for path in report["differing_direct_includes_in_alloy"])
    markdown.extend(["", "## Missing transitive include files", ""])
    markdown.extend(f"- `{path}`" for path in report["missing_transitive_includes_in_alloy"])
    markdown.extend(["", "## Unresolved quoted includes in pinned Bambu source", ""])
    for path, includes in report["unresolved_quoted_includes"].items():
        markdown.append(f"- `{path}`: {', '.join(f'`{include}`' for include in includes)}")
    markdown.extend(["", "## Differing transitive same-path files", ""])
    markdown.extend(f"- `{path}`" for path in report["differing_transitive_includes_in_alloy"])
    (output_dir / "BAMBU_TREESUPPORT3D_AUDIT.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    print("\n".join(markdown[:12]))
    raise SystemExit(0 if report["status"] == "PORT_READY" else 2)


if __name__ == "__main__":
    main()
