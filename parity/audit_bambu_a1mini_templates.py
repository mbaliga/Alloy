#!/usr/bin/env python3
"""Audit Bambu A1 Mini machine G-code templates before any runtime adapter work.

The resolved Orca/Bambu profile is useful compatibility evidence, but its
start/end templates contain firmware macros and placeholder expressions that
SliceBeam cannot safely execute unchanged. This tool makes that boundary
deterministic: it records exact template hashes and reports every unresolved
placeholder, conditional block, and Bambu-specific command.

Exit status is 0 only for a template that is ready for a reviewed adapter.
The current upstream A1 Mini templates are expected to return status 2 and are
therefore suitable as review evidence, not as Android runtime input.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
from typing import Iterable


TEMPLATE_KEYS = {
    "start_gcode": "machine_start_gcode",
    "end_gcode": "machine_end_gcode",
}

# These are identifiers available to the pinned Prusa/SliceBeam placeholder
# path or safe scalar settings projected by Alloy. Indexed Bambu arrays are
# deliberately not accepted by this audit: they require a multi-extruder
# resolver and are not valid in Alloy's single-nozzle A1 Mini contract.
BUILTIN_PLACEHOLDERS = {
    "layer_num",
    "layer_z",
    "max_layer_z",
    "filament_extruder_id",
    "initial_extruder",
    "nozzle_diameter",
    "temperature",
    "first_layer_temperature",
    "bed_temperature",
    "first_layer_bed_temperature",
    "filament_type",
}

TEMPLATE_ALIASES = {
    "nozzle_temperature_initial_layer": "first_layer_temperature",
    "bed_temperature_initial_layer_single": "first_layer_bed_temperature",
}

# Firmware macros that are not ordinary Marlin/Prusa commands and must not be
# copied into a generic SliceBeam Android send path without a real A1 Mini
# transcript and a firmware-specific allowlist.
BAMBU_MACROS = {
    "M1002", "M1004", "M1006", "M1007", "M620", "M620.1", "M620.3",
    "M620.10", "M620.11", "M621", "M629", "M630", "M631", "M960",
    "M970", "M970.3", "M971", "M974", "M975", "M982.2", "M983",
    "M983.2", "M9833", "M984", "M991", "M211", "M412", "M622",
}

BRACKET_TOKEN = re.compile(
    r"\[([A-Za-z_][A-Za-z0-9_.]*(?:\[[^\]\r\n]+\])?)\]"
)
BRACE_TOKEN = re.compile(r"\{([^}\r\n]+)\}")
IDENTIFIER = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
COMMAND = re.compile(r"^\s*(?:N\s*\d+\s+)?([GMT]\s*\d+(?:\.\d+)?)\b", re.IGNORECASE)


def translated_key(value: str) -> str:
    return TEMPLATE_ALIASES.get(value, value)


def load_supported_keys(slice_repo: Path | None) -> set[str]:
    keys = set(BUILTIN_PLACEHOLDERS)
    if slice_repo is None:
        return keys
    cpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.cpp").read_text(errors="replace")
    hpp = (slice_repo / "app/src/main/jni/libslic3r/PrintConfig.hpp").read_text(errors="replace")
    keys.update(re.findall(r'\badd\s*\(\s*"([^"]+)"', cpp))
    keys.update(re.findall(
        r"\(\(\s*ConfigOption[A-Za-z0-9_<>:]*\s*,\s*([A-Za-z0-9_]+)\s*\)\)", hpp
    ))
    if len(keys) < 500:
        raise RuntimeError(f"unexpectedly few SliceBeam config keys: {len(keys)}")
    return keys


def non_comment_lines(template: str) -> Iterable[tuple[int, str]]:
    for line_number, raw in enumerate(template.splitlines(), 1):
        command = raw.split(";", 1)[0]
        if command.strip():
            yield line_number, command


def placeholder_tokens(template: str) -> tuple[list[str], list[str]]:
    bracket = sorted(set(match.group(1) for match in BRACKET_TOKEN.finditer(template)))
    brace = sorted(set(match.group(1).strip() for match in BRACE_TOKEN.finditer(template)))
    return bracket, brace


def token_roots(token: str) -> tuple[str, list[str]]:
    root = token.split("[", 1)[0]
    indexes = re.findall(r"\[([^\]]+)\]", token)
    return translated_key(root), indexes


def unsupported_placeholders(template: str, supported: set[str]) -> list[dict[str, object]]:
    bracket, brace = placeholder_tokens(template)
    failures: list[dict[str, object]] = []
    for token in bracket:
        root, indexes = token_roots(token)
        if root not in supported or any(not index.strip().isdigit() for index in indexes):
            failures.append({"syntax": "bracket", "token": f"[{token}]", "root": root})
    for expression in brace:
        if re.match(r"^(?:if|else|endif)\b", expression, re.IGNORECASE):
            continue
        names = [translated_key(name) for name in IDENTIFIER.findall(expression)]
        unknown = sorted({name for name in names if name not in supported})
        if unknown:
            failures.append({
                "syntax": "expression",
                "token": "{" + expression + "}",
                "unknown_names": unknown,
            })
    return failures


def bambu_commands(template: str) -> list[dict[str, object]]:
    found: list[dict[str, object]] = []
    for line_number, line in non_comment_lines(template):
        match = COMMAND.match(line)
        if not match:
            continue
        code = match.group(1).replace(" ", "").upper()
        if code in BAMBU_MACROS:
            found.append({"line": line_number, "command": code})
    return found


def conditional_blocks(template: str) -> dict[str, object]:
    markers = []
    depth = 0
    unbalanced = False
    for line_number, raw in enumerate(template.splitlines(), 1):
        for match in re.finditer(r"\{\s*(if|else|endif)\b[^}]*\}", raw, re.IGNORECASE):
            marker = match.group(1).lower()
            markers.append({"line": line_number, "marker": marker})
            if marker == "if":
                depth += 1
            elif marker == "endif":
                depth -= 1
                if depth < 0:
                    unbalanced = True
                    depth = 0
    return {"count": len(markers), "markers": markers[:64], "unbalanced": unbalanced or depth != 0}


def audit_template(name: str, template: str, supported: set[str]) -> dict[str, object]:
    if not isinstance(template, str):
        template = ""
    bracket, brace = placeholder_tokens(template)
    unsupported = unsupported_placeholders(template, supported)
    conditionals = conditional_blocks(template)
    macros = bambu_commands(template)
    failures: list[str] = []
    if not template.strip():
        failures.append("template is empty")
    if unsupported:
        failures.append(f"{len(unsupported)} unsupported placeholder expression(s)")
    if conditionals["count"]:
        failures.append(f"{conditionals['count']} conditional template marker(s) require a resolver")
    if conditionals["unbalanced"]:
        failures.append("conditional template markers are unbalanced")
    if macros:
        failures.append(f"{len(macros)} Bambu firmware macro occurrence(s) require a firmware adapter")
    return {
        "name": name,
        "sha256": hashlib.sha256(template.encode("utf-8")).hexdigest(),
        "byte_length": len(template.encode("utf-8")),
        "line_count": len(template.splitlines()),
        "bracket_tokens": bracket,
        "brace_tokens": brace,
        "unsupported_placeholders": unsupported,
        "conditional_blocks": conditionals,
        "bambu_macros": macros,
        "failures": failures,
        "status": "safe-adapter-ready" if not failures else "review-only",
    }


def audit_profile(profile_path: Path, slice_repo: Path | None = None) -> dict[str, object]:
    profile = json.loads(profile_path.read_text(encoding="utf-8"))
    supported = load_supported_keys(slice_repo)
    templates = {}
    for output_key, source_key in TEMPLATE_KEYS.items():
        value = profile.get(source_key, profile.get(output_key, ""))
        templates[output_key] = audit_template(source_key, value, supported)
    failures = [name for name, report in templates.items() if report["status"] != "safe-adapter-ready"]
    return {
        "schema_version": 1,
        "profile": {"path": str(profile_path), "name": profile.get("name", "")},
        "slice_repo": str(slice_repo) if slice_repo else None,
        "supported_placeholder_key_count": len(supported),
        "templates": templates,
        "status": "safe-adapter-ready" if not failures else "review-only",
        "failed_templates": failures,
        "policy": "Exact upstream Bambu templates are compatibility evidence only; unresolved templates are never runtime input.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("profile_json", type=Path)
    parser.add_argument("--slice-repo", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    report = audit_profile(args.profile_json, args.slice_repo)
    rendered = json.dumps(report, indent=2, ensure_ascii=False) + "\n"
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(rendered, encoding="utf-8")
    print(f"A1 Mini template policy: {report['status']}")
    for name, template in report["templates"].items():
        print(f"{name}: {template['status']} · sha256={template['sha256']} · {template['byte_length']} bytes")
        for failure in template["failures"]:
            print(f"  - {failure}")
    return 0 if report["status"] == "safe-adapter-ready" else 2


if __name__ == "__main__":
    raise SystemExit(main())
