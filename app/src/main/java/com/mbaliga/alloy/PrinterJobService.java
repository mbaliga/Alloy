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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns long-running printer upload/monitoring work independently of the
 * foreground Activity. The durable checkpoint remains the authority; this
 * service only keeps a live LAN session alive while Android permits it.
 */
public final class PrinterJobService extends Service {
    public static final String ACTION_UPLOAD = "com.mbaliga.alloy.action.UPLOAD";
    public static final String ACTION_START = "com.mbaliga.alloy.action.START";
    public static final String ACTION_PAUSE = "com.mbaliga.alloy.action.PAUSE";
    public static final String ACTION_RESUME = "com.mbaliga.alloy.action.RESUME";
    public static final String ACTION_CANCEL = "com.mbaliga.alloy.action.CANCEL";
    public static final String ACTION_STATUS = "com.mbaliga.alloy.action.STATUS";
    public static final String EXTRA_JOB_ID = "job_id";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_PROGRESS = "progress";
    public static final String EXTRA_PHASE = "phase";

    private static final String CHANNEL_ID = "printer_jobs";
    private static final int NOTIFICATION_ID = 701;
    private static final long HEARTBEAT_MS = 5_000L;

    private static volatile String activeJobId;
    private static volatile String activeWorkerId;

    private PrinterJobStore jobStore;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = this::heartbeatTick;

    private void heartbeatTick() {
        String jobId = activeJobId;
        String workerId = activeWorkerId;
        if (jobId != null && workerId != null && jobStore != null && jobStore.heartbeat(jobId, workerId)) {
            handler.postDelayed(heartbeat, HEARTBEAT_MS);
        }
    }
    private ExecutorService executor;
    private volatile BambuLanTransport transport;
    private volatile String workerJobId;
    private volatile String workerId;

    /** True only while a service instance in this app process owns the job. */
    public static boolean ownsJob(String jobId) {
        return jobId != null && jobId.equals(activeJobId) && activeWorkerId != null;
    }

    /** Used during Activity recreation to distinguish a live service from a dead process. */
    public static boolean ownsAnyJob() {
        return activeJobId != null && activeWorkerId != null;
    }

    public static void startUpload(Context context, String jobId) {
        start(context, ACTION_UPLOAD, jobId);
    }

    public static void startPrint(Context context, String jobId) {
        start(context, ACTION_START, jobId);
    }

    public static void pausePrint(Context context, String jobId) {
        start(context, ACTION_PAUSE, jobId);
    }

    public static void resumePrint(Context context, String jobId) {
        start(context, ACTION_RESUME, jobId);
    }

    public static void cancelPrint(Context context, String jobId) {
        start(context, ACTION_CANCEL, jobId);
    }

    private static void start(Context context, String action, String jobId) {
        if (context == null || action == null || jobId == null || jobId.trim().length() == 0)
            throw new IllegalArgumentException("printer service inputs are required");
        Intent intent = new Intent(context, PrinterJobService.class).setAction(action)
                .putExtra(EXTRA_JOB_ID, jobId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    @Override public void onCreate() {
        super.onCreate();
        jobStore = new PrinterJobStore(getSharedPreferences("alloy_printer_job", MODE_PRIVATE));
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alloy-printer-service");
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat("Printer job", "Preparing secure LAN connection");
        if (intent == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        String jobId = intent.getStringExtra(EXTRA_JOB_ID);
        if ((!ACTION_UPLOAD.equals(action) && !ACTION_START.equals(action) && !ACTION_PAUSE.equals(action)
                && !ACTION_RESUME.equals(action) && !ACTION_CANCEL.equals(action))
                || jobId == null || !jobId.matches("[0-9a-fA-F-]{36}")) {
            publish(jobId, PrinterTransport.State.FAILED, "Invalid printer service request", -1, "");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            executor.execute(() -> cancelJob(jobId));
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action) || ACTION_RESUME.equals(action)) {
            executor.execute(() -> controlJob(jobId, ACTION_PAUSE.equals(action)));
            return START_NOT_STICKY;
        }
        if (ownsJob(jobId)) return START_NOT_STICKY;

        String claimedWorker;
        try {
            claimedWorker = jobStore.claim(jobId);
        } catch (Exception error) {
            failWithoutWorker(jobId, "Could not claim printer job: " + safeMessage(error));
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (claimedWorker == null) {
            PrinterJobStore.Job current = jobStore.load();
            if (current != null) publish(current.jobId, current.state, current.detail, -1, "");
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        workerJobId = jobId;
        workerId = claimedWorker;
        activeJobId = jobId;
        activeWorkerId = claimedWorker;
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
        executor.execute(() -> run(action, jobId));
        return START_NOT_STICKY;
    }

    private void run(String action, String jobId) {
        if (ACTION_UPLOAD.equals(action)) uploadJob(jobId);
        else if (ACTION_START.equals(action)) startJob(jobId);
        else failJob(jobId, "Unsupported printer operation");
    }

    private void uploadJob(String jobId) {
        PrinterJobStore.Job job = jobStore.load();
        if (!matchesWorker(jobId, job)) {
            finishWorker(jobId);
            return;
        }
        try {
            PrinterCredentialStore.Credentials credentials = credentials();
            PrinterTransport.PrinterTarget target = target(job);
            PrinterTransport.Artifact artifact = artifact(job);
            BambuLanTransport active = makeTransport(credentials);
            transport = active;
            active.upload(target, artifact,
                    (percent, phase) -> publish(jobId, PrinterTransport.State.UPLOADING, phase, percent, phase),
                    (state, detail) -> onTransportState(jobId, target, artifact, state, detail,
                            state == PrinterTransport.State.UPLOADED ? "/" + artifact.displayName : null));
        } catch (Exception error) {
            failJob(jobId, "Printer upload failed: " + safeMessage(error));
        }
    }

    private void startJob(String jobId) {
        PrinterJobStore.Job job = jobStore.load();
        if (!matchesWorker(jobId, job)) {
            finishWorker(jobId);
            return;
        }
        try {
            // A start request is only valid for the durable uploaded state.
            // Replaying START_REQUESTED/RUNNING could duplicate MQTT
            // project_file after an ambiguous process interruption.
            if (job == null || job.state != PrinterTransport.State.UPLOADED)
                throw new IllegalStateException("The uploaded printer job is not ready to start");
            PrinterCredentialStore.Credentials credentials = credentials();
            PrinterTransport.PrinterTarget target = target(job);
            PrinterTransport.Artifact artifact = artifact(job);
            String remotePath = job.remotePath.length() == 0 ? "/" + artifact.displayName : job.remotePath;
            BambuLanTransport active = makeTransport(credentials);

            // Upload success is a point-in-time fact. Recheck the local
            // package identity and remote SIZE immediately before persisting
            // START_REQUESTED, so a replaced/missing same-name file cannot be
            // selected by the subsequent MQTT project_file command.
            try {
                active.verifyUploadedArtifact(target, artifact);
            } catch (Exception verificationError) {
                String detail = "Start blocked: remote artifact verification failed; re-upload before continuing: "
                        + safeMessage(verificationError);
                jobStore.update(PrinterTransport.State.RECOVERY_REQUIRED, detail, remotePath);
                publish(jobId, PrinterTransport.State.RECOVERY_REQUIRED, detail, -1, "");
                finishWorker(jobId);
                return;
            }
            jobStore.update(PrinterTransport.State.START_REQUESTED, "Start command requested", remotePath);
            job = jobStore.load();
            if (job == null || job.state != PrinterTransport.State.START_REQUESTED)
                throw new IllegalStateException("Could not persist the printer start request");
            transport = active;
            active.startPrint(target, remotePath,
                    new PrinterTransport.PrintOptions(false, true, false, true),
                    (state, detail) -> onTransportState(jobId, target, artifact, state, detail, remotePath));
        } catch (Exception error) {
            failJob(jobId, "Print start failed: " + safeMessage(error));
        }
    }

    private void cancelJob(String jobId) {
        BambuLanTransport active = transport;
        PrinterJobStore.Job job = jobStore.load();
        if (!ownsJob(jobId) || active == null || !matchesWorker(jobId, job)) {
            if (job != null && job.jobId.equals(jobId) && job.requiresRecovery()
                    && job.state != PrinterTransport.State.RECOVERY_REQUIRED) {
                jobStore.update(PrinterTransport.State.RECOVERY_REQUIRED,
                        "Cancellation could not reach the printer session; verify the printer before continuing.",
                        job.remotePath);
                publish(jobId, PrinterTransport.State.RECOVERY_REQUIRED,
                        "Cancellation could not be verified", -1, "");
            }
            stopSelf();
            return;
        }
        try {
            if (job.state == PrinterTransport.State.CANCEL_REQUESTED) {
                publish(jobId, job.state, job.detail, -1, "");
                return;
            }
            if (!canRequestCancel(job.state))
                throw new IllegalStateException("The printer job is not in a cancellable state");
            PrinterTransport.PrinterTarget target = target(job);
            PrinterTransport.Artifact artifact = artifact(job);
            jobStore.update(PrinterTransport.State.CANCEL_REQUESTED, "Stop command requested", job.remotePath);
            active.cancel(target, (state, detail) -> onTransportState(jobId, target, artifact, state, detail, null));
        } catch (Exception error) {
            failJob(jobId, "Print cancellation failed: " + safeMessage(error));
        }
    }

    private static boolean canRequestCancel(PrinterTransport.State state) {
        return state == PrinterTransport.State.START_REQUESTED
                || state == PrinterTransport.State.RUNNING
                || state == PrinterTransport.State.PAUSE_REQUESTED
                || state == PrinterTransport.State.PAUSED
                || state == PrinterTransport.State.RESUME_REQUESTED;
    }

    private void controlJob(String jobId, boolean pause) {
        BambuLanTransport active = transport;
        PrinterJobStore.Job job = jobStore.load();
        if (!ownsJob(jobId) || active == null || !matchesWorker(jobId, job)) {
            if (job != null && job.jobId.equals(jobId) && job.requiresRecovery()
                    && job.state != PrinterTransport.State.RECOVERY_REQUIRED) {
                jobStore.update(PrinterTransport.State.RECOVERY_REQUIRED,
                        "Pause/resume could not reach the printer session; verify the printer before continuing.",
                        job.remotePath);
                publish(jobId, PrinterTransport.State.RECOVERY_REQUIRED,
                        "Pause/resume could not be verified", -1, "");
            } else {
                publish(jobId, PrinterTransport.State.FAILED, "No active printer session is available", -1, "");
            }
            stopSelf();
            return;
        }
        try {
            PrinterTransport.PrinterTarget target = target(job);
            PrinterTransport.Artifact artifact = artifact(job);
            if (pause) active.pause(target, (state, detail) -> onTransportState(
                    jobId, target, artifact, state, detail, job.remotePath));
            else active.resume(target, (state, detail) -> onTransportState(
                    jobId, target, artifact, state, detail, job.remotePath));
        } catch (Exception error) {
            failJob(jobId, (pause ? "Print pause" : "Print resume") + " failed: " + safeMessage(error));
        }
    }

    private void onTransportState(String jobId, PrinterTransport.PrinterTarget target,
                                  PrinterTransport.Artifact artifact, PrinterTransport.State state,
                                  String detail, String remotePath) {
        if (state == null) return;
        boolean applied = jobStore.updateIfMatches(jobId, workerId, target, artifact, state, detail, remotePath);
        PrinterJobStore.Job current = jobStore.load();
        if (applied && current != null) publish(jobId, current.state, current.detail, -1, "");
        else if (current != null && jobId.equals(current.jobId)) publish(jobId, current.state, current.detail, -1, "");
        if (applied && state == PrinterTransport.State.COMPLETED && current != null) {
            try {
                InventoryReconciliationStore reconciliation = new InventoryReconciliationStore(
                        getSharedPreferences("alloy_inventory_reconciliation", MODE_PRIVATE));
                reconciliation.enqueue(jobId, current.filament, current.filamentMm, current.filamentDiameterMm);
                reconciliation.drain(new InventoryStore(getSharedPreferences("alloy_inventory", MODE_PRIVATE)));
            } catch (Exception ignored) {
                // The completed checkpoint remains durable. If enqueue or the
                // inventory write fails, startup retries from the queue.
            }
        }
        if (state == PrinterTransport.State.UPLOADED || state == PrinterTransport.State.FAILED
                || state == PrinterTransport.State.COMPLETED || state == PrinterTransport.State.CANCELLED
                || state == PrinterTransport.State.RECOVERY_REQUIRED) {
            finishWorker(jobId);
        }
    }

    private void failJob(String jobId, String detail) {
        PrinterJobStore.Job current = jobStore.load();
        if (current != null && jobId.equals(current.jobId) && current.state != PrinterTransport.State.RECOVERY_REQUIRED
                && current.state != PrinterTransport.State.FAILED && current.state != PrinterTransport.State.COMPLETED
                && current.state != PrinterTransport.State.CANCELLED) {
            jobStore.update(PrinterTransport.State.FAILED, detail, current.remotePath);
        }
        publish(jobId, PrinterTransport.State.FAILED, detail, -1, "");
        finishWorker(jobId);
    }

    private void failWithoutWorker(String jobId, String detail) {
        PrinterJobStore.Job current = jobStore.load();
        if (current != null && jobId != null && jobId.equals(current.jobId)
                && current.state != PrinterTransport.State.RECOVERY_REQUIRED
                && current.state != PrinterTransport.State.FAILED) {
            jobStore.update(PrinterTransport.State.FAILED, detail, current.remotePath);
        }
        publish(jobId, PrinterTransport.State.FAILED, detail, -1, "");
    }

    private boolean matchesWorker(String jobId, PrinterJobStore.Job job) {
        return ownsJob(jobId) && job != null && jobId.equals(job.jobId);
    }

    private PrinterCredentialStore.Credentials credentials() throws Exception {
        PrinterCredentialStore.Credentials result = new PrinterCredentialStore(
                getSharedPreferences("alloy_printer", MODE_PRIVATE)).load();
        if (result == null) throw new IllegalStateException("No paired printer credentials are available");
        return result;
    }

    private BambuLanTransport makeTransport(PrinterCredentialStore.Credentials credentials) throws Exception {
        if (credentials == null || !credentials.hasCertificatePin())
            throw new IllegalStateException("Printer certificate pin is required before physical printing");
        if (!credentials.isA1Mini())
            throw new IllegalStateException("The current profile is restricted to a confirmed Bambu A1 Mini (model N1)");
        return BambuLanTransport.pinned(credentials, credentials.certificateFingerprint);
    }

    private PrinterTransport.PrinterTarget target(PrinterJobStore.Job job) {
        return new PrinterTransport.PrinterTarget(job.printerName, job.host, job.serial);
    }

    private PrinterTransport.Artifact artifact(PrinterJobStore.Job job) throws Exception {
        if (BuildConfig.PHYSICAL_PILOT_ENABLED) {
            return ArtifactStore.recoverForA1MiniNoSupportPilot(getFilesDir(), job.artifactName,
                    job.artifactSize, job.artifactSha256);
        }
        return ArtifactStore.recoverForPhysicalPrint(getFilesDir(), job.artifactName, job.artifactSize, job.artifactSha256);
    }

    private void publish(String jobId, PrinterTransport.State state, String detail, int progress, String phase) {
        if (state != null) updateNotification(state.name(), detail == null ? "" : detail);
        Intent event = new Intent(ACTION_STATUS).setPackage(getPackageName())
                .putExtra(EXTRA_JOB_ID, jobId == null ? "" : jobId)
                .putExtra(EXTRA_STATE, state == null ? PrinterTransport.State.FAILED.name() : state.name())
                .putExtra(EXTRA_DETAIL, detail == null ? "" : detail)
                .putExtra(EXTRA_PROGRESS, progress)
                .putExtra(EXTRA_PHASE, phase == null ? "" : phase);
        sendBroadcast(event);
    }

    private void finishWorker(String jobId) {
        if (jobId == null || !jobId.equals(workerJobId)) return;
        handler.removeCallbacks(heartbeat);
        String lease = workerId;
        workerJobId = null;
        workerId = null;
        jobStore.release(jobId, lease);
        if (jobId.equals(activeJobId)) {
            activeJobId = null;
            activeWorkerId = null;
        }
        BambuLanTransport active = transport;
        transport = null;
        if (active != null) active.close();
        stopForeground(true);
        stopSelf();
    }

    private void startForegroundCompat(String title, String detail) {
        Notification notification = notification(title, detail);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String title, String detail) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(title, detail));
    }

    private Notification notification(String title, String detail) {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(this, 701, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_upload)
                .setContentTitle(title == null ? "Printer job" : title)
                .setContentText(detail == null ? "" : detail)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Printer jobs", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        handler.removeCallbacks(heartbeat);
        BambuLanTransport active = transport;
        transport = null;
        if (active != null) active.close();
        String interruptedJob = workerJobId;
        String interruptedWorker = workerId;
        if (interruptedJob != null && jobStore != null) {
            try {
                PrinterJobStore.Job current = jobStore.load();
                if (current != null && interruptedJob.equals(current.jobId) && current.requiresRecovery()) {
                    PrinterJobStore.Job recovered = jobStore.recoverAfterRestart(false);
                    if (recovered != null && recovered.state == PrinterTransport.State.RECOVERY_REQUIRED) {
                        publish(interruptedJob, recovered.state, recovered.detail, -1, "Recovery review");
                    }
                }
            } catch (Exception ignored) {
                // Keep the durable checkpoint and send lock intact if teardown
                // itself is interrupted; the next Activity launch retries the
                // same fail-closed recovery promotion.
            }
            jobStore.release(interruptedJob, interruptedWorker);
        }
        if (workerJobId != null && workerJobId.equals(activeJobId)) {
            activeJobId = null;
            activeWorkerId = null;
        }
        workerJobId = null;
        workerId = null;
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }
}
