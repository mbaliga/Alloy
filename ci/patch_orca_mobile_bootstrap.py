#!/usr/bin/env python3
"""Make OrcaSlicer-Mobile's native dependency bootstrap reproducible in Alloy CI.

This does not modify Alloy runtime code. It patches a fresh, detached upstream
checkout only for the G1 build gate:
- replaces maintainer-local Android SDK/NDK paths with CI paths;
- pins dependency repositories that upstream otherwise clones from moving heads.
"""

from pathlib import Path
import sys

OPENVDB_ANDROID_SHA = "4d4a057d0a26d9cff88d6d7cc7bea80d27ffa7ec"
BOOST_ANDROID_SHA = "7943955c4d11a5bd61381a8b200c28619323eb0f"
OCCT_SHA = "7d2efad9c8a9a57ea96c4c8587134b34dd503cd8"
BOOST_ANDROID_ARCH = "arm64-v8a"
BOOST_ANDROID_TARGET_VERSION = "26"


def replace_once(text: str, old: str, new: str) -> str:
    if old not in text:
        raise SystemExit(f"Expected bootstrap text not found: {old!r}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 4:
        raise SystemExit(
            "usage: patch_orca_mobile_bootstrap.py <script> <sdk-root> <ndk-root>"
        )

    path = Path(sys.argv[1])
    sdk_root = sys.argv[2]
    ndk_root = sys.argv[3]
    text = path.read_text()

    text = replace_once(
        text,
        'export ANDROID_SDK_ROOT="/home/cody/android-sdk"',
        f'export ANDROID_SDK_ROOT="{sdk_root}"',
    )
    text = replace_once(
        text,
        'export ANDROID_NDK_ROOT="/home/cody/android-sdk/ndk/23.1.7779620"',
        f'export ANDROID_NDK_ROOT="{ndk_root}"',
    )

    text = replace_once(
        text,
        "    git clone https://github.com/syoyo/openvdb-android.git\n",
        "    git clone https://github.com/syoyo/openvdb-android.git\n"
        f"    git -C openvdb-android checkout --detach {OPENVDB_ANDROID_SHA}\n",
    )
    text = replace_once(
        text,
        "    git clone --recursive https://github.com/moritz-wundke/Boost-for-Android.git\n",
        "    git clone --recursive https://github.com/moritz-wundke/Boost-for-Android.git\n"
        f"    git -C Boost-for-Android checkout --detach {BOOST_ANDROID_SHA}\n"
        "    git -C Boost-for-Android submodule update --init --recursive\n",
    )
    text = replace_once(
        text,
        "    git clone https://github.com/Open-Cascade-SAS/OCCT.git\n",
        "    git clone https://github.com/Open-Cascade-SAS/OCCT.git\n"
        f"    git -C OCCT checkout --detach {OCCT_SHA}\n",
    )

    text = replace_once(
        text,
        "export N_CORES=$(nproc)\n",
        'export N_CORES="${N_CORES:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"\n',
    )
    text = replace_once(
        text,
        "./build-android.sh --boost=1.85.0 $ANDROID_NDK_ROOT\n",
        "./build-android.sh --arch=arm64-v8a --target-version=26 --boost=1.85.0 $ANDROID_NDK_ROOT\n",
    )
    text = replace_once(
        text,
        'cp -r openvdb-android/dist/include/* "$JNI_IMPORTS_DIR/oneTBB/include/"\n',
        'cp -r openvdb-android/dist/include/* "$JNI_IMPORTS_DIR/oneTBB/include/"\n'
        'mkdir -p "$JNI_IMPORTS_DIR/oneTBB/include/oneapi"\n'
        'ln -s ../tbb "$JNI_IMPORTS_DIR/oneTBB/include/oneapi/tbb"\n',
    )
    text = replace_once(
        text,
        'cp -r include/opencascade/* "$JNI_IMPORTS_DIR/../occt/include/$ABI/"\n',
        'cp -r include/opencascade/* "$JNI_IMPORTS_DIR/../occt/include/$ABI/"\n'
        'mkdir -p "$JNI_IMPORTS_DIR/../occt/src"\n'
        'cp -r "$WORK_DIR/OCCT/src/." "$JNI_IMPORTS_DIR/../occt/src/"\n',
    )

    path.write_text(text)

    print("Patched bootstrap with:")
    print(f"  OpenVDB-Android {OPENVDB_ANDROID_SHA}")
    print(f"  Boost-for-Android {BOOST_ANDROID_SHA}")
    print(f"  Boost ABI {BOOST_ANDROID_ARCH}")
    print(f"  Boost target API {BOOST_ANDROID_TARGET_VERSION}")
    print(f"  OCCT {OCCT_SHA}")
    print(f"  Android SDK {sdk_root}")
    print(f"  Android NDK {ndk_root}")


if __name__ == "__main__":
    main()
