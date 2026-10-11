#!/usr/bin/env python3
"""Pin Boost-for-Android's optional ICU backend source used by Boost.Locale."""

from pathlib import Path
import sys


LIBICONV_ICU_ANDROID_SHA = "1d9e3e389de92818bafbca061d454ec917af9ec0"
UPSTREAM_CLONE = "      git clone --depth=1 https://github.com/pelya/libiconv-libicu-android.git || exit 1\n"
PINNED_CLONE = (
    "      git clone --depth=1 --no-checkout https://github.com/pelya/libiconv-libicu-android.git || exit 1\n"
    "      git -C libiconv-libicu-android fetch --depth=1 origin "
    f"{LIBICONV_ICU_ANDROID_SHA} || exit 1\n"
    "      git -C libiconv-libicu-android checkout --detach "
    f"{LIBICONV_ICU_ANDROID_SHA} || exit 1\n"
)


def patch(text: str) -> str:
    if text.count(UPSTREAM_CLONE) != 1:
        raise ValueError("Pinned Boost-for-Android ICU clone command changed")
    return text.replace(UPSTREAM_CLONE, PINNED_CLONE, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: patch_boost_android_locale.py <build-android.sh>")
    path = Path(sys.argv[1])
    path.write_text(patch(path.read_text(encoding="utf-8")), encoding="utf-8")
    print(f"Pinned Boost.Locale ICU bootstrap to {LIBICONV_ICU_ANDROID_SHA}")


if __name__ == "__main__":
    main()
