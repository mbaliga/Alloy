package com.mbaliga.alloy;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
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

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.bouncycastle.jsse.BCSSLSocket;

/** On-device acceptance coverage for the safe, offline phone workflow. */
@RunWith(AndroidJUnit4.class)
public final class AndroidPipelineTest {
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
            jobs.complete(jobId, "All plates ready");
            Assert.assertEquals(BatchSliceJobStore.State.COMPLETED, jobs.load().state);
        } finally {
            for (PrinterTransport.Artifact artifact : artifacts) if (artifact != null && artifact.sourceFile != null) artifact.sourceFile.delete();
            checkpointPreferences.edit().clear().commit();
            if (batchDir != null) deleteRecursively(batchDir);
        }
    }

    @Test
    public void foregroundBatchCheckpointFailsClosedAfterProcessLoss() {
        String jobId = BatchSliceRequestStore.newJobId();
        SharedPreferences preferences = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getSharedPreferences("foreground-batch-recovery-test-" + jobId, Context.MODE_PRIVATE);
        try {
            BatchSliceJobStore jobs = new BatchSliceJobStore(preferences);
            jobs.begin(jobId, "Boxes", 2);
            jobs.markRunning(jobId);
            jobs.progress(jobId, 1, 50, "Plate 1");
            BatchSliceJobStore.Job recovered = jobs.recoverAfterRestart(false);
            Assert.assertEquals(BatchSliceJobStore.State.RECOVERY_REQUIRED, recovered.state);
            Assert.assertTrue(recovered.completedPlates == 1);
            Assert.assertFalse(recovered.isTerminal());
        } finally {
            preferences.edit().clear().commit();
        }
    }


    @Test
    public void modelImportSniffsStlWhenDocumentNameHasNoExtension() throws Exception {
        String stl = "solid box\n"
                + facet("0 0 0", "10 0 0", "0 10 0")
                + facet("0 0 1", "0 10 1", "10 0 1")
                + "endsolid box\n";
        MeshModel mesh = MeshModel.read("download", new ByteArrayInputStream(stl.getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("download.stl", mesh.displayName);
        Assert.assertEquals(2, mesh.triangles.length / 3);
    }

    @Test
    public void unitlessModelSupportsKnownSizeScaleAndPlateRoundTrip() throws Exception {
        MeshModel source = ModelWorkbench.create(ModelWorkbench.Primitive.BOX,
                "normalized-part", 5f, 4f, 3f);
        MeshModel scaled = source.transformed("normalized-part", 20f, 0f);
        Assert.assertEquals(100f, scaled.maxX - scaled.minX, 0.001f);
        Assert.assertEquals(80f, scaled.maxY - scaled.minY, 0.001f);
        Assert.assertEquals(60f, scaled.maxZ - scaled.minZ, 0.001f);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String preferenceName = "known-size-scale-test-" + System.nanoTime();
        SharedPreferences preferences = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        try {
            PlateStore store = new PlateStore(preferences);
            store.save(new PlateStore.Plate(0, "Scaled part", new ArrayList<>(), new ArrayList<>(),
                    20f, 0f, -1, new ArrayList<>()));
            Assert.assertEquals(20f, store.activePlate().scale, 0.001f);
        } finally {
            preferences.edit().clear().commit();
        }
    }

    @Test
    public void modelImportRejectsUnsafe3mfZipEntry() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("../outside.stl"));
            zip.write("solid outside".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            zip.write(("<model unit=\"millimeter\"><resources><object id=\"1\"><mesh>"
                    + "<vertices><vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"1\" y=\"0\" z=\"0\"/><vertex x=\"0\" y=\"1\" z=\"0\"/></vertices>"
                    + "<triangles><triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles>"
                    + "</mesh></object></resources><build><item objectid=\"1\"/></build></model>")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            MeshModel.read("download", new ByteArrayInputStream(archive.toByteArray()));
            Assert.fail("unsafe 3MF ZIP entry should be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("unsafe"));
        }
    }

    @Test
    public void modelImportReadsValid3mfUnitComponentAndMetadataEntries() throws Exception {
        String model = "<model unit=\"inch\"><resources>"
                + "<object id=\"1\" name=\"Box\"><mesh><vertices>"
                + "<vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"1\" y=\"0\" z=\"0\"/>"
                + "<vertex x=\"0\" y=\"1\" z=\"0\"/><vertex x=\"0\" y=\"0\" z=\"1\"/>"
                + "</vertices><triangles><triangle v1=\"0\" v2=\"2\" v3=\"1\"/>"
                + "<triangle v1=\"0\" v2=\"1\" v3=\"3\"/><triangle v1=\"0\" v2=\"3\" v3=\"2\"/>"
                + "<triangle v1=\"1\" v2=\"2\" v3=\"3\"/></triangles></mesh></object>"
                + "<object id=\"2\" name=\"Lid\"><components><component objectid=\"1\" "
                + "transform=\"1 0 0 0 1 0 0 0 1 2 0 0\"/></components></object>"
                + "</resources><build><item objectid=\"1\"/><item objectid=\"2\"/></build></model>";
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("<Relationships><Relationship Target=\"/3D/3dmodel.model\"/></Relationships>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Metadata/thumbnail.png"));
            zip.write(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            zip.write(model.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        MeshModel mesh = MeshModel.read("download", new ByteArrayInputStream(archive.toByteArray()));
        Assert.assertEquals("download.3mf", mesh.displayName);
        Assert.assertEquals(8, mesh.triangles.length / 3);
        Assert.assertEquals(2, mesh.parts.length);
        Assert.assertEquals(76.2f, mesh.maxX - mesh.minX, 0.001f);
        Assert.assertTrue(mesh.parts[1].name.contains("Lid"));
    }

    @Test
    public void modelImportSplitsSlic3rVolumeRangesIntoInspectableParts() throws Exception {
        StringBuilder vertices = new StringBuilder();
        StringBuilder triangles = new StringBuilder();
        for (int triangle = 0; triangle < 6; triangle++) {
            int base = triangle * 3;
            float x = triangle * 3f;
            vertices.append("<vertex x=\"").append(x).append("\" y=\"0\" z=\"0\"/>")
                    .append("<vertex x=\"").append(x + 1f).append("\" y=\"0\" z=\"0\"/>")
                    .append("<vertex x=\"").append(x).append("\" y=\"1\" z=\"0\"/>");
            triangles.append("<triangle v1=\"").append(base).append("\" v2=\"")
                    .append(base + 1).append("\" v3=\"").append(base + 2).append("\"/>");
        }
        String model = "<model unit=\"millimeter\"><resources>"
                + "<object id=\"1\" name=\"Assembly\"><mesh><vertices>"
                + vertices + "</vertices><triangles>" + triangles
                + "</triangles></mesh></object></resources>"
                + "<build><item objectid=\"1\"/></build></model>";
        String config = "<?xml version=\"1.0\"?><config>"
                + "<object id=\"1\" instances_count=\"1\">"
                + "<volume firstid=\"0\" lastid=\"1\"><metadata type=\"volume\" key=\"name\" value=\"Base\"/></volume>"
                + "<volume firstid=\"2\" lastid=\"5\"><metadata type=\"volume\" key=\"name\" value=\"Lid\"/></volume>"
                + "</object></config>";
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            zip.write(model.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("Metadata/Slic3r_PE_model.config"));
            zip.write(config.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        MeshModel mesh = MeshModel.read("assembly.3mf", new ByteArrayInputStream(archive.toByteArray()));
        Assert.assertEquals(6, mesh.triangles.length / 3);
        Assert.assertEquals(2, mesh.parts.length);
        Assert.assertEquals("Assembly · Base", mesh.parts[0].name);
        Assert.assertEquals(0, mesh.parts[0].triangleStart);
        Assert.assertEquals(2, mesh.parts[0].triangleCount);
        Assert.assertEquals("Assembly · Lid", mesh.parts[1].name);
        Assert.assertEquals(2, mesh.parts[1].triangleStart);
        Assert.assertEquals(4, mesh.parts[1].triangleCount);
    }

    @Test
    public void modelImportRejectsMismatched3mfRootRelationship() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("<Relationships><Relationship Target=\"/3D/other.model\"/></Relationships>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            zip.write(("<model><resources><object id=\"1\"><mesh><vertices>"
                    + "<vertex x=\"0\" y=\"0\" z=\"0\"/><vertex x=\"1\" y=\"0\" z=\"0\"/>"
                    + "<vertex x=\"0\" y=\"1\" z=\"0\"/></vertices><triangles>"
                    + "<triangle v1=\"0\" v2=\"1\" v3=\"2\"/></triangles></mesh></object>"
                    + "</resources><build><item objectid=\"1\"/></build></model>").getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            MeshModel.read("wrong-root.3mf", new ByteArrayInputStream(archive.toByteArray()));
            Assert.fail("mismatched 3MF root relationship should be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("root relationships"));
        }
    }

    @Test
    public void modelImportRejectsOversizedAsciiStlLine() throws Exception {
        String hostile = "solid hostile\nvertex " + repeat(' ', 1024 * 1024) + "\n";
        try {
            MeshModel.read("hostile.stl", new ByteArrayInputStream(hostile.getBytes(StandardCharsets.UTF_8)));
            Assert.fail("oversized ASCII STL line should be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("1 MB limit"));
        }
    }

    @Test
    public void modelImportPreservesNamedObjPartsAndNegativeIndices() throws Exception {
        String obj = "# two named solids\n"
                + "o Box\n"
                + "v 0 0 0\nv 10 0 0\nv 0 10 0\nv 0 0 10\n"
                + "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n"
                + "g Lid\n"
                + "v 20 0 0\nv 30 0 0\nv 20 10 0\nv 20 0 10\n"
                + "f -4 -2 -3\nf -4 -3 -1\nf -4 -1 -2\nf -2 -1 -3\n";
        MeshModel mesh = MeshModel.read("bow-part.obj", new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals("bow-part.obj", mesh.displayName);
        Assert.assertEquals(8, mesh.triangles.length / 3);
        Assert.assertEquals(2, mesh.parts.length);
        Assert.assertEquals("Box", mesh.parts[0].name);
        Assert.assertEquals("Lid", mesh.parts[1].name);
    }

    @Test
    public void modelImportPreservesObjMaterialGroupsAsPresentationParts() throws Exception {
        String obj = "o Bow\n"
                + "v 0 0 0\n v 10 0 0\n v 0 10 0\n v 20 0 0\n v 30 0 0\n v 20 10 0\n"
                + "usemtl limb\n"
                + "f 1 2 3\n"
                + "usemtl grip\n"
                + "f 4 5 6\n";
        MeshModel mesh = MeshModel.read("recurve.obj",
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        Assert.assertEquals(2, mesh.parts.length);
        Assert.assertEquals("Bow · limb", mesh.parts[0].name);
        Assert.assertEquals("Bow · grip", mesh.parts[1].name);
        Assert.assertEquals(1, mesh.parts[0].triangleCount);
        Assert.assertEquals(1, mesh.parts[1].triangleCount);
    }

    @Test
    public void meshRepairRemovesDegenerateDuplicatesWeldsVerticesAndNormalizesClosedWinding() throws Exception {
        String obj = "# inward tetrahedron with a duplicate, a degenerate facet, and near-duplicate vertices\n"
                + "v 0 0 0\n v 10 0 0\n v 0 10 0\n v 0 0 10\n"
                + "v 0.00001 0 0\n v 10.00001 0 0\n v 0 10.00001 0\n"
                + "f 1 2 3\n f 1 4 2\n f 1 3 4\n f 2 4 3\n"
                + "f 1 2 3\n f 1 1 2\n f 5 6 7\n";
        MeshModel mesh = MeshModel.read("damaged-tetra.obj",
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        MeshModel.RepairResult repaired = mesh.repair("damaged-tetra.obj");

        Assert.assertEquals(7, repaired.report.sourceTriangleCount);
        Assert.assertEquals(4, repaired.report.outputTriangleCount);
        Assert.assertEquals(1, repaired.report.removedDegenerateTriangles);
        Assert.assertEquals(2, repaired.report.removedDuplicateTriangles);
        Assert.assertTrue(repaired.report.weldedVertices >= 3);
        Assert.assertTrue(repaired.report.flippedTriangles >= 4);
        Assert.assertTrue(repaired.mesh.geometryReport().isWatertight());
        Assert.assertEquals(4, repaired.mesh.triangles.length / 3);
    }

    @Test
    public void meshRepairClosesBoundedPlanarConvexOpening() throws Exception {
        String obj = "v 0 0 0\n v 10 0 0\n v 10 10 0\n v 0 10 0\n"
                + "v 0 0 10\n v 10 0 10\n v 10 10 10\n v 0 10 10\n"
                + "f 1 3 2\n f 1 4 3\n f 1 2 6\n f 1 6 5\n"
                + "f 2 3 7\n f 2 7 6\n f 3 4 8\n f 3 8 7\n"
                + "f 4 1 5\n f 4 5 8\n";
        MeshModel mesh = MeshModel.read("open-box.obj",
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        Assert.assertFalse(mesh.geometryReport().isWatertight());
        MeshModel.RepairResult repaired = mesh.repair("open-box.obj");
        Assert.assertEquals(1, repaired.report.filledPlanarHoles);
        Assert.assertTrue(repaired.mesh.geometryReport().isWatertight());
        Assert.assertTrue(repaired.report.summary().contains("closed 1 planar hole"));
    }

    @Test
    public void meshRepairDoesNotInventSolidsForMultiPartOpenSurfaces() throws Exception {
        String obj = "g panel-a\n"
                + "v 0 0 0\n v 10 0 0\n v 0 10 0\n f 1 2 3\n"
                + "g panel-b\n"
                + "v 20 0 0\n v 30 0 0\n v 20 10 0\n f 4 5 6\n";
        MeshModel mesh = MeshModel.read("open-panels.obj",
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        MeshModel.RepairResult repaired = mesh.repair("open-panels.obj");
        Assert.assertEquals(2, repaired.mesh.parts.length);
        Assert.assertEquals(0, repaired.report.filledPlanarHoles);
        Assert.assertFalse(repaired.mesh.geometryReport().isWatertight());
    }

    @Test
    public void bambuTelemetryRequiresStructuredMatching() {
        String running = "{\"print\":{\"gcode_state\":\"RUNNING\",\"subtask_name\":\"box\"}}";
        Assert.assertTrue(BambuLanTransport.telemetryHasState(running, "running"));
        Assert.assertTrue(BambuLanTransport.telemetryMentionsJob(running, "box.gcode.3mf"));
        Assert.assertTrue(BambuLanTransport.telemetryMentionsJob(
                "{\"print\":{\"gcode_state\":\"PREPARE\",\"gcode_file\":\"/box.gcode.3mf\"}}",
                "box.gcode.3mf"));

        String unrelated = "{\"print\":{\"gcode_state\":\"RUNNING\",\"subtask_name\":\"other\",\"comment\":\"box.gcode.3mf\"}}";
        Assert.assertFalse(BambuLanTransport.telemetryMentionsJob(unrelated, "box.gcode.3mf"));
        Assert.assertFalse(BambuLanTransport.telemetryHasState("not-json", "running"));
        Assert.assertTrue(BambuLanTransport.telemetryBlocksNewStart(running));
        Assert.assertTrue(BambuLanTransport.telemetryBlocksNewStart(
                "{\"print\":{\"gcode_state\":\"PAUSED\"}}"));
        Assert.assertFalse(BambuLanTransport.telemetryBlocksNewStart(
                "{\"print\":{\"gcode_state\":\"IDLE\"}}"));
        Assert.assertFalse(BambuLanTransport.telemetryBlocksNewStart("not-json"));
    }

    @Test
    public void bambuTelemetrySummaryIsBoundedAndStructured() {
        String summary = BambuLanTransport.telemetrySummary(
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"mc_percent\":42,"
                        + "\"nozzle_temper\":220.5,\"bed_temper\":60,\"mc_remaining_time\":615}}");
        Assert.assertEquals("Telemetry · state RUNNING · 42% · nozzle 220.5°C · bed 60.0°C · 10m", summary);
        Assert.assertEquals("", BambuLanTransport.telemetrySummary("not-json"));
        Assert.assertFalse(BambuLanTransport.telemetrySummary(
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"mc_percent\":999999}}").contains("999999"));
    }

    @Test
    public void bambuPayloadUsesConservativeLocalPrintFields() throws Exception {
        String payload = BambuLanTransport.projectFilePayload("box.gcode.3mf",
                new PrinterTransport.PrintOptions(false, true, false, true));
        org.json.JSONObject print = new org.json.JSONObject(payload).getJSONObject("print");
        Assert.assertEquals("project_file", print.getString("command"));
        Assert.assertEquals("Metadata/plate_1.gcode", print.getString("param"));
        Assert.assertEquals("auto", print.getString("bed_type"));
        Assert.assertEquals("", print.getString("file"));
        Assert.assertEquals("", print.getString("md5"));
        Assert.assertTrue(print.getBoolean("bed_leveling"));
        Assert.assertTrue(print.getBoolean("bed_levelling"));
        Assert.assertFalse(print.getBoolean("use_ams"));
        Assert.assertFalse(print.has("ams_mapping"));
    }

    @Test
    public void bambuStopPayloadIsAConservativeCommand() throws Exception {
        org.json.JSONObject print = new org.json.JSONObject(BambuLanTransport.stopPayload()).getJSONObject("print");
        Assert.assertEquals("stop", print.getString("command"));
        Assert.assertEquals(1, print.length());
    }

    @Test
    public void bambuPauseAndResumePayloadsAreExplicitCommands() throws Exception {
        org.json.JSONObject pause = new org.json.JSONObject(BambuLanTransport.commandPayload("pause")).getJSONObject("print");
        org.json.JSONObject resume = new org.json.JSONObject(BambuLanTransport.commandPayload("resume")).getJSONObject("print");
        Assert.assertEquals("pause", pause.getString("command"));
        Assert.assertEquals("resume", resume.getString("command"));
        try {
            BambuLanTransport.commandPayload("stop");
            Assert.fail("control payload helper must not silently broaden its command set");
        } catch (IllegalArgumentException expected) {
            // Stop remains a separately reviewed cancellation command.
        }
    }

    @Test
    public void bambuTelemetryDoesNotAcceptNearMatchJobNames() {
        String target = "box.gcode.3mf";
        Assert.assertTrue(BambuLanTransport.telemetryMentionsJob(
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"url\":\"ftp:///box.gcode.3mf\"}}", target));
        Assert.assertFalse(BambuLanTransport.telemetryMentionsJob(
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"subtask_name\":\"box-2\"}}", target));
        Assert.assertFalse(BambuLanTransport.telemetryMentionsJob(
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"file\":\"/box.gcode.3mf.bak\"}}", target));
    }

    @Test
    public void bambuCancellationRequiresPriorTargetIdentity() {
        String target = "box.gcode.3mf";
        String idle = "{\"print\":{\"gcode_state\":\"IDLE\"}}";
        Assert.assertFalse(BambuLanTransport.telemetryConfirmsCancellation(idle, target, false, false));
        Assert.assertFalse(BambuLanTransport.telemetryConfirmsCancellation(idle, target, true, false));
        Assert.assertTrue(BambuLanTransport.telemetryConfirmsCancellation(idle, target, true, true));
        Assert.assertFalse(BambuLanTransport.telemetryConfirmsCancellation(
                "{\"print\":{\"gcode_state\":\"RUNNING\"}}", target, true, true));
        Assert.assertFalse(BambuLanTransport.telemetryConfirmsCancellation(idle, "../box.gcode.3mf", true, true));
    }

    @Test
    public void telemetryReducerReplaysPauseResumeAndCompletionTranscript() {
        String target = "box.gcode.3mf";
        boolean accepted = false;
        boolean running = false;
        boolean matching = false;
        PrinterTelemetryReducer.ControlRequest control = PrinterTelemetryReducer.ControlRequest.NONE;

        PrinterTelemetryReducer.Decision prepare = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"PREPARE\",\"subtask_name\":\"box\"}}",
                accepted, running, matching, false, control);
        Assert.assertEquals(PrinterTelemetryReducer.Event.IGNORE, prepare.event);
        Assert.assertTrue(prepare.accepted);
        accepted = prepare.accepted; running = prepare.running; matching = prepare.matchingJobSeen;

        PrinterTelemetryReducer.Decision started = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"url\":\"ftp:///box.gcode.3mf\"}}",
                accepted, running, matching, false, control);
        Assert.assertEquals(PrinterTelemetryReducer.Event.RUNNING, started.event);
        accepted = started.accepted; running = started.running; matching = started.matchingJobSeen;

        PrinterTelemetryReducer.Decision paused = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"PAUSED\",\"file\":\"/box.gcode.3mf\"}}",
                accepted, running, matching, false, PrinterTelemetryReducer.ControlRequest.PAUSE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.PAUSED, paused.event);
        Assert.assertEquals(PrinterTelemetryReducer.ControlRequest.NONE, paused.controlRequest);
        accepted = paused.accepted; running = paused.running; matching = paused.matchingJobSeen;

        PrinterTelemetryReducer.Decision resumed = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"PREPARE\",\"subtask_name\":\"box\"}}",
                accepted, running, matching, false, PrinterTelemetryReducer.ControlRequest.RESUME);
        Assert.assertEquals(PrinterTelemetryReducer.Event.RESUMED, resumed.event);
        Assert.assertTrue(resumed.running);
        accepted = resumed.accepted; running = resumed.running; matching = resumed.matchingJobSeen;

        PrinterTelemetryReducer.Decision finished = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"FINISH\",\"subtask_name\":\"box\"}}",
                accepted, running, matching, false, PrinterTelemetryReducer.ControlRequest.NONE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.COMPLETED, finished.event);
    }

    @Test
    public void telemetryReducerRequiresMatchingIdentityForCancelAndIgnoresNoise() {
        String target = "box.gcode.3mf";
        PrinterTelemetryReducer.Decision noise = PrinterTelemetryReducer.observe(target, "not-json",
                true, true, true, true, PrinterTelemetryReducer.ControlRequest.NONE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.IGNORE, noise.event);
        Assert.assertTrue(noise.matchingJobSeen);

        PrinterTelemetryReducer.Decision unrelated = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"IDLE\",\"subtask_name\":\"other\"}}",
                true, true, false, true, PrinterTelemetryReducer.ControlRequest.NONE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.IGNORE, unrelated.event);
        Assert.assertFalse(unrelated.matchingJobSeen);

        PrinterTelemetryReducer.Decision identified = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"RUNNING\",\"subtask_name\":\"box\"}}",
                true, true, false, true, PrinterTelemetryReducer.ControlRequest.NONE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.IGNORE, identified.event);
        Assert.assertTrue(identified.matchingJobSeen);

        PrinterTelemetryReducer.Decision cancelled = PrinterTelemetryReducer.observe(target,
                "{\"print\":{\"gcode_state\":\"IDLE\"}}",
                identified.accepted, identified.running, identified.matchingJobSeen,
                true, PrinterTelemetryReducer.ControlRequest.NONE);
        Assert.assertEquals(PrinterTelemetryReducer.Event.CANCELLED, cancelled.event);
    }

    @Test
    public void bambuFtpParsersValidatePassiveAddressAndRemoteSize() throws Exception {
        Assert.assertArrayEquals(new int[]{192, 168, 1, 42, 19, 136},
                BambuLanTransport.parsePassiveAddress("227 Entering Passive Mode (192,168,1,42,19,136)."));
        Assert.assertEquals(123456L, BambuLanTransport.parseRemoteSize("213 123456"));
        try {
            BambuLanTransport.parsePassiveAddress("227 Entering Passive Mode (192,168,1,999,19,136).");
            Assert.fail("out-of-range PASV octets must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("out-of-range"));
        }
        try {
            BambuLanTransport.parseRemoteSize("550 no size");
            Assert.fail("non-SIZE replies must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("remote file size"));
        }
    }

    @Test
    public void bambuMqttParserHandlesQosAndRejectsMalformedPackets() throws Exception {
        byte[] report = "{\"print\":{\"gcode_state\":\"RUNNING\"}}".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0);
        body.write(3);
        body.write("rpt".getBytes(StandardCharsets.UTF_8));
        body.write(0x12);
        body.write(0x34);
        body.write(report);
        ByteArrayOutputStream packetBytes = new ByteArrayOutputStream();
        packetBytes.write(0x32); // PUBLISH, QoS 1
        packetBytes.write(body.size());
        packetBytes.write(body.toByteArray());
        BambuLanTransport.MqttPacket packet = BambuLanTransport.readMqttPacket(
                new ByteArrayInputStream(packetBytes.toByteArray()));
        Assert.assertEquals(3, packet.type);
        Assert.assertEquals(2, packet.flags);
        Assert.assertEquals(new String(report, StandardCharsets.UTF_8), packet.publishPayload());

        try {
            BambuLanTransport.readMqttPacket(new ByteArrayInputStream(
                    new byte[]{0x30, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}));
            Assert.fail("five-byte MQTT remaining lengths must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("more than four"));
        }
        try {
            BambuLanTransport.readMqttPacket(new ByteArrayInputStream(new byte[]{0x11, 0}));
            Assert.fail("fixed MQTT control-packet flags must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("packet flags"));
        }
        try {
            new BambuLanTransport.MqttPacket(3, 0, new byte[]{0, 0}).publishPayload();
            Assert.fail("empty MQTT publish topics must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("publish topic"));
        }
    }

    @Test
    public void bambuCertificateFingerprintNormalizationIsStrict() throws Exception {
        String fingerprint = repeat('a', 64);
        Assert.assertEquals(fingerprint, BambuLanTransport.normalizeFingerprint(
                repeat('A', 32) + " " + repeat('A', 32)));
        try {
            BambuLanTransport.normalizeFingerprint("not-a-sha256-fingerprint");
            Assert.fail("non-SHA-256 fingerprints must be rejected");
        } catch (java.security.GeneralSecurityException expected) {
            Assert.assertTrue(expected.getMessage().contains("SHA-256"));
        }
    }

    @Test
    public void bambuDefaultTransportUsesBcjsseForFtpsSessionReuse() throws Exception {
        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                "A1 Mini", "192.0.2.42", "SERIAL-TLS", "access-code");
        BambuLanTransport transport = new BambuLanTransport(credentials);
        Field socketFactoryField = BambuLanTransport.class.getDeclaredField("socketFactory");
        socketFactoryField.setAccessible(true);
        SSLSocketFactory factory = (SSLSocketFactory) socketFactoryField.get(transport);
        SSLSocket socket = (SSLSocket) factory.createSocket();
        try {
            Assert.assertTrue("default probing must expose explicit BCJSSE session state",
                    socket instanceof BCSSLSocket);
        } finally {
            socket.close();
            transport.close();
        }
    }

    @Test
    public void persistedNativeSettingsRejectUnknownProjection() throws Exception {
        org.json.JSONObject approved = new org.json.JSONObject().put("fill_pattern", "grid");
        Assert.assertTrue(NativeSettings.parse(approved, true).containsKey("fill_pattern"));
        try {
            NativeSettings.parse(new org.json.JSONObject().put("arbitrary_native_key", "1"), true);
            Assert.fail("unknown native profile keys must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("not approved"));
        }
    }

    @Test
    public void sharedModelIntentCarriesStreamUri() {
        Uri model = Uri.parse("content://com.example.documents/community-bow.obj");
        android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND)
                .putExtra(android.content.Intent.EXTRA_STREAM, model);
        Assert.assertEquals(model, MainActivity.modelUriFromIntent(share));
    }

    @Test
    public void importedModelSourcesMaterializeForOfflineUse() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(context.getCacheDir(), "offline-model-" + System.nanoTime() + ".obj");
        byte[] contents = ("# community model\n"
                + "v 0 0 0\nv 10 0 0\nv 0 10 0\nv 0 0 10\n"
                + "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n").getBytes(StandardCharsets.UTF_8);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(source)) {
            output.write(contents);
        }
        ModelStore.Materialized materialized = null;
        try {
            materialized = ModelStore.materialize(context.getFilesDir(), context.getContentResolver(),
                    Uri.fromFile(source), "community-bow.obj");
            Assert.assertEquals("file", materialized.uri.getScheme());
            Assert.assertEquals("models", materialized.file.getParentFile().getName());
            Assert.assertTrue(materialized.file.getName().endsWith(".obj"));
            Assert.assertEquals(contents.length, materialized.sizeBytes);
            Assert.assertEquals(materialized.sha256, ModelStore.sha256(materialized.file));
            Assert.assertTrue(source.delete());
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel mesh = MeshModel.read("community-bow.obj", input);
                Assert.assertEquals(4, mesh.triangles.length / 3);
            }
        } finally {
            if (source.exists()) Assert.assertTrue(source.delete());
            if (materialized != null && materialized.file.exists()) Assert.assertTrue(materialized.file.delete());
        }
    }

    @Test
    public void bundledExampleMaterializesForPortableProjects() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelStore.Materialized materialized = ModelStore.materializeAsset(
                context.getFilesDir(), context.getAssets(), "models/box-and-lid.stl");
        try {
            Assert.assertEquals("file", materialized.uri.getScheme());
            Assert.assertEquals(".stl", materialized.extension);
            Assert.assertTrue(materialized.file.getName().endsWith(".stl"));
            Assert.assertEquals(materialized.sha256, ModelStore.sha256(materialized.file));
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel mesh = MeshModel.read("Box + lid assembly.stl", input);
                Assert.assertEquals(2, mesh.parts.length);
            }
        } finally {
            if (materialized.file.exists()) Assert.assertTrue(materialized.file.delete());
        }
    }

    @Test
    public void misleadingDocumentNameUsesDetectedModelParser() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File source = new File(context.getCacheDir(), "download-" + System.nanoTime());
        byte[] contents = ("v\t0 0 0\nv\t10 0 0\nv\t0 10 0\nv\t0 0 10\n"
                + "f\t1 3 2\nf\t1 2 4\nf\t1 4 3\nf\t2 3 4\n").getBytes(StandardCharsets.UTF_8);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(source)) {
            output.write(contents);
        }
        ModelStore.Materialized materialized = null;
        try {
            materialized = ModelStore.materialize(context.getFilesDir(), context.getContentResolver(),
                    Uri.fromFile(source), "community-bow.stl");
            Assert.assertEquals(".obj", materialized.extension);
            Assert.assertEquals("community-bow.obj", ModelStore.parserName("community-bow.stl", materialized.extension));
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel mesh = MeshModel.read(ModelStore.parserName("community-bow.stl", materialized.extension), input);
                Assert.assertEquals(4, mesh.triangles.length / 3);
            }
        } finally {
            if (source.exists()) Assert.assertTrue(source.delete());
            if (materialized != null && materialized.file.exists()) Assert.assertTrue(materialized.file.delete());
        }
    }

    @Test
    public void largeObjWithFacesAfterInitialProbeStillUsesObjParser() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        StringBuilder source = new StringBuilder(180_000);
        source.append("# exporter writes vertices before faces\n");
        for (int index = 0; index < 12_000; index++)
            source.append("v ").append(index).append(" 0 0\n");
        // The first face is deliberately well beyond ModelStore's historical
        // 64 KB signature probe. This mirrors large downloaded OBJ exports.
        source.append("f 1 2 3\n");
        source.append("f 1 3 4\n");
        source.append("f 1 4 5\n");
        source.append("f 1 5 6\n");
        ModelStore.Materialized materialized = ModelStore.materializeGenerated(
                context.getFilesDir(), source.toString().getBytes(StandardCharsets.UTF_8));
        try {
            Assert.assertEquals(".obj", materialized.extension);
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel mesh = MeshModel.read("delayed.obj", input);
                Assert.assertEquals(4, mesh.triangles.length / 3);
            }
        } finally {
            if (materialized.file.exists()) Assert.assertTrue(materialized.file.delete());
        }
    }

    @Test
    public void legacyCachedModelRechecksItsContentFormat() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getFilesDir(), "models");
        Assert.assertTrue(root.exists() || root.mkdirs());
        byte[] contents = ("v 0 0 0\nv 10 0 0\nv 0 10 0\nv 0 0 10\n"
                + "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n").getBytes(StandardCharsets.UTF_8);
        File temp = new File(context.getCacheDir(), "legacy-model-" + System.nanoTime());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(temp)) {
            output.write(contents);
        }
        String digest = ModelStore.sha256(temp);
        File legacy = new File(root, digest + ".stl");
        try (InputStream input = new java.io.FileInputStream(temp);
             java.io.FileOutputStream output = new java.io.FileOutputStream(legacy)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        Assert.assertTrue(temp.delete());
        try {
            ModelStore.Materialized materialized = ModelStore.materialize(context.getFilesDir(), context.getContentResolver(),
                    Uri.fromFile(legacy), "legacy-model.stl");
            Assert.assertEquals(".obj", materialized.extension);
            try (InputStream input = context.getContentResolver().openInputStream(materialized.uri)) {
                Assert.assertNotNull(input);
                MeshModel mesh = MeshModel.read(ModelStore.parserName("legacy-model.stl", materialized.extension), input);
                Assert.assertEquals(4, mesh.triangles.length / 3);
            }
        } finally {
            if (legacy.exists()) Assert.assertTrue(legacy.delete());
        }
    }

    @Test
    public void unreadablePrinterCheckpointStillBlocksSending() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-corrupt-printer-job", Context.MODE_PRIVATE);
        preferences.edit().clear().putString("state", "UPLOADING").commit();
        PrinterJobStore.Job job = new PrinterJobStore(preferences).recoverAfterRestart();
        Assert.assertNotNull(job);
        Assert.assertEquals(PrinterTransport.State.RECOVERY_REQUIRED, job.state);
        Assert.assertTrue(job.isUnreadable());
        preferences.edit().clear().commit();
    }

    @Test
    public void stalePrinterCallbackCannotOverwriteNewArtifactCheckpoint() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-job-ownership", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.11", "SERIAL-2");
        PrinterTransport.Artifact first = new PrinterTransport.Artifact("first.gcode.3mf", 10L,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        PrinterTransport.Artifact second = new PrinterTransport.Artifact("second.gcode.3mf", 11L,
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc");
        String firstJobId = store.begin(target, second);
        Assert.assertTrue(store.updateIfMatches(firstJobId, target, second, PrinterTransport.State.UPLOADING,
                "first transaction", "/second.gcode.3mf"));
        String jobId = store.begin(target, second);
        Assert.assertNotEquals(firstJobId, jobId);
        Assert.assertFalse(store.updateIfMatches(firstJobId, target, second, PrinterTransport.State.RUNNING,
                "late callback from prior transaction", "/second.gcode.3mf"));
        Assert.assertFalse(store.updateIfMatches(jobId, target, first, PrinterTransport.State.RUNNING,
                "late callback", "/first.gcode.3mf"));
        Assert.assertEquals(PrinterTransport.State.CONNECTING, store.load().state);
        Assert.assertTrue(store.updateIfMatches(jobId, target, second, PrinterTransport.State.UPLOADING,
                "current callback", "/second.gcode.3mf"));
        Assert.assertEquals(PrinterTransport.State.UPLOADING, store.load().state);
        Assert.assertFalse(store.updateIfMatches(jobId, target, second, PrinterTransport.State.COMPLETED,
                "late terminal callback", "/second.gcode.3mf"));
        Assert.assertEquals(PrinterTransport.State.UPLOADING, store.load().state);
        store.clear();
    }

    @Test
    public void printerCheckpointPersistsMaterialEstimateForCompletionAccounting() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-material", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.12", "SERIAL-3");
        PrinterTransport.Artifact artifact = new PrinterTransport.Artifact("material.gcode.3mf", 12L,
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        String jobId = store.begin(target, artifact, "PLA", 600_000f, 1.75f);
        PrinterJobStore.Job reloaded = new PrinterJobStore(preferences).load();
        Assert.assertNotNull(reloaded);
        Assert.assertEquals(jobId, reloaded.jobId);
        Assert.assertEquals("PLA", reloaded.filament);
        Assert.assertEquals(600_000f, reloaded.filamentMm, 0.01f);
        Assert.assertEquals(1.75f, reloaded.filamentDiameterMm, 0.001f);
        store.clear();
    }

    @Test
    public void printerCheckpointAllowsOnlyConfirmedPauseResumeTransitions() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-pause", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.14", "SERIAL-5");
        PrinterTransport.Artifact artifact = new PrinterTransport.Artifact("pause.gcode.3mf", 13L,
                "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        store.begin(target, artifact);
        store.update(PrinterTransport.State.UPLOADING, "Uploading artifact", "");
        store.update(PrinterTransport.State.UPLOADED, "Artifact uploaded", "/pause.gcode.3mf");
        store.update(PrinterTransport.State.START_REQUESTED, "Start requested", "/pause.gcode.3mf");
        store.update(PrinterTransport.State.RUNNING, "Printer confirmed running", "/pause.gcode.3mf");
        store.update(PrinterTransport.State.PAUSE_REQUESTED, "Pause requested", "/pause.gcode.3mf");
        store.update(PrinterTransport.State.PAUSED, "Printer confirmed paused", "/pause.gcode.3mf");
        Assert.assertEquals(PrinterTransport.State.PAUSED, store.load().state);
        store.update(PrinterTransport.State.RESUME_REQUESTED, "Resume requested", "/pause.gcode.3mf");
        store.update(PrinterTransport.State.RUNNING, "Printer confirmed resumed", "/pause.gcode.3mf");
        Assert.assertEquals(PrinterTransport.State.RUNNING, store.load().state);
        store.update(PrinterTransport.State.CANCEL_REQUESTED, "Stop requested", "/pause.gcode.3mf");
        Assert.assertEquals(PrinterTransport.State.CANCEL_REQUESTED, store.load().state);
        store.update(PrinterTransport.State.CANCELLED, "Printer confirmed cancelled", "/pause.gcode.3mf");
        Assert.assertEquals(PrinterTransport.State.CANCELLED, store.load().state);
        store.clear();
    }

    @Test
    public void durableArtifactCanBeRecoveredAfterCheckpointReload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Config config = new Slicer.Config();
        Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
        String name = "durable-recovery-" + System.nanoTime();
        PrinterTransport.Artifact staged = ArtifactStore.stage(context.getFilesDir(), mesh, result, config, name);
        try {
            PrinterTransport.Artifact recovered = ArtifactStore.recover(context.getFilesDir(), staged.displayName,
                    staged.sizeBytes, staged.sha256);
            Assert.assertEquals(staged.displayName, recovered.displayName);
            Assert.assertEquals(staged.sizeBytes, recovered.sizeBytes);
            Assert.assertEquals(staged.sha256, recovered.sha256);
            Assert.assertEquals(staged.sourceFile.getAbsolutePath(), recovered.sourceFile.getAbsolutePath());
        } finally {
            if (staged.sourceFile.exists()) Assert.assertTrue(staged.sourceFile.delete());
        }
    }

    @Test
    public void projectAndInventoryCheckpointsSurviveReload() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        SharedPreferences projectPreferences = context.getSharedPreferences("alloy-test-project", Context.MODE_PRIVATE);
        projectPreferences.edit().clear().commit();
        ProjectStore projectStore = new ProjectStore(projectPreferences);
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/box.stl"));
        uris.add(Uri.parse("content://com.example.documents/lid.stl"));
        ArrayList<String> names = new ArrayList<>();
        names.add("box.stl");
        names.add("lid.stl");
        projectStore.saveModels(uris, names);
        projectStore.saveTransform(1.1f, 10f, 8f, -4f);
        ProjectStore.SavedProject saved = new ProjectStore(projectPreferences).savedProject();
        Assert.assertNotNull(saved);
        Assert.assertEquals(2, saved.uris.size());
        Assert.assertEquals("lid.stl", saved.names.get(1));
        Assert.assertEquals(8f, new ProjectStore(projectPreferences).savedTiltX(), 0.0001f);
        Assert.assertEquals(-4f, new ProjectStore(projectPreferences).savedTiltY(), 0.0001f);
        Slicer.Config savedRecipe = new Slicer.Config();
        savedRecipe.nativeSettings.put("fill_pattern", "grid");
        savedRecipe.nativeSettings.put("support_material_style", "organic");
        projectStore.saveRecipe(savedRecipe);
        Slicer.Config restoredRecipe = new Slicer.Config();
        restoredRecipe.nativeSettings.put("stale_setting", "must be replaced");
        new ProjectStore(projectPreferences).restoreRecipe(restoredRecipe);
        Assert.assertEquals(savedRecipe.nativeSettings, restoredRecipe.nativeSettings);

        SharedPreferences inventoryPreferences = context.getSharedPreferences("alloy-test-inventory", Context.MODE_PRIVATE);
        inventoryPreferences.edit().clear().commit();
        InventoryStore inventory = new InventoryStore(inventoryPreferences);
        InventoryStore.Item plate = null;
        for (InventoryStore.Item item : inventory.items()) if ("pei-plate".equals(item.id)) plate = item;
        Assert.assertNotNull(plate);
        Assert.assertTrue(plate.serviceDue);
        inventory.markServiced(plate);
        InventoryStore.Item reloaded = null;
        for (InventoryStore.Item item : new InventoryStore(inventoryPreferences).items()) if ("pei-plate".equals(item.id)) reloaded = item;
        Assert.assertNotNull(reloaded);
        Assert.assertFalse(reloaded.serviceDue);
        Assert.assertTrue(reloaded.lastServicedAt > 0L);
        Assert.assertTrue(reloaded.nextServiceAt > reloaded.lastServicedAt);
    }

    @Test
    public void customInventoryItemsPersistWithReorderAndService() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-custom-inventory", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        InventoryStore store = new InventoryStore(preferences);
        InventoryStore.Item item = store.addCustom("Bow string jig", "Tool", "each", 0, 1, 30,
                "Keep dry; inspect alignment before use");
        Assert.assertTrue(item.isCustom());

        InventoryStore.Item reloaded = findInventoryItem(new InventoryStore(preferences), item.id);
        Assert.assertNotNull(reloaded);
        Assert.assertTrue(reloaded.needsReorder());
        Assert.assertFalse(reloaded.serviceDue);
        store.addOne(reloaded);
        InventoryStore.Item stocked = findInventoryItem(new InventoryStore(preferences), item.id);
        Assert.assertNotNull(stocked);
        Assert.assertEquals(1, stocked.quantity);
        store.markServiced(stocked);
        InventoryStore.Item serviced = findInventoryItem(new InventoryStore(preferences), item.id);
        Assert.assertNotNull(serviced);
        Assert.assertFalse(serviced.serviceDue);
        Assert.assertTrue(serviced.nextServiceAt > serviced.lastServicedAt);
        Assert.assertTrue(store.delete(serviced));
        Assert.assertNull(findInventoryItem(new InventoryStore(preferences), item.id));
    }

    @Test
    public void completedPrintConsumesFilamentOnceAndTriggersReorder() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-inventory-usage", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        InventoryStore store = new InventoryStore(preferences);
        InventoryStore.Item before = findInventoryItem(store, "pla-basic");
        Assert.assertNotNull(before);
        Assert.assertEquals("g", before.unit);
        Assert.assertEquals(2_000, before.quantity);

        InventoryStore.Usage usage = store.recordCompletedPrint("completed-job-1", "PLA", 600_000f, 1.75f);
        Assert.assertTrue(usage.recorded);
        Assert.assertFalse(usage.alreadyRecorded);
        Assert.assertTrue(usage.grams > 0);
        Assert.assertTrue(usage.remainingGrams < before.minimum);

        InventoryStore.Item after = findInventoryItem(new InventoryStore(preferences), "pla-basic");
        Assert.assertNotNull(after);
        Assert.assertEquals(usage.remainingGrams, after.quantity);
        Assert.assertTrue(after.needsReorder());
        Assert.assertTrue(after.filamentUsageMm >= 600_000L);

        InventoryStore.Usage duplicate = store.recordCompletedPrint("completed-job-1", "PLA", 600_000f, 1.75f);
        Assert.assertTrue(duplicate.alreadyRecorded);
        Assert.assertFalse(duplicate.recorded);
        Assert.assertEquals(after.quantity, findInventoryItem(store, "pla-basic").quantity);
        preferences.edit().clear().commit();
    }

    @Test
    public void inventoryFlagsServiceSoonAndOverdue() {
        long now = System.currentTimeMillis();
        InventoryStore.Item soon = new InventoryStore.Item("soon", "Nozzle", "Consumable", "each", 1, 0,
                false, "Inspect", now, now + 7L * 24L * 60L * 60L * 1000L, 30, false);
        Assert.assertTrue(soon.serviceSoon());
        Assert.assertTrue(soon.needsServiceAttention());
        Assert.assertEquals("SERVICE SOON", soon.statusLabel());

        InventoryStore.Item overdue = new InventoryStore.Item("overdue", "Plate", "Build surface", "each", 1, 0,
                false, "Clean", now - 60L * 24L * 60L * 60L * 1000L, now - 1L, 30, false);
        Assert.assertTrue(overdue.serviceOverdue());
        Assert.assertEquals("SERVICE OVERDUE", overdue.statusLabel());
    }

    @Test
    public void projectHistoryIsBoundedAndReloadable() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-project-history", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        ProjectHistoryStore store = new ProjectHistoryStore(preferences);
        Uri archive = Uri.parse("content://com.example.documents/box.alloy.zip");
        store.record("Box project", archive, 2, 3);
        store.record("Box project renamed", archive, 2, 3);
        ArrayList<ProjectHistoryStore.Record> reloaded = new ProjectHistoryStore(preferences).records();
        Assert.assertEquals(1, reloaded.size());
        Assert.assertEquals("Box project renamed", reloaded.get(0).name);
        Assert.assertEquals(2, reloaded.get(0).modelCount);
        Assert.assertEquals(3, reloaded.get(0).plateCount);
        store.clear();
        Assert.assertTrue(new ProjectHistoryStore(preferences).records().isEmpty());
    }

    @Test
    public void printerJobCheckpointFailsClosedAfterReload() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-job", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.10", "SERIAL-1");
        PrinterTransport.Artifact artifact = new PrinterTransport.Artifact("box.gcode.3mf", 42L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        store.begin(target, artifact);
        store.update(PrinterTransport.State.UPLOADING, "Uploading artifact", "");
        store.update(PrinterTransport.State.UPLOADED, "Artifact uploaded", "/box.gcode.3mf");
        store.update(PrinterTransport.State.START_REQUESTED, "Start requested", "/box.gcode.3mf");
        store.update(PrinterTransport.State.RUNNING, "Printer confirmed running", "/box.gcode.3mf");

        PrinterJobStore.Job recovered = new PrinterJobStore(preferences).recoverAfterRestart();
        Assert.assertNotNull(recovered);
        Assert.assertEquals(PrinterTransport.State.RECOVERY_REQUIRED, recovered.state);
        Assert.assertTrue(recovered.requiresRecovery());
        Assert.assertEquals("/box.gcode.3mf", recovered.remotePath);
        Assert.assertEquals(64, recovered.artifactSha256.length());

        store.clear();
        Assert.assertNull(new PrinterJobStore(preferences).load());
    }

    @Test
    public void printerWorkerLeaseSurvivesActivityRecreationButNotProcessRestart() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-worker", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.13", "SERIAL-4");
        PrinterTransport.Artifact artifact = new PrinterTransport.Artifact("worker.gcode.3mf", 42L,
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        String jobId = store.begin(target, artifact);
        String lease = store.claim(jobId);
        Assert.assertNotNull(lease);
        Assert.assertEquals(PrinterTransport.State.CONNECTING, store.recoverAfterRestart(true).state);
        Assert.assertTrue(store.heartbeat(jobId, lease));
        store.release(jobId, lease);
        Assert.assertEquals(PrinterTransport.State.RECOVERY_REQUIRED, store.recoverAfterRestart().state);
        store.clear();
    }

    @Test
    public void expiredPrinterWorkerCannotApplyLateNetworkCallback() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-printer-worker-callback", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PrinterJobStore store = new PrinterJobStore(preferences);
        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget("A1 Mini", "192.0.2.15", "SERIAL-6");
        PrinterTransport.Artifact artifact = new PrinterTransport.Artifact("lease.gcode.3mf", 42L,
                "1212121212121212121212121212121212121212121212121212121212121212");
        String jobId = store.begin(target, artifact);
        String oldWorker = store.claim(jobId);
        Assert.assertNotNull(oldWorker);
        Assert.assertTrue(store.updateIfMatches(jobId, oldWorker, target, artifact,
                PrinterTransport.State.UPLOADING, "old worker", ""));

        preferences.edit().putLong("worker_heartbeat", System.currentTimeMillis() - 91_000L).commit();
        String newWorker = store.claim(jobId);
        Assert.assertNotNull(newWorker);
        Assert.assertNotEquals(oldWorker, newWorker);
        Assert.assertFalse("an expired service callback must be ignored",
                store.updateIfMatches(jobId, oldWorker, target, artifact,
                        PrinterTransport.State.FAILED, "late old callback", ""));
        Assert.assertEquals(PrinterTransport.State.UPLOADING, store.load().state);
        Assert.assertTrue(store.updateIfMatches(jobId, newWorker, target, artifact,
                PrinterTransport.State.FAILED, "current worker", ""));
        Assert.assertEquals(PrinterTransport.State.FAILED, store.load().state);
        store.release(jobId, newWorker);
        store.clear();
    }

    @Test
    public void partTransformChangesOnlySelectedPartAndSurvivesReload() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-and-lid.stl")) {
            mesh = MeshModel.read("box-and-lid.stl", input);
        }
        MeshModel.PartBounds originalBox = mesh.partBounds(0);
        MeshModel.PartBounds originalLid = mesh.partBounds(1);
        MeshModel moved = mesh.transformedPart("box-and-lid.stl", 0, 0.5f, 0f, 20f, -10f, 4f, -2f).leveledOnBed("box-and-lid.stl");
        Assert.assertEquals(2, moved.parts.length);
        Assert.assertTrue(moved.partBounds(0).width() < originalBox.width());
        Assert.assertEquals(originalLid.width(), moved.partBounds(1).width(), 0.0001f);
        Assert.assertTrue(moved.minZ >= -0.0001f);

        SharedPreferences preferences = context.getSharedPreferences("alloy-test-part-transforms", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        ProjectStore store = new ProjectStore(preferences);
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        transforms.add(new MeshModel.PartTransform(0.5f, 15f, 20f, -10f, 4f, -2f));
        store.savePartTransforms(transforms);
        ArrayList<MeshModel.PartTransform> reloaded = new ProjectStore(preferences).savedPartTransforms(2);
        Assert.assertEquals(1, reloaded.size());
        Assert.assertEquals(0.5f, reloaded.get(0).scale, 0.0001f);
        Assert.assertEquals(15f, reloaded.get(0).rotationDegrees, 0.0001f);
        Assert.assertEquals(20f, reloaded.get(0).tiltXDegrees, 0.0001f);
        Assert.assertEquals(-10f, reloaded.get(0).tiltYDegrees, 0.0001f);
        Assert.assertEquals(4f, reloaded.get(0).offsetX, 0.0001f);
    }

    @Test
    public void orientedModelIsReleveledBeforeSlicing() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-and-lid.stl")) {
            mesh = MeshModel.read("box-and-lid.stl", input);
        }
        MeshModel oriented = mesh.transformed("box-and-lid.stl", 1f, 27f, 24f, -18f);
        Assert.assertTrue(oriented.minZ >= -0.0001f);
        Assert.assertTrue(oriented.maxZ > oriented.minZ);
        Slicer.Config config = new Slicer.Config();
        Slicer.Result result = new LegacyOfflineEngine().slice(oriented, config, null);
        Assert.assertFalse(result.layers.isEmpty());
        Assert.assertTrue(result.gcode.contains("G90"));
    }

    @Test
    public void offlineFallbackGeneratesConservativeSupportsForDownwardFace() throws Exception {
        String stl = "solid overhang\n"
                + facet("0 0 8", "10 10 8", "10 0 8")
                + facet("0 0 8", "0 10 8", "10 10 8")
                + facet("0 0 0", "10 0 0", "10 0 8")
                + facet("0 0 0", "10 0 8", "0 0 8")
                + facet("10 0 0", "10 10 0", "10 10 8")
                + facet("10 0 0", "10 10 8", "10 0 8")
                + "endsolid overhang\n";
        MeshModel mesh = MeshModel.read("overhang.stl",
                new ByteArrayInputStream(stl.getBytes(StandardCharsets.UTF_8)));
        Slicer.Config config = new Slicer.Config();
        config.bedX = 180f; config.bedY = 180f; config.bedZ = 180f;
        config.infill = 0f; config.supports = true; config.supportThresholdDegrees = 30f;
        Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
        boolean supportLayer = false;
        for (Slicer.Layer layer : result.layers) if (!layer.supportSegments.isEmpty()) supportLayer = true;
        Assert.assertTrue(supportLayer);
        Assert.assertTrue(result.gcode.contains(";TYPE:Support material"));
        Assert.assertFalse(result.gcode.contains("support generation is unavailable"));
    }

    @Test
    public void preparationPreflightBlocksModelOutsideBuildVolume() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Config config = new Slicer.Config();
        config.bedX = 10f;
        PreparationValidator.Report report = PreparationValidator.validate(mesh, config, null);
        Assert.assertFalse(report.isReady());
        Assert.assertTrue(report.message().contains("build volume"));
    }

    @Test
    public void preparationPreflightBlocksLayerHeightsBelowDocumentedMinimum() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Config config = new Slicer.Config();
        config.layerHeight = 0.009f;
        PreparationValidator.Report report = PreparationValidator.validate(mesh, config, null);
        Assert.assertFalse(report.isReady());
        Assert.assertTrue(report.message().contains("0.01"));
    }

    @Test
    public void partBoundsBroadPhaseReportsOnlyPositiveOverlap() {
        MeshModel.PartBounds left = new MeshModel.PartBounds(0f, 10f, 0f, 10f, 0f, 10f, 12);
        MeshModel.PartBounds touching = new MeshModel.PartBounds(10f, 20f, 0f, 10f, 0f, 10f, 12);
        MeshModel.PartBounds intersecting = new MeshModel.PartBounds(9.9f, 20f, 1f, 9f, 1f, 9f, 12);
        Assert.assertFalse("touching faces are not an intersection", left.intersects(touching, 0.05f));
        Assert.assertTrue("overlapping broad-phase bounds require review", left.intersects(intersecting, 0.05f));
    }

    @Test
    public void gcodeSafetyIgnoresCommentTokensAndRequiresPrintableCommands() {
        GcodeSafetyValidator.Report commentsOnly = GcodeSafetyValidator.inspect(
                "; G90\n; M83\n; M104 S0\n; G1 X1 Y1 E1\n");
        Assert.assertFalse(commentsOnly.isValid());
        Assert.assertTrue(commentsOnly.summary().contains("G90"));

        GcodeSafetyValidator.Report valid = GcodeSafetyValidator.inspect(
                "N1 G90*11\nM83\nG1 X1 Y1 E0.25\nM104 S0\n");
        Assert.assertTrue(valid.summary(), valid.isValid());
        Assert.assertTrue(valid.hasAbsolutePositioning);
        Assert.assertTrue(valid.hasExtrusionMode);
        Assert.assertTrue(valid.hasStopTemperature);
        Assert.assertTrue(valid.hasPrintableMotion);
    }

    @Test
    public void a1MiniTemplatePolicyRequiresTheReviewedIdentityAndRejectsTemplates() throws Exception {
        String valid = "; Alloy native start · Bambu Lab A1 Mini\n"
                + "; Alloy template policy: " + A1MiniTemplatePolicy.ID + "\n"
                + "M204 S6000\nM140 S60\nM104 S220\nM190 S60\nM109 S220\nG28\nG90\nM83\nG92 E0\nM107\n"
                + "G1 X10 Y10 E0.4\n; Alloy native end\nG91\nG1 Z2 F600\nG90\nG1 X0 Y0 F9000\nM104 S0\nM140 S0\nM107\nM84\n";
        A1MiniTemplatePolicy.Report accepted = A1MiniTemplatePolicy.inspect(valid);
        Assert.assertTrue(accepted.summary(), accepted.isValid());
        Assert.assertFalse(accepted.hasUnresolvedTokens);

        A1MiniTemplatePolicy.Report unresolved = A1MiniTemplatePolicy.inspect(
                valid.replace("M104 S220", "M104 S[nozzle_temperature_initial_layer]"));
        Assert.assertFalse("unresolved profile placeholders must never reach a printer", unresolved.isValid());
        Assert.assertTrue(unresolved.hasUnresolvedTokens);

        A1MiniTemplatePolicy.Report unsupportedMacro = A1MiniTemplatePolicy.inspect(
                valid.replace("M140 S60\n", "M140 S60\nM620 S1\n"));
        Assert.assertFalse("Bambu firmware macros must not enter the neutral template", unsupportedMacro.isValid());
        Assert.assertTrue(unsupportedMacro.summary().contains("allowlist"));
        A1MiniTemplatePolicy.Report unsafeAcceleration = A1MiniTemplatePolicy.inspect(
                valid.replace("M204 S6000\n", "M204 P6000\n"));
        Assert.assertFalse("only the reviewed numeric default acceleration form is allowed",
                unsafeAcceleration.isValid());
        Assert.assertTrue(unsafeAcceleration.summary().contains("allowlist"));

        A1MiniTemplatePolicy.Report missingIdentity = A1MiniTemplatePolicy.inspect(
                valid.replace("; Alloy template policy: " + A1MiniTemplatePolicy.ID + "\n", ""));
        Assert.assertFalse(missingIdentity.isValid());
        Assert.assertTrue(missingIdentity.summary().contains("policy identity"));

        A1MiniTemplatePolicy.Report reordered = A1MiniTemplatePolicy.inspect(
                valid.replace("G28\n", "").replace("; Alloy native end\n", "; Alloy native end\nG28\n"));
        Assert.assertFalse("startup commands must remain inside the reviewed startup section", reordered.isValid());
        Assert.assertTrue(reordered.summary().contains("G28"));

        String escapedHeaderOnly = valid.replace("; Alloy native end\n", "")
                + "; end_gcode = ; Alloy native end\\nG91\\nM104 S0\\nM140 S0\\nM107\\nM84\n";
        A1MiniTemplatePolicy.Report fakeMarker = A1MiniTemplatePolicy.inspect(escapedHeaderOnly);
        Assert.assertFalse("escaped config-header text must not create a shutdown section", fakeMarker.hasEndMarker);
        Assert.assertFalse(fakeMarker.isValid());
    }

    @Test
    public void gcodeSafetyStrictlyBoundsRawMovesAndRequiresSafeShutdown() {
        Slicer.Config config = new Slicer.Config();
        String validGcode = "G28\nG90\nM83\nG92 E0\nG1 X10 Y10 E0.4\nM104 S0\nM140 S0\n";
        GcodeSafetyValidator.Report valid = GcodeSafetyValidator.inspect(validGcode, config);
        Assert.assertTrue(valid.summary(), valid.isValid());
        Assert.assertTrue(valid.hasHoming);
        Assert.assertTrue(valid.hasStopBed);

        GcodeSafetyValidator.Report outside = GcodeSafetyValidator.inspect(
                validGcode.replace("X10 Y10", "X180.6 Y10"), config);
        Assert.assertFalse("raw travel beyond the configured bed must be rejected", outside.isValid());
        Assert.assertTrue(outside.summary().contains("build plate"));

        GcodeSafetyValidator.Report missingShutdown = GcodeSafetyValidator.inspect(
                validGcode.replace("M140 S0\n", ""), config);
        Assert.assertFalse("bed shutdown is required for a staged artifact", missingShutdown.isValid());
        Assert.assertTrue(missingShutdown.summary().contains("M140 S0"));
    }

    @Test
    public void gcodeSafetyStreamHandlesChunkBoundaries() throws Exception {
        GcodeSafetyValidator.Stream stream = new GcodeSafetyValidator.Stream();
        byte[] gcode = "G90\nM83\nG1 X2 Y3 E0.4\nM104 S0\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < gcode.length; i++) stream.accept(gcode, i, 1);
        GcodeSafetyValidator.Report report = stream.finish();
        Assert.assertTrue(report.summary(), report.isValid());
    }

    @Test
    public void strictPackageValidationRechecksGcodeAtSendBoundary() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File packageFile = new File(context.getCacheDir(), "strict-package-boundary-" + System.nanoTime() + ".gcode.3mf");
        Slicer.Config config = new Slicer.Config();
        MeshModel mesh = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "package test", 20f, 20f, 10f);
        ArrayList<Slicer.Layer> layers = new ArrayList<>();
        Slicer.Layer layer = new Slicer.Layer(0, config.layerHeight);
        layer.segments.add(new Slicer.Segment(new Slicer.Point(10f, 10f), new Slicer.Point(11f, 10f)));
        layers.add(layer);
        Slicer.Result result = new Slicer.Result(
                "G90\nM83\nG1 X10 Y10 E0.4\nM104 S0\n",
                layers, 0.4f, 0, "test-engine", false, 1f, 0f);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(packageFile)) {
            GcodePackageWriter.write(mesh, result, config, output);
        }
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(packageFile)) {
            String metadata = readZipText(zip, "Metadata/plate_1.json");
            String sliceInfo = readZipText(zip, "Metadata/slice_info.config");
            Assert.assertTrue(metadata.contains("\"support_engine\":\"disabled\""));
            Assert.assertTrue(metadata.contains("\"support_parity_verified\":true"));
            Assert.assertTrue(sliceInfo.contains("key=\"support_engine\" value=\"disabled\""));
            Assert.assertTrue(sliceInfo.contains("key=\"support_parity_verified\" value=\"true\""));
        }
        try {
            GcodePackageValidator.validate(packageFile);
            try {
                GcodePackageValidator.validate(packageFile, config);
                Assert.fail("strict package validation must reject a package without homing and bed shutdown");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("G-code safety"));
            }
        } finally {
            Assert.assertTrue(packageFile.delete() || !packageFile.exists());
        }
    }

    @Test
    public void physicalArtifactRecoveryRejectsUnverifiedEngineOutput() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Config config = new Slicer.Config();
        Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
        PrinterTransport.Artifact staged = ArtifactStore.stage(context.getFilesDir(), mesh, result, config,
                "physical-gate-" + System.nanoTime());
        try {
            try {
                ArtifactStore.recoverForPhysicalPrint(context.getFilesDir(), staged.displayName,
                        staged.sizeBytes, staged.sha256);
                Assert.fail("physical recovery must reject unverified engine output");
            } catch (IOException expected) {
                Assert.assertTrue(expected.getMessage(), expected.getMessage().contains("verified slicer engine"));
            }
        } finally {
            if (staged.sourceFile.exists()) Assert.assertTrue(staged.sourceFile.delete());
        }
    }

    private static String readZipText(java.util.zip.ZipFile zip, String name) throws IOException {
        java.util.zip.ZipEntry entry = zip.getEntry(name);
        Assert.assertNotNull("package entry should exist: " + name, entry);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }

    @Test
    public void nativePreviewPreservesPerimeterSupportAndNeutralToolpaths() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File gcode = new File(context.getCacheDir(), "native-preview-roles-" + System.nanoTime() + ".gcode");
        String source = "G90\nM83\n;LAYER:0\n;TYPE:Outer wall\n"
                + "G0 X10 Y10\nG1 X20 Y10 E0.4\n"
                + ";TYPE:Support material\nG0 X20 Y20\nG1 X30 Y20 E0.3\n"
                + ";TYPE:Sparse infill\nG0 X30 Y30\nG1 X40 Y30 E0.2\n"
                + "M104 S0\n";
        java.nio.file.Files.write(gcode.toPath(), source.getBytes(StandardCharsets.UTF_8));
        try {
            Slicer.Result result = NativeSlicerEngine.parseGcode(gcode, new Slicer.Config());
            Assert.assertEquals(3, result.layers.get(0).segments.size());
            Assert.assertEquals(1, result.layers.get(0).perimeterSegments.size());
            Assert.assertEquals(1, result.layers.get(0).supportSegments.size());
            Assert.assertEquals(1, result.layers.get(0).segments.size()
                    - result.layers.get(0).perimeterSegments.size()
                    - result.layers.get(0).supportSegments.size());
        } finally {
            Assert.assertTrue(!gcode.exists() || gcode.delete());
        }
    }

    @Test
    public void nativePreviewRejectsAnOversizedOutputLine() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File gcode = new File(context.getCacheDir(), "native-preview-oversized-line-" + System.nanoTime() + ".gcode");
        StringBuilder source = new StringBuilder("G90\nM83\nG1 X1 Y1 E0.2\n");
        for (int index = 0; index <= 1 * 1024 * 1024; index++) source.append('x');
        source.append('\n').append("M104 S0\n");
        java.nio.file.Files.write(gcode.toPath(), source.toString().getBytes(StandardCharsets.UTF_8));
        try {
            try {
                NativeSlicerEngine.parseGcode(gcode, new Slicer.Config());
                Assert.fail("oversized native output line should be rejected");
            } catch (java.io.IOException expected) {
                Assert.assertTrue(expected.getMessage().contains("line"));
            }
        } finally {
            Assert.assertTrue(!gcode.exists() || gcode.delete());
        }
    }

    @Test
    public void plateSnapshotsSurviveReload() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences = context.getSharedPreferences("alloy-test-plates", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        PlateStore store = new PlateStore(preferences);
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/box.stl"));
        uris.add(Uri.parse("content://com.example.documents/lid.stl"));
        ArrayList<String> names = new ArrayList<>();
        names.add("box.stl");
        names.add("lid.stl");
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        transforms.add(new MeshModel.PartTransform(0.75f, 12f, 3f, -1f));
        store.save(new PlateStore.Plate(2, "Box assembly", uris, names, 1.25f, 30f, 22f, -11f, 1, transforms, true));
        store.setActiveIndex(2);

        PlateStore.Plate reloaded = new PlateStore(preferences).activePlate();
        Assert.assertEquals(2, reloaded.index);
        Assert.assertEquals(2, reloaded.uris.size());
        Assert.assertEquals("lid.stl", reloaded.names.get(1));
        Assert.assertEquals(1.25f, reloaded.scale, 0.0001f);
        Assert.assertEquals(30f, reloaded.rotationDegrees, 0.0001f);
        Assert.assertEquals(22f, reloaded.tiltXDegrees, 0.0001f);
        Assert.assertEquals(-11f, reloaded.tiltYDegrees, 0.0001f);
        Assert.assertEquals(1, reloaded.selectedPart);
        Assert.assertEquals(0.75f, reloaded.partTransforms.get(0).scale, 0.0001f);
        Assert.assertTrue(reloaded.geometryRepairEnabled);

        PlateStore.Plate created = new PlateStore(preferences).createNext();
        Assert.assertEquals(0, created.index);
        Assert.assertTrue(new PlateStore(preferences).plateAt(2).uris.size() == 2);
    }

    @Test
    public void projectArchiveEmbedsModelsPlatesAndRecipe() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences inventoryPreferences = context.getSharedPreferences("alloy-test-archive-inventory", Context.MODE_PRIVATE);
        inventoryPreferences.edit().clear().commit();
        InventoryStore inventory = new InventoryStore(inventoryPreferences);
        InventoryStore.Item archivedItem = inventory.addCustom("Bow string jig", "Tool", "each", 0, 1, 30,
                "Keep dry; inspect alignment before use");
        org.json.JSONArray inventorySnapshot = inventory.snapshot();

        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/box.stl"));
        ArrayList<String> names = new ArrayList<>();
        names.add("box.stl");
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        transforms.add(new MeshModel.PartTransform(0.75f, 12f, 3f, -1f));
        plates.add(new PlateStore.Plate(0, "Box", uris, names, 1.25f, 30f, 18f, -9f, 0, transforms));
        plates.add(PlateStore.Plate.empty(2));
        Slicer.Config config = new Slicer.Config();
        config.layerHeight = 0.16f;
        config.supports = true;
        config.nativeSettings.put("fill_pattern", "grid");
        config.nativeSettings.put("support_material_style", "organic");
        ArrayList<ModelHistoryStore.HistoryEntry> historyEntries = new ArrayList<>();
        historyEntries.add(new ModelHistoryStore.HistoryEntry(plates.get(0), "Open model", 1L));
        historyEntries.add(new ModelHistoryStore.HistoryEntry(new PlateStore.Plate(0, "Box", uris, names,
                1.5f, 30f, 18f, -9f, 0, transforms), "Prepare model", 2L));
        ArrayList<ModelHistoryStore.Timeline> history = new ArrayList<>();
        history.add(new ModelHistoryStore.Timeline(0, 1, historyEntries));
        byte[] source = "solid box\nendsolid box\n".getBytes(StandardCharsets.UTF_8);
        byte[] thumbnail = new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ProjectArchive.write(archive, 0, plates, config, ignored -> new ByteArrayInputStream(source), inventorySnapshot, thumbnail, history);
        String manifest = new String(readZipEntry(archive.toByteArray(), "alloy/project.json"), StandardCharsets.UTF_8);
        Assert.assertTrue(manifest.contains("\"sha256\":"));
        Assert.assertTrue(manifest.contains("\"history\":"));

        File destination = new File(context.getCacheDir(), "project-archive-test-" + System.nanoTime());
        try {
            ProjectArchive.ImportedProject imported = ProjectArchive.read(
                    new ByteArrayInputStream(archive.toByteArray()), destination);
            Assert.assertEquals(0, imported.activePlateIndex);
            Assert.assertEquals(2, imported.plates.size());
            Assert.assertEquals(1, imported.plates.get(0).uris.size());
            Assert.assertEquals("box.stl", imported.plates.get(0).names.get(0));
            Assert.assertEquals(18f, imported.plates.get(0).tiltXDegrees, 0.0001f);
            Assert.assertEquals(-9f, imported.plates.get(0).tiltYDegrees, 0.0001f);
            Assert.assertEquals(3f, imported.plates.get(0).partTransforms.get(0).offsetX, 0.0001f);
            Assert.assertEquals(0.16f, imported.config.layerHeight, 0.0001f);
            Assert.assertTrue(imported.config.supports);
            Assert.assertEquals(config.nativeSettings, imported.config.nativeSettings);
            Assert.assertNotNull(imported.inventorySnapshot);
            Assert.assertEquals(inventorySnapshot.length(), imported.inventorySnapshot.length());
            Assert.assertNotNull(imported.thumbnailPng);
            Assert.assertArrayEquals(thumbnail, imported.thumbnailPng);
            Assert.assertEquals(1, imported.history.size());
            Assert.assertEquals(1, imported.history.get(0).cursor);
            Assert.assertEquals(2, imported.history.get(0).entries.size());
            Assert.assertEquals(1.5f, imported.history.get(0).entries.get(1).plate.scale, 0.0001f);
            InventoryStore restoredInventory = new InventoryStore(
                    context.getSharedPreferences("alloy-test-archive-restore", Context.MODE_PRIVATE));
            restoredInventory.restoreSnapshot(imported.inventorySnapshot);
            Assert.assertTrue(findInventoryItem(restoredInventory, archivedItem.id).needsReorder());
            File extracted = new File(imported.plates.get(0).uris.get(0).getPath());
            Assert.assertTrue(extracted.isFile());
            Assert.assertEquals(source.length, extracted.length());
        } finally {
            deleteRecursively(destination);
        }
    }

    @Test
    public void projectArchivePreservesObjSourceExtension() throws Exception {
        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/bow-parts.obj"));
        ArrayList<String> names = new ArrayList<>();
        names.add("bow-parts.obj");
        plates.add(new PlateStore.Plate(0, "Bow parts", uris, names, 1f, 0f, -1,
                new ArrayList<>()));
        byte[] source = ("v 0 0 0\nv 10 0 0\nv 0 10 0\nv 0 0 10\n"
                + "f 1 3 2\nf 1 2 4\nf 1 4 3\nf 2 3 4\n").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ProjectArchive.write(archive, 0, plates, new Slicer.Config(),
                ignored -> new ByteArrayInputStream(source));
        Assert.assertTrue(readZipEntry(archive.toByteArray(), "models/0001.obj").length > 0);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "obj-project-archive-test-" + System.nanoTime());
        try {
            ProjectArchive.ImportedProject imported = ProjectArchive.read(
                    new ByteArrayInputStream(archive.toByteArray()), destination);
            Assert.assertEquals("bow-parts.obj", imported.plates.get(0).names.get(0));
            Assert.assertTrue(imported.plates.get(0).uris.get(0).getPath().endsWith("0001.obj"));
        } finally {
            deleteRecursively(destination);
        }
    }

    @Test
    public void projectArchivePreservesStepSourceExtension() throws Exception {
        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/chassis.step"));
        ArrayList<String> names = new ArrayList<>();
        names.add("chassis.step");
        plates.add(new PlateStore.Plate(0, "CAD chassis", uris, names, 1f, 0f, -1,
                new ArrayList<>()));
        byte[] source = ("ISO-10303-21;\nHEADER;\nENDSEC;\nDATA;\nENDSEC;\nEND-ISO-10303-21;\n")
                .getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ProjectArchive.write(archive, 0, plates, new Slicer.Config(),
                ignored -> new ByteArrayInputStream(source));
        Assert.assertTrue(readZipEntry(archive.toByteArray(), "models/0001.step").length > 0);

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "step-project-archive-test-" + System.nanoTime());
        try {
            ProjectArchive.ImportedProject imported = ProjectArchive.read(
                    new ByteArrayInputStream(archive.toByteArray()), destination);
            Assert.assertEquals("chassis.step", imported.plates.get(0).names.get(0));
            Assert.assertTrue(imported.plates.get(0).uris.get(0).getPath().endsWith("0001.step"));
        } finally {
            deleteRecursively(destination);
        }
    }

    @Test
    public void projectArchiveRejectsUnsafeZipPaths() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        ZipOutputStream zip = new ZipOutputStream(archive);
        zip.putNextEntry(new ZipEntry("../outside.stl"));
        zip.write(1);
        zip.closeEntry();
        zip.finish();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "unsafe-project-archive-test-" + System.nanoTime());
        try {
            ProjectArchive.read(new ByteArrayInputStream(archive.toByteArray()), destination);
            Assert.fail("unsafe project path should be rejected");
        } catch (java.io.IOException expected) {
            File[] leftovers = destination.listFiles();
            Assert.assertTrue(leftovers == null || leftovers.length == 0);
        }
        if (destination.exists()) Assert.assertTrue(destination.delete());
    }

    @Test
    public void projectArchiveRejectsTamperedEmbeddedModel() throws Exception {
        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse("content://com.example.documents/box.stl"));
        ArrayList<String> names = new ArrayList<>();
        names.add("box.stl");
        plates.add(new PlateStore.Plate(0, "Box", uris, names, 1f, 0f, -1,
                new ArrayList<>()));
        ByteArrayOutputStream original = new ByteArrayOutputStream();
        ProjectArchive.write(original, 0, plates, new Slicer.Config(),
                ignored -> new ByteArrayInputStream("solid box\nendsolid box\n".getBytes(StandardCharsets.UTF_8)));

        ByteArrayOutputStream tampered = new ByteArrayOutputStream();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(original.toByteArray()), StandardCharsets.UTF_8);
             ZipOutputStream output = new ZipOutputStream(tampered, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[4096];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream contents = new ByteArrayOutputStream();
                int read;
                while ((read = input.read(buffer)) != -1) contents.write(buffer, 0, read);
                byte[] value = contents.toByteArray();
                if ("models/0001.stl".equals(entry.getName()) && value.length > 0) value[0] ^= 0x01;
                output.putNextEntry(new ZipEntry(entry.getName()));
                output.write(value);
                output.closeEntry();
            }
        }

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "tampered-project-test-" + System.nanoTime());
        try {
            ProjectArchive.read(new ByteArrayInputStream(tampered.toByteArray()), destination);
            Assert.fail("tampered project model should be rejected");
        } catch (java.io.IOException expected) {
            File[] leftovers = destination.listFiles();
            Assert.assertTrue(leftovers == null || leftovers.length == 0);
        }
        if (destination.exists()) Assert.assertTrue(destination.delete());
    }

    @Test
    public void extractedProjectStoragePrunesUnreferencedCopies() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "project-retention-test-" + System.nanoTime());
        Assert.assertTrue(destination.mkdirs());
        File protectedModel = null;
        try {
            for (int index = 0; index < 9; index++) {
                File project = new File(destination, "project-" + index);
                Assert.assertTrue(project.mkdirs());
                File model = new File(project, "0001.stl");
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(model)) {
                    output.write(index);
                }
                if (index == 8) protectedModel = model;
                project.setLastModified(1_000L + index);
            }
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.fromFile(protectedModel));
            ArrayList<String> names = new ArrayList<>();
            names.add("box.stl");
            ArrayList<PlateStore.Plate> protectedPlates = new ArrayList<>();
            protectedPlates.add(new PlateStore.Plate(0, "Current", uris, names, 1f, 0f, -1,
                    new ArrayList<>()));

            ProjectArchive.pruneStoredProjects(destination, protectedPlates);

            int projectCount = 0;
            File[] remaining = destination.listFiles();
            if (remaining != null) for (File child : remaining) if (child.isDirectory()) projectCount++;
            Assert.assertTrue(projectCount <= 8);
            Assert.assertTrue(protectedModel.isFile());
        } finally {
            deleteRecursively(destination);
        }
    }

    @Test
    public void projectRetentionDoesNotFollowSymlinks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File destination = new File(context.getCacheDir(), "project-symlink-test-" + System.nanoTime());
        File external = new File(context.getCacheDir(), "project-symlink-target-" + System.nanoTime());
        File link = null;
        try {
            Assert.assertTrue(destination.mkdirs());
            Assert.assertTrue(external.mkdirs());
            File sentinel = new File(external, "keep.stl");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(sentinel)) {
                output.write(7);
            }
            File project = new File(destination, "project-unsafe");
            Assert.assertTrue(project.mkdirs());
            link = new File(project, "linked-files");
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), external.toPath());
            } catch (Exception unavailable) {
                org.junit.Assume.assumeTrue("symbolic links are unavailable on this device", false);
                return;
            }
            for (int index = 0; index < 9; index++) {
                File other = new File(destination, "project-" + index);
                Assert.assertTrue(other.mkdirs());
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(new File(other, "0001.stl"))) {
                    output.write(index);
                }
                other.setLastModified(2_000L + index);
            }
            // Process the linked project after the retention budget is full;
            // pruning must preserve it instead of following the link.
            project.setLastModified(20_000L);

            ProjectArchive.pruneStoredProjects(destination, new ArrayList<PlateStore.Plate>());

            Assert.assertTrue("external cache target must survive pruning", new File(external, "keep.stl").isFile());
            Assert.assertTrue("the link itself must remain inspectable", java.nio.file.Files.isSymbolicLink(link.toPath()));
        } finally {
            if (link != null && java.nio.file.Files.isSymbolicLink(link.toPath())) Assert.assertTrue(link.delete());
            deleteRecursively(destination);
            deleteRecursively(external);
        }
    }

    @Test
    public void printerCredentialsRejectProtocolControlCharacters() {
        try {
            new PrinterCredentialStore.Credentials("A1 Mini", "192.168.1.2", "01S\nreport", "1234");
            Assert.fail("serial control characters must be rejected");
        } catch (IllegalArgumentException expected) {
            // MQTT topic segments must remain single, literal tokens.
        }
        try {
            new PrinterCredentialStore.Credentials("A1 Mini", "192.168.1.2", "01S123", "1234\r\nQUIT");
            Assert.fail("FTP command control characters must be rejected");
        } catch (IllegalArgumentException expected) {
            // The access code is later interpolated into PASS, so it is
            // deliberately bounded to a single protocol-safe value.
        }
    }

    @Test
    public void physicalPrinterCredentialsRequireLeafCertificatePin() throws Exception {
        PrinterCredentialStore.Credentials unpinned = new PrinterCredentialStore.Credentials(
                "A1 Mini", "192.168.1.2", "01S123", "1234");
        Assert.assertFalse(unpinned.hasCertificatePin());
        PrinterCredentialStore.Credentials pinned = new PrinterCredentialStore.Credentials(
                "A1 Mini", "192.168.1.2", "01S123", "1234",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Assert.assertTrue(pinned.hasCertificatePin());
        Assert.assertFalse(pinned.isA1Mini());
        PrinterCredentialStore.Credentials discovered = new PrinterCredentialStore.Credentials(
                "A1 Mini", "192.168.1.2", "01S123", "1234",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "N1");
        Assert.assertTrue(discovered.isA1Mini());
        String preferencesName = "printer-credentials-model-test-" + System.nanoTime();
        android.content.SharedPreferences preferences = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
        PrinterCredentialStore store = new PrinterCredentialStore(preferences);
        try {
            store.save(discovered);
            PrinterCredentialStore.Credentials restored = store.load();
            Assert.assertNotNull(restored);
            Assert.assertEquals("N1", restored.model);
            Assert.assertTrue(restored.isA1Mini());
        } finally {
            store.clear();
        }
    }

    @Test
    public void printerReadinessReportIsCompleteAndFailClosed() {
        PrinterReadiness.Report blocked = PrinterReadiness.evaluate(
                true, false, true, false, true, false, true,
                false, false, false, false);
        Assert.assertFalse(blocked.canSend());
        Assert.assertEquals("every physical prerequisite should be represented", 8, blocked.checks().size());
        Assert.assertEquals("the staged package is the only passing prerequisite in this fixture", 1, blocked.passedCount());
        Assert.assertTrue(blocked.summary().startsWith("BLOCKED"));
        Assert.assertTrue(blocked.checks().get(0).detail.contains("not promoted"));
        Assert.assertTrue(blocked.checks().get(6).detail.contains("fingerprint"));

        PrinterReadiness.Report ready = PrinterReadiness.evaluate(
                true, true, true, true, true, true, true,
                true, true, true, true);
        Assert.assertTrue(ready.canSend());
        Assert.assertEquals(8, ready.passedCount());
        Assert.assertTrue(ready.summary().startsWith("READY"));
    }

    @Test
    public void supportParityIsIndependentFromGeneralNativeEngineVerification() {
        Slicer.Config config = new Slicer.Config();
        config.supports = true;
        config.nativeSettings.put("support_material_style", "organic");
        Slicer.Result legacy = new Slicer.Result("G90\n", new ArrayList<>(), 0f, 0,
                "prusa-slicebeam-native", true);
        Assert.assertFalse(SupportEngineStatus.physicalPrintReady(config, legacy));
        Assert.assertTrue(SupportEngineStatus.detail(config, legacy).contains("Bambu tree(auto) parity"));

        Slicer.Result verified = new Slicer.Result("G90\n", new ArrayList<>(), 0f, 0,
                SupportEngineStatus.BAMBU_TREESUPPORT3D_ENGINE_ID, true);
        Assert.assertTrue(SupportEngineStatus.physicalPrintReady(config, verified));

        config.supports = false;
        Assert.assertTrue(SupportEngineStatus.physicalPrintReady(config, null));
    }

    @Test
    public void printerReadinessAddsSupportParityGateWhenRequested() {
        PrinterReadiness.Report blocked = PrinterReadiness.evaluate(
                true, true, true, true, true, true, true,
                true, true, true, true, false,
                "Current result uses the legacy SliceBeam organic/tree path");
        Assert.assertFalse(blocked.canSend());
        Assert.assertEquals(9, blocked.checks().size());
        Assert.assertFalse(blocked.checks().get(3).passed());
        Assert.assertTrue(blocked.checks().get(3).detail.contains("legacy SliceBeam"));
    }

    @Test
    public void artifactStoreRejectsSymlinkedSource() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "artifact-symlink-test-" + System.nanoTime());
        File external = new File(context.getCacheDir(), "artifact-symlink-target-" + System.nanoTime());
        File link = new File(root, "linked.gcode.3mf");
        try {
            Assert.assertTrue(root.mkdirs());
            Assert.assertTrue(external.mkdirs());
            File target = new File(external, "real.gcode.3mf");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                output.write(1);
            }
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
            } catch (Exception unavailable) {
                org.junit.Assume.assumeTrue("symbolic links are unavailable on this device", false);
                return;
            }
            try {
                new PrinterTransport.Artifact(link, "linked.gcode.3mf", target.length(),
                        "0000000000000000000000000000000000000000000000000000000000000000");
                Assert.fail("symlinked artifact sources must be rejected");
            } catch (IllegalArgumentException expected) {
                // The artifact identity must refer to a regular app-owned file.
            }
        } finally {
            if (java.nio.file.Files.isSymbolicLink(link.toPath())) Assert.assertTrue(link.delete());
            deleteRecursively(root);
            deleteRecursively(external);
        }
    }

    @Test
    public void artifactStoreRejectsSymlinkedTarget() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "artifact-target-symlink-" + System.nanoTime());
        File external = new File(context.getCacheDir(), "artifact-target-external-" + System.nanoTime());
        File link = new File(root, "target.gcode.3mf");
        try {
            Assert.assertTrue(root.mkdirs());
            Assert.assertTrue(external.mkdirs());
            File target = new File(external, "real.gcode.3mf");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) {
                output.write(1);
            }
            try {
                java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath());
            } catch (Exception unavailable) {
                org.junit.Assume.assumeTrue("symbolic links are unavailable on this device", false);
                return;
            }
            MeshModel mesh;
            try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
                mesh = MeshModel.read("box-20mm.stl", input);
            }
            Slicer.Config config = new Slicer.Config();
            Slicer.Result result = new LegacyOfflineEngine().slice(mesh, config, null);
            try {
                ArtifactStore.stage(root, mesh, result, config, "target");
                Assert.fail("symlinked artifact targets must be rejected");
            } catch (java.io.IOException expected) {
                // A staged artifact must never replace or follow an existing link.
            }
        } finally {
            if (java.nio.file.Files.isSymbolicLink(link.toPath())) Assert.assertTrue(link.delete());
            deleteRecursively(root);
            deleteRecursively(external);
        }
    }

    @Test
    public void boundedModelWorkbenchPrimitivesRoundTripThroughStl() throws Exception {
        ModelWorkbench.Primitive[] primitives = ModelWorkbench.Primitive.values();
        for (ModelWorkbench.Primitive primitive : primitives) {
            MeshModel generated = ModelWorkbench.create(primitive, "test-" + primitive.label, 36f, 28f, 24f);
            Assert.assertTrue(primitive.label + " should have triangles", generated.triangles.length / 3 > 0);
            Assert.assertTrue(primitive.label + " should stay within the phone mesh bound", generated.triangles.length / 3 <= 10_000);
            byte[] stl = ModelWorkbench.toBinaryStl(generated);
            Assert.assertEquals(84 + generated.triangles.length / 3 * 50, stl.length);
            MeshModel reparsed = MeshModel.read(primitive.label + ".stl", new ByteArrayInputStream(stl));
            Assert.assertEquals(generated.triangles.length / 3, reparsed.triangles.length / 3);
            Assert.assertEquals(generated.parts.length, reparsed.parts.length);
            Assert.assertTrue(reparsed.maxX - reparsed.minX <= 36.01f);
            Assert.assertTrue(reparsed.maxY - reparsed.minY <= 28.01f);
            Assert.assertTrue(reparsed.maxZ - reparsed.minZ <= 24.01f);
        }
    }

    @Test
    public void largestFaceLayFlatPreservesMeshAndPlacesItOnBed() throws Exception {
        MeshModel source = ModelWorkbench.create(ModelWorkbench.Primitive.WEDGE,
                "tilted wedge", 48f, 32f, 26f);
        MeshModel tilted = source.transformed("tilted wedge", 1f, 17f, 31f, -23f);
        MeshModel flat = tilted.layFlat("wedge laid flat");
        Assert.assertEquals("lay-flat must preserve triangle count", tilted.triangles.length, flat.triangles.length);
        Assert.assertEquals("lay-flat must preserve named parts", tilted.parts.length, flat.parts.length);
        Assert.assertEquals("the lowest vertex must rest on Z=0", 0f, flat.minZ, 0.001f);
        Assert.assertTrue("the laid-flat mesh must retain positive height", flat.maxZ > 0.1f);
        for (float vertex : flat.vertices)
            Assert.assertTrue("lay-flat must keep finite coordinates", Float.isFinite(vertex));
    }

    @Test
    public void autoOrientChoosesLowerAxisAlignedPrintHeight() throws Exception {
        MeshModel tall = ModelWorkbench.create(ModelWorkbench.Primitive.BOX,
                "tall auto-orient", 20f, 30f, 80f);
        MeshModel oriented = tall.autoOrient("oriented");
        Assert.assertEquals(20f, oriented.maxZ - oriented.minZ, 0.01f);
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
