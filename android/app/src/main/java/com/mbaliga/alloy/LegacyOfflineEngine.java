package com.mbaliga.alloy;

/**
 * v1 fallback engine. It remains export-only until native parity and physical
 * A1 Mini validation are complete.
 */
public final class LegacyOfflineEngine implements SlicerEngine {
    private final Slicer slicer = new Slicer();

    @Override
    public Slicer.Result slice(MeshModel mesh, Slicer.Config config, Slicer.ProgressListener listener) {
        return slicer.slice(mesh, config, listener);
    }
}
