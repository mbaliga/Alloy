package ru.ytkab0bp.slicebeam.slic3r;

/**
 * JNI compatibility stub required by SliceBeam's library load hook. Alloy's
 * Canvas viewport does not use the upstream OpenGL renderer, so no shader
 * pointer is exposed here.
 */
public final class GLShadersManager {
    private GLShadersManager() { }

    public static long getCurrentShaderPointer() { return 0L; }
}
