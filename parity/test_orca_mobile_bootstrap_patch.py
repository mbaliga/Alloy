import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "patch_orca_mobile_bootstrap", ROOT / "ci/patch_orca_mobile_bootstrap.py"
)
PATCHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PATCHER)


class OrcaMobileBootstrapPatchTests(unittest.TestCase):
    def test_pins_all_external_dependency_sources_and_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "build_all_deps_android.sh"
            script.write_text(
                'export ANDROID_SDK_ROOT="/home/cody/android-sdk"\n'
                'export ANDROID_NDK_ROOT="/home/cody/android-sdk/ndk/23.1.7779620"\n'
                'export N_CORES=$(nproc)\n'
                '    git clone https://github.com/syoyo/openvdb-android.git\n'
                '    git clone --recursive https://github.com/moritz-wundke/Boost-for-Android.git\n'
                '    git clone https://github.com/Open-Cascade-SAS/OCCT.git\n'
                'cp -r openvdb-android/dist/include/* "$JNI_IMPORTS_DIR/oneTBB/include/"\n'
                'cp -r include/opencascade/* "$JNI_IMPORTS_DIR/../occt/include/$ABI/"\n'
                './build-android.sh --boost=1.85.0 $ANDROID_NDK_ROOT\n'
            )
            with patch.object(PATCHER.sys, "argv", [
                "patch_orca_mobile_bootstrap.py", str(script), "/sdk", "/ndk"
            ]):
                PATCHER.main()
            result = script.read_text()
            self.assertIn('ANDROID_SDK_ROOT="/sdk"', result)
            self.assertIn('ANDROID_NDK_ROOT="/ndk"', result)
            self.assertIn(PATCHER.OPENVDB_ANDROID_SHA, result)
            self.assertIn(PATCHER.BOOST_ANDROID_SHA, result)
            self.assertIn(PATCHER.OCCT_SHA, result)
            self.assertIn(
                './build-android.sh --arch=arm64-v8a --target-version=26 '
                '--boost=1.85.0 $ANDROID_NDK_ROOT',
                result,
            )
            self.assertIn(
                'export N_CORES="${N_CORES:-$(getconf _NPROCESSORS_ONLN '
                '2>/dev/null || echo 2)}"',
                result,
            )
            self.assertIn('mkdir -p "$JNI_IMPORTS_DIR/oneTBB/include/oneapi"', result)
            self.assertIn('ln -s ../tbb "$JNI_IMPORTS_DIR/oneTBB/include/oneapi/tbb"', result)
            self.assertIn('mkdir -p "$JNI_IMPORTS_DIR/../occt/src"', result)
            self.assertIn('cp -r "$WORK_DIR/OCCT/src/." "$JNI_IMPORTS_DIR/../occt/src/"', result)

    def test_fails_closed_when_a_pinned_anchor_is_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            script = Path(directory) / "build_all_deps_android.sh"
            script.write_text('export ANDROID_SDK_ROOT="/home/cody/android-sdk"\n')
            with self.assertRaises(SystemExit):
                PATCHER.replace_once(script.read_text(), "missing-anchor", "replacement")


if __name__ == "__main__":
    unittest.main()
