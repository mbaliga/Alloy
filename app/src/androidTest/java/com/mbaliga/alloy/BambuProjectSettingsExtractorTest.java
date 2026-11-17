package com.mbaliga.alloy;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Coverage for reading Bambu's recipe metadata without materializing ZIP paths. */
public final class BambuProjectSettingsExtractorTest {
    @Test public void extractsOnlyTheStandardBambuProjectSettingsEntry() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File packageFile = new File(context.getCacheDir(), "bambu-settings-" + System.nanoTime() + ".3mf");
        byte[] expected = "{\"printer_model\":\"Bambu Lab A1 mini\",\"printer_settings_id\":\"A1\"}"
                .getBytes(StandardCharsets.UTF_8);
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packageFile))) {
                zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
                zip.write("<model/>".getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("Metadata/project_settings.config"));
                zip.write(expected);
                zip.closeEntry();
                zip.putNextEntry(new ZipEntry("Metadata/notes.txt"));
                zip.write("ignored".getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry();
            }
            Assert.assertArrayEquals(expected, BambuProjectSettingsExtractor.extract(packageFile));
        } finally {
            if (packageFile.exists()) Assert.assertTrue(packageFile.delete());
        }
    }

    @Test public void returnsNullForPlainModelPackageWithoutRecipeMetadata() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File packageFile = new File(context.getCacheDir(), "plain-3mf-" + System.nanoTime() + ".3mf");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packageFile))) {
                zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
                zip.write("<model/>".getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry();
            }
            Assert.assertNull(BambuProjectSettingsExtractor.extract(packageFile));
        } finally {
            if (packageFile.exists()) Assert.assertTrue(packageFile.delete());
        }
    }
}
