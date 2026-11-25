from __future__ import annotations

import unittest

from ci import patch_boost_android_locale


class PatchBoostAndroidLocaleTests(unittest.TestCase):
    def test_pins_the_icu_backend_source(self) -> None:
        original = (
            "      git clone --depth=1 https://github.com/pelya/libiconv-libicu-android.git || exit 1\n"
        )
        result = patch_boost_android_locale.patch(original)
        self.assertNotIn("git clone --depth=1 https://", result)
        self.assertIn(patch_boost_android_locale.LIBICONV_ICU_ANDROID_SHA, result)
        self.assertIn("checkout --detach", result)

    def test_fails_if_upstream_clone_command_changes(self) -> None:
        with self.assertRaises(ValueError):
            patch_boost_android_locale.patch("git clone some-other-repo\n")


if __name__ == "__main__":
    unittest.main()
