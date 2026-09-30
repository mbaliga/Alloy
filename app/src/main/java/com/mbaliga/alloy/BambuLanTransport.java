package com.mbaliga.alloy;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.BCExtendedSSLSession;
import org.bouncycastle.jsse.BCSSLSocket;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;

/**
 * Open LAN transport for Bambu Developer Mode. It uses implicit TLS FTPS for
 * the artifact and MQTT/TLS for start, telemetry evidence, and cancellation.
 * It deliberately has no trust-all mode and remains behind PrinterTransport so
 * the UI cannot accidentally expose a physical Print action before validation.
 */
public final class BambuLanTransport implements PrinterTransport, Closeable {
    private static final int FTPS_PORT = 990;
    private static final int MQTT_PORT = 8883;
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 1_000;
    private static final int MAX_FTP_REPLY_BYTES = 64 * 1024;
    private static final long ACCEPT_TIMEOUT_MS = 30_000L;
    private static final long CONTROL_TIMEOUT_MS = 30_000L;
    private static final long STATUS_TIMEOUT_MS = 6_000L;
    private static final long RUN_TIMEOUT_MS = 24L * 60L * 60L * 1000L;
    private static final int MAX_TELEMETRY_RECONNECTS = 3;
    private static final long TELEMETRY_RECONNECT_DELAY_MS = 350L;
    private static final String[] FTPS_PROTOCOLS = new String[]{"TLSv1.2"};
    private static final Pattern PASV = Pattern.compile("\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern FTP_SIZE = Pattern.compile("(?m)^213\\s+(\\d+)\\s*$");
    private static final Pattern SAFE_REMOTE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,180}");

    private final PrinterCredentialStore.Credentials credentials;
    private final SSLSocketFactory socketFactory;
    private final int ftpsPort;
    private final int mqttPort;
    private final boolean requireFtpsSessionReuse;
    private final boolean certificatePinned;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "alloy-bambu-lan");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private volatile MqttSession activeSession;
    private volatile PrinterTransport.PrinterTarget activeTarget;
    private volatile boolean closed;
    private volatile ControlRequest controlRequest = ControlRequest.NONE;
    private volatile long controlRequestedAt;

    public BambuLanTransport(PrinterCredentialStore.Credentials credentials) {
        // Bambu's FTPS service requires the data socket to resume the exact
        // TLS session opened for the control socket. Android's default
        // Conscrypt factory does not expose the explicit BCJSSE session API
        // that the transport uses for that check, so even an unpinned
        // read-only probe would fail after the MQTT handshake. Keep the
        // system trust store for probing, but use the same BCJSSE provider as
        // the certificate-pinned send path.
        this(credentials, systemTrustedSocketFactory(), FTPS_PORT, MQTT_PORT, true, false);
    }

    public BambuLanTransport(PrinterCredentialStore.Credentials credentials, SSLSocketFactory socketFactory) {
        this(credentials, socketFactory, FTPS_PORT, MQTT_PORT, true, false);
    }

    /** Package-visible endpoint seam for a deterministic loopback fake printer test. */
    BambuLanTransport(PrinterCredentialStore.Credentials credentials, SSLSocketFactory socketFactory,
                      int ftpsPort, int mqttPort) {
        this(credentials, socketFactory, ftpsPort, mqttPort, true, false);
    }

    /**
     * Package-visible test seam. Production constructors always require
     * explicit BCJSSE FTPS session reuse; false is used only with the
     * plaintext loopback protocol transcript, which has no TLS session.
     */
    BambuLanTransport(PrinterCredentialStore.Credentials credentials, SSLSocketFactory socketFactory,
                      int ftpsPort, int mqttPort, boolean requireFtpsSessionReuse) {
        this(credentials, socketFactory, ftpsPort, mqttPort, requireFtpsSessionReuse, false);
    }

    private BambuLanTransport(PrinterCredentialStore.Credentials credentials, SSLSocketFactory socketFactory,
                              int ftpsPort, int mqttPort, boolean requireFtpsSessionReuse,
                              boolean certificatePinned) {
        if (credentials == null) throw new IllegalArgumentException("printer credentials are required");
        if (socketFactory == null) throw new IllegalArgumentException("TLS socket factory is required");
        if (ftpsPort < 1 || ftpsPort > 65535 || mqttPort < 1 || mqttPort > 65535)
            throw new IllegalArgumentException("printer service ports are invalid");
        this.credentials = credentials;
        this.socketFactory = socketFactory;
        this.ftpsPort = ftpsPort;
        this.mqttPort = mqttPort;
        this.requireFtpsSessionReuse = requireFtpsSessionReuse;
        this.certificatePinned = certificatePinned;
    }

    /** Create a transport for a printer whose self-signed leaf certificate was explicitly paired. */
    public static BambuLanTransport pinned(PrinterCredentialStore.Credentials credentials, String sha256Fingerprint)
            throws GeneralSecurityException {
        return new BambuLanTransport(credentials, pinnedSocketFactory(sha256Fingerprint), FTPS_PORT, MQTT_PORT,
                true, true);
    }

    @Override
    public void probe(PrinterTarget target, Callback callback) {
        execute(() -> {
            state(callback, State.CONNECTING, "Connecting to printer LAN service");
            try {
                requirePinnedAuthentication();
                validateTarget(target);
                try (MqttSession session = MqttSession.connect(socketFactory, target.host, credentials, mqttPort)) {
                try (ImplicitFtpsClient ignored = new ImplicitFtpsClient(socketFactory, target.host, credentials.accessCode,
                        ftpsPort, requireFtpsSessionReuse)) {
                    state(callback, State.READY, "FTPS and MQTT/TLS connections verified");
                }
                }
            } catch (Exception error) {
                if (!closed) state(callback, State.FAILED, safeMessage(error));
            }
        }, callback);
    }

    @Override
    public void readStatus(PrinterTarget target, Callback callback) {
        execute(() -> {
            MqttSession session = null;
            try {
                requirePinnedAuthentication();
                validateTarget(target);
                state(callback, State.CONNECTING, "Reading printer telemetry");
                session = MqttSession.connect(socketFactory, target.host, credentials, mqttPort);
                session.subscribe(reportTopic(target.serial));
                long deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS;
                while (!closed && System.currentTimeMillis() < deadline) {
                    try {
                        session.keepAlive();
                        MqttPacket packet = session.readPacket();
                        if (packet.type != 3) continue;
                        Telemetry telemetry = Telemetry.parse(packet.publishPayload());
                        if (telemetry.valid) {
                            state(callback, State.READY, telemetry.summary());
                            return;
                        }
                    } catch (SocketTimeoutException ignored) {
                        // Keep the short read timeout so closing the transport
                        // or leaving the screen can interrupt this snapshot.
                    }
                }
                if (!closed) state(callback, State.STATUS_UNCONFIRMED,
                        "LAN services reachable, but the printer returned no telemetry snapshot; physical state is unconfirmed");
            } catch (Exception error) {
                if (!closed) state(callback, State.FAILED, safeMessage(error));
            } finally {
                closeQuietly(session);
            }
        }, callback);
    }

    @Override
    public void upload(PrinterTarget target, Artifact artifact, ProgressListener progress, Callback callback) {
        execute(() -> {
            try {
                requirePinnedAuthentication();
                validateTarget(target);
                if (artifact == null || !artifact.hasSourceFile()) throw new IllegalArgumentException("a local artifact is required");
                state(callback, State.CONNECTING, "Connecting to printer file service");
                state(callback, State.UPLOADING, artifact.displayName);
                String remotePath = uploadInternal(target.host, artifact, progress);
                state(callback, State.UPLOADED, remotePath + " · local SHA-256 and remote size verified");
            } catch (Exception error) {
                state(callback, State.FAILED, safeMessage(error));
            }
        }, callback);
    }

    @Override
    public void startPrint(PrinterTarget target, String remotePath, PrintOptions options, Callback callback) {
        execute(() -> {
            MqttSession session = null;
            // A successful write does not prove that the printer accepted the
            // command, and a failed write does not prove that no bytes were
            // received. Once a start packet is attempted, a retry could
            // duplicate a physical print, so every post-attempt failure must
            // remain recoverable rather than looking like a clean failure.
            boolean startCommandAttempted = false;
            try {
                requirePinnedAuthentication();
                validateTarget(target);
                if (options == null) throw new IllegalArgumentException("print options are required");
                String remoteName = remoteName(remotePath);
                state(callback, State.CONNECTING, "Connecting to printer telemetry");
                session = MqttSession.connect(socketFactory, target.host, credentials, mqttPort);
                session.subscribe(reportTopic(target.serial));
                synchronized (this) {
                    activeSession = session;
                    activeTarget = target;
                    cancelRequested = false;
                    controlRequest = ControlRequest.NONE;
                    controlRequestedAt = 0L;
                }
                try {
                    // A printer may publish its current state immediately
                    // after SUBSCRIBE. Reject explicit busy states before
                    // project_file can replace an active job; a missing
                    // snapshot is tolerated because some firmware revisions
                    // do not send one until the next report interval.
                    MqttPacket preflight = session.readPacket();
                    if (preflight.type == 3 && telemetryBlocksNewStart(preflight.publishPayload()))
                        throw new IOException("Printer is already busy; review its current job before starting another");
                } catch (SocketTimeoutException ignored) {
                    // No immediate snapshot is not proof of a busy printer.
                }
                startCommandAttempted = true;
                session.publish(requestTopic(target.serial), projectFilePayload(remoteName, options));
                state(callback, State.START_REQUESTED, "Start command sent; waiting for printer telemetry");
                monitorPrint(session, target, remoteName, callback);
            } catch (Exception error) {
                if (!closed) state(callback,
                        startCommandAttempted ? State.RECOVERY_REQUIRED : State.FAILED,
                        startCommandAttempted
                                ? "Start command outcome is unknown; verify the printer before retrying: "
                                + safeMessage(error)
                                : safeMessage(error));
            } finally {
                MqttSession sessionToClose;
                synchronized (this) {
                    sessionToClose = activeSession;
                    activeSession = null;
                    activeTarget = null;
                    controlRequest = ControlRequest.NONE;
                    controlRequestedAt = 0L;
                }
                closeQuietly(sessionToClose);
                closeQuietly(session);
            }
        }, callback);
    }

    /**
     * Revalidate the local package identity and the printer's remote file
     * immediately before a start command is allowed. Upload completion is not
     * permanent evidence: the printer may have rebooted, rotated its storage,
     * or replaced the same-name file while Alloy was backgrounded.
     *
     * This is intentionally a concrete transport operation rather than a UI
     * check. The service calls it after recovering an uploaded job and before
     * it persists START_REQUESTED or publishes MQTT project_file.
     */
    public void verifyUploadedArtifact(PrinterTarget target, Artifact artifact) throws IOException {
        requirePinnedAuthentication();
        validateTarget(target);
        validateLocalArtifact(artifact);
        String name = remoteName(artifact.displayName);
        try (ImplicitFtpsClient ftp = new ImplicitFtpsClient(socketFactory, target.host, credentials.accessCode,
                ftpsPort, requireFtpsSessionReuse)) {
            ftp.verifyRemoteSize(name, artifact.sizeBytes);
        }
    }

    @Override
    /**
     * Serialize stop with pause/resume. A stop request is a physical side
     * effect, so it must not race a control request that is already checking
     * or updating the shared session state. The lock is deliberately held
     * through publish: once either command has started writing, the other
     * command observes the resulting pending state instead of interleaving
     * bytes or presenting two contradictory commands to the printer.
     */
    public synchronized void cancel(PrinterTarget target, Callback callback) {
        try {
            requirePinnedAuthentication();
        } catch (IOException error) {
            state(callback, State.FAILED, safeMessage(error));
            return;
        }
        MqttSession session = activeSession;
        if (session == null || activeTarget == null || !sameTarget(activeTarget, target)) {
            state(callback, State.FAILED, "No active printer job is connected");
            return;
        }
        boolean commandAttempted = false;
        try {
            cancelRequested = true;
            controlRequest = ControlRequest.NONE;
            controlRequestedAt = 0L;
            commandAttempted = true;
            session.publish(requestTopic(target.serial), stopPayload());
            state(callback, State.CANCEL_REQUESTED, "Stop command sent; waiting for idle telemetry");
        } catch (Exception error) {
            cancelRequested = false;
            state(callback,
                    commandAttempted ? State.RECOVERY_REQUIRED : State.FAILED,
                    commandAttempted
                            ? "Stop command outcome is unknown; verify the printer before retrying: "
                            + safeMessage(error)
                            : safeMessage(error));
        }
    }

    @Override
    public void pause(PrinterTarget target, Callback callback) {
        requestControl(target, callback, ControlRequest.PAUSE);
    }

    @Override
    public void resume(PrinterTarget target, Callback callback) {
        requestControl(target, callback, ControlRequest.RESUME);
    }

    private synchronized void requestControl(PrinterTarget target, Callback callback, ControlRequest request) {
        try {
            requirePinnedAuthentication();
        } catch (IOException error) {
            state(callback, State.FAILED, safeMessage(error));
            return;
        }
        MqttSession session = activeSession;
        if (session == null || activeTarget == null || !sameTarget(activeTarget, target)) {
            state(callback, State.FAILED, "No active printer job is connected");
            return;
        }
        if (cancelRequested || controlRequest != ControlRequest.NONE) {
            state(callback, State.FAILED, "Another printer control request is already pending");
            return;
        }
        boolean commandAttempted = false;
        try {
            controlRequest = request;
            controlRequestedAt = System.currentTimeMillis();
            commandAttempted = true;
            session.publish(requestTopic(target.serial), commandPayload(request == ControlRequest.PAUSE ? "pause" : "resume"));
            state(callback, request == ControlRequest.PAUSE ? State.PAUSE_REQUESTED : State.RESUME_REQUESTED,
                    (request == ControlRequest.PAUSE ? "Pause" : "Resume") + " command sent; waiting for printer telemetry");
        } catch (Exception error) {
            controlRequest = ControlRequest.NONE;
            controlRequestedAt = 0L;
            state(callback,
                    commandAttempted ? State.RECOVERY_REQUIRED : State.FAILED,
                    commandAttempted
                            ? (request == ControlRequest.PAUSE ? "Pause" : "Resume")
                            + " command outcome is unknown; verify the printer before retrying: "
                            + safeMessage(error)
                            : safeMessage(error));
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeQuietly(activeSession);
        executor.shutdownNow();
    }

    private void monitorPrint(MqttSession session, PrinterTransport.PrinterTarget target,
                              String remoteName, Callback callback) throws IOException {
        long acceptedDeadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS;
        long terminalDeadline = System.currentTimeMillis() + RUN_TIMEOUT_MS;
        boolean accepted = false;
        boolean running = false;
        // Some firmware revisions omit file fields once a stopped job returns
        // to idle. Preserve identity evidence from an earlier matching
        // PREPARE/RUNNING packet, but never accept an unrelated idle packet
        // before the requested job has been observed.
        boolean matchingJobSeen = false;
        int reconnects = 0;
        MqttSession currentSession = session;
        while (!closed && System.currentTimeMillis() < terminalDeadline) {
            if (!accepted && System.currentTimeMillis() > acceptedDeadline)
                throw new IOException("Printer did not confirm the requested job over telemetry");
            if (controlRequest != ControlRequest.NONE
                    && System.currentTimeMillis() - controlRequestedAt > CONTROL_TIMEOUT_MS) {
                controlRequest = ControlRequest.NONE;
                controlRequestedAt = 0L;
                state(callback, State.RECOVERY_REQUIRED,
                        "Printer did not confirm the pause/resume request; verify the printer before continuing");
                return;
            }
            try {
                currentSession.keepAlive();
                MqttPacket packet = currentSession.readPacket();
                if (packet.type != 3) continue;
                PrinterTelemetryReducer.ControlRequest request = controlRequest == ControlRequest.PAUSE
                        ? PrinterTelemetryReducer.ControlRequest.PAUSE
                        : controlRequest == ControlRequest.RESUME
                        ? PrinterTelemetryReducer.ControlRequest.RESUME
                        : PrinterTelemetryReducer.ControlRequest.NONE;
                PrinterTelemetryReducer.Decision decision = PrinterTelemetryReducer.observe(
                        remoteName, packet.publishPayload(), accepted, running, matchingJobSeen,
                        cancelRequested, request);
                accepted = decision.accepted;
                running = decision.running;
                matchingJobSeen = decision.matchingJobSeen;
                if (decision.controlRequest == PrinterTelemetryReducer.ControlRequest.NONE
                        && request != PrinterTelemetryReducer.ControlRequest.NONE) {
                    controlRequest = ControlRequest.NONE;
                    controlRequestedAt = 0L;
                }
                switch (decision.event) {
                    case PAUSED:
                        state(callback, State.PAUSED, "Printer confirmed the job is paused");
                        break;
                    case RESUMED:
                        state(callback, State.RUNNING, "Printer confirmed the job resumed");
                        break;
                    case RUNNING:
                        state(callback, State.RUNNING, "Printer confirmed the job is running");
                        break;
                    case COMPLETED:
                        state(callback, State.COMPLETED, "Printer reported job completion");
                        return;
                    case CANCELLED:
                        state(callback, State.CANCELLED, cancelRequested
                                ? "Printer returned to idle after stop request"
                                : "Printer cancelled the job");
                        return;
                    case FAILED:
                        state(callback, State.FAILED, "Printer reported a print error");
                        return;
                    default:
                        break;
                }
            } catch (SocketTimeoutException ignored) {
                // The short read timeout lets close() and cancel() interrupt monitoring.
            } catch (IOException connectionError) {
                if (closed) return;
                closeQuietly(currentSession);
                synchronized (this) {
                    if (activeSession == currentSession) activeSession = null;
                }
                if (++reconnects > MAX_TELEMETRY_RECONNECTS) {
                    state(callback, State.RECOVERY_REQUIRED,
                            "Printer telemetry connection was lost; verify the printer before continuing");
                    return;
                }
                state(callback, State.CONNECTING, "Printer telemetry interrupted; reconnecting ("
                        + reconnects + "/" + MAX_TELEMETRY_RECONNECTS + ")");
                if (!interruptibleDelay(TELEMETRY_RECONNECT_DELAY_MS * reconnects)) return;
                try {
                    currentSession = MqttSession.connect(socketFactory, target.host, credentials, mqttPort);
                    currentSession.subscribe(reportTopic(target.serial));
                    synchronized (this) {
                        if (closed) {
                            closeQuietly(currentSession);
                            return;
                        }
                        activeSession = currentSession;
                        activeTarget = target;
                    }
                    reconnects = 0;
                } catch (IOException reconnectError) {
                    closeQuietly(currentSession);
                    if (++reconnects > MAX_TELEMETRY_RECONNECTS) {
                        state(callback, State.RECOVERY_REQUIRED,
                                "Printer telemetry reconnect failed; verify the printer before continuing");
                        return;
                    }
                    state(callback, State.CONNECTING, "Printer telemetry reconnect attempt failed; retrying");
                    if (!interruptibleDelay(TELEMETRY_RECONNECT_DELAY_MS * reconnects)) return;
                }
            }
        }
        if (closed) return;
        throw new IOException("Timed out waiting for terminal printer telemetry");
    }

    /** Delay in small interruptible increments so closing the Activity cancels promptly. */
    private boolean interruptibleDelay(long delayMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, delayMs);
        while (!closed && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(100L, Math.max(1L, deadline - System.currentTimeMillis())));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !closed;
    }

    /* Cancellation evidence is deliberately limited to the currently paired printer session. */
    private volatile boolean cancelRequested;

    private String uploadInternal(String host, Artifact artifact, ProgressListener progress) throws IOException {
        validateLocalArtifact(artifact);
        File source = artifact.sourceFile;
        String name = remoteName(artifact.displayName);
        try (ImplicitFtpsClient ftp = new ImplicitFtpsClient(socketFactory, host, credentials.accessCode,
                ftpsPort, requireFtpsSessionReuse)) {
            ftp.upload(name, source, artifact.sizeBytes, progress);
        }
        return "/" + name;
    }

    private synchronized void execute(Runnable task, Callback callback) {
        if (closed) {
            state(callback, State.FAILED, "Transport is closed");
            return;
        }
        // Serialize the closed check with close(): otherwise shutdownNow()
        // can land between the check and execute(), leaking
        // RejectedExecutionException through the public transport API.
        try {
            executor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            state(callback, State.FAILED, "Transport is closed");
        }
    }

    private void requirePinnedAuthentication() throws IOException {
        // The only unauthenticated TLS operation allowed in production is the
        // separate certificate-inspection helper below. Authenticated MQTT or
        // FTPS traffic must use the explicitly paired leaf certificate. The
        // plaintext loopback seam used by Android tests is exempt because it
        // never represents a real printer or sends real credentials.
        if (requireFtpsSessionReuse && !certificatePinned)
            throw new IOException("Printer certificate pin is required before LAN authentication");
    }

    private void validateTarget(PrinterTarget target) {
        if (target == null) throw new IllegalArgumentException("printer target is required");
        if (!credentials.host.equals(target.host) || !credentials.serial.equals(target.serial))
            throw new IllegalArgumentException("Printer target does not match the paired credentials");
    }

    private static boolean sameTarget(PrinterTarget a, PrinterTarget b) {
        return b != null && a.host.equals(b.host) && a.serial.equals(b.serial);
    }

    private static void state(Callback callback, State state, String detail) {
        if (callback != null) callback.onState(state, detail == null ? "" : detail);
    }

    private static String requestTopic(String serial) { return "device/" + serial + "/request"; }
    private static String reportTopic(String serial) { return "device/" + serial + "/report"; }

    private static String remoteName(String path) {
        if (path == null) throw new IllegalArgumentException("remote artifact path is required");
        String name = path;
        if (name.startsWith("/")) name = name.substring(1);
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (!SAFE_REMOTE_NAME.matcher(name).matches() || !name.toLowerCase(Locale.US).endsWith(".gcode.3mf"))
            throw new IllegalArgumentException("Only a safe .gcode.3mf artifact name may be uploaded");
        return name;
    }

    static String projectFilePayload(String remoteName, PrintOptions options) throws Exception {
        JSONObject print = new JSONObject();
        print.put("sequence_id", "0");
        print.put("command", "project_file");
        print.put("param", "Metadata/plate_1.gcode");
        print.put("subtask_name", remoteName.substring(0, remoteName.length() - ".gcode.3mf".length()));
        print.put("url", "ftp:///" + remoteName);
        print.put("project_id", "0");
        print.put("profile_id", "0");
        print.put("task_id", "0");
        print.put("subtask_id", "0");
        print.put("timelapse", options.timelapse);
        // `auto` is the firmware-neutral local-print value. The user-visible
        // plate selection is intentionally not encoded until it is validated
        // against the target printer firmware.
        print.put("bed_type", "auto");
        print.put("bed_leveling", options.bedLeveling);
        // Some firmware/tooling documents use the alternate spelling. Keeping
        // both booleans equal is harmless for parsers that ignore unknown keys
        // and avoids silently dropping leveling on older targets.
        print.put("bed_levelling", options.bedLeveling);
        print.put("flow_cali", options.flowCalibration);
        print.put("vibration_cali", options.vibrationCalibration);
        print.put("layer_inspect", false);
        print.put("file", "");
        print.put("md5", "");
        print.put("use_ams", false);
        return new JSONObject().put("print", print).toString();
    }

    static String stopPayload() throws Exception {
        return new JSONObject().put("print", new JSONObject().put("command", "stop")).toString();
    }

    static String commandPayload(String command) throws Exception {
        if (command == null || !("pause".equals(command) || "resume".equals(command)))
            throw new IllegalArgumentException("Unsupported printer control command");
        return new JSONObject().put("print", new JSONObject().put("command", command)).toString();
    }

    /** Package-visible pure seams for regression tests; no network is touched. */
    static boolean telemetryMentionsJob(String payload, String remoteName) {
        return Telemetry.parse(payload).mentionsJob(remoteName);
    }

    static boolean telemetryHasState(String payload, String... values) {
        return Telemetry.parse(payload).hasState(values);
    }

    /** Return true only for explicit states in which replacing a print is unsafe. */
    static boolean telemetryBlocksNewStart(String payload) {
        return Telemetry.parse(payload).hasState("prepare", "running", "pause", "paused", "slicing");
    }

    /**
     * A final idle report may omit the filename, but only after an earlier
     * report identified the requested artifact. This pure seam keeps the
     * cancellation rule regression-testable without a printer connection.
     */
    static boolean telemetryConfirmsCancellation(String payload, String remoteName, boolean accepted,
                                                 boolean matchingJobSeen) {
        if (remoteName == null || !SAFE_REMOTE_NAME.matcher(remoteName).matches()
                || !remoteName.toLowerCase(Locale.US).endsWith(".gcode.3mf")) return false;
        return confirmsCancellation(Telemetry.parse(payload), remoteName, accepted, matchingJobSeen);
    }

    private static boolean confirmsCancellation(Telemetry telemetry, String remoteName, boolean accepted,
                                                 boolean matchingJobSeen) {
        return accepted && matchingJobSeen && telemetry.valid && telemetry.hasState("idle");
    }

    static String telemetrySummary(String payload) {
        Telemetry telemetry = Telemetry.parse(payload);
        return telemetry.valid ? telemetry.summary() : "";
    }

    /** Package-visible protocol seams used by deterministic Android tests. */
    static MqttPacket readMqttPacket(InputStream input) throws IOException {
        return MqttSession.readMqttPacket(input);
    }

    static int[] parsePassiveAddress(String response) throws IOException {
        return ImplicitFtpsClient.parsePassiveAddress(response);
    }

    static long parseRemoteSize(String response) throws IOException {
        return ImplicitFtpsClient.parseRemoteSize(response);
    }

    /** Parse the structured print object instead of substring-matching raw JSON. */
    private static final class Telemetry {
        final JSONObject print;
        final boolean valid;

        private Telemetry(JSONObject print, boolean valid) {
            this.print = print;
            this.valid = valid;
        }

        static Telemetry parse(String payload) {
            if (payload == null || payload.length() == 0) return new Telemetry(null, false);
            try {
                JSONObject root = new JSONObject(payload);
                JSONObject print = root.optJSONObject("print");
                return new Telemetry(print, print != null);
            } catch (Exception ignored) {
                // Reports are periodic; malformed packets are ignored and the
                // monitoring deadline remains the source of failure evidence.
                return new Telemetry(null, false);
            }
        }

        boolean hasState(String... values) {
            if (!valid || values == null) return false;
            String state = print.optString("gcode_state", "").trim().toLowerCase(Locale.US);
            for (String value : values) if (value != null && state.equals(value.toLowerCase(Locale.US))) return true;
            return false;
        }

        boolean mentionsJob(String remoteName) {
            if (!valid || remoteName == null || !SAFE_REMOTE_NAME.matcher(remoteName).matches()
                    || !remoteName.toLowerCase(Locale.US).endsWith(".gcode.3mf")) return false;
            String normalizedRemote = remoteName.toLowerCase(Locale.US);
            String baseName = remoteName.substring(0, remoteName.length() - ".gcode.3mf".length()).toLowerCase(Locale.US);
            return matches(print.optString("subtask_name", ""), normalizedRemote, baseName)
                    || matches(print.optString("file", ""), normalizedRemote, baseName)
                    || matches(print.optString("url", ""), normalizedRemote, baseName)
                    || matches(print.optString("gcode_file", ""), normalizedRemote, baseName);
        }

        String summary() {
            StringBuilder result = new StringBuilder("Telemetry · state ")
                    .append(text(print, "gcode_state", "UNKNOWN", 32).toUpperCase(Locale.US));
            int percent = integer(print, "mc_percent", -1, 0, 100);
            if (percent >= 0) result.append(" · ").append(percent).append('%');
            float nozzle = decimal(print, "nozzle_temper", -1f);
            if (nozzle < 0f) nozzle = decimal(print, "nozzle_temperature", -1f);
            if (nozzle >= 0f) result.append(" · nozzle ").append(String.format(Locale.US, "%.1f°C", nozzle));
            float bed = decimal(print, "bed_temper", -1f);
            if (bed < 0f) bed = decimal(print, "bed_temperature", -1f);
            if (bed >= 0f) result.append(" · bed ").append(String.format(Locale.US, "%.1f°C", bed));
            int remaining = integer(print, "mc_remaining_time", -1, 0, 7 * 24 * 60);
            if (remaining >= 0) result.append(" · ").append(remaining / 60).append('m');
            return result.toString();
        }

        private static String text(JSONObject object, String key, String fallback, int max) {
            String value = object == null ? "" : object.optString(key, "").trim();
            if (value.length() == 0) return fallback;
            return value.length() > max ? value.substring(0, max) : value;
        }

        private static int integer(JSONObject object, String key, int fallback, int min, int max) {
            try {
                int value = Integer.parseInt(object.optString(key, "").trim());
                return value < min || value > max ? fallback : value;
            } catch (Exception ignored) { return fallback; }
        }

        private static float decimal(JSONObject object, String key, float fallback) {
            try {
                float value = Float.parseFloat(object.optString(key, "").trim());
                return Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value > 500f ? fallback : value;
            } catch (Exception ignored) { return fallback; }
        }

        private static boolean matches(String value, String remoteName, String baseName) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
            return normalized.equals(remoteName)
                    || normalized.equals(baseName)
                    || normalized.endsWith("/" + remoteName)
                    || normalized.endsWith("/" + baseName);
        }
    }

    private enum ControlRequest { NONE, PAUSE, RESUME }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.length() == 0 ? error.getClass().getSimpleName() : message;
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) try { closeable.close(); } catch (IOException ignored) { }
    }

    private static SSLSocketFactory pinnedSocketFactory(String expectedFingerprint) throws GeneralSecurityException {
        final String expected = normalizeFingerprint(expectedFingerprint);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        X509TrustManager delegate = null;
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager) {
            delegate = (X509TrustManager) manager;
            break;
        }
        if (delegate == null) throw new GeneralSecurityException("No X.509 trust manager is available");
        final X509TrustManager trusted = delegate;
        X509TrustManager pinned = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                trusted.checkClientTrusted(chain, authType);
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0 || !expected.equals(fingerprint(chain[0])))
                    throw new CertificateException("Printer certificate fingerprint mismatch");
                try {
                    trusted.checkServerTrusted(chain, authType);
                } catch (CertificateException ignored) {
                    // Explicit certificate pinning is the deliberate pairing decision for self-signed printers.
                }
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return trusted.getAcceptedIssuers(); }
        };
        Provider cryptoProvider = new BouncyCastleProvider();
        SSLContext context = SSLContext.getInstance("TLS", new BouncyCastleJsseProvider(cryptoProvider));
        context.init(null, new TrustManager[]{pinned}, new SecureRandom());
        return context.getSocketFactory();
    }

    /** Build an explicit BCJSSE factory while retaining Android's system CAs. */
    private static SSLSocketFactory systemTrustedSocketFactory() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            factory.init((java.security.KeyStore) null);
            Provider cryptoProvider = new BouncyCastleProvider();
            SSLContext context = SSLContext.getInstance(
                    "TLS", new BouncyCastleJsseProvider(cryptoProvider));
            context.init(null, factory.getTrustManagers(), new SecureRandom());
            return context.getSocketFactory();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Could not initialize Bambu TLS", error);
        }
    }

    private static void configureFtpsSocket(SSLSocket socket) throws IOException {
        try {
            // Bambu's session-reuse requirement is defined for its TLS 1.2
            // FTPS service. Do not allow a TLS 1.3 ticket to look like a
            // resumed control/data session without an exact-session check.
            socket.setEnabledProtocols(FTPS_PROTOCOLS);
        } catch (IllegalArgumentException error) {
            throw new IOException("FTPS TLS 1.2 is unavailable", error);
        }
    }

    static String normalizeFingerprint(String value) throws GeneralSecurityException {
        if (value == null) throw new GeneralSecurityException("Printer certificate fingerprint is required");
        String normalized = value.replace(":", "").replace(" ", "").toLowerCase(Locale.US);
        if (!normalized.matches("[0-9a-f]{64}")) throw new GeneralSecurityException("Printer fingerprint must be SHA-256");
        return normalized;
    }

    /**
     * Inspect the MQTT TLS leaf certificate without opening an MQTT or FTP
     * session. This is a pairing-only TOFU helper for self-signed printers:
     * the returned digest must still be explicitly saved as the pin before a
     * physical upload/start can be attempted. No access code or command is
     * sent on this socket.
     */
    static String inspectCertificateFingerprint(String host) throws IOException {
        String safeHost = pairingHost(host);
        CertificateCaptureTrustManager capture = new CertificateCaptureTrustManager();
        SSLContext context;
        try {
            context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{capture}, new SecureRandom());
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not initialize certificate inspection", error);
        }
        SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket();
        try {
            socket.connect(new InetSocketAddress(safeHost, MQTT_PORT), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(CONNECT_TIMEOUT_MS);
            socket.startHandshake();
            if (capture.leaf == null) throw new IOException("Printer did not present a leaf certificate");
            try {
                return fingerprint(capture.leaf);
            } catch (CertificateException error) {
                throw new IOException("Could not fingerprint the printer certificate", error);
            }
        } finally {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    private static String pairingHost(String value) {
        return PrinterHostValidator.require(value);
    }

    private static final class CertificateCaptureTrustManager implements X509TrustManager {
        private X509Certificate leaf;

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("Client certificates are not accepted");
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0 || chain[0] == null)
                throw new CertificateException("Printer did not present a certificate");
            leaf = chain[0];
        }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private static String fingerprint(X509Certificate certificate) throws CertificateException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) out.append(String.format(Locale.US, "%02x", value & 0xff));
            return out.toString();
        } catch (Exception error) {
            throw new CertificateException("Could not fingerprint printer certificate", error);
        }
    }

    private static final class MqttSession implements Closeable {
        private final SSLSocket socket;
        private final InputStream input;
        private final OutputStream output;
        private int packetId = 1;
        private long lastWrite = System.currentTimeMillis();

        private MqttSession(SSLSocket socket) throws IOException {
            this.socket = socket;
            this.input = socket.getInputStream();
            this.output = socket.getOutputStream();
        }

        static MqttSession connect(SSLSocketFactory factory, String host, PrinterCredentialStore.Credentials credentials,
                                   int port) throws IOException {
            SSLSocket socket = (SSLSocket) factory.createSocket();
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                socket.startHandshake();
                MqttSession session = new MqttSession(socket);
                session.connectPacket(credentials);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                return session;
            } catch (Exception error) {
                try { socket.close(); } catch (IOException ignored) { }
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("MQTT/TLS connection failed", error);
            }
        }

        private void connectPacket(PrinterCredentialStore.Credentials credentials) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            utf8(body, "MQTT");
            body.write(4);
            body.write(0xC2);
            body.write(0);
            body.write(60);
            utf8(body, "alloy-" + UUID.randomUUID().toString());
            utf8(body, "bblp");
            utf8(body, credentials.accessCode);
            packet(0x10, body.toByteArray());
            MqttPacket response = readPacket();
            if (response.type != 2 || response.body.length < 2 || response.body[1] != 0)
                throw new IOException("Printer rejected the MQTT connection");
        }

        void subscribe(String topic) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int subscriptionId = packetId++;
            body.write((subscriptionId >> 8) & 0xff); body.write(subscriptionId & 0xff);
            utf8(body, topic); body.write(0);
            packet(0x82, body.toByteArray());
            MqttPacket response;
            do { response = readPacket(); } while (response.type != 9);
            if (response.body.length < 3
                    || ((response.body[0] & 0xff) << 8 | (response.body[1] & 0xff)) != subscriptionId
                    || (response.body[2] & 0xff) >= 0x80)
                throw new IOException("Printer rejected the telemetry subscription");
        }

        synchronized void publish(String topic, String payload) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            utf8(body, topic);
            body.write(payload.getBytes(StandardCharsets.UTF_8));
            packet(0x30, body.toByteArray());
        }

        synchronized void keepAlive() throws IOException {
            if (System.currentTimeMillis() - lastWrite >= 30_000L) packet(0xC0, new byte[0]);
        }

        MqttPacket readPacket() throws IOException {
            return readMqttPacket(input);
        }

        /** Package-visible protocol seam used by deterministic Android tests. */
        static MqttPacket readMqttPacket(InputStream input) throws IOException {
            if (input == null) throw new IllegalArgumentException("MQTT input is required");
            int header = input.read();
            if (header < 0) throw new IOException("Printer closed the MQTT connection");
            int multiplier = 1;
            long remainingLong = 0L;
            int digit;
            int lengthBytes = 0;
            do {
                if (++lengthBytes > 4) throw new IOException("MQTT packet length uses more than four bytes");
                digit = input.read();
                if (digit < 0) throw new IOException("Malformed MQTT packet length");
                remainingLong += (long) (digit & 127) * multiplier;
                if (remainingLong > 8L * 1024L * 1024L) throw new IOException("MQTT packet exceeds the supported size");
                multiplier *= 128;
            } while ((digit & 128) != 0);
            int remaining = (int) remainingLong;
            byte[] body = new byte[remaining];
            readFully(input, body);
            int type = (header >> 4) & 0x0f;
            int flags = header & 0x0f;
            validateMqttHeader(type, flags);
            return new MqttPacket(type, flags, body);
        }

        private static void validateMqttHeader(int type, int flags) throws IOException {
            if (type == 0 || type > 15) throw new IOException("Malformed MQTT packet type");
            // MQTT fixes the low nibble for every packet except PUBLISH.
            // Rejecting bad flags here prevents a malformed control packet
            // from being mistaken for an acknowledgement or keep-alive.
            int expected = -1;
            switch (type) {
                case 1: case 2: case 4: case 5: case 7: case 9:
                case 11: case 12: case 13: case 14: case 15:
                    expected = 0; break;
                case 6: case 8: case 10:
                    expected = 2; break;
                case 3:
                    if (((flags >> 1) & 0x03) == 3)
                        throw new IOException("Malformed MQTT publish QoS");
                    return;
                default:
                    throw new IOException("Unsupported MQTT packet type");
            }
            if (flags != expected) throw new IOException("Malformed MQTT packet flags");
        }

        private void packet(int header, byte[] body) throws IOException {
            output.write(header);
            int length = body.length;
            do {
                int encoded = length % 128;
                length /= 128;
                if (length > 0) encoded |= 128;
                output.write(encoded);
            } while (length > 0);
            output.write(body);
            output.flush();
            lastWrite = System.currentTimeMillis();
        }

        @Override public void close() throws IOException { socket.close(); }

        private static void utf8(OutputStream out, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 65535) throw new IOException("MQTT string is too long");
            out.write((bytes.length >> 8) & 0xff); out.write(bytes.length & 0xff); out.write(bytes);
        }
    }

    static final class MqttPacket {
        final int type;
        final int flags;
        final byte[] body;
        MqttPacket(int type, int flags, byte[] body) { this.type = type; this.flags = flags; this.body = body; }

        String publishPayload() throws IOException {
            if (body.length < 2) throw new IOException("Malformed MQTT publish packet");
            int topicLength = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
            if (topicLength == 0 || topicLength + 2 > body.length) throw new IOException("Malformed MQTT publish topic");
            int payloadOffset = topicLength + 2;
            int qos = (flags >> 1) & 0x03;
            if (qos == 3) throw new IOException("Malformed MQTT publish QoS");
            if (qos > 0) payloadOffset += 2;
            if (payloadOffset > body.length) throw new IOException("Malformed MQTT publish packet identifier");
            return new String(body, payloadOffset, body.length - payloadOffset, StandardCharsets.UTF_8);
        }
    }

    private static final class ImplicitFtpsClient implements Closeable {
        private final SSLSocket socket;
        private final SSLSocketFactory socketFactory;
        private final BCExtendedSSLSession controlSession;
        private final BufferedReader input;
        private final OutputStream output;

        private final boolean requireSessionReuse;

        ImplicitFtpsClient(SSLSocketFactory factory, String host, String password, int port,
                           boolean requireSessionReuse) throws IOException {
            socketFactory = factory;
            this.requireSessionReuse = requireSessionReuse;
            socket = (SSLSocket) factory.createSocket();
            try {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(CONNECT_TIMEOUT_MS);
                configureFtpsSocket(socket);
                socket.startHandshake();
                // Read-only pairing/probing may use the platform provider. A
                // physical upload, however, must come through the pinned
                // BCJSSE factory and therefore must have an explicit session
                // object to carry to the data socket.
                BCExtendedSSLSession session = socket instanceof BCSSLSocket
                        ? ((BCSSLSocket) socket).getBCSession() : null;
                if (session != null && (session.getId() == null || session.getId().length == 0))
                    throw new IOException("FTPS control TLS session is unavailable");
                controlSession = session;
                input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                output = socket.getOutputStream();
                requireReply(readReply(), 200, 399, "FTP greeting");
                requireReply(command("USER bblp"), 200, 399, "FTP username");
                requireReply(command("PASS " + password), 200, 399, "FTP password");
            } catch (Exception error) {
                try { socket.close(); } catch (IOException ignored) { }
                if (error instanceof IOException) throw (IOException) error;
                throw new IOException("FTPS connection failed", error);
            }
        }

        void upload(String name, File source, long expectedSize, ProgressListener progress) throws IOException {
            requireReply(command("TYPE I"), 200, 299, "FTP binary mode");
            requireReply(command("PBSZ 0"), 200, 299, "FTP protection buffer");
            requireReply(command("PROT P"), 200, 299, "FTP data protection");
            FtpReply passive = command("PASV");
            requireReply(passive, 200, 299, "FTP passive mode");
            int[] address = parsePassiveAddress(passive.text);
            if (requireSessionReuse && controlSession == null)
                throw new IOException("FTPS upload requires BCJSSE explicit TLS session reuse");
            SSLSocket data = null;
            try {
                int dataPort = address[4] * 256 + address[5];
                data = (SSLSocket) socketFactory.createSocket(socket.getInetAddress().getHostAddress(), dataPort);
                data.setSoTimeout(CONNECT_TIMEOUT_MS);
                configureFtpsSocket(data);
                if (requireSessionReuse && !(data instanceof BCSSLSocket))
                    throw new IOException("FTPS provider does not expose explicit TLS session reuse");
                BCSSLSocket reusable = data instanceof BCSSLSocket ? (BCSSLSocket) data : null;
                if (reusable != null && controlSession != null) reusable.setBCSessionToResume(controlSession);
                data.startHandshake();
                BCExtendedSSLSession resumed = reusable == null ? null : reusable.getBCSession();
                if (requireSessionReuse && (resumed == null || !Arrays.equals(controlSession.getId(), resumed.getId())))
                    throw new IOException("FTPS data TLS session was not resumed from the control session");
                requireReply(command("STOR /" + name), 100, 199, "FTP upload start");
                long sent = 0L;
                int lastPercent = -1;
                try (FileInputStream input = new FileInputStream(source)) {
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        data.getOutputStream().write(buffer, 0, read);
                        sent += read;
                        int percent = expectedSize == 0 ? 100 : (int) Math.min(100, sent * 100L / expectedSize);
                        if (percent != lastPercent && progress != null) {
                            lastPercent = percent;
                            progress.onProgress(percent, "Uploading artifact");
                        }
                    }
                    data.getOutputStream().flush();
                }
                if (sent != expectedSize) throw new IOException("FTP upload size mismatch");
            } finally {
                if (data != null) try { data.close(); } catch (IOException ignored) { }
            }
            requireReply(readReply(), 200, 299, "FTP upload completion");
            verifyRemoteSize(name, expectedSize);
        }

        private void verifyRemoteSize(String name, long expectedSize) throws IOException {
            FtpReply remoteSize = command("SIZE /" + name);
            requireReply(remoteSize, 200, 299, "FTP remote file verification");
            long verifiedSize = parseRemoteSize(remoteSize.text);
            if (verifiedSize != expectedSize)
                throw new IOException("Printer stored a different artifact size");
        }

        private FtpReply command(String command) throws IOException {
            output.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();
            return readReply();
        }

        private FtpReply readReply() throws IOException {
            String first = input.readLine();
            if (first == null || first.length() < 3) throw new IOException("Malformed FTP response");
            if (first.length() > MAX_FTP_REPLY_BYTES) throw new IOException("FTP response is too large");
            int code;
            try { code = Integer.parseInt(first.substring(0, 3)); } catch (NumberFormatException error) { throw new IOException("Malformed FTP response", error); }
            StringBuilder text = new StringBuilder(first);
            if (first.length() > 3 && first.charAt(3) == '-') {
                String end = first.substring(0, 3) + " ";
                String line;
                while ((line = input.readLine()) != null) {
                    text.append('\n').append(line);
                    if (text.length() > MAX_FTP_REPLY_BYTES) throw new IOException("FTP response is too large");
                    if (line.startsWith(end)) break;
                }
                if (line == null) throw new IOException("Truncated FTP response");
            }
            return new FtpReply(code, text.toString());
        }

        static int[] parsePassiveAddress(String response) throws IOException {
            Matcher matcher = PASV.matcher(response);
            if (!matcher.find()) throw new IOException("Printer returned an invalid FTP passive address");
            int[] values = new int[6];
            for (int i = 0; i < values.length; i++) values[i] = Integer.parseInt(matcher.group(i + 1));
            for (int value : values) if (value < 0 || value > 255)
                throw new IOException("Printer returned an out-of-range FTP passive address");
            return values;
        }

        static long parseRemoteSize(String response) throws IOException {
            Matcher matcher = FTP_SIZE.matcher(response);
            if (!matcher.find()) throw new IOException("Printer returned no usable remote file size");
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException error) {
                throw new IOException("Printer returned an invalid remote file size", error);
            }
        }

        private static void requireReply(FtpReply reply, int min, int max, String phase) throws IOException {
            if (reply.code < min || reply.code > max) throw new IOException(phase + " failed (FTP " + reply.code + ")");
        }

        @Override public void close() throws IOException {
            try { output.write("QUIT\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush(); } catch (IOException ignored) { }
            socket.close();
        }
    }

    private static void validateLocalArtifact(Artifact artifact) throws IOException {
        if (artifact == null || !artifact.hasSourceFile())
            throw new IOException("A local artifact is required");
        File source = artifact.sourceFile;
        if (source.length() != artifact.sizeBytes) throw new IOException("Artifact changed before printer operation");
        // The transport owns the last local-artifact boundary before FTPS or
        // MQTT side effects. Recheck A1 Mini motion, homing and heater-stop
        // invariants here even when the Activity has been recreated.
        GcodePackageValidator.validateForPhysicalPrint(source, new Slicer.Config());
        String digest = ArtifactStore.sha256(source);
        if (!digest.equalsIgnoreCase(artifact.sha256))
            throw new IOException("Artifact digest does not match the staged manifest");
    }

    private static final class FtpReply {
        final int code;
        final String text;
        FtpReply(int code, String text) { this.code = code; this.text = text; }
    }

    private static void readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = input.read(buffer, offset, buffer.length - offset);
            if (read < 0) throw new IOException("Truncated network packet");
            offset += read;
        }
    }
}
