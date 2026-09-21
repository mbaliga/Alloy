# Private visual-review build

The immersive supplied-model experience is intentionally a separate owner-only
APK. The ordinary/public variants omit the supplied A1 Mini and Redmagic
assets because their files do not carry redistributable licence metadata.

From the Alloy checkout, build the review APK with the native renderer and the
explicit visual opt-in:

```sh
gradle :app:assembleRelease \
  -PalloyNativeEngine=true \
  -PalloyCiDebugSign=true \
  -PalloyIncludeSuppliedReferenceVisuals=true \
  --no-daemon
```

Install `app/build/outputs/apk/release/app-release.apk` on the Android phone.
On first launch this variant opens the supplied Redmagic Keyboard Case v0.4
assembly in the object-first Hero view when the OCCT bridge is present, with
the A1 Mini machine study available from **A1 study** / **Machine view**. The
Model Atlas also exposes the bezel, phone sled, service hatch, fit coupon,
editable STEP assembly and the supplied A1 Mini v5 3MF reference.

The scene is presentation-only: its material finish, lighting, exploded view
and machine envelope never alter the printable mesh, recipe, G-code or printer
commands. Use **Done** to return to the normal Import → Prepare → Slice →
Inspect → Export workflow. Use **Model** for primitives, convex sketch
extrusion, arrays, X/Y mirroring and native OCCT booleans.

The latest local review APK was exercised on an API-35 ARM64 emulator. Its
SHA-256 is recorded in `docs/GATE_STATUS.md`; it is CI-signed for local review,
not a production-distribution signature.
