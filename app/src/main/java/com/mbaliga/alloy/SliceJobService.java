package com.mbaliga.alloy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Runs one immutable slice request independently of the foreground Activity. */
public final class SliceJobService extends Service {
    public static final String ACTION_START = "com.mbaliga.alloy.action.SLICE_START";
    public static final String ACTION_CANCEL = "com.mbaliga.alloy.action.SLICE_CANCEL";
    public static final String ACTION_STATUS = "com.mbaliga.alloy.action.SLICE_STATUS";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_PHASE = "phase";

    private static final String CHANNEL_ID = "slice_jobs";
    private static final int NOTIFICATION_ID = 702;
    private static volatile String activeJobId;

    private SliceJobStore store;
    private ExecutorService executor;
    private Future<?> active;
    private volatile SlicerEngine engine;
    private volatile boolean cancelRequested;

    public static boolean ownsJob(String jobId) { return jobId != null && jobId.equals(activeJobId); }
    public static boolean ownsAnyJob() { return activeJobId != null; }

    public static void start(Context context, String jobId) {
        dispatch(context, ACTION_START, jobId);
    }

    public static void cancel(Context context, String jobId) {
        dispatch(context, ACTION_CANCEL, jobId);
    }

    private static void dispatch(Context context, String action, String jobId) {
        if (context == null || action == null || jobId == null || !jobId.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("slice service inputs are invalid");
        Intent intent = new Intent(context, SliceJobService.class).setAction(action).putExtra(EXTRA_JOB_ID, jobId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        store = new SliceJobStore(getSharedPreferences("alloy_slice_job", MODE_PRIVATE));
        engine = BuildConfig.NATIVE_ENGINE_ENABLED
                ? new NativeSlicerEngine(getCacheDir(), BuildConfig.NATIVE_ENGINE_VERIFIED)
                : new LegacyOfflineEngine();
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alloy-slice-service");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat("Alloy slice", "Preparing foreground model processing");
        if (intent == null) { stopSelfResult(startId); return START_NOT_STICKY; }
        String action = intent.getAction();
        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        if ((!ACTION_START.equals(action) && !ACTION_CANCEL.equals(action))
                || jobId == null || !jobId.matches("[0-9a-fA-F-]{36}")) {
            publish(jobId, SliceJobStore.State.FAILED, "Invalid slice service request", -1, "");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(action)) {
            cancelRequested = true;
            SlicerEngine currentEngine = engine;
            if (currentEngine != null) currentEngine.cancel();
            Future<?> current = active;
            if (current != null) current.cancel(true);
            if (activeJobId == null || !jobId.equals(activeJobId)) {
                SliceJobStore.Job checkpoint = store.load();
                if (checkpoint != null && jobId.equals(checkpoint.jobId)
                        && (checkpoint.state == SliceJobStore.State.QUEUED || checkpoint.state == SliceJobStore.State.RUNNING)) {
                    store.cancel(jobId, "Slice cancelled");
                    publish(jobId, SliceJobStore.State.CANCELLED, "Slice cancelled", checkpoint.progress, "Cancelled");
                }
                stopForeground(true);
                stopSelfResult(startId);
            }
            return START_NOT_STICKY;
        }
        if (ownsJob(jobId)) return START_NOT_STICKY;
        if (activeJobId != null) {
            publish(jobId, SliceJobStore.State.FAILED, "Another slice is already running", -1, "");
            return START_NOT_STICKY;
        }
        SliceJobStore.Job checkpoint = store.load();
        if (checkpoint == null || !jobId.equals(checkpoint.jobId)) {
            publish(jobId, SliceJobStore.State.FAILED, "Slice request checkpoint is unavailable", -1, "");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (checkpoint.state != SliceJobStore.State.QUEUED) {
            publish(jobId, checkpoint.state, checkpoint.detail, checkpoint.progress, checkpoint.phase);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            store.markRunning(jobId);
        } catch (Exception error) {
            store.fail(jobId, "Could not start slice: " + safeMessage(error));
            publish(jobId, SliceJobStore.State.FAILED, "Could not start slice", -1, "");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        activeJobId = jobId;
        cancelRequested = false;
        active = executor.submit(() -> run(jobId));
        return START_NOT_STICKY;
    }

    private void run(String jobId) {
        try {
            File filesDir = getFilesDir();
            SliceRequestStore.Request request = SliceRequestStore.read(filesDir, jobId);
            publish(jobId, SliceJobStore.State.RUNNING, "Slicing in the foreground", 1, "Preparing geometry");
            Slicer.Result result = engine.slice(request.model, request.config, (percent, phase) -> {
                if (cancelRequested || Thread.currentThread().isInterrupted()) throw new CancellationException("Slice cancelled");
                store.progress(jobId, percent, phase);
                publish(jobId, SliceJobStore.State.RUNNING, phase, percent, phase);
            });
            if (cancelRequested || Thread.currentThread().isInterrupted()) throw new CancellationException("Slice cancelled");
            File jobDir = SliceRequestStore.jobDirectory(filesDir, jobId);
            SliceResultStore.write(jobDir, result);
            PrinterTransport.Artifact artifact = ArtifactStore.stage(filesDir, request.model, result, request.config,
                    request.displayName + "-foreground", null);
            store.complete(jobId, artifact, "Slice complete");
            publish(jobId, SliceJobStore.State.COMPLETED, "Slice complete", 100, "Complete");
        } catch (CancellationException ignored) {
            store.cancel(jobId, "Slice cancelled");
            publish(jobId, SliceJobStore.State.CANCELLED, "Slice cancelled", -1, "Cancelled");
        } catch (Exception error) {
            store.fail(jobId, "Slice failed: " + safeMessage(error));
            publish(jobId, SliceJobStore.State.FAILED, "Slice failed: " + safeMessage(error), -1, "Failed");
        } finally {
            if (jobId.equals(activeJobId)) activeJobId = null;
            active = null;
            stopForeground(true);
            stopSelf();
        }
    }

    private void publish(String jobId, SliceJobStore.State state, String detail, int progress, String phase) {
        updateNotification(state == SliceJobStore.State.RUNNING ? "Slicing model" : "Slice " + state,
                detail == null ? "" : detail);
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_JOB_ID, jobId == null ? "" : jobId)
                .putExtra(EXTRA_STATE, state == null ? SliceJobStore.State.FAILED.name() : state.name())
                .putExtra(EXTRA_DETAIL, detail == null ? "" : detail)
                .putExtra(EXTRA_PROGRESS, progress).putExtra(EXTRA_PHASE, phase == null ? "" : phase);
        sendBroadcast(event);
    }

    private void startForegroundCompat(String title, String detail) {
        Notification value = notification(title, detail);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION_ID, value);
    }

    private Notification notification(String title, String detail) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, NOTIFICATION_ID, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_manage).setContentTitle(title)
                .setContentText(detail).setContentIntent(pending).setOngoing(true).setOnlyAlertOnce(true).build();
    }

    private void updateNotification(String title, String detail) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(title, detail));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Slice jobs", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        SlicerEngine currentEngine = engine;
        if (activeJobId != null && store != null) {
            if (currentEngine != null) currentEngine.cancel();
            store.recoverAfterRestart(false);
            activeJobId = null;
        }
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }
}
