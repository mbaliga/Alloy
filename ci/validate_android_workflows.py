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
        "runs-on: macos-15-intel",
        "BAMBU_PROFILE_REPO:",
        "BAMBU_PROFILE_SHA:",
        "Verify pinned Bambu planning profiles",
        "bambu-profile-source app/src/main/assets/profiles/a1-0.4-pla-basic.json",
        "bambu-profile-source app/src/main/assets/profiles/p1s-0.4-pla-basic.json",
    ):
        require(ordinary_text, token, ordinary)
    if "assembleDebug" in ordinary_text or "connectedDebugAndroidTest" in ordinary_text:
        raise SystemExit(f"{ordinary}: ordinary CI must use its declared release test variant")
    if "profile: pixel_6" not in ordinary_text or "profile: Pixel 6" in ordinary_text:
        raise SystemExit(f"{ordinary}: emulator must use the stable avdmanager profile id pixel_6")
    if "ubuntu-24.04" in ordinary_text:
        raise SystemExit(f"{ordinary}: ordinary Android acceptance must use the stable macOS emulator host")
    if "99-kvm4all.rules" in ordinary_text or "disable-linux-hw-accel" in ordinary_text:
        raise SystemExit(f"{ordinary}: macOS Android acceptance must not carry Linux-only KVM configuration")

    native_text = native.read_text(encoding="utf-8")
    for token in (
        "gradle :app:assembleRelease -PalloyNativeEngine=true -PalloyCiDebugSign=true",
        "gradle :app:assembleReleaseAndroidTest -PalloyNativeEngine=true",
        "gradle :app:connectedReleaseAndroidTest -PalloyNativeEngine=true -PalloyCiDebugSign=true",
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk",
        "ci/patch_slicebeam_stage1_no_step.py slicebeam",
        "Run native release instrumentation through API-35 ARM translation",
        "Export native G3 evidence through API-35 ARM translation",
        "target: google_apis",
        "arch: x86_64",
        "disable-linux-hw-accel: false",
        "Enable hosted KVM permissions for native instrumentation",
        "99-kvm4all.rules",
        'MODE="0666"',
        "sudo udevadm control --reload-rules",
        "sudo udevadm trigger --name-match=kvm",
        "com.mbaliga.alloy.NativeEngineSmokeTest#exportsNativeG3EvidenceWhenRequested",
        "path-b-g3-evidence/overhang_support.gcode",
        "name: alloy-native-g3-evidence",
        "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java",
        "app/src/androidTest/**",
        "name: alloy-native-instrumentation-report",
        "if: always()",
        "Initialize pinned official libnoise source",
        "git submodule update --init --depth 1 third_party/orca-deps-libnoise",
        "ci/validate_official_orca_snapshot.py",
        "--libnoise-source-root third_party/orca-deps-libnoise",
        "BAMBU_PROFILE_REPO:",
        "BAMBU_PROFILE_SHA:",
        "Verify pinned Bambu planning profiles",
        "bambu-profile-source app/src/main/assets/profiles/a1-0.4-pla-basic.json",
        "bambu-profile-source app/src/main/assets/profiles/p1s-0.4-pla-basic.json",
    ):
        require(native_text, token, native)
    if "assembleDebug" in native_text or "app/build/outputs/apk/debug" in native_text:
        raise SystemExit(f"{native}: native workflow must use the declared release test variant")
    if native_text.count("profile: pixel_6") != 2 or "profile: Pixel 6" in native_text:
        raise SystemExit(f"{native}: translated-ARM emulator jobs must use the stable avdmanager profile id pixel_6")
    if native_text.count("disable-animations: false") != 2:
        raise SystemExit(f"{native}: both translated-ARM emulator jobs must avoid optional post-boot animation writes")
    if native_text.count("target: google_apis") != 2 or native_text.count("arch: x86_64") != 2:
        raise SystemExit(f"{native}: native runtime must use API-35 ARM translation on a bootable x86_64 host")
    if native_text.count("disable-linux-hw-accel: false") != 2:
        raise SystemExit(f"{native}: translated-ARM emulator jobs must retain Linux hardware acceleration")
    if native_text.count("sudo udevadm trigger --name-match=kvm") != 1:
        raise SystemExit(f"{native}: native runtime must configure hosted-runner KVM permissions once before both emulator boots")
    if "group: alloy-native-${{ github.ref }}" not in native_text or "cancel-in-progress: false" not in native_text:
        raise SystemExit(f"{native}: native source builds must queue instead of cancelling live diagnostic runs")
    if native_text.count("app/src/androidTest/**") != 2:
        raise SystemExit(f"{native}: every full-suite native trigger must include all instrumentation tests")
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
        "BAMBU_PROFILE_REPO:",
        "BAMBU_PROFILE_SHA:",
        "Verify pinned Bambu planning profiles",
        "bambu-profile-source app/src/main/assets/profiles/a1-0.4-pla-basic.json",
        "bambu-profile-source app/src/main/assets/profiles/p1s-0.4-pla-basic.json",
    ):
        require(release_text, token, release)
    release_bootstrap = release_text.find("python3 ci/patch_orca_mobile_bootstrap.py")
    release_scope = release_text.find("python3 ci/patch_slicebeam_stage1_no_step.py slicebeam")
    if release_bootstrap < 0 or release_scope < 0 or release_bootstrap > release_scope:
        raise SystemExit(f"{release}: bootstrap pinning must run before Stage 1 scope patch")

    print("validated matching Android CI-signed release, native release, and unsigned release-candidate variants")


if __name__ == "__main__":
    main()
