package com.mbaliga.alloy;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * On-device qualification harness for Alloy's deliberately narrow first
 * physical-print pilot: A1 Mini, 0.4 mm nozzle, PLA Basic and no supports.
 *
 * This proves native output is structurally safe and correctly marked for
 * review on a real ARM64 Android runtime. It never promotes the result to a
 * printable job: profile parity and the physical-printer acceptance record
 * remain separate release gates.
 */
@RunWith(AndroidJUnit4.class)
public final class NoSupportPlaPilotTest {
    private static final String TAG = "AlloyNoSupportPilot";
    private static final String EXPORT_ARGUMENT = "export-no-support-pilot";
    private static final String[] FIXTURES = {
            "box-20mm.stl",
            "mounting-block.stl",
            "thin-wall-frame-fixture.stl",
            "travel_obstacle.stl",
            "box-and-lid.stl"
    };

    @Test
    public void nativeNoSupportPilotFixturesRemainInspectableAndPackageable() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        boolean exportEvidence = "true".equalsIgnoreCase(arguments.getString(EXPORT_ARGUMENT));
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);

        // The pilot must not accidentally inherit a recipe that needs the
        // unresolved support-parity path.
        config.supports = false;
        Assert.assertTrue("the selected profile must retain its A1 Mini identity",
                config.printer.startsWith("Bambu Lab A1 Mini"));
        Assert.assertEquals(0.4f, config.nozzle, 0.0001f);
        Assert.assertTrue("the pinned preset must retain the PLA Basic identity",
                profile.filamentName.contains("PLA Basic"));
        Assert.assertEquals("PLA", config.filament);
        Assert.assertFalse(config.supports);

        NativeSlicerEngine engine = new NativeSlicerEngine(context.getCacheDir(), false);
        File evidenceDir = exportEvidence ? createEvidenceDirectory(context) : null;
        if (evidenceDir != null) {
            copyAsset(context, "profiles/a1-mini-0.4-pla-basic.json",
                    new File(evidenceDir, "a1-mini-0.4-pla-basic.json"));
        }
        ArrayList<PilotArtifact> exported = new ArrayList<>();
        for (String fixture : FIXTURES) {
            PilotArtifact artifact = assertPilotArtifact(context, engine, config, fixture, evidenceDir);
            if (artifact != null) exported.add(artifact);
        }
        if (evidenceDir != null) {
            writeEvidenceManifest(context, testContext, evidenceDir, exported);
            Log.i(TAG, "Software-only pilot evidence exported to " + evidenceDir.getAbsolutePath());
        }
    }

    private static void assertPilotArtifact(Context context, NativeSlicerEngine engine,
                                            Slicer.Config config, String fixture) throws Exception {
        assertPilotArtifact(context, engine, config, fixture, null);
    }

    /**
     * Retains packages only when the caller explicitly opts into the physical
     * pilot evidence route. Normal regression runs keep the previous
     * disposable-cache behavior so they cannot silently accumulate model data.
     */
    private static PilotArtifact assertPilotArtifact(Context context, NativeSlicerEngine engine,
                                                     Slicer.Config config, String fixture,
                                                     File evidenceDir) throws Exception {
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/" + fixture)) {
            mesh = MeshModel.read(fixture, input);
        }
        Slicer.validate(mesh, config);
        Slicer.Result result = engine.slice(mesh, config.copy(), null);

        Assert.assertEquals("native pilot must identify its engine", "orca-mobile-native", result.engineId);
        Assert.assertFalse("a qualification run must not claim production verification", result.engineVerified);
        Assert.assertTrue("native pilot must generate layers", result.layers != null && !result.layers.isEmpty());
        Assert.assertTrue("native pilot must report filament", result.filamentMm > 0f);
        Assert.assertTrue("native pilot must report print time", result.printTimeSeconds > 0f);
        Assert.assertFalse("no-support pilot must not emit support paths", result.gcode.contains(";TYPE:Support"));
        Assert.assertTrue("reviewed A1 template must be present", A1MiniTemplatePolicy.inspect(result.gcode).isValid());
        Assert.assertTrue("generated toolpath must fit the selected bed",
                ArtifactValidator.validate(mesh, result, config).isValid());

        File storage = evidenceDir == null ? context.getCacheDir() : evidenceDir;
        PrinterTransport.Artifact artifact = ArtifactStore.stage(storage, mesh, result,
                config, "no-support-pilot-" + fixture, null);
        try {
            GcodePackageValidator.validate(artifact.sourceFile, config);
            if (BuildConfig.PHYSICAL_PILOT_ENABLED) {
                GcodePackageValidator.validateForA1MiniNoSupportPilot(artifact.sourceFile, config);
            }
            try (InputStream archived = new FileInputStream(artifact.sourceFile)) {
                MeshModel roundTrip = MeshModel.read(artifact.sourceFile.getName(), archived);
                Assert.assertEquals("package must retain printable mesh triangles", mesh.triangles.length,
                        roundTrip.triangles.length);
            }
            if (evidenceDir != null) {
                return new PilotArtifact(fixture, artifact, result);
            }
        } finally {
            if (evidenceDir == null) {
                if (artifact.sourceFile != null) artifact.sourceFile.delete();
                File parent = artifact.sourceFile == null ? null : artifact.sourceFile.getParentFile();
                if (parent != null && parent.isDirectory()) parent.delete();
            }
        }
        return null;
    }

    private static File createEvidenceDirectory(Context context) throws Exception {
        File root = context.getExternalFilesDir("alloy-no-support-pilot");
        if (root == null) throw new IllegalStateException("App-external storage is unavailable for pilot evidence");
        File run = new File(root, "run-" + System.currentTimeMillis());
        if (!run.mkdirs()) throw new IllegalStateException("Could not create pilot evidence directory");
        return run;
    }

    private static void copyAsset(Context context, String assetPath, File destination) throws Exception {
        try (InputStream input = context.getAssets().open(assetPath);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private static void writeEvidenceManifest(Context context, Context testContext, File evidenceDir,
                                              ArrayList<PilotArtifact> artifacts) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("schema_version", 1);
        manifest.put("scope", "A1 Mini / N1 / 0.4 mm / PLA Basic / no supports");
        manifest.put("software_only", true);
        manifest.put("physical_qualification", "PENDING");
        manifest.put("direct_send_qualification", "PENDING");
        manifest.put("exported_at_epoch_ms", System.currentTimeMillis());
        manifest.put("apk_sha256", ArtifactStore.sha256(new File(context.getApplicationInfo().sourceDir)));
        manifest.put("instrumentation_apk_sha256",
                ArtifactStore.sha256(new File(testContext.getApplicationInfo().sourceDir)));
        File profile = new File(evidenceDir, "a1-mini-0.4-pla-basic.json");
        manifest.put("profile_sha256", ArtifactStore.sha256(profile));
        JSONArray fixtureRecords = new JSONArray();
        for (PilotArtifact artifact : artifacts) fixtureRecords.put(artifact.toJson());
        manifest.put("fixtures", fixtureRecords);
        manifest.put("next_step",
                "Review the packages, then collect physical photos, measurements and printer telemetry separately. This manifest does not authorize upload or start.");

        File destination = new File(evidenceDir, "software-pilot-manifest.json");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(manifest.toString(2).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.getFD().sync();
        }
    }

    private static final class PilotArtifact {
        final String fixture;
        final PrinterTransport.Artifact artifact;
        final Slicer.Result result;

        PilotArtifact(String fixture, PrinterTransport.Artifact artifact, Slicer.Result result) {
            this.fixture = fixture;
            this.artifact = artifact;
            this.result = result;
        }

        JSONObject toJson() throws Exception {
            JSONObject value = new JSONObject();
            value.put("fixture", fixture);
            value.put("package", artifact.sourceFile.getName());
            value.put("package_sha256", artifact.sha256);
            value.put("package_bytes", artifact.sizeBytes);
            value.put("layers", result.layers == null ? 0 : result.layers.size());
            value.put("filament_mm", result.filamentMm);
            value.put("print_time_seconds", result.printTimeSeconds);
            value.put("native_engine", result.engineId);
            value.put("engine_verified", result.engineVerified);
            value.put("supports", false);
            value.put("package_preflight", "PASS");
            return value;
        }
    }
}
