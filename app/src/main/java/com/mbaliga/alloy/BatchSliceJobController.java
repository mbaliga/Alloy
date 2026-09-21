package com.mbaliga.alloy;

import android.content.ContentResolver;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Slices each non-empty project plate in sequence and stages every result.
 *
 * A batch is deliberately a sequence of ordinary Alloy artifacts rather than
 * one printer transaction: Bambu LAN upload/start remains a one-plate,
 * explicitly confirmed operation until multi-plate printer semantics have
 * been verified against hardware.
 */
public final class BatchSliceJobController {
    public static final int MAX_PLATES = PlateStore.MAX_PLATES;

    public interface Callback {
        void onProgress(long jobId, int percent, String phase);
        void onComplete(long jobId, BatchResult result);
        void onFailure(long jobId, Exception error);
    }

    /** Called after one plate has been sliced, validated, and staged. */
    public interface PlateCallback {
        void onPlate(PlateResult result, int completedPlates, int totalPlates) throws Exception;
    }

    public static final class PlateResult {
        public final PlateStore.Plate plate;
        public final MeshModel model;
        public final Slicer.Result slice;
        public final PrinterTransport.Artifact artifact;

        PlateResult(PlateStore.Plate plate, MeshModel model, Slicer.Result slice,
                    PrinterTransport.Artifact artifact) {
            this.plate = plate;
            this.model = model;
            this.slice = slice;
            this.artifact = artifact;
        }
    }

    public static final class BatchResult {
        public final ArrayList<PlateResult> plates;

        BatchResult(ArrayList<PlateResult> plates) {
            this.plates = new ArrayList<>(plates);
        }

        public int totalLayers() {
            int total = 0;
            for (PlateResult result : plates) total += result.slice.layers == null ? 0 : result.slice.layers.size();
            return total;
        }

        public float totalFilamentMm() {
            float total = 0f;
            for (PlateResult result : plates) total += Math.max(0f, result.slice.filamentMm);
            return total;
        }

        public float totalPrintTimeSeconds() {
            float total = 0f;
            for (PlateResult result : plates) {
                if (result.slice.printTimeSeconds >= 0f) total += result.slice.printTimeSeconds;
            }
            return total;
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alloy-batch-slice");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final AtomicLong ids = new AtomicLong();
    private final SlicerEngine engine;
    private Future<?> active;
    private long activeId;

    public BatchSliceJobController(SlicerEngine engine) {
        if (engine == null) throw new IllegalArgumentException("Slicer engine is required");
        this.engine = engine;
    }

    public synchronized long start(ContentResolver resolver, File filesDir,
                                    ArrayList<PlateStore.Plate> plates,
                                    Slicer.Config config, Callback callback) {
        if (resolver == null || filesDir == null || plates == null || config == null || callback == null)
            throw new IllegalArgumentException("Batch slice inputs are required");
        cancel();
        final long jobId = ids.incrementAndGet();
        activeId = jobId;
        final ArrayList<PlateStore.Plate> snapshot = new ArrayList<>(plates);
        final Slicer.Config recipe = config.copy();
        active = executor.submit(() -> {
            try {
                BatchResult result = run(resolver, filesDir, snapshot, recipe,
                        engine, null,
                        (percent, phase) -> postIfActive(jobId, () -> callback.onProgress(jobId, percent, phase)));
                postIfActive(jobId, () -> callback.onComplete(jobId, result));
            } catch (Exception error) {
                postIfActive(jobId, () -> callback.onFailure(jobId, error));
            }
        });
        return jobId;
    }

    public synchronized void cancel() {
        activeId = 0L;
        engine.cancel();
        if (active != null) {
            active.cancel(true);
            active = null;
        }
    }

    public synchronized void close() {
        cancel();
        executor.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }

    static BatchResult run(ContentResolver resolver, File filesDir,
                           ArrayList<PlateStore.Plate> plates,
                           Slicer.Config config,
                           SlicerEngine engine,
                           PlateCallback plateCallback,
                           Slicer.ProgressListener listener) throws Exception {
        if (engine == null) throw new IllegalArgumentException("Slicer engine is required");
        ArrayList<PlateStore.Plate> nonEmpty = new ArrayList<>();
        for (PlateStore.Plate plate : plates) {
            if (plate != null && plate.uris != null && !plate.uris.isEmpty()) nonEmpty.add(plate);
        }
        if (nonEmpty.isEmpty()) throw new IllegalArgumentException("Add a model to at least one plate before batch slicing");

        ArrayList<PlateResult> results = new ArrayList<>();
        for (int index = 0; index < nonEmpty.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Batch slice cancelled");
            PlateStore.Plate plate = nonEmpty.get(index);
            final int plateNumber = index;
            notify(listener, index * 100 / nonEmpty.size(), "Preparing " + plate.name);
            MeshModel model = loadPlate(resolver, filesDir, plate);
            Slicer.Config plateConfig = config.copy();
            Slicer.validate(model, plateConfig);
            Slicer.Result slice = engine.slice(model, plateConfig,
                    (percent, phase) -> notify(listener,
                            (plateNumber * 100 + Math.max(0, Math.min(100, percent))) / nonEmpty.size(),
                            plate.name + "  ·  " + (phase == null ? "Slicing" : phase)));
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Batch slice cancelled");
            PrinterTransport.Artifact artifact = ArtifactStore.stage(filesDir, model, slice, plateConfig,
                    plate.name + "-plate-" + (plate.index + 1));
            PlateResult result = new PlateResult(plate, model, slice, artifact);
            results.add(result);
            if (plateCallback != null) plateCallback.onPlate(result, index + 1, nonEmpty.size());
            notify(listener, (index + 1) * 100 / nonEmpty.size(), "Ready  ·  " + plate.name);
        }
        return new BatchResult(results);
    }

    static MeshModel loadPlate(ContentResolver resolver, File filesDir,
                               PlateStore.Plate plate) throws Exception {
        ArrayList<MeshModel> loaded = new ArrayList<>();
        for (int index = 0; index < plate.uris.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("Batch import cancelled");
            Uri source = plate.uris.get(index);
            String name = index < plate.names.size() ? plate.names.get(index) : "model.stl";
            ModelStore.Materialized materialized = ModelStore.materialize(filesDir, resolver, source, name);
            try (InputStream input = resolver.openInputStream(materialized.uri)) {
                if (input == null) throw new IOException("Could not open " + name);
                loaded.add(MeshModel.read(ModelStore.parserName(name, materialized.extension), input));
            }
        }
        String name = plate.name == null ? "Plate " + (plate.index + 1) : plate.name;
        MeshModel source = loaded.size() == 1 ? loaded.get(0) : MeshModel.combine(name, loaded);
        MeshModel transformed = source.transformed(name, plate.scale, plate.rotationDegrees,
                plate.tiltXDegrees, plate.tiltYDegrees);
        int count = Math.min(plate.partTransforms.size(), transformed.parts.length);
        for (int index = 0; index < count; index++) {
            MeshModel.PartTransform transform = plate.partTransforms.get(index);
            if (transform == null) continue;
            if (transform.scale == 1f && transform.rotationDegrees == 0f
                    && transform.tiltXDegrees == 0f && transform.tiltYDegrees == 0f
                    && transform.offsetX == 0f && transform.offsetY == 0f) continue;
            transformed = transformed.transformedPart(name, index, transform.scale, transform.rotationDegrees,
                    transform.tiltXDegrees, transform.tiltYDegrees, transform.offsetX, transform.offsetY);
        }
        return transformed.leveledOnBed(name);
    }

    private void postIfActive(long jobId, Runnable callback) {
        main.post(() -> {
            synchronized (BatchSliceJobController.this) {
                if (jobId != activeId) return;
            }
            callback.run();
        });
    }

    private static void notify(Slicer.ProgressListener listener, int percent, String phase) {
        if (listener != null) listener.onProgress(Math.max(0, Math.min(100, percent)), phase);
    }
}
