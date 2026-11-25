#!/bin/sh
# Regenerates the golden file from the Android Java source with a JVM (needs JDK 17, python3).
set -eu
here=$(cd "$(dirname "$0")" && pwd); root=$here/../../..   # repository root (this file is slicer-ports/ios/parity/)
w=$(mktemp -d); mkdir -p "$w/com/mbaliga/alloy"
cp "$root/android/app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java" "$w/com/mbaliga/alloy/"
cp "$here/GcodeParityDump.java.txt" "$w/com/mbaliga/alloy/Dump.java"
printf 'package com.mbaliga.alloy;\npublic class Slicer { public static class Config { public float bedX=180f,bedY=180f,bedZ=180f; } }\n' > "$w/com/mbaliga/alloy/Slicer.java"
javac -d "$w/out" "$w"/com/mbaliga/alloy/*.java
python3 "$here/gen_gcode_corpus.py" "$w/corpus.tsv"
java -cp "$w/out" com.mbaliga.alloy.Dump "$w/corpus.tsv" "$here/../AlloyCore/Tests/AlloyCoreTests/Fixtures/gcode_parity.golden.tsv"
# Plate planner golden (bit-exact float placements)
cp "$root/android/app/src/main/java/com/mbaliga/alloy/PlateArrangementPlanner.java" "$w/com/mbaliga/alloy/"
cp "$here/PlanParityDump.java.txt" "$w/com/mbaliga/alloy/PlanDump.java"
javac -d "$w/out" "$w"/com/mbaliga/alloy/PlateArrangementPlanner.java "$w"/com/mbaliga/alloy/PlanDump.java
python3 "$here/gen_plan_corpus.py" "$w/plan.in"
java -cp "$w/out" com.mbaliga.alloy.PlanDump "$w/plan.in" "$here/../AlloyCore/Tests/AlloyCoreTests/Fixtures/plan_parity.golden.tsv"
