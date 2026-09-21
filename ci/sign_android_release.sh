#!/usr/bin/env bash
set -euo pipefail

# Sign only the release artifacts produced by the current workflow. All secret
# values are read from the process environment and the temporary keystore is
# removed on exit; no signing material is written to the repository or an
# uploaded artifact.

RELEASE_DIR=${1:?usage: sign_android_release.sh <release-dir> <android-test-dir>}
TEST_DIR=${2:?usage: sign_android_release.sh <release-dir> <android-test-dir>}
BUILD_TOOLS_VERSION=${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}
ANDROID_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}

: "${ANDROID_KEYSTORE_B64:?ANDROID_KEYSTORE_B64 is required}"
: "${ANDROID_KEY_ALIAS:?ANDROID_KEY_ALIAS is required}"
: "${ANDROID_KEYSTORE_PASSWORD:?ANDROID_KEYSTORE_PASSWORD is required}"
: "${ANDROID_KEY_PASSWORD:?ANDROID_KEY_PASSWORD is required}"

if [[ -z "$ANDROID_ROOT" ]]; then
    echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
    exit 2
fi

APK_SIGNER="$ANDROID_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"
if [[ ! -x "$APK_SIGNER" ]]; then
    echo "missing Android apksigner: $APK_SIGNER" >&2
    exit 2
fi

if [[ ! -f "$RELEASE_DIR/app-release-unsigned.apk" ]]; then
    echo "missing unsigned release APK" >&2
    exit 2
fi
if [[ ! -f "$TEST_DIR/app-release-androidTest.apk" ]]; then
    echo "missing release instrumentation APK" >&2
    exit 2
fi

TEMP_ROOT=$(mktemp -d)
KEYSTORE="$TEMP_ROOT/release.keystore"
SIGNED_APP="$TEMP_ROOT/app-release.apk"
SIGNED_TEST="$TEMP_ROOT/app-release-androidTest.apk"
trap 'rm -rf "$TEMP_ROOT"' EXIT
umask 077
printf '%s' "$ANDROID_KEYSTORE_B64" | base64 --decode > "$KEYSTORE"

COMMON_ARGS=(
    --ks "$KEYSTORE"
    --ks-key-alias "$ANDROID_KEY_ALIAS"
    --ks-pass "env:ANDROID_KEYSTORE_PASSWORD"
    --key-pass "env:ANDROID_KEY_PASSWORD"
)

"$APK_SIGNER" sign "${COMMON_ARGS[@]}" --out "$SIGNED_APP" "$RELEASE_DIR/app-release-unsigned.apk"
"$APK_SIGNER" sign "${COMMON_ARGS[@]}" --out "$SIGNED_TEST" "$TEST_DIR/app-release-androidTest.apk"
"$APK_SIGNER" verify --verbose "$SIGNED_APP" >/dev/null
"$APK_SIGNER" verify --verbose "$SIGNED_TEST" >/dev/null

# The instrumentation APK is installed alongside the app during release QA.
# Compare the actual signer digests before replacing the workflow outputs so a
# mismatched pair cannot be promoted as a release test harness.
APP_CERT=$("$APK_SIGNER" verify --print-certs "$SIGNED_APP" 2>/dev/null \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest/ { print $2; exit }')
TEST_CERT=$("$APK_SIGNER" verify --print-certs "$SIGNED_TEST" 2>/dev/null \
    | awk -F': ' '/Signer #1 certificate SHA-256 digest/ { print $2; exit }')
if [[ -z "$APP_CERT" || -z "$TEST_CERT" || "$APP_CERT" != "$TEST_CERT" ]]; then
    echo "release and instrumentation APK signer certificates do not match" >&2
    exit 1
fi

# Replace only the ephemeral workflow outputs after both signatures verify.
mv "$SIGNED_APP" "$RELEASE_DIR/app-release.apk"
mv "$SIGNED_TEST" "$TEST_DIR/app-release-androidTest.apk"
rm -f "$RELEASE_DIR/app-release-unsigned.apk"

echo "signed and verified release app plus matching instrumentation APK"
