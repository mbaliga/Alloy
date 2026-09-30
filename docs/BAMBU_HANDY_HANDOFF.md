# Bambu Handy handoff

Alloy can stage a structurally validated `.gcode.3mf` package and expose it
through Android's read-only `content://` provider. **Try Bambu Handy** first
resolves the current Google Play application id
`bbl.intl.bambulab.com`, then launches its `ACTION_SEND` target with a
one-time read grant. If that package is missing or does not advertise a
matching receiving activity, Alloy opens the ordinary Android chooser instead.

The package id was checked against the [Bambu Handy Google Play listing](https://play.google.com/store/apps/details?id=bbl.intl.bambulab.com)
on 2026-10-01. It is an integration locator, not evidence of a file-import
contract.

This handoff is deliberately **not** a Bambu Handy compatibility claim:

- Android intent resolution proves only that a recipient activity is available.
- Alloy's structural package validation does not prove that Bambu Handy will
  import, preview, upload, or print the package.
- A successful import and printer upload/start must be recorded as separate
  on-device acceptance evidence before this route can be described as
  Bambu-ready.

The action remains useful for a user-controlled trial: if Handy accepts the
share, Alloy has not silently granted it write access outside its staged
artifact; if it does not, the user is returned to a generic, clearly
unvalidated share flow.
