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
