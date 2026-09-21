package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Product viewport container. The Stage 2 renderer lives behind this stable
 * API so the task flow, thumbnails and selection callbacks do not depend on
 * the rendering implementation.
 */
public final class ViewportView extends FrameLayout {
    /** Maximum mesh triangles the phone renderer submits in one frame. */
    public static final int MAX_DRAW_TRIANGLES = 120_000;

    public interface PartSelectionListener {
        void onPartSelected(int part);
    }

    private final GlesViewportSurface surface;
    private final TextView emptyHint;
    private boolean machineStudy;
    private boolean hasModel;

    public ViewportView(Context context) {
        super(context);
        setBackgroundColor(android.graphics.Color.rgb(250, 249, 246));
        setClipChildren(true);
        surface = new GlesViewportSurface(context, MAX_DRAW_TRIANGLES);
        addView(surface, new FrameLayout.LayoutParams(-1, -1));
        emptyHint = new TextView(context);
        emptyHint.setText("Import an STL, OBJ, 3MF or STEP to begin");
        emptyHint.setTextColor(Color.rgb(88, 82, 73));
        emptyHint.setTextSize(16f);
        emptyHint.setGravity(android.view.Gravity.CENTER);
        emptyHint.setPadding(24, 18, 24, 18);
        emptyHint.setContentDescription("Import an STL, OBJ, 3MF or STEP to begin");
        addView(emptyHint, new FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER));
    }

    public void setModel(MeshModel model) {
        hasModel = model != null;
        surface.setModel(model);
        emptyHint.setVisibility(model == null && !machineStudy ? VISIBLE : GONE);
    }
    public void setResult(Slicer.Result result) { surface.setResult(result); }
    public void setToolpathOnly(boolean value) { surface.setToolpathOnly(value); }
    public void setPresentationMode(boolean value) { surface.setPresentationMode(value); }
    public void setCleanPresentation(boolean value) { surface.setCleanPresentation(value); }
    public void setExplodedPresentation(boolean value) { surface.setExplodedPresentation(value); }
    public boolean isExplodedPresentation() { return surface.isExplodedPresentation(); }
    public void setFinishMode(int value) { surface.setFinishMode(value); }
    public int getFinishMode() { return surface.getFinishMode(); }
    public void setNightStage(boolean value) { surface.setNightStage(value); }
    public boolean isNightStage() { return surface.isNightStage(); }
    public boolean hasReferenceMachineModel() { return surface.hasReferenceMachineModel(); }
    /** Show the printer as a standalone study subject, without a print model. */
    public void setMachineStudy(boolean value) {
        machineStudy = value;
        surface.setMachineStudy(value);
        emptyHint.setVisibility(value || hasModel ? GONE : VISIBLE);
    }
    public boolean isToolpathOnly() { return surface.isToolpathOnly(); }
    public void setSelectedLayer(int layer) { surface.setSelectedLayer(layer); }
    public int getSelectedLayer() { return surface.getSelectedLayer(); }
    public void setSelectedPart(int part) { surface.setSelectedPart(part); }
    public int getSelectedPart() { return surface.getSelectedPart(); }
    public void setPartSelectionListener(PartSelectionListener listener) {
        surface.setPartSelectionListener(listener);
    }
    public void fitModel() { surface.fitModel(); }
    public void resetView() { surface.resetView(); }
    public byte[] thumbnailPng(int maxSize) { return surface.thumbnailPng(maxSize); }
    public void onHostPause() { surface.onPause(); }
    public void onHostResume() { surface.onResume(); }

    /** Exposes the underlying surface for future desktop/hover integrations. */
    public View renderSurface() { return surface; }
}
