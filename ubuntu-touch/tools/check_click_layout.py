#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Structural check of an installed click staging directory (the output of `cmake --install`).

Catches the mistakes that make a click fail review or install but never fail a compile:
bad JSON, hook names not matching the desktop file, missing Exec binary, wrong framework,
and (the privacy promise) any networking policy group in the AppArmor profile.
Usage: check_click_layout.py <staging-dir> [--arch amd64|arm64|armhf]
"""
import configparser, json, os, re, sys

def main(argv):
    if not argv:
        print(__doc__); return 2
    root, arch = argv[0], None
    if "--arch" in argv: arch = argv[argv.index("--arch") + 1]
    errs = []
    def need(path):
        full = os.path.join(root, path)
        if not os.path.isfile(full): errs.append(f"missing file: {path}")
        return full
    manifest = json.load(open(need("manifest.json")))
    for key in ("name", "version", "framework", "architecture", "maintainer", "title", "description", "hooks"):
        if not manifest.get(key): errs.append(f"manifest.json: empty or missing '{key}'")
    if manifest.get("framework") != "ubuntu-sdk-20.04": errs.append("manifest.json: framework must be ubuntu-sdk-20.04")
    if arch and manifest.get("architecture") != arch: errs.append(f"manifest.json: architecture {manifest.get('architecture')!r} != {arch!r}")
    if re.search(r"@[A-Z_]+@", json.dumps(manifest)): errs.append("manifest.json: unsubstituted @VARIABLE@")
    hooks = manifest.get("hooks", {})
    if len(hooks) != 1: errs.append("expected exactly one app hook")
    for app, h in hooks.items():
        desk = configparser.ConfigParser(interpolation=None)
        desk.read(need(h.get("desktop", "")))
        entry = desk["Desktop Entry"] if desk.has_section("Desktop Entry") else {}
        exe = entry.get("Exec", "").split()[0] if entry.get("Exec") else ""
        if exe != app: errs.append(f"desktop Exec={exe!r} must equal the hook name {app!r}")
        if not os.access(os.path.join(root, exe), os.X_OK): errs.append(f"Exec binary {exe!r} missing or not executable")
        if not os.path.isfile(os.path.join(root, entry.get("Icon", "-"))): errs.append("desktop Icon file missing")
        prof = json.load(open(need(h.get("apparmor", ""))))
        groups = prof.get("policy_groups", [])
        if "networking" in groups: errs.append("apparmor: 'networking' policy group is forbidden (no-network promise)")
        if prof.get("template") == "unconfined": errs.append("apparmor: unconfined template is forbidden")
        if str(prof.get("policy_version")) not in ("16.04", "20.04"): errs.append("apparmor: unexpected policy_version")
        print(f"app {app}: policy_groups={groups}")
    if errs:
        print("\n".join("FAIL " + e for e in errs)); return 1
    print(f"click layout OK: {manifest['name']} {manifest['version']} {manifest['architecture']}")
    return 0

if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
