package com.mbaliga.alloy;

/**
 * Typed seam between the phone shell and a validated native slicing engine.
 * The temporary engine is deliberately an implementation detail behind this
 * interface so the UI does not need to change when C++/JNI arrives.
 */
public interface SlicerEngine {
    Slicer.Result slice(MeshModel mesh, Slicer.Config config, Slicer.ProgressListener listener);

    /** Cooperative cancellation hook; native implementations interrupt their engine job. */
    default void cancel() { }
}
