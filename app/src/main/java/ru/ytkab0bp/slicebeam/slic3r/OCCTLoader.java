package ru.ytkab0bp.slicebeam.slic3r;

/**
 * Loads the OpenCASCADE shared-library dependency chain before SliceBeam.
 * Android's linker does not guarantee that transitive JNI dependencies have
 * been loaded in the order expected by the upstream native target.
 */
final class OCCTLoader {
    private static final String[] LIBRARIES = {
            "TKernel", "TKMath", "TKG2d", "TKG3d", "TKGeomBase", "TKBRep",
            "TKGeomAlgo", "TKTopAlgo", "TKShHealing", "TKHLR", "TKPrim",
            "TKMesh", "TKService", "TKV3d", "TKCDF", "TKLCAF", "TKCAF",
            "TKVCAF", "TKXCAF", "TKXMesh", "TKBO", "TKDE", "TKXSBase",
            "TKDESTEP"
    };

    private OCCTLoader() { }

    static void load() {
        for (String library : LIBRARIES) System.loadLibrary(library);
    }
}
