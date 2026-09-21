package com.mbaliga.alloy;

import java.io.File;
import java.util.Locale;

/**
 * Transport seam for discovery, upload and verified print control. Concrete
 * Bambu LAN code belongs behind this interface; the UI must never speak MQTT
 * or FTPS directly.
 */
public interface PrinterTransport {
    void probe(PrinterTarget target, Callback callback);
    /** Read one bounded telemetry snapshot without starting or changing a job. */
    void readStatus(PrinterTarget target, Callback callback);
    void upload(PrinterTarget target, Artifact artifact, ProgressListener progress, Callback callback);
    void startPrint(PrinterTarget target, String remotePath, PrintOptions options, Callback callback);
    void pause(PrinterTarget target, Callback callback);
    void resume(PrinterTarget target, Callback callback);
    void cancel(PrinterTarget target, Callback callback);

    interface ProgressListener {
        void onProgress(int percent, String phase);
    }

    interface Callback {
        void onState(State state, String detail);
    }

    enum State {
        IDLE,
        RECOVERY_REQUIRED,
        CONNECTING,
        READY,
        UPLOADING,
        UPLOADED,
        START_REQUESTED,
        RUNNING,
        PAUSE_REQUESTED,
        PAUSED,
        RESUME_REQUESTED,
        COMPLETED,
        CANCEL_REQUESTED,
        CANCELLED,
        FAILED
    }

    final class PrinterTarget {
        public final String name;
        public final String host;
        public final String serial;

        public PrinterTarget(String name, String host, String serial) {
            this.name = require(name, "printer name");
            this.host = PrinterHostValidator.require(host);
            this.serial = require(serial, "printer serial");
        }
    }

    final class Artifact {
        private static final String SHA256_PATTERN = "[0-9a-f]{64}";
        public final File sourceFile;
        public final String displayName;
        public final long sizeBytes;
        public final String sha256;

        public Artifact(String displayName, long sizeBytes, String sha256) {
            this(null, displayName, sizeBytes, sha256);
        }

        public Artifact(File sourceFile, String displayName, long sizeBytes, String sha256) {
            if (sourceFile != null && (isSymbolicLink(sourceFile) || !sourceFile.isFile() || !sourceFile.canRead()))
                throw new IllegalArgumentException("artifact source is not readable");
            this.sourceFile = sourceFile == null ? null : sourceFile.getAbsoluteFile();
            this.displayName = require(displayName, "artifact name");
            if (sizeBytes < 0) throw new IllegalArgumentException("artifact size is invalid");
            if (sourceFile != null && sourceFile.length() != sizeBytes)
                throw new IllegalArgumentException("artifact size does not match its source file");
            this.sizeBytes = sizeBytes;
            String digest = require(sha256, "artifact digest").toLowerCase(Locale.US);
            if (!digest.matches(SHA256_PATTERN)) throw new IllegalArgumentException("artifact digest must be SHA-256");
            this.sha256 = digest;
        }

        public boolean hasSourceFile() { return sourceFile != null; }
    }

    /** Do not let a durable artifact identity resolve outside app-owned storage. */
    static boolean isSymbolicLink(File file) {
        if (file == null) return false;
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            // If link inspection is unavailable, reject the source rather than
            // allowing a path whose ownership cannot be established.
            return true;
        }
    }

    final class PrintOptions {
        public final boolean timelapse;
        public final boolean bedLeveling;
        public final boolean flowCalibration;
        public final boolean vibrationCalibration;

        public PrintOptions(boolean timelapse, boolean bedLeveling, boolean flowCalibration, boolean vibrationCalibration) {
            this.timelapse = timelapse;
            this.bedLeveling = bedLeveling;
            this.flowCalibration = flowCalibration;
            this.vibrationCalibration = vibrationCalibration;
        }
    }

    static String require(String value, String label) {
        if (value == null || value.trim().length() == 0) throw new IllegalArgumentException(label + " is required");
        return value;
    }
}
