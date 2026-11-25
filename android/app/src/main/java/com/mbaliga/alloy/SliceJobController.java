package com.mbaliga.alloy;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns one cancellable slice job and reports state on the main thread. This
 * keeps worker cleanup and stale-result suppression out of the Activity.
 */
public final class SliceJobController {
    public interface Callback {
        void onProgress(long jobId, int percent, String phase);
        void onComplete(long jobId, Slicer.Result result);
        void onFailure(long jobId, Exception error);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alloy-slice");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong ids = new AtomicLong();
    private final SlicerEngine engine;
    private Future<?> active;
    private long activeId;

    public SliceJobController(SlicerEngine engine) {
        if (engine == null) throw new IllegalArgumentException("Slicer engine is required");
        this.engine = engine;
    }

    public synchronized long start(MeshModel mesh, Slicer.Config config, Callback callback) {
        if (callback == null) throw new IllegalArgumentException("Slice callback is required");
        cancel();
        final long jobId = ids.incrementAndGet();
        activeId = jobId;
        final Slicer.Config snapshot = config.copy();
        active = executor.submit(() -> {
            try {
                Slicer.Result result = engine.slice(mesh, snapshot, (percent, phase) -> {
                    if (Thread.currentThread().isInterrupted()) throw new CancellationException("Slice cancelled");
                    postIfActive(jobId, () -> callback.onProgress(jobId, percent, phase));
                });
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

    private void postIfActive(long jobId, Runnable callback) {
        main.post(() -> {
            synchronized (SliceJobController.this) {
                if (jobId != activeId) return;
            }
            callback.run();
        });
    }
}
