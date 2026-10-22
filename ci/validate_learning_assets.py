#!/usr/bin/env python3
"""Validate that offline learning artwork is packaged and matches its manifest."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "app/src/main/assets/learn/catalog-v1.json"
MANIFEST = ROOT / "docs/LEARNING_ASSET_MANIFEST.md"
ASSET_RE = re.compile(r"learn/[a-z0-9][a-z0-9-]{0,63}\.(?:png|jpe?g)\Z")
SECTION_RE = re.compile(r"^## `(?P<path>learn/[^`]+)`\s*$", re.MULTILINE)
HASH_RE = re.compile(r"^- \*\*SHA-256:\*\* `(?P<sha>[0-9a-f]{64})`\s*$", re.MULTILINE)


def fail(message: str) -> None:
    raise SystemExit(f"learning assets: {message}")


def main() -> None:
    try:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        manifest_text = MANIFEST.read_text(encoding="utf-8")
    except (OSError, json.JSONDecodeError) as error:
        fail(str(error))

    articles = catalog.get("articles") if isinstance(catalog, dict) else None
    if not isinstance(articles, list):
        fail("catalog must contain an articles array")

    referenced: set[str] = set()
    article_ids_by_asset: dict[str, set[str]] = {}
    for article in articles:
        if not isinstance(article, dict):
            fail("catalog article must be an object")
        artwork = article.get("artwork", "")
        if not artwork:
            continue
        if not isinstance(artwork, str) or not ASSET_RE.fullmatch(artwork):
            fail(f"unsafe or unsupported artwork path for {article.get('id', '<unknown>')}")
        referenced.add(artwork)
        article_id = article.get("id")
        if not isinstance(article_id, str) or not article_id:
            fail("every illustrated article must have a stable id")
        article_ids_by_asset.setdefault(artwork, set()).add(article_id)

    sections = list(SECTION_RE.finditer(manifest_text))
    manifest_assets: dict[str, str] = {}
    for index, match in enumerate(sections):
        section_end = sections[index + 1].start() if index + 1 < len(sections) else len(manifest_text)
        body = manifest_text[match.end():section_end]
        digest_match = HASH_RE.search(body)
        if not digest_match:
            fail(f"manifest section for {match.group('path')} has no SHA-256")
        relative = match.group("path")
        used_match = re.search(r"^- \*\*Used by:\*\*\s*(.*?)\n- \*\*Type:\*\*", body, re.MULTILINE | re.DOTALL)
        if not used_match:
            fail(f"manifest section for {relative} has no Used by list")
        used_text = " ".join(used_match.group(1).split())
        mentioned = {
            article_id
            for article in articles
            if isinstance(article, dict)
            for article_id in [article.get("id")]
            if isinstance(article_id, str)
            and re.search(rf"(?<![a-z0-9-]){re.escape(article_id)}(?![a-z0-9-])", used_text)
        }
        expected_articles = article_ids_by_asset.get(relative, set())
        if mentioned != expected_articles:
            fail(f"Used by list for {relative} differs from catalog; missing={sorted(expected_articles - mentioned)}, stale={sorted(mentioned - expected_articles)}")
        manifest_assets[relative] = digest_match.group("sha")

    if referenced != set(manifest_assets):
        missing = sorted(referenced - set(manifest_assets))
        unreferenced = sorted(set(manifest_assets) - referenced)
        fail(f"catalog/manifest mismatch; undocumented={missing}, unused={unreferenced}")

    for relative, expected_hash in sorted(manifest_assets.items()):
        path = ROOT / "app/src/main/assets" / relative
        if not path.is_file():
            fail(f"catalog artwork is missing: {relative}")
        data = path.read_bytes()
        actual_hash = hashlib.sha256(data).hexdigest()
        if actual_hash != expected_hash:
            fail(f"SHA-256 mismatch for {relative}: expected {expected_hash}, got {actual_hash}")
        if relative.endswith(".png") and not data.startswith(b"\x89PNG\r\n\x1a\n"):
            fail(f"file extension does not match PNG data: {relative}")
        if relative.endswith((".jpg", ".jpeg")) and not data.startswith(b"\xff\xd8\xff"):
            fail(f"file extension does not match JPEG data: {relative}")

    print(f"validated {len(referenced)} offline learning illustrations and manifest hashes")


if __name__ == "__main__":
    main()
