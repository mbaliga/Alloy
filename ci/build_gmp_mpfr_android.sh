#!/usr/bin/env bash
set -euo pipefail

# Build the arithmetic libraries required by CGAL/libslic3r for the supported
# Android phone ABI. The archives, URLs and hashes are deliberately pinned;
# do not replace this with an APK extraction step.
GMP_URL="https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2"
GMP_SHA256="eae9326beb4158c386e39a356818031bd28f3124cf915f8c5b1dc4c7a36b4d7c"
MPFR_URL="https://www.mpfr.org/mpfr-4.2.2/mpfr-4.2.2.tar.bz2"
MPFR_SHA256="9ad62c7dc910303cd384ff8f1f4767a655124980bb6d8650fe62c815a231bb7b"

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "usage: build_gmp_mpfr_android.sh <app-src-root> [abi] [api-level]" >&2
  exit 2
fi

APP_SRC_ROOT="$1"
ABI="${2:-arm64-v8a}"
API_LEVEL="${3:-26}"
if [[ "$ABI" != "arm64-v8a" ]]; then
  echo "only arm64-v8a is supported by the native engine gate" >&2
  exit 2
fi
if ! [[ "$API_LEVEL" =~ ^[0-9]+$ ]] || (( API_LEVEL < 26 )); then
  echo "Android API level must be an integer >= 26" >&2
  exit 2
fi

NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}"
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "ANDROID_NDK_ROOT or ANDROID_NDK_HOME must point to the Android NDK" >&2
  exit 2
fi

case "$(uname -s)" in
  Darwin)
    HOST_TAGS=(darwin-arm64 darwin-x86_64)
    ;;
  Linux)
    HOST_TAGS=(linux-aarch64 linux-x86_64)
    ;;
  *)
    echo "unsupported host for the Android NDK: $(uname -s)" >&2
    exit 2
    ;;
esac

TOOLCHAIN=""
for host_tag in "${HOST_TAGS[@]}"; do
  candidate="$NDK_ROOT/toolchains/llvm/prebuilt/$host_tag"
  if [[ -d "$candidate" ]]; then
    TOOLCHAIN="$candidate"
    break
  fi
done
if [[ -z "$TOOLCHAIN" ]]; then
  echo "missing NDK LLVM toolchain for $(uname -s)/$(uname -m) under $NDK_ROOT" >&2
  exit 2
fi

case "$ABI" in
  arm64-v8a)
    TARGET="aarch64-linux-android"
    ;;
esac

JOBS="${ALLOY_NATIVE_JOBS:-2}"
if ! [[ "$JOBS" =~ ^[1-9][0-9]*$ ]]; then
  echo "ALLOY_NATIVE_JOBS must be a positive integer" >&2
  exit 2
fi

sha256_file() {
  local path="$1"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$path" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$path" | awk '{print $1}'
  else
    echo "sha256sum or shasum is required to verify $path" >&2
    exit 2
  fi
}

WORK_PARENT="${RUNNER_TEMP:-${TMPDIR:-/tmp}}"
WORK_ROOT="$(mktemp -d "$WORK_PARENT/alloy-gmp-mpfr-${ABI}.XXXXXX")"
trap 'rm -rf "$WORK_ROOT"' EXIT
mkdir -p "$WORK_ROOT/src" "$WORK_ROOT/prefix"

download_and_verify() {
  local url="$1"
  local expected="$2"
  local output="$3"
  curl --fail --location --retry 3 --retry-delay 2 --silent --show-error "$url" -o "$output"
  local actual="$(sha256_file "$output")"
  if [[ "$actual" != "$expected" ]]; then
    echo "SHA-256 mismatch for $output: expected $expected, got $actual" >&2
    exit 1
  fi
}

download_and_verify "$GMP_URL" "$GMP_SHA256" "$WORK_ROOT/src/gmp-6.2.1.tar.bz2"
download_and_verify "$MPFR_URL" "$MPFR_SHA256" "$WORK_ROOT/src/mpfr-4.2.2.tar.bz2"
tar -xjf "$WORK_ROOT/src/gmp-6.2.1.tar.bz2" -C "$WORK_ROOT/src"
tar -xjf "$WORK_ROOT/src/mpfr-4.2.2.tar.bz2" -C "$WORK_ROOT/src"

CC="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang"
CXX="$TOOLCHAIN/bin/${TARGET}${API_LEVEL}-clang++"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
for tool in "$CC" "$CXX" "$AR" "$RANLIB"; do
  if [[ ! -x "$tool" ]]; then
    echo "missing Android toolchain executable: $tool" >&2
    exit 2
  fi
done

export CC CXX AR RANLIB
export CPP="$CC -E"
export CFLAGS="-O2 -fPIC -ffunction-sections -fdata-sections -D__ANDROID_API__=${API_LEVEL}"
export CXXFLAGS="$CFLAGS"
export LDFLAGS="-Wl,--gc-sections"

pushd "$WORK_ROOT/src/gmp-6.2.1" >/dev/null
./configure \
  --build=x86_64-pc-linux-gnu \
  --host="$TARGET" \
  --prefix="$WORK_ROOT/prefix" \
  --enable-cxx \
  --enable-shared \
  --disable-static \
  --with-pic
make -j"$JOBS"
make install
popd >/dev/null

export CPPFLAGS="-I$WORK_ROOT/prefix/include"
export LDFLAGS="-L$WORK_ROOT/prefix/lib -Wl,--gc-sections"
pushd "$WORK_ROOT/src/mpfr-4.2.2" >/dev/null
./configure \
  --build=x86_64-pc-linux-gnu \
  --host="$TARGET" \
  --prefix="$WORK_ROOT/prefix" \
  --with-gmp="$WORK_ROOT/prefix" \
  --enable-shared \
  --disable-static \
  --with-pic
make -j"$JOBS"
make install
popd >/dev/null

LIB_ROOT="$APP_SRC_ROOT/jniLibs/$ABI"
HEADER_ROOT="$APP_SRC_ROOT/jniImports/gmp/include/$ABI"
mkdir -p "$LIB_ROOT" "$HEADER_ROOT"

copy_shared_family() {
  local name="$1"
  local found=0
  for candidate in "$WORK_ROOT/prefix/lib/lib${name}.so"*; do
    [[ -e "$candidate" ]] || continue
    case "$candidate" in
      *.la) continue ;;
    esac
    cp -L "$candidate" "$LIB_ROOT/$(basename "$candidate")"
    found=1
  done
  if (( found == 0 )); then
    echo "source build did not install lib${name}.so" >&2
    exit 1
  fi
}

copy_shared_family gmp
copy_shared_family gmpxx
copy_shared_family mpfr

READELF="$TOOLCHAIN/bin/llvm-readelf"
if [[ ! -x "$READELF" ]]; then
  echo "missing Android ELF inspection tool: $READELF" >&2
  exit 2
fi
for name in gmp gmpxx mpfr; do
  soname="$("$READELF" -d "$LIB_ROOT/lib${name}.so" | sed -n 's/.*SONAME.*\[\([^]]*\)\].*/\1/p' | head -n 1)"
  case "$soname" in
    "lib${name}.so"*) ;;
    *) echo "lib${name}.so has an unexpected or missing SONAME: $soname" >&2; exit 1 ;;
  esac
done
for header in gmp.h gmpxx.h mpfr.h mpf2mpfr.h; do
  if [[ ! -f "$WORK_ROOT/prefix/include/$header" ]]; then
    echo "source build did not install $header" >&2
    exit 1
  fi
  cp "$WORK_ROOT/prefix/include/$header" "$HEADER_ROOT/$header"
done

cat > "$APP_SRC_ROOT/jniImports/gmp/SOURCE_BUILD_METADATA.txt" <<EOF
SOURCE_BUILD_MODE=official-source
GMP_VERSION=6.2.1
GMP_SOURCE_URL=$GMP_URL
GMP_SOURCE_SHA256=$GMP_SHA256
MPFR_VERSION=4.2.2
MPFR_SOURCE_URL=$MPFR_URL
MPFR_SOURCE_SHA256=$MPFR_SHA256
ANDROID_ABI=$ABI
ANDROID_API=$API_LEVEL
GMP_SO_SHA256=$(sha256_file "$LIB_ROOT/libgmp.so")
GMPXX_SO_SHA256=$(sha256_file "$LIB_ROOT/libgmpxx.so")
MPFR_SO_SHA256=$(sha256_file "$LIB_ROOT/libmpfr.so")
EOF

echo "built GMP 6.2.1 and MPFR 4.2.2 from pinned official archives for $ABI"
