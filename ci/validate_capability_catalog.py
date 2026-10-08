#!/usr/bin/env python3
"""Fail closed on malformed or unproven initial printer/material capability data."""

import datetime as dt
import json
import sys
from pathlib import Path
from urllib.parse import urlparse


CATALOG = Path("app/src/main/assets/capabilities/bambu-initial-v1.json")
EXPECTED_PRINTERS = {"a1-mini", "a1", "p1s"}
MAX_BYTES = 128 * 1024
PRINTER_FIELDS = {
    "id", "name", "build_volume", "nozzle", "bed", "reviewed",
    "alloy_direct_send", "source", "source_url", "materials", "feed_routes",
    "spool_forms", "spool_guidance",
}
MATERIAL_FIELDS = {"id", "name", "bambu_status", "alloy_direct_send", "note", "source_url"}
ROUTE_FIELDS = {"id", "title", "state", "note"}
SPOOL_FIELDS = {"id", "title", "form", "compatible_routes", "geometry", "state", "note", "source", "source_url"}

# These are deliberately material records rather than one marketing string.  A
# later picker must not silently turn a printer-level material family into a
# recipe or direct-send approval, but it also must not lose one of the A1 mini
# classes the user needs to review.
A1_MINI_MATERIAL_IDS = {
    "pla", "petg", "tpu", "pva", "support-pla", "support-petg",
    "abs", "asa", "pc", "pa", "pet", "pla-cf", "petg-cf", "cf-gf-filled",
}


def require_fields(value, fields, label):
    if not isinstance(value, dict):
        raise ValueError(f"{label} must be an object")
    missing = sorted(fields - value.keys())
    if missing:
        raise ValueError(f"{label} is missing: {', '.join(missing)}")
    for key in fields - {"materials", "feed_routes", "spool_forms", "spool_guidance"}:
        if not isinstance(value[key], str) or not value[key].strip():
            raise ValueError(f"{label}.{key} must be non-empty text")


def official_bambu_url(value, label):
    parsed = urlparse(value)
    host = parsed.hostname.lower() if parsed.hostname else ""
    if parsed.scheme != "https" or not (host == "bambulab.com" or host.endswith(".bambulab.com")):
        raise ValueError(f"{label} must be a direct HTTPS bambulab.com URL")


def unique_ids(values, label):
    ids = []
    for index, value in enumerate(values):
        if not isinstance(value, dict):
            raise ValueError(f"{label}[{index}] must be an object")
        identifier = value.get("id")
        if not isinstance(identifier, str) or not identifier.strip():
            raise ValueError(f"{label}[{index}].id must be non-empty")
        ids.append(identifier)
    if len(ids) != len(set(ids)):
        raise ValueError(f"{label} has duplicate ids")


def validate_catalog(path):
    if not path.is_file():
        raise ValueError(f"missing catalog: {path}")
    if path.stat().st_size > MAX_BYTES:
        raise ValueError("capability catalog exceeds 128 KB")
    with path.open(encoding="utf-8") as handle:
        root = json.load(handle)
    if root.get("schema_version") != 1:
        raise ValueError("capability catalog schema_version must be 1")
    printers = root.get("printers")
    if not isinstance(printers, list) or len(printers) != 3:
        raise ValueError("initial catalog must contain exactly three printers")
    ids = set()
    for printer in printers:
        require_fields(printer, PRINTER_FIELDS, "printer")
        identifier = printer["id"]
        if identifier in ids:
            raise ValueError(f"duplicate printer id: {identifier}")
        ids.add(identifier)
        official_bambu_url(printer["source_url"], f"{identifier}.source_url")
        try:
            reviewed = dt.date.fromisoformat(printer["reviewed"])
        except ValueError as error:
            raise ValueError(f"{identifier}.reviewed must be ISO-8601 date") from error
        if reviewed > dt.date.today():
            raise ValueError(f"{identifier}.reviewed cannot be in the future")
        if not printer["alloy_direct_send"].lower().startswith("not qualified"):
            raise ValueError(f"{identifier} must stay not qualified for Alloy direct send")
        if not isinstance(printer["materials"], list) or not printer["materials"]:
            raise ValueError(f"{identifier}.materials must be a non-empty list")
        unique_ids(printer["materials"], f"{identifier}.materials")
        for material in printer["materials"]:
            require_fields(material, MATERIAL_FIELDS, f"{identifier}.material")
            official_bambu_url(material["source_url"], f"{identifier}.material.source_url")
            if not material["alloy_direct_send"].lower().startswith(("not qualified", "blocked")):
                raise ValueError(f"{identifier}.material must stay unavailable for Alloy direct send")
        if not isinstance(printer["feed_routes"], list) or not printer["feed_routes"]:
            raise ValueError(f"{identifier}.feed_routes must be a non-empty list")
        unique_ids(printer["feed_routes"], f"{identifier}.feed_routes")
        for route in printer["feed_routes"]:
            require_fields(route, ROUTE_FIELDS, f"{identifier}.feed_route")
        if not isinstance(printer["spool_forms"], list) or not printer["spool_forms"]:
            raise ValueError(f"{identifier}.spool_forms must be a non-empty list")
        unique_ids(printer["spool_forms"], f"{identifier}.spool_forms")
        for form in printer["spool_forms"]:
            require_fields(form, SPOOL_FIELDS, f"{identifier}.spool_form")
            official_bambu_url(form["source_url"], f"{identifier}.spool_form.source_url")
        if not isinstance(printer["spool_guidance"], list) or not all(isinstance(x, str) and x.strip() for x in printer["spool_guidance"]):
            raise ValueError(f"{identifier}.spool_guidance must be non-empty text entries")
    if ids != EXPECTED_PRINTERS:
        raise ValueError("initial catalog printer ids must be A1 mini, A1 and P1S")
    mini = next(printer for printer in printers if printer["id"] == "a1-mini")
    if {material["id"] for material in mini["materials"]} != A1_MINI_MATERIAL_IDS:
        raise ValueError("A1 mini must disclose each ideal and not-recommended material class separately")
    support_materials = {material["id"]: material for material in mini["materials"]
                         if material["id"] in {"support-pla", "support-petg"}}
    if set(support_materials) != {"support-pla", "support-petg"}:
        raise ValueError("A1 mini must disclose PLA and PETG support-filament families separately")
    for material in support_materials.values():
        if material["alloy_direct_send"] != "Not qualified" or "does not imply AMS lite compatibility" not in material["note"]:
            raise ValueError("A1 mini support-filament families must stay unqualified and route-caveated")
    if {form["id"] for form in mini["spool_forms"]} != {
        "bambu-spooled", "bambu-refill", "third-party-direct", "regular-ams-spool"
    }:
        raise ValueError("A1 mini must disclose complete, refill, third-party and regular-AMS spool forms")
    if not any(route["id"] == "regular-ams" and route["state"] == "Unsupported" for route in mini["feed_routes"]):
        raise ValueError("A1 mini must label regular AMS unsupported")
    print(f"validated {path}")


def main():
    root = Path(__file__).resolve().parents[1]
    validate_catalog(root / CATALOG)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"capability catalog validation failed: {error}", file=sys.stderr)
        sys.exit(1)
