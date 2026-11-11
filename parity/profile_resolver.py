#!/usr/bin/env python3
"""Deterministic Orca/SliceBeam-style INI profile inheritance resolver for G4.

This is a test oracle, not Alloy runtime code. It resolves profile sections by
name with explicit recursion, stable parent precedence, cycle detection and
missing-parent errors. It exists to compare the Android fork's resolved profile
output against a deterministic reference rather than duplicating its HashMap
iteration behavior.

Expected section form follows the mobile bundle convention:
  [printer:Child]
  inherits = Parent A;Parent B
  key = value

Parents are applied left-to-right; later parents override earlier parents, then
the child overrides all parents. Global metadata sections/keys are preserved but
are not part of inheritance resolution unless explicitly requested.
"""

from __future__ import annotations

import argparse
from collections import OrderedDict
import json
from pathlib import Path
import re
from typing import Iterable

SECTION_RE = re.compile(r"^\s*\[([^\]]+)\]\s*$")
KEY_RE = re.compile(r"^\s*([^#;][^=]*?)\s*=\s*(.*?)\s*$")
PROFILE_KINDS = {"printer", "print", "filament", "sla_print", "sla_material"}


class ProfileError(RuntimeError):
    pass


class MissingParentError(ProfileError):
    pass


class InheritanceCycleError(ProfileError):
    pass


def logical_lines(text: str) -> Iterable[str]:
    """Yield INI lines with simple backslash continuation support."""
    pending = ""
    for raw in text.splitlines():
        line = raw.rstrip("\r\n")
        if pending:
            line = pending + line.lstrip()
            pending = ""
        if line.endswith("\\") and not line.endswith("\\\\"):
            pending = line[:-1]
            continue
        yield line
    if pending:
        yield pending


def parse_ini(text: str) -> tuple[OrderedDict[str, OrderedDict[str, str]], OrderedDict[str, str]]:
    sections: OrderedDict[str, OrderedDict[str, str]] = OrderedDict()
    globals_: OrderedDict[str, str] = OrderedDict()
    current: OrderedDict[str, str] | None = None

    for lineno, raw in enumerate(logical_lines(text), 1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or stripped.startswith(";"):
            continue
        section = SECTION_RE.match(raw)
        if section:
            name = section.group(1).strip()
            if name in sections:
                raise ProfileError(f"duplicate section {name!r} at line {lineno}")
            current = OrderedDict()
            sections[name] = current
            continue
        kv = KEY_RE.match(raw)
        if kv:
            key, value = kv.group(1).strip(), kv.group(2).strip()
            target = globals_ if current is None else current
            target[key] = value
            continue
        raise ProfileError(f"unparsed line {lineno}: {raw!r}")

    return sections, globals_


def split_profile_name(section_name: str) -> tuple[str | None, str]:
    if ":" not in section_name:
        return None, section_name
    kind, name = section_name.split(":", 1)
    return (kind if kind in PROFILE_KINDS else None), name


def parent_candidates(child_section: str, parent_name: str) -> list[str]:
    """Resolve a parent first in the child's kind, then by exact section name.

    Mobile/SliceBeam bundle inheritance values normally contain the profile name
    without the `printer:`/`print:` prefix. Exact prefixed values are accepted for
    fixture clarity.
    """
    parent_name = parent_name.strip()
    if not parent_name:
        return []
    if ":" in parent_name:
        return [parent_name]
    kind, _ = split_profile_name(child_section)
    return [f"{kind}:{parent_name}" if kind else parent_name, parent_name]


def resolve_all(
    sections: OrderedDict[str, OrderedDict[str, str]],
) -> OrderedDict[str, OrderedDict[str, str]]:
    resolved: OrderedDict[str, OrderedDict[str, str]] = OrderedDict()
    visiting: list[str] = []

    def resolve(section_name: str) -> OrderedDict[str, str]:
        if section_name in resolved:
            return resolved[section_name]
        if section_name in visiting:
            cycle = " -> ".join(visiting + [section_name])
            raise InheritanceCycleError(f"inheritance cycle: {cycle}")
        if section_name not in sections:
            raise MissingParentError(f"missing section: {section_name}")

        visiting.append(section_name)
        own = sections[section_name]
        out: OrderedDict[str, str] = OrderedDict()
        raw_parents = own.get("inherits", "")
        if raw_parents:
            for raw_parent in raw_parents.split(";"):
                found = None
                for candidate in parent_candidates(section_name, raw_parent):
                    if candidate in sections:
                        found = candidate
                        break
                if found is None:
                    raise MissingParentError(
                        f"{section_name!r} inherits missing parent {raw_parent.strip()!r}"
                    )
                for key, value in resolve(found).items():
                    out[key] = value

        for key, value in own.items():
            if key != "inherits":
                out[key] = value

        visiting.pop()
        resolved[section_name] = out
        return out

    for section_name in sections:
        resolve(section_name)
    return resolved


def load_and_resolve(path: Path):
    sections, globals_ = parse_ini(path.read_text(errors="replace"))
    return globals_, resolve_all(sections)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("ini", type=Path)
    p.add_argument("--section", help="emit one resolved section only")
    p.add_argument("--json-out", type=Path)
    args = p.parse_args()

    try:
        globals_, resolved = load_and_resolve(args.ini)
        if args.section:
            if args.section not in resolved:
                raise MissingParentError(f"section not found: {args.section}")
            payload = {"section": args.section, "values": resolved[args.section]}
        else:
            payload = {"globals": globals_, "sections": resolved}
        rendered = json.dumps(payload, indent=2, ensure_ascii=False)
        if args.json_out:
            args.json_out.parent.mkdir(parents=True, exist_ok=True)
            args.json_out.write_text(rendered + "\n")
        print(rendered)
    except ProfileError as exc:
        print(json.dumps({"error": type(exc).__name__, "message": str(exc)}, indent=2))
        raise SystemExit(2)


if __name__ == "__main__":
    main()
