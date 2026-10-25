#!/usr/bin/env python3
"""Create and validate an inventory-only provenance record for native CI.

This manifest binds declared pins, Alloy-side patch tooling, copied source
inputs, available build checkouts, APKs, and packaged shared-library bytes. It
is deliberately not a legal review, source-lineage pass, or release gate.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile


SCHEMA_VERSION = 1
WORKFLOW_PATH = ".github/workflows/alloy-native-build.yml"
PATCH_TOOLS = (
    "ci/patch_orca_mobile_bootstrap.py",
    "ci/patch_boost_android_locale.py",
    "ci/patch_slicebeam_stage1_no_step.py",
    "ci/normalize_occt_headers.py",
    "ci/build_gmp_mpfr_android.sh",
)
NATIVE_SOURCE_ROOTS = (
    "app/src/main/jni",
    "app/src/main/occt",
    "app/src/main/jniImports",
    "app/src/main/jniLibs",
    "app/src/main/java/ru/ytkab0bp/slicebeam/slic3r",
    "app/src/main/java/com/mbaliga/alloy/NativeSlicerEngine.java",
    "app/src/main/java/com/mbaliga/alloy/NativeEngine.java",
    "app/CMakeLists.native.txt",
)
LEGAL_MARKERS = ("license", "copying", "notice", "copyright")
INPUT_PINS = (
    "SLICEBEAM_REPO",
    "SLICEBEAM_SHA",
    "ORCA_MOBILE_REPO",
    "ORCA_MOBILE_SHA",
    "ORCA_PROFILE_REPO",
    "ORCA_PROFILE_SHA",
    "BAMBU_PROFILE_REPO",
    "BAMBU_PROFILE_SHA",
    "ANDROID_API",
    "NDK_VERSION",
    "OPENCV_VERSION",
    "OPENCV_SHA256",
)
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_git(path: Path, *args: str) -> str | None:
    result = subprocess.run(
        ["git", "-C", str(path), *args],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode:
        return None
    return result.stdout.decode("utf-8", errors="replace").strip()


def _workflow_pins(root: Path) -> dict[str, str]:
    path = root / WORKFLOW_PATH
    text = path.read_text(encoding="utf-8")
    match = re.search(r"(?ms)^env:\s*\n(.*?)(?=^jobs:)", text)
    if not match:
        raise ValueError(f"{path}: workflow env pin block is missing")
    values: dict[str, str] = {}
    for line in match.group(1).splitlines():
        item = re.match(r"^\s{2}([A-Z][A-Z0-9_]+):\s*['\"]?([^'\"#]+?)['\"]?\s*$", line)
        if item:
            values[item.group(1)] = item.group(2).strip()
    missing = [key for key in INPUT_PINS if not values.get(key)]
    if missing:
        raise ValueError(f"{path}: missing required workflow pins: {missing}")
    return {key: values[key] for key in INPUT_PINS}


def _file_inventory(root: Path, include_roots: tuple[str, ...]) -> list[dict[str, object]]:
    paths: set[Path] = set()
    for relative in include_roots:
        candidate = root / relative
        if candidate.is_file():
            paths.add(candidate)
        elif candidate.is_dir():
            paths.update(path for path in candidate.rglob("*") if path.is_file())
    entries = []
    for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix()):
        entries.append({
            "path": path.relative_to(root).as_posix(),
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        })
    return entries


def _legal_inventory(root: Path) -> list[dict[str, object]]:
    paths = set()
    for relative in ("third_party/licenses", "app/src/main/jni", "app/src/main/occt"):
        candidate = root / relative
        if candidate.is_dir():
            paths.update(
                path for path in candidate.rglob("*")
                if path.is_file() and (
                    relative == "third_party/licenses"
                    or any(marker in path.name.lower() for marker in LEGAL_MARKERS)
                )
            )
    return [
        {
            "path": path.relative_to(root).as_posix(),
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        for path in sorted(paths, key=lambda item: item.relative_to(root).as_posix())
    ]


def _checkout_record(path: Path, label: str) -> dict[str, object]:
    commit = run_git(path, "rev-parse", "HEAD") if path.is_dir() else None
    remote = run_git(path, "config", "--get", "remote.origin.url") if commit else None
    dirty = run_git(path, "status", "--porcelain") if commit else None
    result: dict[str, object] = {
        "name": label,
        "path": path.name,
        "available": bool(commit),
        "commit": commit,
        "origin": remote,
        "worktree_clean": dirty == "" if dirty is not None else None,
    }
    if commit:
        submodules = run_git(path, "submodule", "status", "--recursive")
        if submodules is None:
            result["submodules"] = {"available": False, "entries": []}
        else:
            entries = []
            for line in submodules.splitlines():
                match = re.match(r"^([-+ U]?)([0-9a-f]{40})\s+(.+)$", line)
                if match:
                    entries.append({
                        "status_prefix": match.group(1),
                        "commit": match.group(2),
                        "path": match.group(3),
                    })
            result["submodules"] = {"available": True, "entries": entries}
        diff = subprocess.run(
            ["git", "-C", str(path), "diff", "--binary", "HEAD"],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        if diff.returncode == 0:
            result["tracked_patch"] = {
                "sha256": sha256_bytes(diff.stdout),
                "size_bytes": len(diff.stdout),
            }
        else:
            result["tracked_patch"] = None
    return result


def _archive_record(root: Path, relative: str) -> dict[str, object]:
    path = root / relative
    if not path.is_file():
        return {"path": relative, "available": False}
    return {
        "path": relative,
        "available": True,
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def _pinned_archives(root: Path, pins: dict[str, str], opencv_archive: str) -> list[dict[str, object]]:
    build_script = (root / "ci/build_gmp_mpfr_android.sh").read_text(encoding="utf-8")
    records = [{
        "component": "OpenCV",
        "version": pins["OPENCV_VERSION"],
        "url": f"https://github.com/opencv/opencv/archive/refs/tags/{pins['OPENCV_VERSION']}.tar.gz",
        "path": opencv_archive,
        "expected_sha256": pins["OPENCV_SHA256"],
    }]
    for component in ("GMP", "MPFR"):
        url = re.search(rf'^{component}_URL="([^"]+)"$', build_script, re.MULTILINE)
        digest = re.search(rf'^{component}_SHA256="([0-9a-f]{{64}})"$', build_script, re.MULTILINE)
        if not url or not digest:
            raise ValueError(f"{component} archive URL/SHA-256 pin is missing from build script")
        version = re.search(rf"{component.lower()}-([0-9]+(?:\.[0-9]+)+)\.tar\.bz2", url.group(1))
        records.append({
            "component": component,
            "version": version.group(1) if version else None,
            "url": url.group(1),
            "path": None,
            "expected_sha256": digest.group(1),
            "archive_retained": False,
        })
    for record in records:
        path = record["path"]
        if path:
            actual = _archive_record(root, path)
            record.update({key: value for key, value in actual.items() if key != "path"})
            record["archive_retained"] = bool(actual.get("available"))
    return records


def _apk_record(root: Path, relative: str) -> dict[str, object]:
    path = root / relative
    if not path.is_file():
        return {"path": relative, "available": False}
    libraries: list[dict[str, object]] = []
    license_assets: list[dict[str, object]] = []
    try:
        with zipfile.ZipFile(path) as archive:
            for name in sorted(archive.namelist()):
                if name.startswith("lib/") and name.endswith(".so"):
                    content = archive.read(name)
                    libraries.append({
                        "path": name,
                        "size_bytes": len(content),
                        "sha256": sha256_bytes(content),
                    })
                elif any(marker in Path(name).name.lower() for marker in LEGAL_MARKERS):
                    content = archive.read(name)
                    license_assets.append({
                        "path": name,
                        "size_bytes": len(content),
                        "sha256": sha256_bytes(content),
                    })
    except (OSError, zipfile.BadZipFile) as error:
        return {
            "path": relative,
            "available": True,
            "valid_zip": False,
            "error": str(error),
            "size_bytes": path.stat().st_size,
            "sha256": sha256_file(path),
            "packaged_shared_libraries": [],
            "packaged_license_assets": [],
        }
    return {
        "path": relative,
        "available": True,
        "valid_zip": True,
        "size_bytes": path.stat().st_size,
        "sha256": sha256_file(path),
        "packaged_shared_libraries": libraries,
        "packaged_license_assets": license_assets,
    }


def build_manifest(
    root: Path,
    *,
    slice_beam_root: Path | None = None,
    bootstrap_root: Path | None = None,
    opencv_archive: str = "opencv-4.6.0.tar.gz",
    apk_paths: tuple[str, ...] = (
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk",
    ),
) -> dict[str, object]:
    root = root.resolve()
    pins = _workflow_pins(root)
    patch_tools = []
    for relative in PATCH_TOOLS:
        path = root / relative
        patch_tools.append({
            "path": relative,
            "available": path.is_file(),
            "sha256": sha256_file(path) if path.is_file() else None,
        })
    version_text = (root / "app/CMakeLists.native.txt").read_text(encoding="utf-8")
    boost_match = re.search(r'set\(BOOST_VERSION\s+"([^\"]+)"\)', version_text)
    openvdb_parts = [
        re.search(rf"set\(OpenVDB_{part}_VERSION\s+(\d+)\)", version_text)
        for part in ("MAJOR", "MINOR", "PATCH")
    ]
    if not boost_match or any(part is None for part in openvdb_parts):
        raise ValueError("native CMake input is missing Boost/OpenVDB version markers")
    openvdb_version = ".".join(part.group(1) for part in openvdb_parts if part is not None)

    notices = _legal_inventory(root)
    notice_map = {item["path"]: item["sha256"] for item in notices}
    licenses = [
        {
            "component": "OrcaSlicer-Mobile libslic3r engine",
            "declared_license": "AGPL-3.0",
            "license_source": f"{pins['ORCA_MOBILE_REPO']}/blob/{pins['ORCA_MOBILE_SHA']}/LICENSE",
            "upstream_license_git_blob": "dba13ed2ddf783ee8118c6a581dbf75305f816a3",
            "candidate_notice_path": "third_party/licenses/SLICEBEAM_AGPL-3.0.txt",
            "candidate_notice_sha256": notice_map.get("third_party/licenses/SLICEBEAM_AGPL-3.0.txt"),
            "review_status": "PENDING_LEGAL_REVIEW",
        },
        {
            "component": "SliceBeam JNI compatibility lineage",
            "declared_license": "AGPL-3.0",
            "license_source": f"{pins['SLICEBEAM_REPO']}/blob/{pins['SLICEBEAM_SHA']}/LICENSE",
            "candidate_notice_path": "third_party/licenses/SLICEBEAM_AGPL-3.0.txt",
            "candidate_notice_sha256": notice_map.get("third_party/licenses/SLICEBEAM_AGPL-3.0.txt"),
            "review_status": "PENDING_LEGAL_REVIEW",
        },
        {
            "component": "Boost",
            "version": boost_match.group(1).replace("_", "."),
            "declared_license": "BSL-1.0",
            "license_source": "https://github.com/boostorg/boost/tree/boost-1.85.0",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "oneTBB",
            "declared_license": "Apache-2.0",
            "license_source": "pinned OpenVDB-Android tbb-aarch64 submodule license",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "OCCT",
            "declared_license": "LGPL-2.1-only WITH OCCT-exception-1.0",
            "license_source": "pinned OCCT LICENSE_LGPL_21.txt and OCCT_LGPL_EXCEPTION.txt",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "OpenVDB",
            "version": openvdb_version,
            "declared_license": "MPL-2.0",
            "license_source": "app/src/main/jni/openvdb/LICENSE",
            "candidate_notice_path": "app/src/main/jni/openvdb/LICENSE",
            "candidate_notice_sha256": notice_map.get("app/src/main/jni/openvdb/LICENSE"),
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "OpenCV",
            "version": pins["OPENCV_VERSION"],
            "archive_url": f"https://github.com/opencv/opencv/archive/refs/tags/{pins['OPENCV_VERSION']}.tar.gz",
            "archive_sha256": pins["OPENCV_SHA256"],
            "declared_license": "Apache-2.0",
            "license_source": f"https://github.com/opencv/opencv/blob/{pins['OPENCV_VERSION']}/LICENSE",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "GMP",
            "version": "6.2.1",
            "archive_url": "https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2",
            "archive_sha256": "eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c",
            "declared_license": "GPL-2.0-or-later OR LGPL-3.0-or-later",
            "license_source": "GMP 6.2.1 source archive COPYING and COPYING.LESSERv3",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "MPFR",
            "version": "4.2.2",
            "archive_url": "https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2",
            "archive_sha256": "9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b",
            "declared_license": "LGPL-3.0-or-later",
            "license_source": "MPFR 4.2.2 source archive COPYING.LESSER",
            "candidate_notice_path": None,
            "candidate_notice_sha256": None,
            "review_status": "PENDING_NOTICE_COVERAGE_REVIEW",
        },
        {
            "component": "libnoise",
            "declared_license": "LGPL-2.1-only",
            "license_source": "third_party/orca-deps-libnoise pinned submodule",
            "candidate_notice_path": "third_party/licenses/LIBNOISE_LGPL-2.1.txt",
            "candidate_notice_sha256": notice_map.get("third_party/licenses/LIBNOISE_LGPL-2.1.txt"),
            "review_status": "PINNED_NOTICE_COPY_PRESENT_REVIEW_PENDING",
        },
    ]

    checkouts = []
    if slice_beam_root is not None:
        checkouts.append(_checkout_record(slice_beam_root, "SliceBeam dependency bootstrap"))
    if bootstrap_root is not None:
        checkouts.append(_checkout_record(bootstrap_root, "OrcaSlicer-Mobile bootstrap source"))
    for nested in (
        (slice_beam_root / "openvdb-android") if slice_beam_root else None,
        (slice_beam_root / "Boost-for-Android") if slice_beam_root else None,
        (slice_beam_root / "OCCT") if slice_beam_root else None,
    ):
        if nested is not None:
            checkouts.append(_checkout_record(nested, nested.name))

    artifacts = [_apk_record(root, relative) for relative in apk_paths]
    shared_objects = []
    for artifact in artifacts:
        if artifact.get("valid_zip"):
            for library in artifact.get("packaged_shared_libraries", []):
                shared_objects.append({
                    "apk_path": artifact["path"],
                    **library,
                })

    git_commit = run_git(root, "rev-parse", "HEAD")
    result: dict[str, object] = {
        "schema_version": SCHEMA_VERSION,
        "manifest_type": "native-build-provenance-inventory",
        "inventory_only": True,
        "qualification": {
            "native_engine_source_provenance_and_license": "PENDING",
            "g2_source_lineage": "FAIL_OR_UNRESOLVED",
            "legal_signoff": "PENDING",
            "gate_eligible": False,
            "reason": "Inventory records inputs and bytes; it does not establish exact upstream lineage, complete license/notice coverage, or legal approval.",
        },
        "workflow": {
            "repository_commit": git_commit,
            "github_sha": os.environ.get("GITHUB_SHA"),
            "pull_request_head_sha": os.environ.get("ALLOY_PR_HEAD_SHA"),
            "run_id": os.environ.get("GITHUB_RUN_ID"),
            "run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
            "event_name": os.environ.get("GITHUB_EVENT_NAME"),
        },
        "pinned_inputs": pins,
        "toolchain": {
            "android_api": pins["ANDROID_API"],
            "ndk_version": pins["NDK_VERSION"],
            "boost_version": boost_match.group(1).replace("_", "."),
            "openvdb_version": openvdb_version,
        },
        "patch_tool_hashes": patch_tools,
        "checkouts": checkouts,
        "local_native_source_inventory": _file_inventory(root, NATIVE_SOURCE_ROOTS),
        "notice_files_in_repository": notices,
        "dependency_license_inventory": licenses,
        "declared_downloaded_archives": _pinned_archives(root, pins, opencv_archive),
        "build_artifacts": artifacts,
        "packaged_shared_objects": shared_objects,
        "limitations": [
            "The engine sources copied into Alloy are hashed locally but are not mapped file-by-file to the pinned upstream commit and reviewed patch series.",
            "The G2 official Orca provenance criterion remains failed; this manifest does not resolve that result.",
            "Dependency licenses and notice files are an inventory, not a complete legal/redistribution review.",
            "Runtime source build success, APK hashes, and shared-library hashes do not establish slicing parity or physical-printer qualification.",
        ],
    }
    return result


def validate_manifest(document: object) -> None:
    if not isinstance(document, dict) or document.get("schema_version") != SCHEMA_VERSION:
        raise ValueError("manifest schema_version must be 1")
    if document.get("manifest_type") != "native-build-provenance-inventory":
        raise ValueError("manifest_type is not native-build-provenance-inventory")
    qualification = document.get("qualification")
    if not isinstance(qualification, dict):
        raise ValueError("qualification record is missing")
    if (qualification.get("native_engine_source_provenance_and_license") != "PENDING"
            or qualification.get("legal_signoff") != "PENDING"
            or qualification.get("gate_eligible") is not False):
        raise ValueError("inventory must not be marked as qualification evidence")
    for section in ("patch_tool_hashes", "local_native_source_inventory", "notice_files_in_repository"):
        entries = document.get(section)
        if not isinstance(entries, list):
            raise ValueError(f"{section} must be a list")
        for item in entries:
            if not isinstance(item, dict):
                raise ValueError(f"{section} entries must be objects")
            digest = item.get("sha256")
            if item.get("available", True) and not (isinstance(digest, str) and SHA256_RE.fullmatch(digest)):
                raise ValueError(f"{section} contains a missing or invalid SHA-256")
    for artifact in document.get("build_artifacts", []):
        if artifact.get("available") and not SHA256_RE.fullmatch(str(artifact.get("sha256", ""))):
            raise ValueError("available build artifact has no valid SHA-256")
        for library in artifact.get("packaged_shared_libraries", []):
            if not SHA256_RE.fullmatch(str(library.get("sha256", ""))):
                raise ValueError("packaged shared object has no valid SHA-256")
    archives = document.get("declared_downloaded_archives")
    if not isinstance(archives, list):
        raise ValueError("declared_downloaded_archives must be a list")
    for archive in archives:
        if not isinstance(archive, dict) or not SHA256_RE.fullmatch(str(archive.get("expected_sha256", ""))):
            raise ValueError("declared archive is missing a valid pinned SHA-256")


def validate_against_root(document: dict[str, object], root: Path) -> None:
    validate_manifest(document)
    for section in ("local_native_source_inventory", "notice_files_in_repository"):
        for item in document[section]:
            path = root / item["path"]
            if not path.is_file() or sha256_file(path) != item["sha256"]:
                raise ValueError(f"{section}: source digest mismatch for {item['path']}")
    for artifact in document["build_artifacts"]:
        if not artifact.get("available"):
            continue
        path = root / artifact["path"]
        if not path.is_file() or sha256_file(path) != artifact["sha256"]:
            raise ValueError(f"build artifact digest mismatch for {artifact['path']}")
        if artifact.get("valid_zip"):
            current = _apk_record(root, artifact["path"])
            if current["packaged_shared_libraries"] != artifact["packaged_shared_libraries"]:
                raise ValueError(f"packaged shared-object digest mismatch for {artifact['path']}")
            if current["packaged_license_assets"] != artifact.get("packaged_license_assets", []):
                raise ValueError(f"packaged license-asset digest mismatch for {artifact['path']}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--slice-beam-root", type=Path)
    parser.add_argument("--bootstrap-root", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--validate", type=Path, help="validate an existing JSON manifest against the repo root")
    arguments = parser.parse_args()
    try:
        root = arguments.repo_root.resolve()
        if arguments.validate:
            document = json.loads(arguments.validate.read_text(encoding="utf-8"))
            validate_against_root(document, root)
            print(f"validated inventory-only native provenance manifest {arguments.validate}")
            return
        if not arguments.output:
            raise ValueError("--output is required when generating a manifest")
        def resolve_optional(path: Path | None) -> Path | None:
            return path.resolve() if path else None
        document = build_manifest(
            root,
            slice_beam_root=resolve_optional(arguments.slice_beam_root),
            bootstrap_root=resolve_optional(arguments.bootstrap_root),
        )
        validate_manifest(document)
        output = arguments.output if arguments.output.is_absolute() else root / arguments.output
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(document, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        print(f"wrote inventory-only native provenance manifest {output}")
    except (OSError, ValueError, json.JSONDecodeError) as error:
        raise SystemExit(f"native build provenance: {error}") from error


if __name__ == "__main__":
    main()
