package com.mbaliga.alloy;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Loopback protocol acceptance for the real Android LAN adapter. The socket
 * shim deliberately carries plaintext only inside this test; it exercises the
 * FTP/MQTT framing and state machine without weakening the production TLS path.
 */
@RunWith(AndroidJUnit4.class)
public final class BambuLanTransportFakePrinterTest {
    @Test(timeout = 10_000)
    public void unpinnedAuthenticatedTransportFailsClosedBeforeNetwork() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter();
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SNO-PIN", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), true);
        CountDownLatch failed = new CountDownLatch(1);
        AtomicReference<String> detail = new AtomicReference<>("");
        try {
            transport.probe(target, (state, message) -> {
                if (state == PrinterTransport.State.FAILED) {
                    detail.set(message);
                    failed.countDown();
                }
            });
            Assert.assertTrue("unpinned probe did not fail closed", failed.await(5, TimeUnit.SECONDS));
            Assert.assertTrue("failure should identify the missing pin: " + detail.get(),
                    detail.get().toLowerCase().contains("certificate pin"));
            Assert.assertNull("fail-closed probe must not contact the fake printer", fake.failure());
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 10_000)
    public void busyPrinterRejectsStartBeforeProjectFilePublish() throws Exception {
        FakeBambuPrinter fake = FakeBambuPrinter.busy();
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SBUSY", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), false);
        CountDownLatch terminal = new CountDownLatch(1);
        AtomicReference<PrinterTransport.State> terminalState = new AtomicReference<>();
        AtomicReference<String> detail = new AtomicReference<>("");
        try {
            fake.start();
            transport.startPrint(target, "/already-busy.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, message) -> {
                        if (state == PrinterTransport.State.FAILED || state == PrinterTransport.State.COMPLETED
                                || state == PrinterTransport.State.RECOVERY_REQUIRED) {
                            terminalState.set(state);
                            detail.set(message);
                            terminal.countDown();
                        }
                    });
            Assert.assertTrue("busy preflight did not produce a terminal result",
                    terminal.await(5, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertEquals("busy printer should fail before a start command", PrinterTransport.State.FAILED,
                    terminalState.get());
            Assert.assertTrue("failure should explain the busy printer", detail.get().contains("already busy"));
            Assert.assertEquals("busy preflight must not publish project_file", 0L, fake.startCommandCount());
            Assert.assertNull("fake busy printer failed: " + fake.failure(), fake.failure());
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void loopbackFakePrinterExercisesUploadStartAndTelemetry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Result offline = new LegacyOfflineEngine().slice(mesh, config, null);
        // The protocol test intentionally uses a verified no-support package;
        // the transport must not be the place where an unverified slicer is
        // promoted into a physical-print claim.
        Slicer.Result result = new Slicer.Result(offline.gcode, offline.layers, offline.filamentMm,
                offline.warnings, "verified-test-engine", true,
                offline.printTimeSeconds, offline.travelMm);
        FileTree root = new FileTree(context.getCacheDir(), "fake-bambu-" + System.nanoTime());
        Assert.assertTrue(root.directory.mkdirs());

        FakeBambuPrinter fake = new FakeBambuPrinter();
        PrinterTransport.Artifact artifact = null;
        BambuLanTransport transport = null;
        try {
            artifact = ArtifactStore.stage(root.directory, mesh, result, config, "fake-printer");
            PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                    "Fake A1 Mini", "127.0.0.1", "01SFAKE", "12345678");
            PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                    credentials.name, credentials.host, credentials.serial);
            transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                    fake.ftpPort(), fake.mqttPort(), false);
            BambuLanTransport activeTransport = transport;
            fake.start();

            List<PrinterTransport.State> uploadStates = Collections.synchronizedList(new ArrayList<>());
            AtomicReference<String> uploadFailure = new AtomicReference<>("");
            CountDownLatch uploaded = new CountDownLatch(1);
            transport.upload(target, artifact, null, (state, detail) -> {
                uploadStates.add(state);
                if (state == PrinterTransport.State.FAILED) uploadFailure.set(detail);
                if (state == PrinterTransport.State.UPLOADED || state == PrinterTransport.State.FAILED)
                    uploaded.countDown();
            });
            Assert.assertTrue("upload callback did not arrive", uploaded.await(10, TimeUnit.SECONDS));
            Assert.assertFalse("transport upload failed: " + uploadFailure.get() + " / " + uploadStates,
                    uploadStates.contains(PrinterTransport.State.FAILED));
            Assert.assertNull("fake FTP server failed: " + fake.failure(), fake.failure());
            Assert.assertTrue(uploadStates.contains(PrinterTransport.State.UPLOADING));
            Assert.assertTrue(uploadStates.contains(PrinterTransport.State.UPLOADED));
            Assert.assertEquals(artifact.sizeBytes, fake.uploadedBytes());
            // Starting is deliberately preceded by the same remote SIZE
            // revalidation used after a recovered/uploaded job.
            transport.verifyUploadedArtifact(target, artifact);
            fake.awaitFtp();
            Assert.assertEquals("upload completion and pre-start verification must each query remote size",
                    2L, fake.remoteSizeQueries());

            List<PrinterTransport.State> printStates = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch completed = new CountDownLatch(1);
            AtomicBoolean pauseRequested = new AtomicBoolean();
            AtomicBoolean resumeRequested = new AtomicBoolean();
            AtomicBoolean resumeConfirmed = new AtomicBoolean();
            AtomicReference<String> printFailure = new AtomicReference<>("");
            transport.startPrint(target, "/" + artifact.displayName,
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        printStates.add(state);
                        if (state == PrinterTransport.State.RUNNING && detail != null && detail.contains("resumed"))
                            resumeConfirmed.set(true);
                        if (state == PrinterTransport.State.RUNNING && pauseRequested.compareAndSet(false, true))
                            activeTransport.pause(target, (controlState, controlDetail) -> {
                                printStates.add(controlState);
                                if (controlState == PrinterTransport.State.FAILED) printFailure.set(controlDetail);
                            });
                        if (state == PrinterTransport.State.PAUSED && resumeRequested.compareAndSet(false, true))
                            activeTransport.resume(target, (controlState, controlDetail) -> {
                                printStates.add(controlState);
                                if (controlState == PrinterTransport.State.FAILED) printFailure.set(controlDetail);
                            });
                        if (state == PrinterTransport.State.FAILED) printFailure.set(detail);
                        if (state == PrinterTransport.State.COMPLETED || state == PrinterTransport.State.FAILED)
                            completed.countDown();
                    });
            Assert.assertTrue("print telemetry callback did not arrive", completed.await(10, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertEquals("", printFailure.get());
            Assert.assertTrue(printStates.contains(PrinterTransport.State.START_REQUESTED));
            Assert.assertTrue(printStates.contains(PrinterTransport.State.RUNNING));
            Assert.assertTrue(printStates.contains(PrinterTransport.State.PAUSE_REQUESTED));
            Assert.assertTrue(printStates.contains(PrinterTransport.State.PAUSED));
            Assert.assertTrue(printStates.contains(PrinterTransport.State.RESUME_REQUESTED));
            Assert.assertTrue(resumeConfirmed.get());
            Assert.assertTrue(printStates.contains(PrinterTransport.State.COMPLETED));
            Assert.assertFalse(printStates.contains(PrinterTransport.State.FAILED));

            JSONObject sent = new JSONObject(fake.startPayload()).getJSONObject("print");
            Assert.assertEquals("project_file", sent.getString("command"));
            Assert.assertEquals("ftp:///" + artifact.displayName, sent.getString("url"));
            Assert.assertEquals("Metadata/plate_1.gcode", sent.getString("param"));
            Assert.assertFalse(sent.getBoolean("use_ams"));
            Assert.assertFalse(sent.has("ams_mapping"));
        } finally {
            if (transport != null) transport.close();
            fake.close();
            root.delete();
        }
    }

    @Test(timeout = 30_000)
    public void loopbackFakePrinterConfirmsCancellationAfterAcceptedJob() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SCANCEL", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), false);
        BambuLanTransport activeTransport = transport;
        List<PrinterTransport.State> states = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean cancelRequested = new AtomicBoolean();
        AtomicReference<String> failure = new AtomicReference<>("");
        CountDownLatch cancelled = new CountDownLatch(1);
        try {
            fake.start();
            activeTransport.startPrint(target, "/cancel-test.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        states.add(state);
                        if (state == PrinterTransport.State.RUNNING && cancelRequested.compareAndSet(false, true))
                            activeTransport.cancel(target, (controlState, controlDetail) -> {
                                states.add(controlState);
                                if (controlState == PrinterTransport.State.FAILED) failure.set(controlDetail);
                            });
                        if (state == PrinterTransport.State.FAILED) failure.set(detail);
                        if (state == PrinterTransport.State.CANCELLED || state == PrinterTransport.State.FAILED)
                            cancelled.countDown();
                    });
            Assert.assertTrue("cancellation telemetry callback did not arrive",
                    cancelled.await(10, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertEquals("", failure.get());
            Assert.assertTrue(states.contains(PrinterTransport.State.START_REQUESTED));
            Assert.assertTrue(states.contains(PrinterTransport.State.RUNNING));
            Assert.assertTrue(states.contains(PrinterTransport.State.CANCEL_REQUESTED));
            Assert.assertTrue(states.contains(PrinterTransport.State.CANCELLED));
            Assert.assertFalse(states.contains(PrinterTransport.State.FAILED));
        } finally {
            activeTransport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void nativeArtifactTravelsThroughFakePrinterTransaction() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Result result = new NativeSlicerEngine(context.getCacheDir(), true).slice(mesh, config, null);
        Assert.assertEquals(NativeSlicerEngine.ORCA_NATIVE_ENGINE_ID, result.engineId);
        Assert.assertTrue(result.engineVerified);

        FileTree root = new FileTree(context.getCacheDir(), "fake-native-printer-" + System.nanoTime());
        Assert.assertTrue(root.directory.mkdirs());
        FakeBambuPrinter fake = new FakeBambuPrinter(true);
        PrinterTransport.Artifact artifact = null;
        BambuLanTransport transport = null;
        try {
            artifact = ArtifactStore.stage(root.directory, mesh, result, config, "fake-native-printer");
            PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                    "Fake A1 Mini", "127.0.0.1", "01SNATIVE", "12345678");
            PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                    credentials.name, credentials.host, credentials.serial);
            transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                    fake.ftpPort(), fake.mqttPort(), false);
            BambuLanTransport activeTransport = transport;
            fake.start();
            CountDownLatch uploaded = new CountDownLatch(1);
            AtomicReference<String> failure = new AtomicReference<>("");
            transport.upload(target, artifact, null, (state, detail) -> {
                if (state == PrinterTransport.State.FAILED) failure.set(detail);
                if (state == PrinterTransport.State.UPLOADED || state == PrinterTransport.State.FAILED)
                    uploaded.countDown();
            });
            Assert.assertTrue("native artifact upload did not finish", uploaded.await(10, TimeUnit.SECONDS));
            Assert.assertEquals("", failure.get());
            activeTransport.verifyUploadedArtifact(target, artifact);
            fake.awaitFtp();

            CountDownLatch cancelled = new CountDownLatch(1);
            AtomicBoolean requested = new AtomicBoolean();
            List<PrinterTransport.State> states = Collections.synchronizedList(new ArrayList<>());
            transport.startPrint(target, "/" + artifact.displayName,
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        states.add(state);
                        if (state == PrinterTransport.State.RUNNING && requested.compareAndSet(false, true))
                            activeTransport.cancel(target, (controlState, controlDetail) -> {
                                states.add(controlState);
                                if (controlState == PrinterTransport.State.CANCELLED
                                        || controlState == PrinterTransport.State.FAILED
                                        || controlState == PrinterTransport.State.RECOVERY_REQUIRED)
                                    cancelled.countDown();
                            });
                        if (state == PrinterTransport.State.CANCELLED
                                || state == PrinterTransport.State.FAILED
                                || state == PrinterTransport.State.RECOVERY_REQUIRED)
                            cancelled.countDown();
                    });
            Assert.assertTrue("native artifact transaction did not finish: states=" + states
                            + ", fakeFailure=" + fake.failure()
                            + ", startCommands=" + fake.startCommandCount(),
                    cancelled.await(10, TimeUnit.SECONDS));
            Assert.assertNull("fake FTP/MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertEquals(artifact.sizeBytes, fake.uploadedBytes());
            Assert.assertEquals("native artifact must issue exactly one start command", 1L,
                    fake.startCommandCount());
        } finally {
            if (transport != null) transport.close();
            fake.close();
            root.delete();
        }
    }

    @Test(timeout = 30_000)
    public void loopbackFakePrinterReconnectsWithoutResendingStartCommand() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(false, true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SRECONNECT", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), false);
        List<PrinterTransport.State> states = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> failure = new AtomicReference<>("");
        CountDownLatch completed = new CountDownLatch(1);
        try {
            fake.start();
            transport.startPrint(target, "/reconnect-test.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        states.add(state);
                        if (state == PrinterTransport.State.FAILED
                                || state == PrinterTransport.State.RECOVERY_REQUIRED) failure.set(detail);
                        if (state == PrinterTransport.State.COMPLETED) completed.countDown();
                    });
            Assert.assertTrue("reconnected telemetry did not complete",
                    completed.await(10, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertEquals("", failure.get());
            Assert.assertTrue(states.contains(PrinterTransport.State.START_REQUESTED));
            Assert.assertTrue(states.contains(PrinterTransport.State.RUNNING));
            Assert.assertTrue(states.contains(PrinterTransport.State.CONNECTING));
            Assert.assertTrue(states.contains(PrinterTransport.State.COMPLETED));
            Assert.assertEquals("start command must be sent exactly once", 1, fake.startCommandCount());
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void loopbackFakePrinterRecoversAfterMalformedTelemetryWithoutResendingStartCommand() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(false, true, false, true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SMALFORMED", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), false);
        List<PrinterTransport.State> states = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> failure = new AtomicReference<>("");
        CountDownLatch completed = new CountDownLatch(1);
        try {
            fake.start();
            transport.startPrint(target, "/malformed-test.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        states.add(state);
                        if (state == PrinterTransport.State.FAILED
                                || state == PrinterTransport.State.RECOVERY_REQUIRED) failure.set(detail);
                        if (state == PrinterTransport.State.COMPLETED) completed.countDown();
                    });
            Assert.assertTrue("malformed telemetry was not recovered", completed.await(10, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertEquals("", failure.get());
            Assert.assertTrue(states.contains(PrinterTransport.State.RUNNING));
            Assert.assertTrue(states.contains(PrinterTransport.State.CONNECTING));
            Assert.assertTrue(states.contains(PrinterTransport.State.COMPLETED));
            Assert.assertEquals("malformed telemetry must not resend the start command", 1, fake.startCommandCount());
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void loopbackFakePrinterStopsAfterReconnectBudgetWithoutResendingStartCommand() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(false, true, true, false);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SRECOVERY", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials, new PlaintextSocketFactory(),
                fake.ftpPort(), fake.mqttPort(), false);
        List<PrinterTransport.State> states = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<String> terminal = new AtomicReference<>("");
        CountDownLatch recovered = new CountDownLatch(1);
        try {
            fake.start();
            transport.startPrint(target, "/recovery-test.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        states.add(state);
                        if (state == PrinterTransport.State.RECOVERY_REQUIRED
                                || state == PrinterTransport.State.FAILED) {
                            terminal.set(state.name() + ": " + detail);
                            recovered.countDown();
                        }
                    });
            Assert.assertTrue("reconnect exhaustion did not require recovery",
                    recovered.await(10, TimeUnit.SECONDS));
            fake.awaitMqtt();
            Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
            Assert.assertTrue("expected RECOVERY_REQUIRED but got " + terminal.get(),
                    terminal.get().startsWith(PrinterTransport.State.RECOVERY_REQUIRED.name()));
            Assert.assertFalse(states.contains(PrinterTransport.State.FAILED));
            Assert.assertTrue(states.contains(PrinterTransport.State.CONNECTING));
            Assert.assertEquals("reconnect exhaustion must not resend the start command", 1, fake.startCommandCount());
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void startPublishFailureRequiresRecoveryAndIsNotRetryable() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(false, false, false, false, true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SPUBLISHFAIL", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials,
                new FailOnThirdFlushSocketFactory(), fake.ftpPort(), fake.mqttPort(), false);
        AtomicReference<PrinterTransport.State> terminal = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        try {
            fake.start();
            transport.startPrint(target, "/publish-failure.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        if (state == PrinterTransport.State.FAILED
                                || state == PrinterTransport.State.RECOVERY_REQUIRED) {
                            terminal.set(state);
                            finished.countDown();
                        }
                    });
            Assert.assertTrue("publish failure did not produce a terminal state",
                    finished.await(10, TimeUnit.SECONDS));
            Assert.assertEquals("an attempted start must be recoverable, never retryable",
                    PrinterTransport.State.RECOVERY_REQUIRED, terminal.get());
            // A flush failure is deliberately ambiguous: the socket shim
            // fails after writing, so the printer may have received the
            // complete packet even though the client did not get a success.
            // The safety property is that Alloy never retries it and can
            // therefore produce at most one physical start command.
            Assert.assertTrue("an uncertain start must never be retried; observed "
                            + fake.startCommandCount() + " start packets",
                    fake.startCommandCount() <= 1L);
        } finally {
            transport.close();
            fake.close();
        }
    }

    @Test(timeout = 30_000)
    public void pausePublishFailureRequiresRecoveryAndIsNotRetryable() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(false, false, false, false, false, true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SPAUSEFAIL", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials,
                new FailOnFlushSocketFactory(4), fake.ftpPort(), fake.mqttPort(), false);
        AtomicReference<PrinterTransport.State> terminal = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean requested = new AtomicBoolean();
        try {
            fake.start();
            BambuLanTransport active = transport;
            transport.startPrint(target, "/pause-failure.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        if (state == PrinterTransport.State.RUNNING && requested.compareAndSet(false, true)) {
                            active.pause(target, (controlState, controlDetail) -> {
                                if (controlState == PrinterTransport.State.FAILED
                                        || controlState == PrinterTransport.State.RECOVERY_REQUIRED) {
                                    terminal.set(controlState);
                                    finished.countDown();
                                }
                            });
                        }
                    });
            Assert.assertTrue("pause publish failure did not produce a terminal state",
                    finished.await(10, TimeUnit.SECONDS));
            Assert.assertEquals("an attempted pause must be recoverable, never retryable",
                    PrinterTransport.State.RECOVERY_REQUIRED, terminal.get());
        } finally {
            transport.close();
            fake.awaitMqtt();
            fake.close();
        }
        Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
        Assert.assertEquals("the fake printer must observe only the accepted start", 1L,
                fake.startCommandCount());
    }

    @Test(timeout = 30_000)
    public void cancelPublishFailureRequiresRecoveryAndIsNotRetryable() throws Exception {
        FakeBambuPrinter fake = new FakeBambuPrinter(true, false, false, false, false, true);
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "Fake A1 Mini", "127.0.0.1", "01SCANCELFAIL", "12345678");
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                credentials.name, credentials.host, credentials.serial);
        BambuLanTransport transport = new BambuLanTransport(credentials,
                new FailOnFlushSocketFactory(4), fake.ftpPort(), fake.mqttPort(), false);
        AtomicReference<PrinterTransport.State> terminal = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean requested = new AtomicBoolean();
        try {
            fake.start();
            BambuLanTransport active = transport;
            transport.startPrint(target, "/cancel-failure.gcode.3mf",
                    new PrinterTransport.PrintOptions(false, true, false, true), (state, detail) -> {
                        if (state == PrinterTransport.State.RUNNING && requested.compareAndSet(false, true)) {
                            active.cancel(target, (controlState, controlDetail) -> {
                                if (controlState == PrinterTransport.State.FAILED
                                        || controlState == PrinterTransport.State.RECOVERY_REQUIRED) {
                                    terminal.set(controlState);
                                    finished.countDown();
                                }
                            });
                        }
                    });
            Assert.assertTrue("cancel publish failure did not produce a terminal state",
                    finished.await(10, TimeUnit.SECONDS));
            Assert.assertEquals("an attempted stop must be recoverable, never retryable",
                    PrinterTransport.State.RECOVERY_REQUIRED, terminal.get());
        } finally {
            transport.close();
            fake.awaitMqtt();
            fake.close();
        }
        Assert.assertNull("fake MQTT server failed: " + fake.failure(), fake.failure());
        Assert.assertEquals("the fake printer must observe only the accepted start", 1L,
                fake.startCommandCount());
    }

    private static final class FileTree {
        final java.io.File directory;

        FileTree(java.io.File parent, String name) {
            directory = new java.io.File(parent, name);
        }

        void delete() {
            java.io.File[] children = directory.listFiles();
            if (children != null) for (java.io.File child : children) {
                if (child.isDirectory()) new FileTree(directory, child.getName()).delete();
                else child.delete();
            }
            directory.delete();
        }
    }

    private static final class FakeBambuPrinter implements Closeable {
        private final ServerSocket ftp;
        private final ServerSocket mqtt;
        private final boolean cancelMode;
        private final boolean reconnectMode;
        private final boolean reconnectExhaustionMode;
        private final boolean malformedMode;
        private final boolean expectedPublishFailure;
        private final boolean expectedControlFailure;
        private final boolean busyMode;
        private final ExecutorService workers = Executors.newFixedThreadPool(2);
        private final CountDownLatch ftpDone = new CountDownLatch(1);
        private final CountDownLatch mqttDone = new CountDownLatch(1);
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final AtomicLong uploaded = new AtomicLong();
        private final AtomicLong remoteSizeQueries = new AtomicLong();
        private final AtomicLong startCommands = new AtomicLong();
        private volatile String startPayload = "";

        FakeBambuPrinter() throws IOException { this(false, false, false, false); }

        FakeBambuPrinter(boolean cancelMode) throws IOException {
            this(cancelMode, false, false, false);
        }

        FakeBambuPrinter(boolean cancelMode, boolean reconnectMode) throws IOException {
            this(cancelMode, reconnectMode, false, false);
        }

        FakeBambuPrinter(boolean cancelMode, boolean reconnectMode,
                         boolean reconnectExhaustionMode, boolean malformedMode) throws IOException {
            this(cancelMode, reconnectMode, reconnectExhaustionMode, malformedMode, false);
        }

        FakeBambuPrinter(boolean cancelMode, boolean reconnectMode,
                         boolean reconnectExhaustionMode, boolean malformedMode,
                         boolean expectedPublishFailure) throws IOException {
            this(cancelMode, reconnectMode, reconnectExhaustionMode, malformedMode,
                    expectedPublishFailure, false);
        }

        FakeBambuPrinter(boolean cancelMode, boolean reconnectMode,
                         boolean reconnectExhaustionMode, boolean malformedMode,
                         boolean expectedPublishFailure, boolean expectedControlFailure) throws IOException {
            this(cancelMode, reconnectMode, reconnectExhaustionMode, malformedMode,
                    expectedPublishFailure, expectedControlFailure, false);
        }

        private FakeBambuPrinter(boolean cancelMode, boolean reconnectMode,
                                 boolean reconnectExhaustionMode, boolean malformedMode,
                                 boolean expectedPublishFailure, boolean expectedControlFailure,
                                 boolean busyMode) throws IOException {
            this.cancelMode = cancelMode;
            this.reconnectMode = reconnectMode;
            this.reconnectExhaustionMode = reconnectExhaustionMode;
            this.malformedMode = malformedMode;
            this.expectedPublishFailure = expectedPublishFailure;
            this.expectedControlFailure = expectedControlFailure;
            this.busyMode = busyMode;
            ftp = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            mqtt = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        }

        static FakeBambuPrinter busy() throws IOException {
            return new FakeBambuPrinter(false, false, false, false, false, false, true);
        }

        int ftpPort() { return ftp.getLocalPort(); }
        int mqttPort() { return mqtt.getLocalPort(); }
        long uploadedBytes() { return uploaded.get(); }
        long remoteSizeQueries() { return remoteSizeQueries.get(); }
        String startPayload() { return startPayload; }
        long startCommandCount() { return startCommands.get(); }
        Throwable failure() { return failure.get(); }

        void start() {
            workers.execute(this::serveFtp);
            workers.execute(this::serveMqtt);
        }

        void awaitFtp() throws InterruptedException {
            Assert.assertTrue("fake FTP server did not finish", ftpDone.await(10, TimeUnit.SECONDS));
        }

        void awaitMqtt() throws InterruptedException {
            Assert.assertTrue("fake MQTT server did not finish", mqttDone.await(10, TimeUnit.SECONDS));
        }

        private void serveFtp() {
            try {
                // The first session is the upload; the second is the
                // immediate pre-start remote identity check. Keeping these
                // separate catches code that only verifies at upload time.
                for (int session = 0; session < 2; session++) {
                    try (Socket control = ftp.accept()) {
                control.setSoTimeout(10_000);
                BufferedReader input = new BufferedReader(new InputStreamReader(
                        control.getInputStream(), StandardCharsets.US_ASCII));
                BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                        control.getOutputStream(), StandardCharsets.US_ASCII));
                line(output, "220 Alloy fake Bambu FTP");
                String command;
                while ((command = input.readLine()) != null) {
                    String upper = command.toUpperCase(java.util.Locale.US);
                    if (upper.startsWith("USER ")) line(output, "331 Password required");
                    else if (upper.startsWith("PASS ")) line(output, "230 Logged in");
                    else if (upper.equals("TYPE I") || upper.equals("PBSZ 0") || upper.equals("PROT P"))
                        line(output, "200 OK");
                    else if (upper.equals("PASV")) {
                        try (ServerSocket dataServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                            int port = dataServer.getLocalPort();
                            line(output, "227 Entering Passive Mode (127,0,0,1," + (port / 256) + "," + (port % 256) + ")");
                            String store = input.readLine();
                            if (store == null || !store.toUpperCase(java.util.Locale.US).startsWith("STOR "))
                                throw new IOException("fake FTP expected STOR");
                            line(output, "150 Opening data channel");
                            try (Socket data = dataServer.accept()) {
                                InputStream bytes = data.getInputStream();
                                byte[] buffer = new byte[16 * 1024];
                                long total = 0L;
                                int read;
                                while ((read = bytes.read(buffer)) != -1) total += read;
                                uploaded.set(total);
                            }
                            line(output, "226 Transfer complete");
                        }
                    } else if (upper.startsWith("SIZE ")) {
                        remoteSizeQueries.incrementAndGet();
                        line(output, "213 " + uploaded.get());
                    } else if (upper.equals("QUIT")) {
                        line(output, "221 Bye");
                        break;
                    } else {
                        line(output, "500 Unsupported fake FTP command");
                    }
                }
                    }
                }
            } catch (Throwable error) {
                if (!ftp.isClosed()) failure.compareAndSet(null, error);
            } finally {
                ftpDone.countDown();
            }
        }

        private void serveMqtt() {
            String reconnectRemoteName = null;
            try {
                try (Socket socket = mqtt.accept()) {
                socket.setSoTimeout(10_000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                MqttFrame connect = readFrame(input);
                if (connect.type() != 1) throw new IOException("fake MQTT expected CONNECT");
                send(output, 0x20, new byte[]{0, 0});

                MqttFrame subscribe = readFrame(input);
                if (subscribe.type() != 8 || subscribe.body.length < 2)
                    throw new IOException("fake MQTT expected SUBSCRIBE");
                send(output, 0x90, new byte[]{subscribe.body[0], subscribe.body[1], 0});

                if (busyMode) {
                    sendTelemetry(output, "RUNNING", "existing-job", "/existing-job.gcode.3mf");
                    try {
                        MqttFrame unexpected = readFrame(input);
                        if (unexpected.type() == 3) startCommands.incrementAndGet();
                    } catch (java.net.SocketTimeoutException expected) {
                        // A correct client closes after the busy preflight and
                        // never publishes a project_file command.
                    }
                    return;
                }

                MqttFrame publish = readFrame(input);
                if (publish.type() != 3) throw new IOException("fake MQTT expected PUBLISH");
                startCommands.incrementAndGet();
                startPayload = publish.payload();
                String remoteName = new JSONObject(startPayload).getJSONObject("print")
                        .getString("subtask_name") + ".gcode.3mf";
                reconnectRemoteName = remoteName;
                sendTelemetry(output, "PREPARE", remoteName, "");
                sendTelemetry(output, "RUNNING", remoteName, "/" + remoteName);
                if (cancelMode) {
                    MqttFrame cancel = readFrame(input);
                    assertControl(cancel, "stop");
                    sendTelemetry(output, "IDLE", remoteName, "/" + remoteName);
                    return;
                }
                if (reconnectMode) {
                    // Drop the accepted telemetry socket after the job has
                    // been observed. The transport must reconnect and
                    // resubscribe, but must not publish project_file again.
                    if (malformedMode) send(output, 0x30, new byte[]{0});
                } else {
                    MqttFrame pause = readFrame(input);
                    assertControl(pause, "pause");
                    sendTelemetry(output, "PAUSED", remoteName, "/" + remoteName);
                    MqttFrame resume = readFrame(input);
                    assertControl(resume, "resume");
                    sendTelemetry(output, "PREPARE", remoteName, "/" + remoteName);
                    sendTelemetry(output, "FINISH", remoteName, "/" + remoteName);
                }
                }
                if (reconnectMode && reconnectRemoteName != null && !reconnectExhaustionMode) {
                    try (Socket socket = mqtt.accept()) {
                    socket.setSoTimeout(10_000);
                    InputStream input = socket.getInputStream();
                    OutputStream output = socket.getOutputStream();
                    MqttFrame connect = readFrame(input);
                    if (connect.type() != 1) throw new IOException("fake MQTT expected reconnect CONNECT");
                    send(output, 0x20, new byte[]{0, 0});
                    MqttFrame subscribe = readFrame(input);
                    if (subscribe.type() != 8 || subscribe.body.length < 2)
                        throw new IOException("fake MQTT expected reconnect SUBSCRIBE");
                    send(output, 0x90, new byte[]{subscribe.body[0], subscribe.body[1], 0});
                    sendTelemetry(output, "FINISH", reconnectRemoteName, "/" + reconnectRemoteName);
                    }
                }
                if (reconnectMode && reconnectExhaustionMode) mqtt.close();
            } catch (Throwable error) {
                if (!(expectedPublishFailure && startCommands.get() == 0)
                        && !(expectedControlFailure && startCommands.get() == 1)
                        && !(busyMode && startCommands.get() == 0))
                    failure.compareAndSet(null, error);
            } finally {
                mqttDone.countDown();
            }
        }

        private static void assertControl(MqttFrame frame, String command) throws Exception {
            if (frame.type() != 3) throw new IOException("fake MQTT expected control PUBLISH");
            JSONObject print = new JSONObject(frame.payload()).getJSONObject("print");
            if (!command.equals(print.optString("command", "")))
                throw new IOException("fake MQTT expected " + command + " command");
        }

        private static void sendTelemetry(OutputStream output, String state, String subtask, String url) throws Exception {
            JSONObject print = new JSONObject().put("gcode_state", state).put("subtask_name", subtask.replace(".gcode.3mf", ""));
            if (url.length() > 0) print.put("url", "ftp:///" + subtask);
            send(output, 0x30, publishBody("report", new JSONObject().put("print", print).toString()));
        }

        private static byte[] publishBody(String topic, String payload) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            byte[] topicBytes = topic.getBytes(StandardCharsets.UTF_8);
            body.write((topicBytes.length >> 8) & 0xff);
            body.write(topicBytes.length & 0xff);
            body.write(topicBytes);
            body.write(payload.getBytes(StandardCharsets.UTF_8));
            return body.toByteArray();
        }

        private static MqttFrame readFrame(InputStream input) throws IOException {
            int header = input.read();
            if (header < 0) throw new IOException("fake MQTT connection closed");
            int multiplier = 1;
            int remaining = 0;
            int digit;
            do {
                digit = input.read();
                if (digit < 0 || multiplier > 128 * 128 * 128)
                    throw new IOException("fake MQTT remaining length is invalid");
                remaining += (digit & 127) * multiplier;
                multiplier *= 128;
            } while ((digit & 128) != 0);
            byte[] body = new byte[remaining];
            readFully(input, body);
            return new MqttFrame(header, body);
        }

        private static void send(OutputStream output, int header, byte[] body) throws IOException {
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
        }

        private static void readFully(InputStream input, byte[] bytes) throws IOException {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) throw new IOException("fake MQTT packet truncated");
                offset += read;
            }
        }

        private static void line(BufferedWriter output, String value) throws IOException {
            output.write(value);
            output.write("\r\n");
            output.flush();
        }

        @Override public void close() {
            try { ftp.close(); } catch (IOException ignored) { }
            try { mqtt.close(); } catch (IOException ignored) { }
            workers.shutdownNow();
        }
    }

    private static final class MqttFrame {
        final int header;
        final byte[] body;
        MqttFrame(int header, byte[] body) { this.header = header; this.body = body; }
        int type() { return (header >> 4) & 0x0f; }
        String payload() throws IOException {
            if (body.length < 2) throw new IOException("fake MQTT publish is truncated");
            int topicLength = ((body[0] & 0xff) << 8) | (body[1] & 0xff);
            if (topicLength <= 0 || topicLength + 2 > body.length)
                throw new IOException("fake MQTT publish topic is invalid");
            int offset = topicLength + 2;
            if (((header >> 1) & 3) > 0) offset += 2;
            if (offset > body.length) throw new IOException("fake MQTT publish payload is invalid");
            return new String(body, offset, body.length - offset, StandardCharsets.UTF_8);
        }
    }

    /** A non-TLS socket facade used only by the loopback test transcript. */
    private static class PlaintextTlsSocket extends SSLSocket {
        private final Socket delegate;
        private boolean needClientAuth;
        private boolean wantClientAuth;
        private boolean sessionCreation = true;

        PlaintextTlsSocket(Socket delegate) { this.delegate = delegate; }

        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public String[] getEnabledCipherSuites() { return new String[0]; }
        @Override public void setEnabledCipherSuites(String[] suites) { }
        @Override public String[] getSupportedProtocols() { return new String[]{"TLSv1.2"}; }
        @Override public String[] getEnabledProtocols() { return new String[]{"TLSv1.2"}; }
        @Override public void setEnabledProtocols(String[] protocols) { }
        @Override public SSLSession getSession() { return null; }
        @Override public void addHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void removeHandshakeCompletedListener(HandshakeCompletedListener listener) { }
        @Override public void startHandshake() { }
        @Override public void setUseClientMode(boolean mode) { }
        @Override public boolean getUseClientMode() { return true; }
        @Override public void setNeedClientAuth(boolean need) { needClientAuth = need; }
        @Override public boolean getNeedClientAuth() { return needClientAuth; }
        @Override public void setWantClientAuth(boolean want) { wantClientAuth = want; }
        @Override public boolean getWantClientAuth() { return wantClientAuth; }
        @Override public void setEnableSessionCreation(boolean flag) { sessionCreation = flag; }
        @Override public boolean getEnableSessionCreation() { return sessionCreation; }

        @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
            delegate.connect(endpoint, timeout);
        }
        @Override public void connect(SocketAddress endpoint) throws IOException { delegate.connect(endpoint); }
        @Override public InputStream getInputStream() throws IOException { return delegate.getInputStream(); }
        @Override public OutputStream getOutputStream() throws IOException { return delegate.getOutputStream(); }
        @Override public InetAddress getInetAddress() { return delegate.getInetAddress(); }
        @Override public InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
        @Override public int getPort() { return delegate.getPort(); }
        @Override public int getLocalPort() { return delegate.getLocalPort(); }
        @Override public void setSoTimeout(int timeout) throws java.net.SocketException { delegate.setSoTimeout(timeout); }
        @Override public int getSoTimeout() throws java.net.SocketException { return delegate.getSoTimeout(); }
        @Override public boolean isConnected() { return delegate.isConnected(); }
        @Override public boolean isClosed() { return delegate.isClosed(); }
        @Override public void shutdownInput() throws IOException { delegate.shutdownInput(); }
        @Override public void shutdownOutput() throws IOException { delegate.shutdownOutput(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static final class PlaintextSocketFactory extends SSLSocketFactory {
        @Override public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public Socket createSocket() { return new PlaintextTlsSocket(new Socket()); }
        @Override public Socket createSocket(String host, int port) throws IOException {
            return new PlaintextTlsSocket(new Socket(host, port));
        }
        @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return new PlaintextTlsSocket(socket);
        }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException {
            return new PlaintextTlsSocket(new Socket(host, port));
        }
        @Override public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return new PlaintextTlsSocket(socket);
        }
        @Override public Socket createSocket(Socket socket, String host, int port, boolean autoClose) {
            return new PlaintextTlsSocket(socket);
        }
    }

    /** Fails a selected MQTT flush while leaving earlier protocol packets intact. */
    private static class FailOnFlushSocketFactory extends SSLSocketFactory {
        private final int failOnFlush;

        FailOnFlushSocketFactory(int failOnFlush) { this.failOnFlush = failOnFlush; }

        @Override public String[] getDefaultCipherSuites() { return new String[0]; }
        @Override public String[] getSupportedCipherSuites() { return new String[0]; }
        @Override public SSLSocket createSocket() { return wrap(new Socket()); }
        @Override public SSLSocket createSocket(String host, int port) throws IOException {
            return wrap(new Socket(host, port));
        }
        @Override public SSLSocket createSocket(String host, int port, InetAddress local, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(host, port));
            return wrap(socket);
        }
        @Override public SSLSocket createSocket(InetAddress host, int port) throws IOException {
            return wrap(new Socket(host, port));
        }
        @Override public SSLSocket createSocket(InetAddress address, int port, InetAddress local, int localPort)
                throws IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(local, localPort));
            socket.connect(new InetSocketAddress(address, port));
            return wrap(socket);
        }
        @Override public SSLSocket createSocket(Socket socket, String host, int port, boolean autoClose) {
            return wrap(socket);
        }

        protected SSLSocket wrap(Socket socket) { return new FailOnFlushSocket(socket, failOnFlush); }
    }

    private static final class FailOnThirdFlushSocketFactory extends FailOnFlushSocketFactory {
        FailOnThirdFlushSocketFactory() { super(3); }
    }

    private static final class FailOnFlushSocket extends PlaintextTlsSocket {
        private final int failOnFlush;
        private int flushes;
        private OutputStream output;

        FailOnFlushSocket(Socket delegate, int failOnFlush) {
            super(delegate);
            this.failOnFlush = failOnFlush;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            if (output == null) {
                final OutputStream delegate = super.getOutputStream();
                output = new OutputStream() {
                    @Override public void write(int value) throws IOException { delegate.write(value); }
                    @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                        delegate.write(bytes, offset, length);
                    }
                    @Override public void flush() throws IOException {
                        if (++flushes >= failOnFlush)
                            throw new IOException("simulated MQTT publish failure at flush " + failOnFlush);
                        delegate.flush();
                    }
                    @Override public void close() throws IOException { delegate.close(); }
                };
            }
            return output;
        }
    }
}
