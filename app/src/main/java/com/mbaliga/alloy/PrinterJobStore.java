package com.mbaliga.alloy;

import android.content.SharedPreferences;

import java.util.Locale;
import java.util.UUID;

/**
 * Durable checkpoint for the last printer transaction.
 *
 * A process restart is not evidence that a printer job stopped. Any
 * non-terminal checkpoint is therefore promoted to RECOVERY_REQUIRED on the
 * next launch and must be explicitly reviewed before a new job can be sent.
 * Each begin() call also receives a fresh transaction ID so a late callback
 * from a previous attempt cannot update a later attempt using the same file.
 */
public final class PrinterJobStore {
    private static final int MAX_NAME = 200;
    private static final int MAX_DETAIL = 1_000;
    private static final int MAX_HOST = 255;
    private static final int MAX_SERIAL = 128;
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L;
    private static final String STATE = "state";
    private static final String PRINTER_NAME = "printer_name";
    private static final String HOST = "host";
    private static final String SERIAL = "serial";
    private static final String ARTIFACT_NAME = "artifact_name";
    private static final String ARTIFACT_SIZE = "artifact_size";
    private static final String ARTIFACT_SHA256 = "artifact_sha256";
    private static final String JOB_ID = "job_id";
    private static final String REMOTE_PATH = "remote_path";
    private static final String DETAIL = "detail";
    private static final String CREATED_AT = "created_at";
    private static final String UPDATED_AT = "updated_at";
    private static final String FILAMENT_MM = "filament_mm";
    private static final String FILAMENT_DIAMETER = "filament_diameter";
    private static final String FILAMENT = "filament";
    private static final String WORKER_ID = "worker_id";
    private static final String WORKER_HEARTBEAT = "worker_heartbeat";
    private static final long WORKER_LEASE_TIMEOUT_MS = 90_000L;

    private final SharedPreferences preferences;

    public PrinterJobStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("printer job preferences are required");
        this.preferences = preferences;
    }

    /** Start a durable record before any network side effect is attempted. */
    public synchronized String begin(PrinterTransport.PrinterTarget target, PrinterTransport.Artifact artifact) {
        return begin(target, artifact, "", 0f, 1.75f);
    }

    /** Start a durable record with the immutable material estimate for post-completion inventory accounting. */
    public synchronized String begin(PrinterTransport.PrinterTarget target, PrinterTransport.Artifact artifact,
                                     String filament, float filamentMm, float filamentDiameterMm) {
        if (target == null || artifact == null) throw new IllegalArgumentException("printer job inputs are required");
        if (artifact.sizeBytes > MAX_ARTIFACT_BYTES) throw new IllegalArgumentException("printer artifact is too large");
        if (!finite(filamentMm) || filamentMm < 0f || filamentMm > 100_000_000f)
            throw new IllegalArgumentException("printer filament estimate is invalid");
        if (!finite(filamentDiameterMm) || filamentDiameterMm < 1f || filamentDiameterMm > 4f)
            throw new IllegalArgumentException("printer filament diameter is invalid");
        long now = System.currentTimeMillis();
        String jobId = UUID.randomUUID().toString();
        clearLease();
        write(new Job(jobId, PrinterTransport.State.CONNECTING, target.name, target.host, target.serial,
                artifact.displayName, artifact.sizeBytes, artifact.sha256, "", "Connecting to printer", now, now,
                bounded(filament, 80), filamentMm, filamentDiameterMm));
        return jobId;
    }

    /** Update the checkpoint after a transport state transition. */
    public synchronized void update(PrinterTransport.State state, String detail, String remotePath) {
        Job current = load();
        if (current == null || state == null) return;
        if (!canTransition(current.state, state)) return;
        String nextRemote = remotePath == null || remotePath.trim().length() == 0
                ? current.remotePath : bounded(remotePath, MAX_NAME);
        write(new Job(current.jobId, state, current.printerName, current.host, current.serial, current.artifactName,
                current.artifactSize, current.artifactSha256, nextRemote, bounded(detail, MAX_DETAIL),
                current.createdAt, System.currentTimeMillis(), current.filament, current.filamentMm,
                current.filamentDiameterMm));
    }

    /** Update only when the callback still owns the currently checkpointed job. */
    public synchronized boolean updateIfMatches(String jobId, PrinterTransport.PrinterTarget target,
                                                 PrinterTransport.Artifact artifact,
                                                 PrinterTransport.State state,
                                                 String detail, String remotePath) {
        Job current = load();
        if (current == null || jobId == null || !jobId.equals(current.jobId) || target == null || artifact == null
                || !target.host.equals(current.host) || !target.serial.equals(current.serial)
                || !artifact.displayName.equals(current.artifactName)
                || artifact.sizeBytes != current.artifactSize
                || !artifact.sha256.equalsIgnoreCase(current.artifactSha256)
                || state == null || !canTransition(current.state, state)) return false;
        update(state, detail, remotePath);
        return true;
    }

    /**
     * Update only when the callback still owns the live foreground-service
     * lease. The identity-only overload remains for Activity-side metadata
     * updates that occur after a service has released its lease; network
     * callbacks must use this stricter form so an expired worker cannot write
     * into a transaction claimed by a newer service instance.
     */
    public synchronized boolean updateIfMatches(String jobId, String workerId,
                                                 PrinterTransport.PrinterTarget target,
                                                 PrinterTransport.Artifact artifact,
                                                 PrinterTransport.State state,
                                                 String detail, String remotePath) {
        if (!hasLiveLease(jobId, workerId)) return false;
        return updateIfMatches(jobId, target, artifact, state, detail, remotePath);
    }

    /** Claim the single durable printer transaction for the foreground service. */
    public synchronized String claim(String jobId) {
        Job current = load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)
                || current.state == PrinterTransport.State.RECOVERY_REQUIRED
                || !current.requiresRecovery()) return null;
        long now = System.currentTimeMillis();
        String existing = bounded(preferences.getString(WORKER_ID, ""), 80);
        long heartbeat = preferences.getLong(WORKER_HEARTBEAT, 0L);
        if (existing.length() > 0 && heartbeat > now - WORKER_LEASE_TIMEOUT_MS) return null;
        String worker = UUID.randomUUID().toString();
        if (!preferences.edit().putString(WORKER_ID, worker).putLong(WORKER_HEARTBEAT, now).commit())
            throw new IllegalStateException("Could not persist printer worker lease");
        return worker;
    }

    /** Refresh the service lease while a network transaction is in progress. */
    public synchronized boolean heartbeat(String jobId, String workerId) {
        Job current = load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)
                || workerId == null || !workerId.equals(preferences.getString(WORKER_ID, ""))) return false;
        return preferences.edit().putLong(WORKER_HEARTBEAT, System.currentTimeMillis()).commit();
    }

    /** Release only the lease owned by this service instance. */
    public synchronized void release(String jobId, String workerId) {
        Job current = load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)
                || workerId == null || !workerId.equals(preferences.getString(WORKER_ID, ""))) return;
        clearLease();
    }

    /**
     * Convert an in-flight record into an explicit recovery state. This does
     * not infer printer state from the phone process lifecycle.
     */
    public synchronized Job recoverAfterRestart() {
        return recoverAfterRestart(false);
    }

    /**
     * Promote an in-flight record only when no service in this process owns it.
     * Activity recreation is therefore safe, while a real process restart still
     * requires explicit printer review because the in-memory service owner is gone.
     */
    public synchronized Job recoverAfterRestart(boolean activeWorkerInThisProcess) {
        Job current = load();
        if (current != null && current.requiresRecovery() && !activeWorkerInThisProcess) {
            if (current.isUnreadable()) return current;
            clearLease();
            update(PrinterTransport.State.RECOVERY_REQUIRED,
                    "Alloy restarted before the printer confirmed the final state; verify the printer before continuing.",
                    current.remotePath);
            return load();
        }
        return current;
    }

    public synchronized Job load() {
        String encodedState = preferences.getString(STATE, null);
        if (encodedState == null) return null;
        try {
            PrinterTransport.State state = PrinterTransport.State.valueOf(encodedState);
            String name = bounded(preferences.getString(PRINTER_NAME, "Printer"), MAX_NAME);
            String host = bounded(preferences.getString(HOST, ""), MAX_HOST);
            String serial = bounded(preferences.getString(SERIAL, ""), MAX_SERIAL);
            String artifactName = bounded(preferences.getString(ARTIFACT_NAME, ""), MAX_NAME);
            long artifactSize = preferences.getLong(ARTIFACT_SIZE, -1L);
            String digest = preferences.getString(ARTIFACT_SHA256, "");
            if (host.length() == 0 || serial.length() == 0 || artifactName.length() == 0
                    || artifactSize < 0L || artifactSize > MAX_ARTIFACT_BYTES
                    || !digest.matches("[0-9a-fA-F]{64}")) throw new IllegalStateException("invalid checkpoint fields");
            long created = preferences.getLong(CREATED_AT, 0L);
            long updated = preferences.getLong(UPDATED_AT, created);
            if (created <= 0L || updated < created) throw new IllegalStateException("invalid checkpoint timestamps");
            String jobId = bounded(preferences.getString(JOB_ID, ""), 64);
            if (!jobId.matches("[0-9a-fA-F-]{36}")) throw new IllegalStateException("invalid checkpoint job id");
            float filamentMm = preferences.getFloat(FILAMENT_MM, 0f);
            float filamentDiameter = preferences.getFloat(FILAMENT_DIAMETER, 1.75f);
            if (!finite(filamentMm) || filamentMm < 0f || filamentMm > 100_000_000f
                    || !finite(filamentDiameter) || filamentDiameter < 1f || filamentDiameter > 4f)
                throw new IllegalStateException("invalid checkpoint filament estimate");
            return new Job(jobId, state, name, host, serial, artifactName, artifactSize,
                    digest.toLowerCase(Locale.US), bounded(preferences.getString(REMOTE_PATH, ""), MAX_NAME),
                    bounded(preferences.getString(DETAIL, ""), MAX_DETAIL), created, updated,
                    bounded(preferences.getString(FILAMENT, ""), 80), filamentMm, filamentDiameter);
        } catch (Exception ignored) {
            // A partially written or tampered checkpoint must not look like
            // an empty store. Keep the send lock engaged until the user
            // explicitly dismisses the unreadable record.
            long now = Math.max(1L, System.currentTimeMillis());
            return new Job("", PrinterTransport.State.RECOVERY_REQUIRED, "Unknown printer", "", "",
                    "unavailable.gcode.3mf", 0L, "", "",
                    "The printer checkpoint is unreadable; sending is disabled until this record is reviewed.", now, now,
                    "", 0f, 1.75f);
        }
    }

    /** Clear only after the user has reviewed an unconfirmed printer state. */
    public synchronized void clear() {
        preferences.edit().clear().commit();
    }

    private void clearLease() {
        if (!preferences.edit().remove(WORKER_ID).remove(WORKER_HEARTBEAT).commit())
            throw new IllegalStateException("Could not clear printer worker lease");
    }

    private boolean hasLiveLease(String jobId, String workerId) {
        if (jobId == null || workerId == null || workerId.trim().length() == 0
                || !workerId.equals(preferences.getString(WORKER_ID, ""))) return false;
        long heartbeat = preferences.getLong(WORKER_HEARTBEAT, 0L);
        return heartbeat > System.currentTimeMillis() - WORKER_LEASE_TIMEOUT_MS;
    }

    private void write(Job job) {
        boolean committed = preferences.edit()
                .putString(STATE, job.state.name())
                .putString(JOB_ID, bounded(job.jobId, 64))
                .putString(PRINTER_NAME, bounded(job.printerName, MAX_NAME))
                .putString(HOST, bounded(job.host, MAX_HOST))
                .putString(SERIAL, bounded(job.serial, MAX_SERIAL))
                .putString(ARTIFACT_NAME, bounded(job.artifactName, MAX_NAME))
                .putLong(ARTIFACT_SIZE, job.artifactSize)
                .putString(ARTIFACT_SHA256, job.artifactSha256)
                .putString(REMOTE_PATH, bounded(job.remotePath, MAX_NAME))
                .putString(DETAIL, bounded(job.detail, MAX_DETAIL))
                .putLong(CREATED_AT, job.createdAt)
                .putLong(UPDATED_AT, job.updatedAt)
                .putString(FILAMENT, bounded(job.filament, 80))
                .putFloat(FILAMENT_MM, job.filamentMm)
                .putFloat(FILAMENT_DIAMETER, job.filamentDiameterMm)
                .commit();
        if (!committed) throw new IllegalStateException("Could not persist printer job checkpoint");
    }

    /** Permit only forward progress within one transaction, plus recovery promotion. */
    private static boolean canTransition(PrinterTransport.State current, PrinterTransport.State next) {
        if (current == null || next == null) return false;
        if (next == PrinterTransport.State.RECOVERY_REQUIRED) {
            return current == PrinterTransport.State.CONNECTING
                    || current == PrinterTransport.State.UPLOADING
                    || current == PrinterTransport.State.UPLOADED
                    || current == PrinterTransport.State.START_REQUESTED
                    || current == PrinterTransport.State.RUNNING
                    || current == PrinterTransport.State.PAUSE_REQUESTED
                    || current == PrinterTransport.State.PAUSED
                    || current == PrinterTransport.State.RESUME_REQUESTED
                    || current == PrinterTransport.State.CANCEL_REQUESTED;
        }
        if (current == PrinterTransport.State.RECOVERY_REQUIRED) return false;
        if (current == next) return true;
        switch (current) {
            case CONNECTING:
                return next == PrinterTransport.State.UPLOADING || next == PrinterTransport.State.FAILED;
            case UPLOADING:
                return next == PrinterTransport.State.UPLOADED || next == PrinterTransport.State.FAILED;
            case UPLOADED:
                return next == PrinterTransport.State.START_REQUESTED;
            case START_REQUESTED:
                return next == PrinterTransport.State.RUNNING || next == PrinterTransport.State.COMPLETED
                        || next == PrinterTransport.State.CANCEL_REQUESTED || next == PrinterTransport.State.CANCELLED
                        || next == PrinterTransport.State.FAILED;
            case RUNNING:
                return next == PrinterTransport.State.PAUSE_REQUESTED || next == PrinterTransport.State.CANCEL_REQUESTED
                        || next == PrinterTransport.State.COMPLETED
                        || next == PrinterTransport.State.CANCELLED || next == PrinterTransport.State.FAILED;
            case PAUSE_REQUESTED:
                return next == PrinterTransport.State.PAUSED || next == PrinterTransport.State.CANCEL_REQUESTED
                        || next == PrinterTransport.State.FAILED;
            case PAUSED:
                return next == PrinterTransport.State.RESUME_REQUESTED || next == PrinterTransport.State.CANCEL_REQUESTED
                        || next == PrinterTransport.State.COMPLETED || next == PrinterTransport.State.CANCELLED
                        || next == PrinterTransport.State.FAILED;
            case RESUME_REQUESTED:
                return next == PrinterTransport.State.RUNNING || next == PrinterTransport.State.CANCEL_REQUESTED
                        || next == PrinterTransport.State.FAILED;
            case CANCEL_REQUESTED:
                return next == PrinterTransport.State.CANCELLED || next == PrinterTransport.State.COMPLETED
                        || next == PrinterTransport.State.FAILED;
            default:
                return false;
        }
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Job {
        public final String jobId;
        public final PrinterTransport.State state;
        public final String printerName;
        public final String host;
        public final String serial;
        public final String artifactName;
        public final long artifactSize;
        public final String artifactSha256;
        public final String remotePath;
        public final String detail;
        public final long createdAt;
        public final long updatedAt;
        public final String filament;
        public final float filamentMm;
        public final float filamentDiameterMm;

        Job(String jobId, PrinterTransport.State state, String printerName, String host, String serial, String artifactName,
            long artifactSize, String artifactSha256, String remotePath, String detail, long createdAt, long updatedAt,
            String filament, float filamentMm, float filamentDiameterMm) {
            this.jobId = jobId;
            this.state = state;
            this.printerName = printerName;
            this.host = host;
            this.serial = serial;
            this.artifactName = artifactName;
            this.artifactSize = artifactSize;
            this.artifactSha256 = artifactSha256;
            this.remotePath = remotePath;
            this.detail = detail;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
            this.filament = filament;
            this.filamentMm = filamentMm;
            this.filamentDiameterMm = filamentDiameterMm;
        }

        public boolean requiresRecovery() {
            return state == PrinterTransport.State.CONNECTING
                    || state == PrinterTransport.State.UPLOADING
                    || state == PrinterTransport.State.UPLOADED
                    || state == PrinterTransport.State.START_REQUESTED
                    || state == PrinterTransport.State.RUNNING
                    || state == PrinterTransport.State.PAUSE_REQUESTED
                    || state == PrinterTransport.State.PAUSED
                    || state == PrinterTransport.State.RESUME_REQUESTED
                    || state == PrinterTransport.State.CANCEL_REQUESTED
                    || state == PrinterTransport.State.RECOVERY_REQUIRED;
        }

        public boolean isUnreadable() {
            return state == PrinterTransport.State.RECOVERY_REQUIRED && artifactSha256.length() == 0;
        }

        public String summary() {
            return printerName + " · " + artifactName + " · " + state.name();
        }
    }
}
