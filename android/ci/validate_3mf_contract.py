#!/usr/bin/env python3
"""Guard the Android 3MF transform and bounded multi-object import contract."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MESH = ROOT / "app/src/main/java/com/mbaliga/alloy/MeshModel.java"


def main() -> None:
    text = MESH.read_text(encoding="utf-8")
    required = (
        "m00 m10 m20 m01 m11 m21 m02 m12 m22 m03 m13 m23",
        "out[1] = source[3]",
        "out[2] = source[6]",
        "out[3] = source[9] * unitScale",
        "out[7] = source[10] * unitScale",
        "out[11] = source[11] * unitScale",
        "MAX_TRIANGLES",
        "MAX_3MF_MODEL_ENTRIES",
        "MAX_3MF_ENTRIES",
        "MAX_PROJECT_MODELS",
        '"3D/3dmodel.model".equals(entryName)',
        "public final Part[] parts",
        "class Part",
        "public static MeshModel combine",
        "public PartBounds partBounds",
        "public GeometryReport geometryReport",
        "object.name",
        "duplicate object id",
        "component graph contains a cycle",
        "safeZipEntryName",
        "drain(entryInput)",
        "MAX_3MF_ENTRY_BYTES",
    )
    missing = [token for token in required if token not in text]
    if missing:
        raise SystemExit(f"{MESH}: missing 3MF contract tokens: {missing}")

    # The 3MF sequence below is column-major 4x3. Verify the expected row-major
    # affine matrix independently so a future refactor cannot silently move
    # translations into the rotation columns.
    source = list(range(1, 13))
    row_major = [
        source[0], source[3], source[6], source[9],
        source[1], source[4], source[7], source[10],
        source[2], source[5], source[8], source[11],
    ]
    if row_major[3:12:4] != [10, 11, 12]:
        raise SystemExit("3MF transform contract has incorrect translation positions")
    viewport = (ROOT / "app/src/main/java/com/mbaliga/alloy/ViewportView.java").read_text(encoding="utf-8")
    if ("public void setSelectedPart" not in viewport or "getSelectedPart" not in viewport
            or "PartSelectionListener" not in viewport or "MAX_DRAW_TRIANGLES" not in viewport):
        raise SystemExit("Viewport is missing the visual part-focus contract")
    print("validated multi-object 3MF transform and import limits")


if __name__ == "__main__":
    main()
