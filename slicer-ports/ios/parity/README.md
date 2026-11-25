# Java <-> Swift parity corpus (G-code safety)

`gen_gcode_corpus.py` writes 1,508 deterministic cases. `GcodeParityDump.java.txt` is the
harness (compile it next to the *unmodified* `GcodeSafetyValidator.java` plus a 3-line stub of
`Slicer.Config`, defaults 180/180/180) that produced
`AlloyCore/Tests/AlloyCoreTests/Fixtures/gcode_parity.golden.tsv` with the real JVM.
`GcodeParityTests.swift` replays every case through the Swift port and demands identical
flags and identical error lists. Regenerate: see `regen.sh`.
