package com.mbaliga.alloy;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;

/** Device contract for the narrowly scoped Android package-share boundary. */
@RunWith(AndroidJUnit4.class)
public final class ArtifactShareProviderTest {
    @Test public void stagedPackageIsReadableWithDisplayMetadataOnly() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String name = "share-provider-" + System.nanoTime() + ".gcode.3mf";
        File artifact = new File(context.getFilesDir(), name);
        byte[] expected = "alloy-validated-package".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(artifact)) { output.write(expected); }
        try {
            Uri uri = ArtifactShareProvider.uriFor(context.getPackageName(), name);
            Assert.assertEquals("application/octet-stream", context.getContentResolver().getType(uri));
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                Assert.assertNotNull(cursor);
                Assert.assertTrue(cursor.moveToFirst());
                Assert.assertEquals(name, cursor.getString(0));
                Assert.assertEquals(expected.length, cursor.getLong(1));
            }
            try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "r")) {
                Assert.assertNotNull(descriptor);
                byte[] actual = new byte[expected.length];
                int read = new java.io.FileInputStream(descriptor.getFileDescriptor()).read(actual);
                Assert.assertEquals(expected.length, read);
                Assert.assertArrayEquals(expected, actual);
            }
            try {
                context.getContentResolver().openFileDescriptor(uri, "w");
                Assert.fail("shared artifacts must not be writable");
            } catch (FileNotFoundException expectedFailure) {
                // Expected: provider is explicitly read-only.
            }
        } finally {
            Assert.assertTrue("test artifact should be removable", artifact.delete() || !artifact.exists());
        }
    }

    @Test public void unsafeNamesCannotBecomeShareUris() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] unsafe = {"../private.gcode.3mf", "artifact.gcode", "artifact.3mf", "a..b.gcode.3mf", "nested/file.gcode.3mf"};
        for (String name : unsafe) {
            try {
                ArtifactShareProvider.uriFor(context.getPackageName(), name);
                Assert.fail("unsafe share name accepted: " + name);
            } catch (IllegalArgumentException expected) {
                // Expected: the provider only accepts a safe top-level artifact name.
            }
        }
    }
}
