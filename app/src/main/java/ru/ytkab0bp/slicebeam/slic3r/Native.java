package ru.ytkab0bp.slicebeam.slic3r;

/**
 * Narrow compatibility declaration for the pinned SliceBeam JNI exports.
 * Alloy code should call NativeEngine, not this inherited package surface.
 */
public final class Native {
    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("gmp");
        System.loadLibrary("gmpxx");
        System.loadLibrary("mpfr");
        OCCTLoader.load();
        System.loadLibrary("slic3r");
    }

    private Native() { }

    public static native long model_create();
    // The upstream Orca mobile bridge carries a plate id so a 3MF can choose
    // the requested build item. Alloy's single-plate seam uses plate zero.
    public static native long model_read_from_file(String path, String baseName, int plateId) throws Slic3rRuntimeError;
    public static native int model_get_objects_count(long ptr);
    public static native int model_get_volumes_count(long ptr, int objectIndex);
    public static native boolean model_boolean_stl(String firstPath, String secondPath, String outputPath, int operation) throws Slic3rRuntimeError;
    // Keep the calibration and multicolor arguments explicit at the JNI seam;
    // Alloy's base workflow supplies one white filament and no calibration.
    public static native long model_slice(long ptr, String configPath, String path, SliceListener listener,
                                          int numFilaments, int[] filamentColors, int calibMode,
                                          double calibStart, double calibEnd, double calibStep) throws Slic3rRuntimeError;
    public static native void model_cancel(long ptr);
    public static native void model_export_3mf(long ptr, String configPath, String path) throws Slic3rRuntimeError;
    public static native void model_release(long ptr);

    public static native String gcoderesult_get_recommended_name(long ptr);
    public static native double gcoderesult_get_used_filament_mm(long ptr, int role);
    public static native double gcoderesult_get_used_filament_g(long ptr, int role);
    public static native void gcoderesult_release(long ptr);
}
