#!/usr/bin/env python3
"""Keep the Android release candidate tied to the source-built native path."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/android-release-candidate.yml"
RELEASE_DOC = ROOT / "docs/RELEASE.md"
APP_BUILD = ROOT / "app/build.gradle"
SIGNING_SCRIPT = ROOT / "ci/sign_android_release.sh"
READINESS_SCRIPT = ROOT / "ci/validate_release_readiness.py"


def main() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")
    release_doc = RELEASE_DOC.read_text(encoding="utf-8")
    signing_text = SIGNING_SCRIPT.read_text(encoding="utf-8")
    readiness_text = READINESS_SCRIPT.read_text(encoding="utf-8")
    required = (
        "SLICEBEAM_REPO:",
        "SLICEBEAM_SHA:",
        "ORCA_MOBILE_REPO:",
        "ORCA_MOBILE_SHA:",
        "ORCA_PROFILE_REPO:",
        "ORCA_PROFILE_SHA:",
        "Verify pinned Orca profile snapshot",
        "ci/verify_profile_snapshot.py orca-profile-source app/src/main/assets/profiles/a1-mini-0.4-pla-basic.json",
        '"ndk;${NDK_VERSION}"',
        '"cmake;3.22.1"',
        "build_all_deps_android.sh",
        "ci/validate_native_dependency_inputs.py arm64-v8a",
        "ci/patch_orca_mobile_bootstrap.py",
        "ci/patch_slicebeam_stage1_no_step.py slicebeam",
        "ci/validate_native_jni_contract.py",
        "ci/build_gmp_mpfr_android.sh app/src/main arm64-v8a 26",
        "ci/validate_native_prebuilts.py arm64-v8a --source-built",
        "jniImports/boost",
        "jniImports/oneTBB",
        "mkdir -p app/src/main/jniImports/boost app/src/main/jniImports/oneTBB",
        "src/main/occt",
        "assembleRelease :app:bundleRelease -PalloyNativeEngine=true",
        "assembleReleaseAndroidTest -PalloyNativeEngine=true",
        "alloy-android-release-instrumentation",
        "-PalloyNativeEngineVerified=false",
        "Sign release-candidate artifacts",
        "github.event_name == 'push' || github.event_name == 'workflow_dispatch'",
        "ci/sign_android_release.sh app/build/outputs/apk/release app/build/outputs/apk/androidTest/release app/build/outputs/bundle/release",
        "ANDROID_KEYSTORE_B64",
        "ANDROID_KEY_ALIAS",
        "ANDROID_KEYSTORE_PASSWORD",
        "ANDROID_KEY_PASSWORD",
        "ci/validate_release_readiness.py release/production-readiness.json",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise SystemExit(f"{WORKFLOW}: missing native release wiring: {missing}")
    signing_required = (
        "APP_CERT=", "TEST_CERT=", "SIGNED_BUNDLE=", "jarsigner", "-signedjar",
        "release and instrumentation APK signer certificates do not match",
        'verify --print-certs',
    )
    missing_signing = [token for token in signing_required if token not in signing_text]
    if missing_signing:
        raise SystemExit(f"{SIGNING_SCRIPT}: missing signer-pair verification: {missing_signing}")
    for token in ("REQUIRED_GATES", "physical_a1_mini_transport", "bambu_treesupport3d_parity"):
        if token not in readiness_text:
            raise SystemExit(f"{READINESS_SCRIPT}: missing production gate: {token}")
    build_text = APP_BUILD.read_text(encoding="utf-8")
    build_required = (
        "validateNativePrebuiltDirectory",
        "tasks.named('preBuild').configure { dependsOn(validateNativePrebuiltDirectory) }",
        "includeSuppliedReferenceVisuals",
        ": true",
        "file('src/debug/assets')",
    )
    missing_build = [token for token in build_required if token not in build_text]
    if missing_build:
        raise SystemExit(f"{APP_BUILD}: missing native prebuilt guard wiring: {missing_build}")
    visual_assets = (
        ROOT / "app/src/debug/assets/visuals/a1-mini-reference.mesh",
        ROOT / "app/src/debug/assets/models/a1-mini-v5-owner.3mf",
    )
    missing_visuals = [str(path) for path in visual_assets if not path.is_file()]
    if missing_visuals:
        raise SystemExit(f"release visual study assets are missing: {missing_visuals}")
    for token in ("RECOVERY_REQUIRED", "must not automatically retry", "offline recovery"):
        if token not in release_doc:
            raise SystemExit(f"{RELEASE_DOC}: missing interruption recovery gate: {token}")
    print("validated native source build is required for the Android release candidate")


if __name__ == "__main__":
    main()
