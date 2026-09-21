import importlib.util
from pathlib import Path
import shutil
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "patch_slicebeam_stage1_no_step", ROOT / "ci/patch_slicebeam_stage1_no_step.py"
)
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)


class Stage1ScopePatchTests(unittest.TestCase):
    def test_patches_bundled_slice_beam_shapes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app/src/main/jni/libslic3r").mkdir(parents=True)
            shutil.copy2(ROOT / "app/CMakeLists.native.txt", root / "app/CMakeLists.txt")
            shutil.copy2(ROOT / "app/src/main/jni/libslic3r/Model.cpp", root / "app/src/main/jni/libslic3r/Model.cpp")
            shutil.copy2(ROOT / "ci/patch_slicebeam_stage1_no_step.py", root / "patch.py")
            quote = chr(34)
            newline = chr(10)
            (root / "build_all_deps_android.sh").write_text(
                "# 3. Build OCCT" + newline
                + "git clone https://github.com/Open-Cascade-SAS/OCCT.git" + newline
                + "echo " + quote + "--- OCCT built and copied! ---" + quote + newline
            )

            PATCHER.patch_cmake(root / "app/CMakeLists.txt")
            PATCHER.patch_model(root / "app/src/main/jni/libslic3r/Model.cpp")
            PATCHER.patch_dependencies(root / "build_all_deps_android.sh")

            combined = "\n".join(path.read_text() for path in (
                root / "app/CMakeLists.txt",
                root / "app/src/main/jni/libslic3r/Model.cpp",
                root / "build_all_deps_android.sh",
            ))
            self.assertNotIn("Format/STEP", combined)
            self.assertNotIn("load_step(", combined)
            self.assertNotIn("OCCT_LIBS", combined)

    def test_removes_current_cmake_model_and_dependency_shapes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "app/src/main/jni/libslic3r").mkdir(parents=True)
            (root / "app").mkdir(exist_ok=True)
            (root / "app/CMakeLists.txt").write_text(
                '# OCCT\n'
                'add_library(occt_core IMPORTED SHARED)\n'
                'list(TRANSFORM OCCT_LIBS PREPEND "occt_")\n'
                'add_library(OCCTWrapper\n'
                '  src/main/jni/OCCTWrapper.cpp\n'
                '  EXPORT_FILE_NAME ${CMAKE_CURRENT_BINARY_DIR}/occtwrapper_export.h)\n'
                'set(BOOST_LIBS atomic charconv chrono container context contract coroutine date_time exception fiber\n'
                '        filesystem graph iostreams json log log_setup math_c99 math_c99f math_c99l math_tr1 math_tr1f\n'
                '        math_tr1l nowide prg_exec_monitor program_options random regex serialization stacktrace_basic\n'
                '        stacktrace_noop system test_exec_moinotr thread timer type_erasure unit_test_framework url wave\n'
                '        wserialization)\n'
                '        src/main/jni/libslic3r/Format/STEP.hpp\n'
                '        src/main/jni/libslic3r/Format/STEP.cpp\n'
                '        ${OCCT_LIBS}\n'
                '        boost_math_c99\n'
                '        boost_math_c99f\n'
                '        boost_math_c99l\n'
                '        boost_math_tr1\n'
                '        boost_math_tr1f\n'
                '        boost_math_tr1l\n'
                '        boost_test_exec_moinotr\n'
                'tbb::filter<void, LayerResult> pipeline_to_layerresult = smooth_path_interpolator & generator;\n'
                'tbb::filter<LayerResult, std::string> pipeline_to_string = cooling;\n'
            )
            (root / "app/src/main/jni/libslic3r/Model.cpp").write_text(
                '#include "Format/STEP.hpp"\n'
                'else if (boost::algorithm::iends_with(input_file, ".step") || boost::algorithm::iends_with(input_file, ".stp"))\n'
                '    result = load_step(input_file.c_str(), &model);\n'
                'Input file must have .stl, .obj, .step/.stp, .svg, .amf(.xml) or extension .3mf(.zip).'
            )
            (root / "build_all_deps_android.sh").write_text(
                '# 3. Build OCCT\n'
                'git clone https://github.com/Open-Cascade-SAS/OCCT.git\n'
                'echo "--- OCCT built and copied! ---"\n'
            )

            for path, function in (
                (root / "app/CMakeLists.txt", PATCHER.patch_cmake),
                (root / "app/src/main/jni/libslic3r/Model.cpp", PATCHER.patch_model),
                (root / "build_all_deps_android.sh", PATCHER.patch_dependencies),
            ):
                function(path)

            combined = "\n".join(path.read_text() for path in (
                root / "app/CMakeLists.txt",
                root / "app/src/main/jni/libslic3r/Model.cpp",
                root / "build_all_deps_android.sh",
            ))
            self.assertNotIn("Format/STEP", combined)
            self.assertNotIn("load_step(", combined)
            self.assertNotIn("OCCT_LIBS", combined)
            self.assertNotIn("Open-Cascade-SAS/OCCT.git", combined)
            self.assertNotIn("math_c99", combined)
            self.assertNotIn("boost_math_", combined)
            self.assertNotIn("test_exec_moinotr", combined)
            self.assertNotIn("tbb::filter<void, LayerResult>", combined)
            self.assertNotIn("tbb::filter<LayerResult, std::string>", combined)

    def test_fails_closed_when_upstream_shape_changes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "Model.cpp"
            path.write_text("#include \\\"Format/STEP.hpp\\\"\n")
            with self.assertRaises(SystemExit):
                PATCHER.patch_model(path)


if __name__ == "__main__":
    unittest.main()
