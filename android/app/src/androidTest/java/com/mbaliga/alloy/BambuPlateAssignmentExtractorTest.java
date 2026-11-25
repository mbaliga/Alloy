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

/** Guards against silently flattening a Bambu multi-plate project into one Alloy plate. */
public final class BambuPlateAssignmentExtractorTest {
    @Test public void detectsTwoBambuPlateDeclarations() throws Exception {
        Assert.assertTrue(hasMultiplePlates("<config><plate><metadata key=\"plater_id\" value=\"1\"/>"
                + "</plate><plate><metadata key=\"plater_id\" value=\"2\"/></plate></config>"));
    }

    @Test public void allowsSingleBambuPlateWithMultipleObjects() throws Exception {
        Assert.assertFalse(hasMultiplePlates("<config><object id=\"1\"/><object id=\"2\"/>"
                + "<plate><metadata key=\"plater_id\" value=\"1\"/></plate></config>"));
    }

    @Test public void ignoresOtherSlicerPlateElementsWithoutBambuPlateIds() throws Exception {
        Assert.assertFalse(hasMultiplePlates("<config><plate><metadata key=\"name\" value=\"one\"/>"
                + "</plate><plate><metadata key=\"name\" value=\"two\"/></plate></config>"));
    }

    @Test public void allowsPlain3mfWithoutBambuPlateMetadata() throws Exception {
        Assert.assertFalse(hasMultiplePlates(null));
    }

    private static boolean hasMultiplePlates(String settings) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File packageFile = new File(context.getCacheDir(), "bambu-plates-" + System.nanoTime() + ".3mf");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(packageFile))) {
                zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
                zip.write("<model/>".getBytes(StandardCharsets.US_ASCII));
                zip.closeEntry();
                if (settings != null) {
                    zip.putNextEntry(new ZipEntry("Metadata/model_settings.config"));
                    zip.write(settings.getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return BambuPlateAssignmentExtractor.hasMultiplePlates(packageFile);
        } finally {
            if (packageFile.exists()) Assert.assertTrue(packageFile.delete());
        }
    }
}
