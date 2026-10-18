#!/usr/bin/env python3
"""Validate that Android CI builds and tests the same installable variant.

Ordinary verification uses the release variant with the Android debug key so
the emulator can install it. The explicit release-candidate workflow omits
that property and remains unsigned until the organization's signing service is
configured. Both paths are checked for matching app/test artifacts; neither
confuses a CI key with a production signing key.
"""

from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
HANDY_PACKAGE = "bbl.intl.bambulab.com"


def validate_handy_visibility() -> None:
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    manifest = ET.parse(manifest_path).getroot()
    queries = manifest.find("queries")
    visible_packages = set()
    if queries is not None:
        visible_packages = {
            node.get(ANDROID_NS + "name")
            for node in queries.findall("package")
        }
    if HANDY_PACKAGE not in visible_packages:
        raise SystemExit(f"{manifest_path}: missing narrow Bambu Handy package visibility query")
    broad_visibility = any(
        node.get(ANDROID_NS + "name") == "android.permission.QUERY_ALL_PACKAGES"
        for node in manifest.findall("uses-permission")
    )
    if broad_visibility:
        raise SystemExit(f"{manifest_path}: broad package visibility is not allowed")
    handoff_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BambuHandyHandoff.java"
    if f'PACKAGE_NAME = "{HANDY_PACKAGE}"' not in handoff_path.read_text(encoding="utf-8"):
        raise SystemExit(f"{handoff_path}: package visibility query and handoff target must match")




def require(text: str, token: str, path: Path) -> None:
    if token not in text:
        raise SystemExit(f"{path}: missing Android workflow contract: {token}")


def validate_pr_trigger_deduplication(workflow: Path, text: str) -> None:
    on_section_match = re.search(r"(?ms)^on:\n(.*?)(?=^[A-Za-z][A-Za-z0-9_-]*:|\Z)", text)
    if on_section_match is None:
        raise SystemExit(f"{workflow}: missing workflow trigger section")
    on_section = on_section_match.group(1)
    for trigger in ("push", "pull_request", "workflow_dispatch"):
        if re.search(rf"(?m)^  {re.escape(trigger)}:\s*(?:#.*)?$", on_section) is None:
            raise SystemExit(f"{workflow}: expected {trigger} trigger must remain enabled")

    push_match = re.search(
        r"(?ms)^  push:\n(?P<body>(?:^ {4,}.*\n|^\s*$)*)",
        on_section,
    )
    if push_match is None:
        raise SystemExit(f"{workflow}: missing push trigger configuration")
    branches_match = re.search(r"(?m)^    branches:\s*\[([^]]*)\]\s*$", push_match.group("body"))
    if branches_match is None:
        raise SystemExit(f"{workflow}: push trigger must keep an explicit branch filter")
    branches = [branch.strip().strip("'\" ") for branch in branches_match.group(1).split(",")]
    if "main" not in branches or "codex/**" in branches:
        raise SystemExit(f"{workflow}: push must include main and exclude codex/** (PRs build through pull_request)")


def main() -> None:
    validate_handy_visibility()
    ordinary = ROOT / ".github/workflows/android-v1.yml"
    native = ROOT / ".github/workflows/alloy-native-build.yml"
    release = ROOT / ".github/workflows/android-release-candidate.yml"

    setup_java_workflows = (
        ordinary,
        native,
        release,
        ROOT / ".github/workflows/g1-orca-mobile-source-build.yml",
        ROOT / ".github/workflows/path-b-g1-slicebeam-source-build.yml",
    )
    for workflow in setup_java_workflows:
        workflow_text = workflow.read_text(encoding="utf-8")
        if "uses: actions/setup-java@v6" not in workflow_text:
            raise SystemExit(f"{workflow}: use supported actions/setup-java@v6")
        if "actions/setup-java@v4" in workflow_text:
            raise SystemExit(f"{workflow}: deprecated actions/setup-java@v4 is not allowed")

    ordinary_text = ordinary.read_text(encoding="utf-8")
    acceptance_runner = ROOT / "ci/run_android_acceptance_with_diagnostics.sh"
    acceptance_runner_text = acceptance_runner.read_text(encoding="utf-8")
    validate_pr_trigger_deduplication(ordinary, ordinary_text)
    for token in (
        "gradle :app:assembleRelease -PalloyCiDebugSign=true --no-daemon --stacktrace",
        "script: bash ci/run_android_acceptance_with_diagnostics.sh",
        "name: alloy-v1-release-ci-apk",
        "app/build/outputs/apk/release/app-release.apk",
        "disable-animations: false",
        "Install Android SDK and preinstall Emulator with retries",
        'sdkmanager --install "emulator" --channel=0',
        "for attempt in 1 2 3",
        "${ANDROID_SDK_ROOT}/emulator/emulator",
        "runs-on: macos-15-intel",
        "BAMBU_PROFILE_REPO:",
        "BAMBU_PROFILE_SHA:",
        "Verify pinned Bambu planning profiles",
        "bambu-profile-source app/src/main/assets/profiles/a1-0.4-pla-basic.json",
        "bambu-profile-source app/src/main/assets/profiles/p1s-0.4-pla-basic.json",
        "python3 ci/validate_learning_assets.py",
        "name: Upload Android acceptance diagnostics\n        if: always()\n        uses: actions/upload-artifact@v4",
        "name: alloy-android-acceptance-diagnostics",
        "app/build/reports/androidTests/connected/",
        "app/build/outputs/androidTest-results/connected/",
    ):
        require(ordinary_text, token, ordinary)
    for token in (
        "gradle :app:connectedReleaseAndroidTest -PalloyCiDebugSign=true --no-daemon --stacktrace",
        "app/build/reports/androidTests/connected",
        "gradle-acceptance.log",
        "cat \"$log_file\"",
        "exit \"$status\"",
    ):
        require(acceptance_runner_text, token, acceptance_runner)
    if "assembleDebug" in ordinary_text or "connectedDebugAndroidTest" in ordinary_text:
        raise SystemExit(f"{ordinary}: ordinary CI must use its declared release test variant")
    if "profile: pixel_6" not in ordinary_text or "profile: Pixel 6" in ordinary_text:
        raise SystemExit(f"{ordinary}: emulator must use the stable avdmanager profile id pixel_6")
    if "ubuntu-24.04" in ordinary_text:
        raise SystemExit(f"{ordinary}: ordinary Android acceptance must use the stable macOS emulator host")
    if "99-kvm4all.rules" in ordinary_text or "disable-linux-hw-accel" in ordinary_text:
        raise SystemExit(f"{ordinary}: macOS Android acceptance must not carry Linux-only KVM configuration")

    native_text = native.read_text(encoding="utf-8")
    validate_pr_trigger_deduplication(native, native_text)
    native_instrumentation = ROOT / "ci/run_native_release_instrumentation.sh"
    native_instrumentation_text = native_instrumentation.read_text(encoding="utf-8")
    for token in (
        "gradle :app:connectedReleaseAndroidTest -PalloyNativeEngine=true -PalloyCiDebugSign=true",
        "-PalloyAdbTimeoutMs=900000",
        "test_status=$?",
    ):
        require(native_instrumentation_text, token, native_instrumentation)
    app_build = ROOT / "app/build.gradle"
    app_build_text = app_build.read_text(encoding="utf-8")
    require(app_build_text, "if (physicalPilot)", app_build)
    require(app_build_text, "applicationIdSuffix '.pilot'", app_build)
    require(app_build_text, "project.findProperty('alloyAdbTimeoutMs')", app_build)
    require(app_build_text, 'timeOutInMs = adbTimeoutInMs.toString().toInteger()', app_build)
    template_probe_script = ROOT / "ci/run_machine_template_probe.sh"
    template_probe_script_text = template_probe_script.read_text(encoding="utf-8")
    for token in (
        "com.mbaliga.alloy.BambuMachineTemplateProbeTest",
        "ci/validate_machine_template_probe_output.py",
        "trap finish_probe EXIT",
        "collect_diagnostics",
        "--exit-code \"$original_status\"",
        "ALLOY_TEMPLATE_PROBE_OUTPUT_DIR",
        "instrumentation.txt",
        "logcat-tail.txt",
    ):
        require(template_probe_script_text, token, template_probe_script)
    for token in (
        "gradle :app:assembleRelease -PalloyNativeEngine=true -PalloyOfficialOrcaCompileProbe=true",
        "gradle :app:assembleReleaseAndroidTest -PalloyNativeEngine=true -PalloyOfficialOrcaCompileProbe=true",
        "script: bash ci/run_native_release_instrumentation.sh",
        
        "app/build/outputs/apk/release/app-release.apk",
        "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk",
        "ci/patch_slicebeam_stage1_no_step.py slicebeam",
        "Run native release instrumentation through API-35 ARM translation",
        "Export native G3 evidence through API-35 ARM translation",
        "python3 ci/verify_machine_template_probe.py orca-profile-source",
        "Initialize standalone Bambu template probe evidence",
        "Render pinned Bambu templates independently",
        "Test Bambu template probe evidence and failure collection",
        "parity/test_machine_template_probe_output.py",
        "parity/test_machine_template_probe_script.py",
        "parity/test_machine_template_probe_diagnostic.py",
        "Compare Bambu template probe with exact-head desktop reference (diagnostic only)",
        "continue-on-error: true",
        "ci/compare_machine_template_probe.py",
        "alloy-bambu-template-probe-diagnostic",
        "if: always() && !cancelled()",
        "script: bash ci/run_machine_template_probe.sh",
        "name: alloy-bambu-template-rendering-probe",
        "target: google_apis",
        "arch: x86_64",
        "disable-linux-hw-accel: ${{ steps.emulator-acceleration.outputs.disable-hw-accel }}",
        "Configure Android emulator acceleration",
        'sdkmanager --install "emulator" --channel=0',
        "for attempt in 1 2 3",
        "${ANDROID_SDK_ROOT}/emulator/emulator",
        'echo "disable-hw-accel=true" >> "$GITHUB_OUTPUT"',
        'echo "disable-hw-accel=false" >> "$GITHUB_OUTPUT"',
        "99-kvm4all.rules",
        'MODE="0666"',
        "sudo udevadm control --reload-rules",
        "sudo udevadm trigger --name-match=kvm",
        "com.mbaliga.alloy.NativeEngineSmokeTest#exportsNativeG3EvidenceWhenRequested",
        "# Keep this POSIX-only: nesting a quoted Bash script causes the",
        "set -eu",
        "path-b-g3-evidence/overhang_support.gcode",
        "name: alloy-native-g3-evidence",
        "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java",
        "app/src/androidTest/**",
        "Build isolated A1 Mini physical-pilot APK pair",
        "-PalloyPhysicalPilot=true",
        'test "$pilot_app_id" = "com.mbaliga.alloy.pilot"',
        'test "$pilot_test_id" = "com.mbaliga.alloy.pilot.test"',
        "Run five-fixture pilot evidence on API-35 ARM translation",
        "com.mbaliga.alloy.NoSupportPlaPilotTest#nativeNoSupportPilotFixturesRemainInspectableAndPackageable",
        "com.mbaliga.alloy.pilot.test/androidx.test.runner.AndroidJUnitRunner",
        "/sdcard/Android/data/com.mbaliga.alloy.pilot/files/alloy-no-support-pilot/.",
        "export-no-support-pilot true",
        "physical_qualification",
        "package_preflight",
        "name: alloy-a1-mini-physical-pilot-ci",
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
        "python3 ci/validate_learning_assets.py",
    ):
        require(native_text, token, native)
    probe_comparator = ROOT / "ci/compare_machine_template_probe.py"
    probe_comparator_text = probe_comparator.read_text(encoding="utf-8")
    for token in (
        '"diagnostic_only": True',
        '"non_gating": True',
        '"strict_g3_gate": "separate; not modified by this diagnostic"',
        'return 0',
        "validate_reference_manifest",
    ):
        require(probe_comparator_text, token, probe_comparator)
    for path in (
        "ci/machine_template_probe_attempt.py",
        "ci/verify_machine_template_probe.py",
        "ci/validate_machine_template_probe_output.py",
        "ci/run_machine_template_probe.sh",
        "ci/compare_machine_template_probe.py",
        "parity/test_machine_template_probe_output.py",
        "parity/test_machine_template_probe_script.py",
        "parity/test_machine_template_probe_diagnostic.py",
    ):
        if native_text.count(f"- '{path}'") != 2:
            raise SystemExit(f"{native}: both push and pull_request filters must run the probe contract for {path}")
    if "assembleDebug" in native_text or "app/build/outputs/apk/debug" in native_text:
        raise SystemExit(f"{native}: native workflow must use the declared release test variant")
    if native_text.count("profile: pixel_6") != 4 or "profile: Pixel 6" in native_text:
        raise SystemExit(f"{native}: translated-ARM emulator jobs must use the stable avdmanager profile id pixel_6")
    if native_text.count("disable-animations: false") != 4:
        raise SystemExit(f"{native}: both translated-ARM emulator jobs must avoid optional post-boot animation writes")
    if native_text.count('emulator-boot-timeout: "1800"') != 4:
        raise SystemExit(f"{native}: each API-35 ARM-translation emulator boot must allow the extended hosted-runner window")
    native_runtime = native_text.find("name: Run native release instrumentation through API-35 ARM translation")
    for artifact in ("alloy-native-release-ci-apk", "alloy-native-instrumentation-apk"):
        artifact_position = native_text.find(f"name: {artifact}")
        if artifact_position < 0 or artifact_position > native_runtime:
            raise SystemExit(f"{native}: {artifact} must upload before emulator startup so build evidence survives boot failures")
    if native_text.count("target: google_apis") != 4 or native_text.count("arch: x86_64") != 4:
        raise SystemExit(f"{native}: native runtime must use API-35 ARM translation on a bootable x86_64 host")
    if native_text.count("disable-linux-hw-accel: ${{ steps.emulator-acceleration.outputs.disable-hw-accel }}") != 4:
        raise SystemExit(f"{native}: translated-ARM emulator jobs must select KVM when available and allow software fallback")
    if native_text.count("sudo udevadm trigger --name-match=kvm") != 1:
        raise SystemExit(f"{native}: native runtime must configure hosted-runner KVM permissions once before emulator boots")
    if (
        "group: alloy-native-${{ github.ref }}-${{ github.event.pull_request.head.sha || github.sha }}"
        not in native_text
        or "cancel-in-progress: false" not in native_text
    ):
        raise SystemExit(f"{native}: preserve live diagnostics while isolating native runs by source revision")
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
        "python3 ci/validate_learning_assets.py",
    ):
        require(release_text, token, release)
    release_bootstrap = release_text.find("python3 ci/patch_orca_mobile_bootstrap.py")
    release_scope = release_text.find("python3 ci/patch_slicebeam_stage1_no_step.py slicebeam")
    if release_bootstrap < 0 or release_scope < 0 or release_bootstrap > release_scope:
        raise SystemExit(f"{release}: bootstrap pinning must run before Stage 1 scope patch")

    print("validated Android QA, native runtime, isolated A1 Mini pilot, and unsigned release-candidate variants")


if __name__ == "__main__":
    main()

