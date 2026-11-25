package com.mbaliga.alloy;

import android.content.SharedPreferences;

import java.util.Locale;

/** Durable checkpoint for a foreground multi-plate slice transaction. */
public final class BatchSliceJobStore {
    private static final String STATE = "state";
    private static final String JOB_ID = "job_id";
    private static final String DISPLAY_NAME = "display_name";
    private static final String PLATE_COUNT = "plate_count";
    private static final String COMPLETED_PLATES = "completed_plates";
    private static final String PROGRESS = "progress";
    private static final String DETAIL = "detail";
    private static final String PHASE = "phase";
    private static final String CREATED_AT = "created_at";
    private static final String UPDATED_AT = "updated_at";
    private static final int MAX_DISPLAY_NAME = 150;
    private static final int MAX_DETAIL = 1_000;
    private static final int MAX_PHASE = 200;

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED, RECOVERY_REQUIRED }

    private final SharedPreferences preferences;

    public BatchSliceJobStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("batch slice preferences are required");
        this.preferences = preferences;
    }

    public synchronized void begin(String jobId, String displayName, int plateCount) {
        if (!validJobId(jobId) || !validText(displayName, MAX_DISPLAY_NAME) || plateCount <= 0
                || plateCount > PlateStore.MAX_PLATES)
            throw new IllegalArgumentException("batch slice identity is invalid");
        long now = System.currentTimeMillis();
        write(new Job(jobId, State.QUEUED, displayName.trim(), plateCount, 0, 0,
                "Queued", "Queued", now, now));
    }

    public synchronized Job markRunning(String jobId) {
        Job current = require(jobId);
        Job next = copy(current, State.RUNNING, current.completedPlates, current.progress,
                "Slicing plates in the foreground", current.phase);
        write(next);
        return next;
    }

    public synchronized Job progress(String jobId, int completedPlates, int progress, String phase) {
        Job current = require(jobId);
        int safeCompleted = Math.max(0, Math.min(current.plateCount, completedPlates));
        int safeProgress = Math.max(0, Math.min(100, progress));
        Job next = copy(current, State.RUNNING, safeCompleted, safeProgress,
                phase == null ? "Slicing plates" : phase, phase == null ? "Slicing" : phase);
        write(next);
        return next;
    }

    public synchronized Job complete(String jobId, String detail) {
        Job current = require(jobId);
        if (current.completedPlates != current.plateCount)
            throw new IllegalStateException("batch cannot complete before every plate is staged");
        Job next = copy(current, State.COMPLETED, current.plateCount, 100,
                detail == null ? "Batch slice complete" : detail, "Complete");
        write(next);
        return next;
    }

    public synchronized Job fail(String jobId, String detail) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId)) return null;
        Job next = copy(current, State.FAILED, current.completedPlates, current.progress,
                detail == null ? "Batch slice failed" : detail, "Failed");
        write(next);
        return next;
    }

    public synchronized Job cancel(String jobId, String detail) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId)) return null;
        Job next = copy(current, State.CANCELLED, current.completedPlates, current.progress,
                detail == null ? "Batch slice cancelled" : detail, "Cancelled");
        write(next);
        return next;
    }

    /** Convert an orphaned worker checkpoint into explicit recovery review. */
    public synchronized Job recoverAfterRestart(boolean serviceOwnsJob) {
        Job current = load();
        if (current == null || serviceOwnsJob
                || (current.state != State.QUEUED && current.state != State.RUNNING)) return current;
        Job next = copy(current, State.RECOVERY_REQUIRED, current.completedPlates, current.progress,
                "Alloy restarted before all plates completed; review and start the batch again.",
                "Recovery review");
        write(next);
        return next;
    }

    public synchronized Job load() {
        String encoded = preferences.getString(STATE, null);
        if (encoded == null) return null;
        try {
            State state = State.valueOf(encoded);
            String jobId = bounded(preferences.getString(JOB_ID, ""), 64);
            String name = bounded(preferences.getString(DISPLAY_NAME, ""), MAX_DISPLAY_NAME);
            int plateCount = preferences.getInt(PLATE_COUNT, 0);
            int completed = preferences.getInt(COMPLETED_PLATES, 0);
            int progress = preferences.getInt(PROGRESS, 0);
            String detail = bounded(preferences.getString(DETAIL, ""), MAX_DETAIL);
            String phase = bounded(preferences.getString(PHASE, ""), MAX_PHASE);
            long created = preferences.getLong(CREATED_AT, 0L);
            long updated = preferences.getLong(UPDATED_AT, created);
            if (!validJobId(jobId) || name.length() == 0 || plateCount <= 0 || plateCount > PlateStore.MAX_PLATES
                    || completed < 0 || completed > plateCount || progress < 0 || progress > 100
                    || created <= 0L || updated < created)
                throw new IllegalStateException("invalid batch slice checkpoint fields");
            return new Job(jobId, state, name, plateCount, completed, progress, detail, phase, created, updated);
        } catch (Exception ignored) {
            long now = Math.max(1L, System.currentTimeMillis());
            return new Job("00000000-0000-0000-0000-000000000000", State.RECOVERY_REQUIRED,
                    "Unreadable batch", 0, 0, 0,
                    "The batch checkpoint is unreadable; start a new batch or clear it.",
                    "Recovery review", now, now);
        }
    }

    public synchronized void clear() {
        if (!preferences.edit().clear().commit()) throw new IllegalStateException("Could not clear batch slice checkpoint");
    }

    private Job require(String jobId) {
        Job current = load();
        if (current == null || !jobId.equals(current.jobId)) throw new IllegalStateException("batch slice job is unavailable");
        return current;
    }

    private void write(Job job) {
        if (!preferences.edit()
                .putString(STATE, job.state.name())
                .putString(JOB_ID, job.jobId)
                .putString(DISPLAY_NAME, bounded(job.displayName, MAX_DISPLAY_NAME))
                .putInt(PLATE_COUNT, job.plateCount)
                .putInt(COMPLETED_PLATES, job.completedPlates)
                .putInt(PROGRESS, job.progress)
                .putString(DETAIL, bounded(job.detail, MAX_DETAIL))
                .putString(PHASE, bounded(job.phase, MAX_PHASE))
                .putLong(CREATED_AT, job.createdAt)
                .putLong(UPDATED_AT, job.updatedAt)
                .commit()) throw new IllegalStateException("Could not persist batch slice checkpoint");
    }

    private static Job copy(Job current, State state, int completed, int progress, String detail, String phase) {
        return new Job(current.jobId, state, current.displayName, current.plateCount, completed, progress,
                bounded(detail, MAX_DETAIL), bounded(phase, MAX_PHASE), current.createdAt, System.currentTimeMillis());
    }

    private static boolean validText(String value, int max) {
        if (value == null || value.trim().length() == 0 || value.length() > max) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
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
        public final int plateCount;
        public final int completedPlates;
        public final int progress;
        public final String detail;
        public final String phase;
        public final long createdAt;
        public final long updatedAt;

        Job(String jobId, State state, String displayName, int plateCount, int completedPlates, int progress,
            String detail, String phase, long createdAt, long updatedAt) {
            this.jobId = jobId;
            this.state = state;
            this.displayName = displayName;
            this.plateCount = plateCount;
            this.completedPlates = completedPlates;
            this.progress = progress;
            this.detail = detail;
            this.phase = phase;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        public boolean isTerminal() {
            return state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED;
        }
    }
}
