#!/usr/bin/env python3
"""Validate that Android CI builds and tests the same installable variant.

Ordinary verification uses the release variant with the Android debug key so
the emulator can install it. The explicit release-candidate workflow omits
that property and remains unsigned until the organization's signing service is
configured. Both paths are checked for matching app/test artifacts; neither
confuses a CI key with a production signing key.
"""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, token: str, path: Path) -> None:
    if token not in text:
        raise SystemExit(f"{path}: missing Android workflow contract: {token}")


def main() -> None:
    ordinary = ROOT / ".github/workflows/android-v1.yml"
    native = ROOT / ".github/workflows/alloy-native-build.yml"
    release = ROOT / ".github/workflows/android-release-candidate.yml"

    ordinary_text = ordinary.read_text(encoding="utf-8")
    for token in (
        "gradle :app:assembleRelease -PalloyCiDebugSign=true --no-daemon --stacktrace",
        "gradle :app:connectedReleaseAndroidTest -PalloyCiDebugSign=true --no-daemon --stacktrace",
        "name: alloy-v1-release-ci-apk",
        "app/build/outputs/apk/release/app-release.apk",
        "disable-animations: false",
    ):
        require(ordinary_text, token, ordinary)
    if "assembleDebug" in ordinary_text or "connectedDebugAndroidTest" in ordinary_text:
        raise SystemExit(f"{ordinary}: ordinary CI must use its declared release test variant")
    if "profile: pixel_6" not in ordinary_text or "profile: Pixel 6" in ordinary_text:
        raise SystemExit(f"{ordinary}: emulator must use the stable avdmanager profile id pixel_6")

    native_text = native.read_text(encoding="utf-8")
    for token in (
        "gradle :app:assembleRelease -PalloyNativeEngine=true -PalloyCiDebugSign=true",
        "gradle :app:assembleReleaseAndroidTest -PalloyNativeEngine=true",
        "gradle :app:connectedReleaseAndroidTest -PalloyNativeEngine=true -PalloyCiDebugSign=true",
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk",
        "ci/patch_slicebeam_stage1_no_step.py slicebeam",
        "Export native G3 evidence from the arm64 emulator",
        "com.mbaliga.alloy.NativeEngineSmokeTest#exportsNativeG3EvidenceWhenRequested",
        "path-b-g3-evidence/overhang_support.gcode",
        "name: alloy-native-g3-evidence",
    ):
        require(native_text, token, native)
    if "assembleDebug" in native_text or "app/build/outputs/apk/debug" in native_text:
        raise SystemExit(f"{native}: native workflow must use the declared release test variant")
    if native_text.count("profile: pixel_6") != 2 or "profile: Pixel 6" in native_text:
        raise SystemExit(f"{native}: arm64 emulator jobs must use the stable avdmanager profile id pixel_6")
    native_bootstrap = native_text.find("python3 ci/patch_orca_mobile_bootstrap.py")
    native_scope = native_text.find("python3 ci/patch_slicebeam_stage1_no_step.py slicebeam")
    if native_bootstrap < 0 or native_scope < 0 or native_bootstrap > native_scope:
        raise SystemExit(f"{native}: bootstrap pinning must run before Stage 1 scope patch")

    release_text = release.read_text(encoding="utf-8")
    for token in (
        # The release-candidate job intentionally builds the APK and bundle
        # in one Gradle invocation. Match the task contract independently of
        # whether assembleRelease is followed by bundleRelease on that line.
        "gradle :app:assembleRelease",
        "-PalloyNativeEngine=true",
        "gradle :app:assembleReleaseAndroidTest -PalloyNativeEngine=true",
        "app/build/outputs/apk/release/*.apk",
        "app/build/outputs/apk/androidTest/release",
        "ci/patch_slicebeam_stage1_no_step.py slicebeam",
    ):
        require(release_text, token, release)
    release_bootstrap = release_text.find("python3 ci/patch_orca_mobile_bootstrap.py")
    release_scope = release_text.find("python3 ci/patch_slicebeam_stage1_no_step.py slicebeam")
    if release_bootstrap < 0 or release_scope < 0 or release_bootstrap > release_scope:
        raise SystemExit(f"{release}: bootstrap pinning must run before Stage 1 scope patch")

    print("validated matching Android CI-signed release, native release, and unsigned release-candidate variants")


if __name__ == "__main__":
    main()
