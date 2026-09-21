# Alloy brand assets

`alloy-logo.png` is the supplied Alloy wordmark used by the Android launcher
icon and splash screen. The same resource is bundled into every APK/AAB build
through `app/src/main/res/drawable-nodpi/alloy_logo.png`.

The source image is kept at its supplied 1100 × 1100 RGBA resolution so the
Android resource pipeline can scale it without introducing a second, altered
brand mark.
