Warning: truncated output (original token count: 42841)
Total output lines: 2976

package com.mbaliga.alloy;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.bouncycastle.jsse.BCSSLSocket;

/** On-device acceptance coverage for the safe, offline phone workflow. */
@RunWith(AndroidJUnit4.class)
public final class AndroidPipelineTest {
    @Test
    public void libnoiseLgplNoticeIsRetainedInPackagedAssets() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream input = context.getAssets().open("LIBNOISE_LGPL-2.1.txt")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            String notice = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            Assert.assertTrue("packaged libnoise notice must retain LGPL identity",
                    notice.contains("GNU LESSER GENERAL PUBLIC LICENSE"));
            Assert.assertTrue("packaged libnoise notice must retain its declared version",
                    notice.contains("Version 2.1, February 1999"));
        }
    }

    @Test
    public void bundledModelSlicesAndStagesValidatedPackage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ArrayList<ModelCatalog.Entry> catalog = ModelCatalog.load(context.getAssets());
        Assert.assertTrue("catalog should include the core and parity examples", catalog.size() >= 5);
        boolean hasOverhang = false;
        boolean hasThinWall = false;
        boolean hasOwnerChassis = false;
        boolean hasOwnerStepAssembly = false;
        boolean hasOwnerA1Reference = false;
        for (ModelCatalog.Entry entry : catalog) {
            ModelCatalog.verify(context.getAssets(), entry);
            hasOverhang |= entry.assetPath.endsWith("overhang-support-fixture.stl");
            hasThinWall |= entry.assetPath.endsWith("thin-wall-frame-fixture.stl");
            hasOwnerChassis |= entry.assetPath.endsWith("redmagic-keyboard-main-chassis-v04.stl");
            hasOwnerStepAssembly |= entry.assetPath.endsWith("redmagic-keyboard-case-assembly-v04-angled.step");
            hasOwnerA1Reference |= entry.assetPath.endsWith("a1-mini-v5-owner.3mf");
        }
        Assert.assertTrue("overhang fixture must be cataloged", hasOverhang);
        Assert.assertTrue("thin-wall fixture must be cataloged", hasThinWall);
        boolean privateCatalogPresent;
        try (InputStream ignored = context.getAssets().open("models/catalog-private.json")) {
            privateCatalogPresent = true;
        } catch (IOException absent) {
            privateCatalogPresent = false;
        }
        if (privateCatalogPresent) {
            Assert.assertTrue("private catalog should expose the supplied chassis", hasOwnerChassis);
            Assert.assertTrue("private catalog should expose the editable STEP assembly", hasOwnerStepAssembly);
            Assert.assertTrue("private catalog should expose the supplied A1 3MF", hasOwnerA1Reference);
            try (InputStream input = context.getAssets().open("models/redmagic-keyboard-main-chassis-v04.stl")) {
                MeshModel ownerChassis = MeshModel.read("redmagic-keyboard-main-chassis-v04.stl", input);
                Assert.assertEquals("owner chassis should remain one watertight part", 1, ownerChassis.parts.length);
                Assert.assertTrue(ownerChassis.geometryReport().isWatertight());
                Assert.assertTrue(ownerChassis.triangles.length / 3 > 10_000);
            }
            try (InputStream input = context.getAssets().open("models/a1-mini-v5-owner.3mf")) {
                MeshModel ownerA1 = MeshModel.read("Bambu Lab A1 Mini v5 owner reference.3mf", input);
                Assert.assertTrue("supplied A1 3MF should contain renderable geometry", ownerA1.triangles.length > 0);
                Assert.assertEquals("supplied A1 3MF should preserve its four component solids", 4,
                        ownerA1.parts.length);
                Assert.assertTrue("supplied A1 3MF should preserve its model bounds",
                        Math.max(ownerA1.maxX - ownerA1.minX,
                                Math.max(ownerA1.maxY - ownerA1.minY, ownerA1.maxZ - ownerA1.minZ)) > 100f);
            }
        }
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);

        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-and-lid.stl")) {
            mesh = MeshModel.read("Box + lid assembly.stl", input);
        }
        Assert.assertEquals("Box + lid assembly.stl", mesh.displayName);
        Assert.assertEquals(2, mesh.parts.length);
        Assert.assertTrue(mesh.geometryReport().isWatertight());

        Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
        Assert.assertFalse(result.gcode.isEmpty());
        Assert.assertFalse(result.layers.isEmpty());
        Assert.assertFalse(result.engineVerified);

        PrinterTransport.Artifact artifact = null;
        PrinterTransport.Artifact headlessArtifact = null;
        try {
            byte[] thumbnail = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
            artifact = ArtifactStore.stage(context.getCacheDir(), mesh, result, config, "android-pipeline", thumbnail);
            Assert.assertTrue(artifact.hasSourceFile());
            Assert.assertTrue(artifact.sizeBytes > 0L);
            Assert.assertTrue("a surface-backed artifact should retain its Bambu thumbnail",
                    ArtifactStore.hasThumbnail(artifact.sourceFile));
            GcodePackageValidator.validate(artifact.sourceFile);
            GcodePackageValidator.validate(artifact.sourceFile, config);
            Assert.assertEquals(artifact.sizeBytes, artifact.sourceFile.length());
            Assert.assertEquals(64, artifact.sha256.length());
            boolean plateThumbnail = false;
            boolean smallThumbnail = false;
            boolean printerThumbnail = false;
            boolean canonicalSliceInfo = false;
            boolean gcodeMd5 = false;
            boolean modelSettings = false;
            boolean projectSettings = false;
            boolean objectModel = false;
            boolean modelRelationships = false;
            boolean cutInformation = false;
            boolean noLightThumbnail = false;
            boolean topThumbnail = false;
            boolean pickThumbnail = false;
            try (ZipInputStream zip = new ZipInputStream(new java.io.FileInputStream(artifact.sourceFile), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if ("Metadata/plate_1.png".equals(entry.getName())) plateThumbnail = true;
                    if ("Metadata/plate_1_small.png".equals(entry.getName())) smallThumbnail = true;
                    if ("Metadata/bbl_thumbnail.png".equals(entry.getName())) printerThumbnail = true;
                    if ("Metadata/slice_info.config".equals(entry.getName())) canonicalSliceInfo = true;
                    if ("Metadata/plate_1.gcode.md5".equals(entry.getName())) gcodeMd5 = true;
                    if ("Metadata/model_settings.config".equals(entry.getName())) modelSettings = true;
                    if ("Metadata/project_settings.config".equals(entry.getName())) projectSettings = true;
                    if ("3D/Objects/object_1.model".equals(entry.getName())) objectModel = true;
                    if ("3D/_rels/3dmodel.model.rels".equals(entry.getName())) modelRelationships = true;
                    if ("Metadata/cut_information.xml".equals(entry.getName())) cutInformation = true;
                    if ("Metadata/plate_no_light_1.png".equals(entry.getName())) noLightThumbnail = true;
                    if ("Metadata/top_1.png".equals(entry.getName())) topThumbnail = true;
                    if ("Metadata/pick_1.png".equals(entry.getName())) pickThumbnail = true;
                }
            }
            Assert.assertTrue(plateThumbnail);
            Assert.assertTrue(smallThumbnail);
            Assert.assertTrue(printerThumbnail);
            Assert.assertTrue(canonicalSliceInfo);
            Assert.assertTrue(gcodeMd5);
            Assert.assertTrue(modelSettings);
            Assert.assertTrue(projectSettings);
            Assert.assertTrue(objectModel);
            Assert.assertTrue(modelRelationships);
            Assert.assertTrue(cutInformation);
            Assert.assertTrue(noLightThumbnail);
            Assert.assertTrue(topThumbnail);
            Assert.assertTrue(pickThumbnail);
            try (InputStream packaged = new java.io.FileInputStream(artifact.sourceFile)) {
                MeshModel roundTrip = MeshModel.read("android-pipeline.gcode.3mf", packaged);
                Assert.assertEquals("related-object 3MF must remain reopenable in Alloy",
                        mesh.triangles.length, roundTrip.triangles.length);
                Assert.assertTrue(roundTrip.geometryReport().isWatertight());
            }

            // Foreground services intentionally stage without a GLES surface.
            // Keep that state observable so the Activity can re-stage before a
            // user-visible export or printer upload.
            headlessArtifact = ArtifactStore.stage(context.getCacheDir(), mesh, result, config,
                    "android-pipeline-headless", null);
            Assert.assertFalse("headless staging must not claim a thumbnail", ArtifactStore.hasThumbnail(headlessArtifact.sourceFile));
        } finally {
            if (artifact != null && artifact.sourceFile.exists()) Assert.assertTrue(artifact.sourceFile.delete());
            if (headlessArtifact != null && headlessArtifact.sourceFile.exists()) Assert.assertTrue(headlessArtifact.sourceFile.delete());
        }
    }

    @Test
    public void stepSourcesAreContentDetectedAndBoundedBeforeNativeConversion() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "step-signature-test-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        try {
            byte[] step = ("ISO-10303-21;\n"
                    + "HEADER;\nFILE_DESCRIPTION(('Alloy test'),'2;1');\nENDSEC;\n"
                    + "DATA;\nENDSEC;\nEND-ISO-10303-21;\n")
                    .getBytes(StandardCharsets.US_ASCII);
            ModelStore.Materialized materialized = ModelStore.materializeGenerated(root, step);
            Assert.assertEquals(".step", materialized.extension);
            Assert.assertTrue(materialized.file.getName().endsWith(".step"));
            Assert.assertEquals("sample.step", ModelStore.parserName("sample.step", materialized.extension));

            ByteArrayOutputStream bundle = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(bundle)) {
                zip.putNextEntry(new ZipEntry("cad/sample.stp"));
                zip.write(step);
                zip.closeEntry();
            }
            ArrayList<ModelBundleExtractor.Extracted> extracted = ModelBundleExtractor.extract(
                    root, new ByteArrayInputStream(bundle.toByteArray()));
            Assert.assertEquals(1, extracted.size());
            Assert.assertEquals(".step", extracted.get(0).materialized.extension);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void batchArtifactsAreIndividuallyValidatedAndPortable() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
        File root = new File(context.getCacheDir(), "batch-archive-test-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        PrinterTransport.Artifact first = null;
        PrinterTransport.Artifact second = null;
        try {
            first = ArtifactStore.stage(root, mesh, result, config, "box-plate-1");
            second = ArtifactStore.stage(root, mesh, result, config, "lid-plate-2");
            ArrayList<BatchSliceJobController.PlateResult> plates = new ArrayList<>();
            plates.add(new BatchSliceJobController.PlateResult(
                    PlateStore.Plate.empty(0), mesh, result, first));
            plates.add(new BatchSliceJobController.PlateResult(
                    PlateStore.Plate.empty(1), mesh, result, second));
            BatchSliceJobController.BatchResult batch = new BatchSliceJobController.BatchResult(plates);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            BatchArtifactArchive.write(bytes, batch);
            File archive = new File(root, "plates.alloy-batch.zip");
            java.nio.file.Files.write(archive.toPath(), bytes.toByteArray());
            BatchArtifactArchive.validate(archive);
            Assert.assertTrue(new String(readZipEntry(bytes.toByteArray(), "alloy-batch.json"), StandardCharsets.UTF_8)
                    .contains("\"plates\""));
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    public void foregroundSliceSnapshotAndResultSurviveActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-and-lid.stl")) {
            mesh = MeshModel.read("box-and-lid.stl", input);
        }
        File root = new File(context.getCacheDir(), "foreground-slice-test-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        String jobId = SliceRequestStore.newJobId();
        SharedPreferences checkpointPreferences = context.getSharedPreferences("foreground-slice-test-" + jobId, Context.MODE_PRIVATE);
        try {
            String modelHash = SliceRequestStore.write(root, jobId, mesh, config, "box-and-lid");
            SliceRequestStore.Request request = SliceRequestStore.read(root, jobId);
            Assert.assertEquals(modelHash, request.modelSha256);
            Assert.assertEquals(modelHash, SliceRequestStore.fingerprint(request.model));
            Assert.assertEquals(mesh.triangles.length / 3, request.model.triangles.length / 3);
            Assert.assertEquals("foreground request must preserve the native profile projection",
                    config.nativeSettings, request.config.nativeSettings);
            Assert.assertEquals("foreground request must bind the complete recipe",
                    SliceRequestStore.configFingerprint(request.config), request.recipeSha256);

            SliceJobStore jobs = new SliceJobStore(checkpointPreferences);
            jobs.begin(jobId, "box-and-lid", modelHash);
            jobs.markRunning(jobId);
            jobs.progress(jobId, 42, "Slicing layer 2");
            Slicer.Result result = new LegacyOfflineEngine().slice(request.model, request.config, null);
            File jobDir = SliceRequestStore.jobDirectory(root, jobId);
            SliceResultStore.write(jobDir, result);
            Slicer.Result restored = SliceResultStore.read(jobDir, request.config);
            Assert.assertEquals(result.gcode, restored.gcode);
            Assert.assertEquals(result.engineId, restored.engineId);
            Assert.assertEquals(result.printTimeSource, restored.printTimeSource);
            Assert.assertEquals(result.printTimeSource,
                    new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(
                            new File(jobDir, "result.json").toPath()), StandardCharsets.UTF_8))
                            .getString("print_time_source"));
            Assert.assertEquals(result.layers.size(), restored.layers.size());
            PrinterTransport.Artifact artifact = ArtifactStore.stage(root, request.model, restored, request.config, "foreground-slice-test");
            jobs.complete(jobId, artifact, "Slice complete");
            SliceJobStore.Job completed = jobs.load();
            Assert.assertEquals(SliceJobStore.State.COMPLETED, completed.state);
            Assert.assertEquals(artifact.sha256, completed.artifactSha256);
            Assert.assertEquals(artifact.sizeBytes, completed.artifactSize);
        } finally {
            checkpointPreferences.edit().clear().commit();
            deleteRecursively(root);
        }
    }

    @Test
    public void foregroundSliceCheckpointFailsClosedAfterProcessLoss() {
        String jobId = SliceRequestStore.newJobId();
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("foreground-slice-recovery-test-" + jobId, Context.MODE_PRIVATE);
        try {
            SliceJobStore jobs = new SliceJobStore(preferences);
            jobs.begin(jobId, "box", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
            jobs.markRunning(jobId);
            SliceJobStore.Job recovered = jobs.recoverAfterRestart(false);
            Assert.assertEquals(SliceJobStore.State.RECOVERY_REQUIRED, recovered.state);
            Assert.assertTrue(recovered.detail.contains("restarted"));
            Assert.assertFalse(recovered.isTerminal());
        } finally {
            preferences.edit().clear().commit();
        }
    }

    @Test
    public void foregroundSliceWatchdogUsesBoundedStallWindow() {
        long now = System.currentTimeMillis();
        Assert.assertFalse("fresh progress must not be treated as stalled",
                SliceJobService.hasStalled(now, now));
        Assert.assertTrue("a silent slice past the watchdog window must require recovery",
                SliceJobService.hasStalled(now, now - 180_000L));
        Assert.assertFalse("future timestamps must not trip the watchdog",
                SliceJobService.hasStalled(now, now + 1L));
    }

    @Test
    public void foregroundBatchSnapshotAndPerPlateResultsSurviveActivityRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        ModelStore.Materialized source = ModelStore.materializeAsset(context.getFilesDir(), context.getAssets(), "models/box-20mm.stl");
        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        plates.add(new PlateStore.Plate(0, "Box", new ArrayList<>(java.util.Collections.singletonList(source.uri)),
                new ArrayList<>(java.util.Collections.singletonList("box-20mm.stl")), 1f, 0f, -1, new ArrayList<>()));
        plates.add(new PlateStore.Plate(1, "Lid", new ArrayList<>(java.util.Collections.singletonList(source.uri)),
                new ArrayList<>(java.util.Collections.singletonList("box-20mm.stl")), 1f, 0f, -1, new ArrayList<>()));
        String jobId = BatchSliceRequestStore.newJobId();
        File batchDir = null;
        SharedPreferences checkpointPreferences = context.getSharedPreferences("foreground-batch-test-" + jobId, Context.MODE_PRIVATE);
        ArrayList<PrinterTransport.Artifact> artifacts = new ArrayList<>();
        try {
            String projectHash = BatchSliceRequestStore.fingerprint(plates, config);
            Slicer.Config changedProfile = config.copy();
            changedProfile.nativeSettings.put("alloy_test_setting", "different-profile-value");
            Assert.assertNotEquals("batch identity must include the native profile projection",
                    projectHash, BatchSliceRequestStore.fingerprint(plates, changedProfile));
            Assert.assertEquals(projectHash, BatchSliceRequestStore.write(context.getFilesDir(), context.getContentResolver(),
                    jobId, plates, config, "Boxes"));
            BatchSliceRequestStore.Request request = BatchSliceRequestStore.read(context.getFilesDir(), jobId);
            Assert.assertEquals(projectHash, request.projectSha256);
            Assert.assertEquals(2, request.plates.size());
            Assert.assertEquals("batch request must preserve the native profile projection",
                    config.nativeSettings, request.config.nativeSettings);
            batchDir = BatchSliceRequestStore.jobDirectory(context.getFilesDir(), jobId);
            final File resultBatchDir = batchDir;
            BatchSliceJobStore jobs = new BatchSliceJobStore(checkpointPreferences);
            jobs.begin(jobId, "Boxes", request.plates.size());
            jobs.markRunning(jobId);
            BatchSliceJobController.BatchResult result = BatchSliceJobController.run(
                    context.getContentResolver(), context.getFilesDir(), request.plates, request.config,
                    new LegacyOfflineEngine(), (plate, completed, total) -> {
                        artifacts.add(plate.artifact);
                        BatchSliceResultStore.record(context.getFilesDir(), resultBatchDir, plate);
                        jobs.progress(jobId, completed, completed * 100 / total, plate.plate.name);
                    }, null);
            Assert.assertEquals(2, result.plates.size());
            BatchSliceJobController.BatchResult restored = BatchSliceResultStore.read(context.getFilesDir(), context.getContentResolver(), request, resultBatchDir);
            Assert.assertEquals(result.plates.size(), restored.plates.size());
            Assert.assertEquals(result.totalLayers(), restored.totalLayers());
            for (int index = 0; index < result.plates.size(); index++)
                Assert.assertEquals(result.plates.get(index).slice.printTimeSource,
                        restored.plates.get(index).slice.printTimeSource);
            jobs.complete(jobId, "All plates ready");
            Assert.assertEquals(BatchSliceJobStore.State.COMPLETED, jobs.load().state);
        } finally {
            for (PrinterTransport.Artifact a…30841 tokens truncated…nZ, 0.01f);
        Assert.assertEquals(80f, oriented.maxX - oriented.minX, 0.01f);
        Assert.assertEquals(30f, oriented.maxY - oriented.minY, 0.01f);
        Assert.assertEquals(0f, oriented.minZ, 0.01f);
    }

    @Test
    public void offlineSlicerHonorsSolidTopAndBottomLayerCounts() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Config config = new Slicer.Config();
        config.infill = 0.10f;
        config.bottomLayers = 1;
        config.topLayers = 1;
        Slicer.Result result = new Slicer().slice(mesh, config, null);
        Assert.assertTrue("the fallback must emit solid infill for selected top/bottom layers",
                result.gcode.contains(";TYPE:Solid infill"));
        Assert.assertTrue("the fallback must retain sparse infill for interior layers",
                result.gcode.contains(";TYPE:Sparse infill"));
    }

    @Test
    public void chamferedBoxAcceptsBoundedCornerParameter() throws Exception {
        MeshModel chamfered = ModelWorkbench.createChamferedBox("enclosure", 60f, 40f, 24f, 6f);
        Assert.assertEquals(28, chamfered.triangles.length / 3);
        Assert.assertEquals(60f, chamfered.maxX - chamfered.minX, 0.001f);
        Assert.assertEquals(40f, chamfered.maxY - chamfered.minY, 0.001f);
        Assert.assertEquals(24f, chamfered.maxZ - chamfered.minZ, 0.001f);
        Assert.assertTrue(chamfered.geometryReport().isWatertight());
        try {
            ModelWorkbench.createChamferedBox("invalid", 20f, 20f, 10f, 5f);
            Assert.fail("oversized chamfer should be rejected");
        } catch (IOException expected) { }
    }

    @Test
    public void hollowBoxPreservesPrintableCavityAndThicknessBounds() throws Exception {
        MeshModel enclosure = ModelWorkbench.createHollowBox("open enclosure", 60f, 40f, 24f, 2f, 2.5f);
        Assert.assertEquals(60f, enclosure.maxX - enclosure.minX, 0.001f);
        Assert.assertEquals(40f, enclosure.maxY - enclosure.minY, 0.001f);
        Assert.assertEquals(24f, enclosure.maxZ - enclosure.minZ, 0.001f);
        Assert.assertEquals(28, enclosure.triangles.length / 3);
        Assert.assertTrue("open-top enclosure should remain a closed printable shell", enclosure.geometryReport().isWatertight());
        try {
            ModelWorkbench.createHollowBox("invalid", 20f, 20f, 10f, 10f, 2f);
            Assert.fail("wall thickness that consumes the cavity should be rejected");
        } catch (IOException expected) { }
    }

    @Test
    public void duplicateArrayPreservesPartsAndBuildVolume() throws Exception {
        MeshModel source = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "array source", 24f, 24f, 10f);
        MeshModel array = ModelWorkbench.createArray("array", source, 4);
        Assert.assertEquals(4, array.parts.length);
        Assert.assertEquals("array copy 1", array.parts[0].name);
        Assert.assertEquals("array copy 4", array.parts[3].name);
        Assert.assertEquals(10f, array.maxZ - array.minZ, 0.001f);
        Assert.assertTrue(array.maxX <= 180.001f);
        Assert.assertTrue(array.maxY <= 180.001f);
        try {
            ModelWorkbench.createArray("too many", ModelWorkbench.create(ModelWorkbench.Primitive.BOX,
                    "large", 90f, 90f, 10f), 4);
            Assert.fail("an oversized duplicate array should be rejected");
        } catch (IOException expected) { }
    }

    @Test
    public void profileSizedWorkbenchUsesA1AndP1sEnvelopeWithoutRelaxingMiniDefault() throws Exception {
        MeshModel source = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "wide source", 110f, 110f, 10f);
        try {
            ModelWorkbench.createArray("mini", source, 4);
            Assert.fail("the A1 Mini compatibility default must still reject this array");
        } catch (IOException expected) { }
        MeshModel a1Array = ModelWorkbench.createArray("a1", source, 4, 256f, 256f, 256f);
        Assert.assertEquals(4, a1Array.parts.length);
        Assert.assertTrue(a1Array.maxX <= 256.001f);
        Assert.assertTrue(a1Array.maxY <= 256.001f);
        Assert.assertEquals("the wider profile should retain a two-by-two shelf pack", 225f,
                a1Array.maxX, 0.001f);
        Assert.assertEquals("the wider profile should retain a two-by-two shelf pack", 225f,
                a1Array.maxY, 0.001f);
        MeshModel p1sSizedPrimitive = ModelWorkbench.create(ModelWorkbench.Primitive.BOX,
                "p1s-sized", 220f, 220f, 220f);
        Assert.assertEquals(220f, p1sSizedPrimitive.maxX - p1sSizedPrimitive.minX, 0.001f);
        Assert.assertEquals(220f, p1sSizedPrimitive.maxY - p1sSizedPrimitive.minY, 0.001f);
        Assert.assertEquals(220f, p1sSizedPrimitive.maxZ - p1sSizedPrimitive.minZ, 0.001f);
    }

    @Test
    public void mirrorPreservesFootprintPartsAndWatertightness() throws Exception {
        MeshModel source = ModelWorkbench.createShowcaseBoxAssembly();
        MeshModel mirroredX = ModelWorkbench.mirror("mirror-x", source, true);
        MeshModel mirroredY = ModelWorkbench.mirror("mirror-y", source, false);
        for (MeshModel mirrored : new MeshModel[]{mirroredX, mirroredY}) {
            Assert.assertEquals(source.maxX - source.minX, mirrored.maxX - mirrored.minX, 0.001f);
            Assert.assertEquals(source.maxY - source.minY, mirrored.maxY - mirrored.minY, 0.001f);
            Assert.assertEquals(source.maxZ - source.minZ, mirrored.maxZ - mirrored.minZ, 0.001f);
            Assert.assertEquals(source.parts.length, mirrored.parts.length);
            Assert.assertTrue("mirrored assembly must remain watertight", mirrored.geometryReport().isWatertight());
        }
        Assert.assertEquals(source.triangles.length / 3, mirroredX.triangles.length / 3);
        Assert.assertEquals(source.parts[0].name, mirroredY.parts[0].name);
    }

    @Test
    public void showcaseAssemblyProvidesInspectablePresentationParts() throws Exception {
        MeshModel showcase = ModelWorkbench.createShowcaseBoxAssembly();
        Assert.assertEquals("Alloy showcase box", showcase.displayName);
        Assert.assertEquals("showcase should expose the authored assembly details", 7, showcase.parts.length);
        Assert.assertTrue("showcase should be more than a two-solid placeholder", showcase.triangles.length / 3 > 100);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.minX >= -0.001f);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.minY >= -0.001f);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.minZ >= -0.001f);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.maxX <= 180.001f);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.maxY <= 180.001f);
        Assert.assertTrue("showcase must stay inside the A1 Mini volume", showcase.maxZ <= 180.001f);
        Assert.assertTrue(showcase.geometryReport().isWatertight());
        Assert.assertEquals("Showcase body", showcase.parts[0].name);
        Assert.assertEquals("Showcase lid", showcase.parts[2].name);
        Assert.assertEquals("Hinge barrels", showcase.parts[3].name);
    }

    @Test
    public void convexSketchExtrusionRoundTripsThroughStl() throws Exception {
        float[][] points = new float[][]{{-20f, -15f}, {20f, -15f}, {20f, 15f}, {-20f, 15f}};
        MeshModel sketch = ModelWorkbench.createExtrudedPolygon("sketch", points, 25f);
        Assert.assertEquals(12, sketch.triangles.length / 3);
        Assert.assertEquals(25f, sketch.maxZ - sketch.minZ, 0.001f);
        byte[] stl = ModelWorkbench.toBinaryStl(sketch);
        Assert.assertTrue(stl.length > 84);
        MeshModel parsed = MeshModel.read("sketch.stl", new ByteArrayInputStream(stl));
        Assert.assertEquals(12, parsed.triangles.length / 3);
        try {
            ModelWorkbench.createExtrudedPolygon("concave", new float[][]{{-20f, -20f}, {20f, -20f}, {0f, 0f}, {20f, 20f}, {-20f, 20f}}, 10f);
            Assert.fail("concave sketch should be rejected");
        } catch (IOException expected) { }
    }

    @Test
    public void generatedAssemblyUsesExistingModelStorageBoundary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File appFiles = new File(context.getCacheDir(), "workbench-storage-" + System.nanoTime());
        try {
            MeshModel box = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "box", 20f, 20f, 10f);
            MeshModel tube = ModelWorkbench.create(ModelWorkbench.Primitive.TUBE, "tube", 24f, 24f, 12f);
            ArrayList<MeshModel> sources = new ArrayList<>(); sources.add(box); sources.add(tube);
            MeshModel assembly = MeshModel.combine("assembly", sources);
            ModelStore.Materialized materialized = ModelStore.materializeGenerated(appFiles, ModelWorkbench.toBinaryStl(assembly));
            Assert.assertTrue(materialized.file.isFile());
            Assert.assertEquals(".stl", materialized.extension);
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel restored = MeshModel.read("assembly.stl", input);
                Assert.assertEquals(assembly.triangles.length / 3, restored.triangles.length / 3);
                Assert.assertTrue(restored.parts.length >= 2);
            }
        } finally {
            deleteRecursively(appFiles);
        }
    }

    @Test
    public void visualizationBoundaryKeepsLocalAiExplicit() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        OnDeviceVisualizationProvider provider = new OnDeviceVisualizationProvider(context.getFilesDir());
        Assert.assertTrue("the bounded local renderer must be available offline", provider.isAvailable());
        Assert.assertFalse("a generative model is not silently assumed to be bundled", provider.hasGenerativeModel());
        Bitmap source = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        try {
            source.eraseColor(Color.WHITE);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            Assert.assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, encoded));
            VisualizationProvider.Result local = provider.generate(new VisualizationProvider.Request(
                    encoded.toByteArray(), "painted black in a workshop"));
            Assert.assertEquals("On-device studio renderer", local.providerLabel);
            Bitmap rendered = BitmapFactory.decodeByteArray(local.imagePng, 0, local.imagePng.length);
            Assert.assertNotNull("offline visualization should return a PNG", rendered);
            Assert.assertEquals(32, rendered.getWidth());
            rendered.recycle();
        } finally {
            source.recycle();
        }
        try {
            new VisualizationCredentialStore.Credentials("http://example.com/images/edits", "model", "key");
            Assert.fail("BYOK endpoint must require HTTPS");
        } catch (IllegalArgumentException expected) {
            // Cloud visualization must not silently opt into cleartext transport.
        }
        HttpURLConnection connection = (HttpURLConnection) new URL("https://example.com/visualize").openConnection();
        try {
            connection.setInstanceFollowRedirects(true);
            ByokVisualizationProvider.disableRedirects(connection);
            Assert.assertFalse("BYOK transport must not follow redirects", connection.getInstanceFollowRedirects());
        } finally {
            connection.disconnect();
        }
        try {
            new VisualizationProvider.Request(new byte[]{1, 2, 3}, "workshop");
            Assert.fail("visualization requests must be PNG thumbnails");
        } catch (IllegalArgumentException expected) { }
        try {
            new VisualizationProvider.Result(new byte[]{1, 2, 3}, "test");
            Assert.fail("visualization results must be PNG images");
        } catch (IOException expected) { }
        try {
            new VisualizationProvider.Result(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "test");
            Assert.fail("visualization results must be decodable bounded PNG images");
        } catch (IOException expected) { }
    }

    @Test
    public void cpuThumbnailUsesPerspectiveAndMaterialShading() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-and-lid.stl")) {
            mesh = MeshModel.read("box-and-lid.stl", input);
        }
        final ViewportView[] holder = new ViewportView[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewportView view = new ViewportView(context);
            view.measure(View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 640, 640);
            view.setModel(mesh);
            holder[0] = view;
        });
        byte[] encoded = holder[0].thumbnailPng(256);
        Assert.assertNotNull("a bounded thumbnail should be generated without a GL frame callback", encoded);
        Bitmap thumbnail = BitmapFactory.decodeByteArray(encoded, 0, encoded.length);
        Assert.assertNotNull(thumbnail);
        Assert.assertEquals(256, thumbnail.getWidth());
        Assert.assertEquals(256, thumbnail.getHeight());
        int first = thumbnail.getPixel(32, 32);
        boolean foundMaterialContrast = false;
        for (int y = 32; y < thumbnail.getHeight() - 32 && !foundMaterialContrast; y += 8) {
            for (int x = 32; x < thumbnail.getWidth() - 32; x += 8) {
                int pixel = thumbnail.getPixel(x, y);
                if (pixel != first && Color.alpha(pixel) > 0) {
                    foundMaterialContrast = true;
                    break;
                }
            }
        }
        Assert.assertTrue("thumbnail should contain a shaded scene rather than one flat fill", foundMaterialContrast);
        thumbnail.recycle();
    }

    @Test
    public void liveGlesCaptureReturnsBoundedPngForA1Study() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        // MainActivity owns a continuously animated GLES viewport, so its
        // main looper may never become idle. startActivitySync waits for idle
        // and can time out even after the Activity has started. Wait for the
        // actual Activity launch instead, then keep the capture assertion real.
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        context.startActivity(intent);
        Activity activity = monitor.waitForActivityWithTimeout(45_000L);
        instrumentation.removeMonitor(monitor);
        Assert.assertNotNull("main activity should launch for a live GLES capture", activity);
        try {
            Field viewportField = MainActivity.class.getDeclaredField("viewport");
            viewportField.setAccessible(true);
            ViewportView viewport = (ViewportView) viewportField.get(activity);
            Assert.assertNotNull("main activity should expose its 3D viewport", viewport);
            instrumentation.runOnMainSync(() -> viewport.setMachineStudy(true));
            SystemClock.sleep(750L);
            CountDownLatch captured = new CountDownLatch(1);
            byte[][] holder = new byte[1][];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                    viewport.capturePng(512, png -> {
                        holder[0] = png;
                        captured.countDown();
                    }));
            Assert.assertTrue("live GLES capture should complete", captured.await(10, TimeUnit.SECONDS));
            Assert.assertNotNull("live A1 study capture should be a PNG", holder[0]);
            Assert.assertTrue("live capture must remain bounded", holder[0].length <= 2 * 1024 * 1024);
            Bitmap bitmap = BitmapFactory.decodeByteArray(holder[0], 0, holder[0].length);
            Assert.assertNotNull("live capture should be decodable", bitmap);
            Assert.assertTrue(bitmap.getWidth() <= 512 && bitmap.getHeight() <= 512);
            bitmap.recycle();
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }

    @Test
    public void localStudioPreviewAppliesBoundedFinishAndEnvironment() throws Exception {
        Bitmap source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(source);
            canvas.drawColor(Color.WHITE);
            Paint model = new Paint(Paint.ANTI_ALIAS_FLAG);
            model.setColor(Color.rgb(70, 70, 75));
            canvas.drawRect(18f, 16f, 46f, 48f, model);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            Assert.assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, encoded));
            Bitmap preview = StudioPreviewRenderer.render(encoded.toByteArray(),
                    StudioPreviewRenderer.Finish.MATTE_BLACK,
                    StudioPreviewRenderer.Environment.WORKSHOP);
            try {
                Assert.assertEquals(64, preview.getWidth());
                Assert.assertEquals(64, preview.getHeight());
                Assert.assertNotEquals("the local environment should frame the reference", Color.WHITE, preview.getPixel(0, 0));
            } finally {
                preview.recycle();
            }
        } finally {
            source.recycle();
        }
    }

    @Test
    public void modelHistoryPersistsUndoRedoAndDropsRedoBranch() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferenceName = "model-history-test-" + System.nanoTime();
        SharedPreferences preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        Uri source = Uri.fromFile(new File("/data/local/tmp/history-source.stl"));
        ArrayList<Uri> uris = new ArrayList<>(java.util.Collections.singletonList(source));
        ArrayList<String> names = new ArrayList<>(java.util.Collections.singletonList("history-source.stl"));
        PlateStore.Plate initial = new PlateStore.Plate(0, "Plate 1  ·  history-source.stl", uris, names,
                1f, 0f, -1, new ArrayList<>());
        PlateStore.Plate transformed = new PlateStore.Plate(0, "Plate 1  ·  history-source.stl", uris, names,
                1.5f, 15f, -1, new ArrayList<>());
        PlateStore.Plate branched = new PlateStore.Plate(0, "Plate 1  ·  history-source.stl", uris, names,
                0.8f, -20f, -1, new ArrayList<>());
        try {
            ModelHistoryStore history = new ModelHistoryStore(preferences);
            history.resetDocument(0, initial, "Open model");
            Assert.assertFalse(history.canUndo(0));
            Assert.assertTrue(history.append(0, transformed, "Prepare model"));
            Assert.assertTrue(history.canUndo(0));
            Assert.assertFalse(history.canRedo(0));
            PlateStore.Plate undone = history.undo(0);
            Assert.assertNotNull(undone);
            Assert.assertEquals(1f, undone.scale, 0.001f);

            ModelHistoryStore restored = new ModelHistoryStore(preferences);
            Assert.assertTrue("redo cursor must survive a new store instance", restored.canRedo(0));
            PlateStore.Plate redone = restored.redo(0);
            Assert.assertNotNull(redone);
            Assert.assertEquals(1.5f, redone.scale, 0.001f);
            Assert.assertTrue(restored.append(0, branched, "Branch edit"));
            Assert.assertFalse("a new edit must discard the redo branch", restored.canRedo(0));
            Assert.assertTrue(restored.labels(0).get(0).contains("Branch edit"));
            Assert.assertFalse(restored.referencedPlates().isEmpty());
        } finally {
            preferences.edit().clear().commit();
        }
    }

    @Test
    public void historicalModelSnapshotsSurviveCachePrune() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "history-prune-test-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        ModelStore.Materialized historical = null;
        try {
            for (int index = 0; index < ModelStore.MAX_MODELS + 3; index++) {
                MeshModel generated = ModelWorkbench.create(ModelWorkbench.Primitive.BOX,
                        "history-" + index, 20f + index, 20f, 10f);
                ModelStore.Materialized materialized = ModelStore.materializeGenerated(
                        root, ModelWorkbench.toBinaryStl(generated));
                if (index == 0) historical = materialized;
            }
            ArrayList<Uri> uris = new ArrayList<>(java.util.Collections.singletonList(historical.uri));
            ArrayList<String> names = new ArrayList<>(java.util.Collections.singletonList("history.stl"));
            PlateStore.Plate historicalPlate = new PlateStore.Plate(0, "History", uris, names,
                    1f, 0f, -1, new ArrayList<>());
            ModelStore.prune(root, new ArrayList<>(),
                    new ArrayList<>(java.util.Collections.singletonList(historicalPlate)));
            Assert.assertTrue("a reachable undo snapshot must not be evicted", historical.file.isFile());
        } finally {
            deleteRecursively(root);
        }
    }

    private static byte[] readZipEntry(byte[] archive, String name) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = zip.getNextEntry()) != null) {
                if (!name.equals(entry.getName())) continue;
                ByteArrayOutputStream result = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) result.write(buffer, 0, read);
                return result.toByteArray();
            }
        }
        return new byte[0];
    }

    private static String facet(String a, String b, String c) {
        return " facet normal 0 0 0\n outer loop\n"
                + "  vertex " + a + "\n  vertex " + b + "\n  vertex " + c + "\n"
                + " endloop\n endfacet\n";
    }

    private static String repeat(char value, int count) {
        StringBuilder output = new StringBuilder(count);
        for (int index = 0; index < count; index++) output.append(value);
        return output.toString();
    }

    private static InventoryStore.Item findInventoryItem(InventoryStore store, String id) {
        for (InventoryStore.Item item : store.items()) if (id.equals(item.id)) return item;
        return null;
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteRecursively(child);
        Assert.assertTrue(file.delete());
    }
}
