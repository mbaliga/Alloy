package com.mbaliga.alloy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

/**
 * On-device visualization provider.
 *
 * The built-in path is a deterministic studio renderer that works offline.
 * A future signed generative model can occupy the same provider seam without
 * making the renderer or the model an authority for printable geometry.
 */
public final class OnDeviceVisualizationProvider implements VisualizationProvider {
    private final File modelBundle;

    public OnDeviceVisualizationProvider(File filesDir) {
        modelBundle = filesDir == null ? null : new File(filesDir, "ai/visualizer/model.bundle");
    }

    /** The bounded local renderer is available in every Android build. */
    public boolean isAvailable() { return true; }

    /** A candidate file is not treated as a generative model until it has a verified runtime. */
    public boolean hasGenerativeModel() { return false; }

    public boolean hasCandidateBundle() {
        return modelBundle != null && modelBundle.isFile() && modelBundle.length() > 0L
                && modelBundle.length() <= 512L * 1024L * 1024L;
    }

    public String availabilityLabel() {
        if (hasGenerativeModel()) return "On-device generative model available";
        return hasCandidateBundle() ? "On-device studio renderer available; candidate model needs a signed runtime"
                : "On-device studio renderer available; generative model not installed";
    }

    @Override public Result generate(Request request) throws IOException {
        if (request == null) throw new IOException("Visualization request is missing");
        StudioPreviewRenderer.Finish finish = finishFor(request.prompt);
        StudioPreviewRenderer.Environment environment = environmentFor(request.prompt);
        android.graphics.Bitmap bitmap = StudioPreviewRenderer.render(request.referencePng, finish, environment);
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, encoded))
                throw new IOException("On-device preview could not be encoded");
            return new Result(encoded.toByteArray(), "On-device studio renderer");
        } finally {
            bitmap.recycle();
        }
    }

    private static StudioPreviewRenderer.Finish finishFor(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.US);
        if (value.contains("orange")) return StudioPreviewRenderer.Finish.SAFETY_ORANGE;
        if (value.contains("white")) return StudioPreviewRenderer.Finish.ARCTIC_WHITE;
        if (value.contains("metal") || value.contains("graphite")) return StudioPreviewRenderer.Finish.METALLIC;
        if (value.contains("black")) return StudioPreviewRenderer.Finish.MATTE_BLACK;
        return StudioPreviewRenderer.Finish.NATURAL_PLA;
    }

    private static StudioPreviewRenderer.Environment environmentFor(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.US);
        if (value.contains("outdoor") || value.contains("outside")) return StudioPreviewRenderer.Environment.OUTDOOR;
        if (value.contains("installed") || value.contains("in situ")) return StudioPreviewRenderer.Environment.INSTALLED;
        if (value.contains("product") || value.contains("photo")) return StudioPreviewRenderer.Environment.PRODUCT;
        return StudioPreviewRenderer.Environment.WORKSHOP;
    }
}
