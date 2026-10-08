package com.mbaliga.alloy;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Safety and multi-model coverage for phone-downloaded model bundles. */
public final class ModelBundleExtractorTest {
    @Test public void keepsDirectThreeMfOnTheMeshReaderPath() {
        Assert.assertTrue(ModelBundleExtractor.isDirect3mf("Bambu project.3mf", null));
        Assert.assertTrue(ModelBundleExtractor.isDirect3mf("download", "model/3mf"));
        Assert.assertTrue(ModelBundleExtractor.isDirect3mf("download", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml"));
        Assert.assertFalse(ModelBundleExtractor.isDirect3mf("download.zip", "application/zip"));
    }

    @Test public void extractsSupportedMeshesAndIgnoresAuxiliaryFiles() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "Olympic Recurve Bow/body.obj", "v 0 0 0\nv 10 0 0\nv 0 10 0\nf 1 2 3\n");
            put(zip, "Olympic Recurve Bow/body.mtl", "newmtl bow\n");
            put(zip, "Olympic Recurve Bow/preview.jpg", "not a model");
            put(zip, "Olympic Recurve Bow/limb.stl", "solid limb\nendsolid limb\n");
        }

        ArrayList<ModelBundleExtractor.Extracted> extracted = ModelBundleExtractor.extract(
                context.getFilesDir(), new ByteArrayInputStream(archive.toByteArray()));
        Assert.assertEquals(2, extracted.size());
        Assert.assertEquals("body.obj", extracted.get(0).displayName);
        Assert.assertEquals("limb.stl", extracted.get(1).displayName);
        for (ModelBundleExtractor.Extracted item : extracted) {
            Assert.assertTrue(item.materialized.file.isFile());
            Assert.assertTrue(item.materialized.file.delete());
        }
    }

    @Test public void rejectsUnsafeEntryPathsBeforeMaterializing() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "../outside.stl", "solid bad\nendsolid bad\n");
        }
        try {
            ModelBundleExtractor.extract(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),
                    new ByteArrayInputStream(archive.toByteArray()));
            Assert.fail("unsafe ZIP path should fail closed");
        } catch (java.io.IOException expected) {
            Assert.assertNotNull("the ZIP reader must explain the rejected entry", expected.getMessage());
            Assert.assertTrue("unexpected rejection: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("entry")
                            || expected.getMessage().toLowerCase().contains("path"));
        }
    }

    @Test public void boundsInflatedAuxiliaryEntries() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File appFilesDir = new File(context.getCacheDir(), "bundle-limit-" + java.util.UUID.randomUUID());
        Assert.assertTrue(appFilesDir.mkdirs());
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "preview/large.png", "123456789");
        }
        try {
            ModelBundleExtractor.extract(appFilesDir, new ByteArrayInputStream(archive.toByteArray()),
                    4, 8, 16);
            Assert.fail("oversized ignored ZIP entries must be bounded");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("decompressed size limit"));
        } finally {
            deleteTree(appFilesDir);
        }
    }

    @Test public void countsAuxiliaryBytesTowardTotalAndCleansPartialImport() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File appFilesDir = new File(context.getCacheDir(), "bundle-cleanup-" + java.util.UUID.randomUUID());
        Assert.assertTrue(appFilesDir.mkdirs());
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "part.stl", "solid cube\\nendsolid cube\\n");
            put(zip, "preview.png", "preview-bytes");
        }
        try {
            ModelBundleExtractor.extract(appFilesDir, new ByteArrayInputStream(archive.toByteArray()),
                    4, 32, 30);
            Assert.fail("auxiliary data must count toward the total decompressed size");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("decompressed size limit"));
            Assert.assertEquals("earlier materialized models must be removed on failure", 0,
                    countFiles(appFilesDir));
        } finally {
            deleteTree(appFilesDir);
        }
    }

    @Test public void failedBundleImportPreservesPreexistingCachedModel() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File appFilesDir = new File(context.getCacheDir(), "bundle-existing-" + java.util.UUID.randomUUID());
        Assert.assertTrue(appFilesDir.mkdirs());
        String model = "solid cube\\nendsolid cube\\n";
        ModelStore.Materialized existing = ModelStore.materializeGenerated(
                appFilesDir, model.getBytes(StandardCharsets.UTF_8), "part.stl");
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "part.stl", model);
            put(zip, "preview.png", "preview-bytes");
        }
        try {
            ModelBundleExtractor.extract(appFilesDir, new ByteArrayInputStream(archive.toByteArray()),
                    4, 32, 30);
            Assert.fail("auxiliary data must count toward the total decompressed size");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("decompressed size limit"));
            Assert.assertTrue("a pre-existing content-addressed model must remain available",
                    existing.file.isFile());
            Assert.assertEquals(1, countFiles(appFilesDir));
        } finally {
            deleteTree(appFilesDir);
        }
    }

    private static int countFiles(File root) {
        File[] children = root.listFiles();
        if (children == null) return 0;
        int count = 0;
        for (File child : children) {
            if (child.isDirectory()) count += countFiles(child);
            else count++;
        }
        return count;
    }

    private static void deleteTree(File root) {
        File[] children = root.listFiles();
        if (children != null) for (File child : children) {
            if (child.isDirectory()) deleteTree(child);
            else child.delete();
        }
        root.delete();
    }


    @Test public void bundleExtractionWaitsForSharedModelCacheMutationLock() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File appFilesDir = new File(context.getCacheDir(), "bundle-lock-" + java.util.UUID.randomUUID());
        Assert.assertTrue(appFilesDir.mkdirs());
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "part.stl", "solid cube\nendsolid cube\n");
        }
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<ArrayList<ModelBundleExtractor.Extracted>> result = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                result.set(ModelBundleExtractor.extract(appFilesDir,
                        new ByteArrayInputStream(archive.toByteArray())));
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                finished.countDown();
            }
        }, "bundle-cache-lock-test");

        try {
            synchronized (ModelStore.class) {
                worker.start();
                Assert.assertTrue("bundle import worker did not start",
                        started.await(5, TimeUnit.SECONDS));
                Assert.assertFalse("bundle extraction must hold the same lock as cache mutation",
                        finished.await(100, TimeUnit.MILLISECONDS));
            }
            Assert.assertTrue("bundle extraction did not finish after cache lock was released",
                    finished.await(5, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("bundle extraction failed", failure.get());
            Assert.assertNotNull(result.get());
            Assert.assertEquals(1, result.get().size());
            Assert.assertTrue(result.get().get(0).materialized.file.isFile());
            Assert.assertTrue(result.get().get(0).materialized.file.delete());
        } finally {
            worker.join(5000);
            deleteTree(appFilesDir);
        }
    }

    @Test public void distinguishesAlloyProjectArchive() throws Exception {
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            put(zip, "alloy-project.json", "{\"schema_version\":3}");
        }
        try {
            ModelBundleExtractor.extract(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),
                    new ByteArrayInputStream(archive.toByteArray()));
            Assert.fail("project archive should use the project restore flow");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("Open project archive"));
        }
    }

    private static void put(ZipOutputStream zip, String name, String contents) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(contents.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
