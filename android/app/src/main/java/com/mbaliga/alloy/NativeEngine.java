package com.mbaliga.alloy;

import ru.ytkab0bp.slicebeam.slic3r.Native;

/** Alloy-owned smoke probe for the optional native engine. */
public final class NativeEngine {
    private NativeEngine() { }

    public static String probe() {
        long model = Native.model_create();
        if (model == 0L) throw new IllegalStateException("Native engine returned an empty model handle");
        try {
            return "SliceBeam PrusaSlicer core · JNI bridge loaded";
        } finally {
            Native.model_release(model);
        }
    }
}
