#!/usr/bin/env python3
"""Remove the unused SliceBeam STEP surface from a temporary Stage 1 checkout.

Alloy's phone-first Stage 1 import contract accepts STL, OBJ and 3MF.  The
pinned SliceBeam source still carries an application-level STEP route that is
not part of that contract.  We intentionally retain the OpenCASCADE runtime
dependency in the bootstrap, however: the pinned native slicer graph and
Alloy's JNI bridge link against its shared-library chain.  Skipping the OCCT
bootstrap would make the later, real Alloy native build non-reproducible.

This script is deliberately limited to CI checkouts; it never edits the Alloy
source tree or upstream.
"""

from pathlib import Path
import re
import sys


def require_sub(pattern: str, replacement: str, text: str, label: str) -> str:
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} match, got {count}")
    return updated


def require_literal(old: str, new: str, text: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one {label} match, got {count}")
    return text.replace(old, new, 1)


def replace_optional_literal(old: str, new: str, text: str, label: str) -> str:
    if old not in text:
        return text
    return require_literal(old, new, text, label)


def replace_optional_all(old: str, new: str, text: str, label: str, maximum: int) -> str:
    count = text.count(old)
    if count == 0:
        return text
    if count > maximum:
        raise SystemExit(f"Expected at most {maximum} {label} matches, got {count}")
    return text.replace(old, new)


def patch_cmake(path: Path) -> None:
    text = path.read_text()
    text = require_sub(
        r"^# OCCT\n.*?^list\(TRANSFORM OCCT_LIBS PREPEND \"occt_\"\)\n?",
        "# SliceBeam OCCT/STEP target intentionally excluded from Alloy Stage 1.\n",
        text,
        "OCCT imported-library block",
    )
    text = require_sub(
        r"^add_library\(OCCTWrapper\n.*?^\s*EXPORT_FILE_NAME .*?occtwrapper_export\.h\)\n?",
        "# OCCTWrapper intentionally excluded from Alloy Stage 1.\n",
        text,
        "OCCTWrapper target",
    )
    text = require_literal(
        "        src/main/jni/libslic3r/Format/STEP.hpp\n",
        "",
        text,
        "STEP.hpp source",
    )
    text = require_literal(
        "        src/main/jni/libslic3r/Format/STEP.cpp\n",
        "",
        text,
        "STEP.cpp source",
    )
    text = require_literal("        ${OCCT_LIBS}\n", "", text, "OCCT link set")
    text = replace_optional_literal(
        "        filesystem graph iostreams json log log_setup math_c99 math_c99f math_c99l math_tr1 math_tr1f\n"
        "        math_tr1l nowide prg_exec_monitor program_options random regex serialization stacktrace_basic\n",
        "        filesystem graph iostreams json log log_setup nowide prg_exec_monitor program_options random regex serialization stacktrace_basic\n",
        text,
        "Boost.Math import list",
    )
    text = replace_optional_literal(
        "        boost_math_c99\n"
        "        boost_math_c99f\n"
        "        boost_math_c99l\n"
        "        boost_math_tr1\n"
        "        boost_math_tr1f\n"
        "        boost_math_tr1l\n",
        "",
        text,
        "Boost.Math link set",
    )
    text = replace_optional_all(
        "test_exec_moinotr",
        "test_exec_monitor",
        text,
        "Boost test monitor target typo",
        2,
    )
    text = replace_optional_all(
        "tbb::filter<void, LayerResult> pipeline_to_layerresult = smooth_path_interpolator & generator;",
        "auto pipeline_to_layerresult = smooth_path_interpolator & generator;",
        text,
        "legacy TBB pipeline type",
        2,
    )
    text = replace_optional_all(
        "tbb::filter<LayerResult, std::string> pipeline_to_string = cooling;",
        "auto pipeline_to_string = cooling;",
        text,
        "legacy TBB string pipeline type",
        2,
    )
    # The temporary SliceBeam build must drop only its legacy JNI/OCCT STEP
    # sources. The separate pinned official-Orca compile probe intentionally
    # keeps its own Format/STEP.cpp source in the shared native CMake file.
    if "src/main/jni/libslic3r/Format/STEP.cpp" in text or "src/main/jni/libslic3r/Format/STEP.hpp" in text or "${OCCT_LIBS}" in text:
        raise SystemExit("STEP/OCCT CMake references remain after patch")
    if "math_c99" in text or "boost_math_" in text or "test_exec_moinotr" in text:
        raise SystemExit("Unsupported Boost.Math or test monitor CMake references remain after patch")
    path.write_text(text)


def patch_model(path: Path) -> None:
    text = path.read_text()
    # Newer Orca-Mobile source routes STEP through Alloy's OCCT JNI bridge
    # instead of libslic3r/Format/STEP.hpp. There is nothing to remove from
    # that Model.cpp; keep the historical patch strict for partial/changed
    # shapes that still advertise any of the old STEP surface.
    if ('Format/STEP.hpp' not in text and 'load_step(' not in text
            and '.step' not in text and '.stp' not in text):
        return
    text = require_literal('#include "Format/STEP.hpp"\n', "", text, "STEP include")
    text = require_sub(
        r'^\s*else if \(boost::algorithm::iends_with\(input_file, "\.step"\)\s*\|\|\s*boost::algorithm::iends_with\(input_file, "\.stp"\)\)\n\s*result = load_step\(input_file\.c_str\(\), &model\);\n?',
        "",
        text,
        "STEP model dispatch branch",
    )
    text = require_literal(
        "Input file must have .stl, .obj, .step/.stp, .svg, .amf(.xml) or extension .3mf(.zip).",
        "Input file must have .stl, .obj, .svg, .amf(.xml) or extension .3mf(.zip).",
        text,
        "STEP format error text",
    )
    if 'Format/STEP.hpp' in text or 'load_step(' in text:
        raise SystemExit("STEP Model.cpp references remain after patch")
    path.write_text(text)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_slicebeam_stage1_no_step.py <slicebeam-root>")

    root = Path(sys.argv[1]).resolve()
    patch_cmake(root / "app/CMakeLists.txt")
    patch_model(root / "app/src/main/jni/libslic3r/Model.cpp")

    print("Stage 1 native scope patched successfully:")
    print("- STEP sources removed from CMake")
    print("- STEP model dispatch removed")
    print("- SliceBeam STEP target/import surface removed")
    print("- OCCT dependency bootstrap retained for Alloy's native runtime")


if __name__ == "__main__":
    main()
