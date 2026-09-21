package com.mbaliga.alloy;

import android.content.SharedPreferences;

import java.util.Locale;

/** Durable checkpoint for the phone's foreground slicing transaction. */
public final class SliceJobStore {
    private static final String STATE = "state";
    private static final String JOB_ID = "job_id";
    private static final String DISPLAY_NAME = "display_name";
    private static final String MODEL_SHA256 = "model_sha256";
    private static final String DETAIL = "detail";
    private static final String PHASE = "phase";
    private static final String PROGRESS = "progress";
    private static final String ARTIFACT_NAME = "artifact_name";
    private static final String ARTIFACT_SIZE = "artifact_size";
    private static final String ARTIFACT_SHA256 = "artifact_sha256";
    private static final String CREATED_AT = "created_at";
    private static final String UPDATED_AT = "updated_at";
    private static final int MAX_DISPLAY_NAME = 150;
    private static final int MAX_DETAIL = 1_000;
    private static final int MAX_PHASE = 200;
    private static final long MAX_ARTIFACT_BYTES = 256L * 1024L * 1024L;

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED }

    private final SharedPreferences preferences;

    public SliceJobStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("slice job preferences are required");
        this.preferences = preferences;
    }

    public synchronized void begin(String jobId, String displayName, String modelSha256) {
        if (!validJobId(jobId) || displayName == null || displayName.trim().length() == 0
                || modelSha256 == null || !modelSha256.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("slice job identity is invalid");
        Job current = load();
        if (current != null && !current.isTerminal())
            throw new IllegalStateException("Another slice job needs attention first");
        long now = System.currentTimeMillis();
        write(new Job(jobId, State.QUEUED, bounded(displayName, MAX_DISPLAY_NAME), modelSha256.toLowerCase(Locale.US),
                "Queued for foreground slicing", "Queued", 0, "", 0L, "", now, now));
    }

    public synchronized Job markRunning(String jobId) {
        Job current = require(jobId);
        if (current.state != State.QUEUED) return current;
        Job next = copy(current, State.RUNNING, "Slicing in the foreground", "Preparing geometry", 0,
                current.artifactName, current.artifactSize, current.artifactSha256);
        write(next);
        return next;
    }

    public synchronized void progress(String jobId, int percent, String phase) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId) || current.state != State.RUNNING) return;
        int safePercent = Math.max(0, Math.min(100, percent));
        write(copy(current, State.RUNNING, phase == null ? "Slicing" : phase,
                phase == null ? "Slicing" : phase, safePercent,
                current.artifactName, current.artifactSize, current.artifactSha256));
    }

    public synchronized void complete(String jobId, PrinterTransport.Artifact artifact, String detail) {
        Job current = require(jobId);
        if (current.state != State.RUNNING || artifact == null) throw new IllegalStateException("slice job is not running");
        if (artifact.sizeBytes <= 0L || artifact.sizeBytes > MAX_ARTIFACT_BYTES
                || artifact.sha256 == null || !artifact.sha256.matches("[0-9a-fA-F]{64}"))
            throw new IllegalArgumentException("slice artifact identity is invalid");
        write(copy(current, State.COMPLETED, bounded(detail, MAX_DETAIL), "Complete", 100,
                artifact.displayName, artifact.sizeBytes, artifact.sha256));
    }

    public synchronized void fail(String jobId, String detail) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId) || current.isTerminal()) return;
        write(copy(current, State.FAILED, bounded(detail, MAX_DETAIL), "Failed", current.progress,
                current.artifactName, current.artifactSize, current.artifactSha256));
    }

    public synchronized void cancel(String jobId, String detail) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId) || current.isTerminal()) return;
        write(copy(current, State.CANCELLED, bounded(detail, MAX_DETAIL), "Cancelled", current.progress,
                current.artifactName, current.artifactSize, current.artifactSha256));
    }

    /** A process death is not evidence that a slice completed. */
    public synchronized Job recoverAfterRestart(boolean activeService) {
        Job current = load();
        if (current != null && (current.state == State.QUEUED || current.state == State.RUNNING) && !activeService) {
            Job next = copy(current, State.RECOVERY_REQUIRED,
                    "Alloy restarted before the slice completed; review and start the slice again.",
                    "Recovery review", current.progress, current.artifactName, current.artifactSize, current.artifactSha256);
            write(next);
            return next;
        }
        return current;
    }

    public synchronized Job load() {
        String encoded = preferences.getString(STATE, null);
        if (encoded == null) return null;
        try {
            State state = State.valueOf(encoded);
            String jobId = bounded(preferences.getString(JOB_ID, ""), 64);
            String name = bounded(preferences.getString(DISPLAY_NAME, ""), MAX_DISPLAY_NAME);
            String hash = preferences.getString(MODEL_SHA256, "");
            String detail = bounded(preferences.getString(DETAIL, ""), MAX_DETAIL);
            String phase = bounded(preferences.getString(PHASE, ""), MAX_PHASE);
            int progress = preferences.getInt(PROGRESS, 0);
            String artifact = bounded(preferences.getString(ARTIFACT_NAME, ""), 180);
            long size = preferences.getLong(ARTIFACT_SIZE, 0L);
            String artifactHash = preferences.getString(ARTIFACT_SHA256, "");
            long created = preferences.getLong(CREATED_AT, 0L);
            long updated = preferences.getLong(UPDATED_AT, created);
            if (!validJobId(jobId) || name.length() == 0 || !hash.matches("[0-9a-fA-F]{64}")
                    || progress < 0 || progress > 100 || created <= 0L || updated < created
                    || (artifact.length() > 0 && (!artifact.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.gcode\\.3mf")
                    || artifact.contains("..") || size <= 0L || size > MAX_ARTIFACT_BYTES
                    || !artifactHash.matches("[0-9a-fA-F]{64}"))))
                throw new IllegalStateException("invalid slice checkpoint fields");
            return new Job(jobId, state, name, hash.toLowerCase(Locale.US), detail, phase, progress,
                    artifact, size, artifactHash.toLowerCase(Locale.US), created, updated);
        } catch (Exception ignored) {
            long now = Math.max(1L, System.currentTimeMillis());
            return new Job("00000000-0000-0000-0000-000000000000", State.RECOVERY_REQUIRED,
                    "Unreadable slice", "", "The slice checkpoint is unreadable; start a new project or clear it.",
                    "Recovery review", 0, "", 0L, "", now, now);
        }
    }

    public synchronized void clear() {
        if (!preferences.edit().clear().commit()) throw new IllegalStateException("Could not clear slice checkpoint");
    }

    private Job require(String jobId) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId)) throw new IllegalStateException("slice job is unavailable");
        return current;
    }

    private void write(Job job) {
        if (!preferences.edit()
                .putString(STATE, job.state.name())
                .putString(JOB_ID, job.jobId)
                .putString(DISPLAY_NAME, bounded(job.displayName, MAX_DISPLAY_NAME))
                .putString(MODEL_SHA256, job.modelSha256)
                .putString(DETAIL, bounded(job.detail, MAX_DETAIL))
                .putString(PHASE, bounded(job.phase, MAX_PHASE))
                .putInt(PROGRESS, job.progress)
                .putString(ARTIFACT_NAME, bounded(job.artifactName, 180))
                .putLong(ARTIFACT_SIZE, job.artifactSize)
                .putString(ARTIFACT_SHA256, job.artifactSha256)
                .putLong(CREATED_AT, job.createdAt)
                .putLong(UPDATED_AT, job.updatedAt)
                .commit()) throw new IllegalStateException("Could not persist slice checkpoint");
    }

    private static Job copy(Job current, State state, String detail, String phase, int progress,
                            String artifactName, long artifactSize, String artifactSha256) {
        return new Job(current.jobId, state, current.displayName, current.modelSha256, detail, phase,
                progress, artifactName, artifactSize, artifactSha256, current.createdAt, System.currentTimeMillis());
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
    }

    private static boolean validJobId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    public static final class Job {
        public final String jobId;
        public final State state;
        public final String displayName;
        public final String modelSha256;
        public final String detail;
        public final String phase;
        public final int progress;
        public final String artifactName;
        public final long artifactSize;
        public final String artifactSha256;
        public final long createdAt;
        public final long updatedAt;

        Job(String jobId, State state, String displayName, String modelSha256, String detail, String phase,
            int progress, String artifactName, long artifactSize, String artifactSha256, long createdAt, long updatedAt) {
            this.jobId = jobId; this.state = state; this.displayName = displayName; this.modelSha256 = modelSha256;
            this.detail = detail; this.phase = phase; this.progress = progress; this.artifactName = artifactName;
            this.artifactSize = artifactSize; this.artifactSha256 = artifactSha256; this.createdAt = createdAt; this.updatedAt = updatedAt;
        }

        public boolean isTerminal() {
            return state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED;
        }
    }
}
