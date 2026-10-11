from __future__ import annotations

import unittest

from ci import patch_orca_mobile_bootstrap


BASE_BOOTSTRAP = '''export ANDROID_SDK_ROOT="/home/cody/android-sdk"
export ANDROID_NDK_ROOT="/home/cody/android-sdk/ndk/23.1.7779620"
    git clone https://github.com/syoyo/openvdb-android.git
    git clone --recursive https://github.com/moritz-wundke/Boost-for-Android.git
    git clone https://github.com/Open-Cascade-SAS/OCCT.git
export N_CORES=$(nproc)
./build-android.sh --boost=1.85.0 $ANDROID_NDK_ROOT
cd Boost-for-Android
# Note: Boost-for-Android requires specific configurations for NDK versions.
cp -r openvdb-android/dist/include/* "$JNI_IMPORTS_DIR/oneTBB/include/"
cp -r include/opencascade/* "$JNI_IMPORTS_DIR/../occt/include/$ABI/"
'''


class PatchOrcaMobileBootstrapTests(unittest.TestCase):
    def test_locale_is_opt_in_and_pinned(self) -> None:
        ordinary = patch_orca_mobile_bootstrap.patch_script(BASE_BOOTSTRAP, "/sdk", "/ndk")
        self.assertNotIn("--with-libraries=locale", ordinary)

        native_probe = patch_orca_mobile_bootstrap.patch_script(
            BASE_BOOTSTRAP, "/sdk", "/ndk", boost_locale=True
        )
        self.assertIn("--with-libraries=locale", native_probe)
        self.assertIn("patch_boost_android_locale.py", native_probe)
        self.assertIn("ANDROID_SDK_ROOT=\"/sdk\"", native_probe)
        self.assertIn("ANDROID_NDK_ROOT=\"/ndk\"", native_probe)


if __name__ == "__main__":
    unittest.main()
