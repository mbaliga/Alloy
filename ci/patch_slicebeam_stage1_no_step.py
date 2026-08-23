#!/usr/bin/env python3
"""Remove STEP/OpenCASCADE from a temporary SliceBeam checkout for Alloy Stage 1.

The Stage 1 import contract is STL + 3MF. This patch intentionally avoids
building/linking OCCT while leaving the rest of the pinned SliceBeam/Prusa 2.8
engine unchanged. It is applied only to CI/extraction checkouts, not upstream.
"""

from pathlib import Path
import re
import sys


def require_sub(pattern: str, replacement: str, text: str, label: str, flags=0) -> str:
    updated, count = re.subn(pattern, replacement, text, flags=flags)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} match, got {count}")
    return updated


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_slicebeam_stage1_no_step.py <slicebeam-root>")

    root = Path(sys.argv[1]).resolve()
    cmake_path = root / "app/CMakeLists.txt"
    model_path = root / "app/src/main/jni/libslic3r/Model.cpp"
    deps_path = root / "build_all_deps_android.sh"

    cmake = cmake_path.read_text()
    cmake = require_sub(
        r"\n# OCCT\n.*?\nlist\(TRANSFORM OCCT_LIBS PREPEND \"occt_\"\)\n",
        "\n# OCCT/STEP intentionally excluded from Alloy Stage 1.\n",
        cmake,
        "OCCT import block",
        re.S,
    )
    cmake = require_sub(
        r"\nadd_library\(OCCTWrapper\n.*?EXPORT_FILE_NAME .*?occtwrapper_export\.h\)\n",
        "\n# OCCTWrapper intentionally excluded from Alloy Stage 1.\n",
        cmake,
        "OCCTWrapper target",
        re.S,
    )
    cmake = cmake.replace("        src/main/jni/libslic3r/Format/STEP.hpp\n", "")
    cmake = cmake.replace("        src/main/jni/libslic3r/Format/STEP.cpp\n", "")
    cmake = cmake.replace("        OCCTWrapper\n", "")
    if "Format/STEP.cpp" in cmake or "OCCTWrapper" in "\n".join(
        line for line in cmake.splitlines() if not line.lstrip().startswith("#")
    ):
        raise SystemExit("STEP/OCCT CMake references remain after patch")
    cmake_path.write_text(cmake)

    model = model_path.read_text()
    model = model.replace('#include "Format/STEP.hpp"\n', "")
    model = require_sub(
        r"\n    else if \(boost::algorithm::iends_with\(input_file, \"\\\.step\"\) \|\| boost::algorithm::iends_with\(input_file, \"\\\.stp\"\)\)\n        result = load_step\(input_file\.c_str\(\), &model\);",
        "",
        model,
        "STEP model dispatch branch",
    )
    model = model.replace(
        "Input file must have .stl, .obj, .step/.stp, .svg, .amf(.xml) or extension .3mf(.zip).",
        "Input file must have .stl, .obj, .svg, .amf(.xml) or extension .3mf(.zip).",
    )
    if 'Format/STEP.hpp' in model or 'load_step(' in model:
        raise SystemExit("STEP Model.cpp references remain after patch")
    model_path.write_text(model)

    deps = deps_path.read_text()
    deps = require_sub(
        r"\n# 3\. Build OCCT\n.*?\necho \"--- OCCT built and copied! ---\"\n",
        "\n# 3. OCCT intentionally skipped: STEP is outside Alloy Stage 1.\n",
        deps,
        "OCCT dependency build block",
        re.S,
    )
    if "git clone https://github.com/Open-Cascade-SAS/OCCT.git" in deps:
        raise SystemExit("OCCT dependency clone remains after patch")
    deps_path.write_text(deps)

    print("Stage 1 native scope patched successfully:")
    print("- STEP sources removed from CMake")
    print("- Model STEP dispatch removed")
    print("- OCCT imported targets/wrapper/link removed")
    print("- OCCT dependency build skipped")


if __name__ == "__main__":
    main()
