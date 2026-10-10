package com.mbaliga.alloy;

import android.content.Intent;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Device-independent contract for the bounded Bambu Handy Android handoff. */
@RunWith(AndroidJUnit4.class)
public final class BambuHandyHandoffTest {
    @Test public void targetedHandoffKeepsTheOneTimeReadGrantAndCurrentPackageId() {
        Uri artifact = Uri.parse("content://com.mbaliga.alloy.artifactshare/artifact/demo.gcode.3mf");
        Intent generic = BambuHandyHandoff.genericShare(artifact);
        Intent targeted = BambuHandyHandoff.targetedShare(artifact);

        Assert.assertEquals(Intent.ACTION_SEND, generic.getAction());
        Assert.assertEquals(BambuHandyHandoff.MIME_TYPE, generic.getType());
        Assert.assertEquals(artifact, generic.getParcelableExtra(Intent.EXTRA_STREAM));
        Assert.assertNotNull(generic.getClipData());
        Assert.assertEquals(artifact, generic.getClipData().getItemAt(0).getUri());
        Assert.assertEquals(0, generic.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        Assert.assertNotEquals(0, generic.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Assert.assertNull(generic.getPackage());
        Assert.assertEquals(BambuHandyHandoff.PACKAGE_NAME, targeted.getPackage());
        Assert.assertEquals(artifact, targeted.getParcelableExtra(Intent.EXTRA_STREAM));
    }

    @Test public void fileOrNetworkUrisCannotBeSharedThroughTheHandoff() {
        for (String unsafe : new String[]{"file:///tmp/demo.gcode.3mf", "https://example.com/demo.gcode.3mf"}) {
            try {
                BambuHandyHandoff.genericShare(Uri.parse(unsafe));
                Assert.fail("unsafe handoff URI accepted: " + unsafe);
            } catch (IllegalArgumentException expected) {
                // Only the read-only Alloy content-provider URI may cross apps.
            }
        }
    }

    @Test public void chooserFallbackRetainsReadOnlyGrantAndNeverClaimsHandyAccepted() {
        Uri artifact = Uri.parse("content://com.mbaliga.alloy.artifactshare/artifact/demo.gcode.3mf");
        Intent chooser = BambuHandyHandoff.chooserShare(artifact);
        Intent generic = (Intent) chooser.getParcelableExtra(Intent.EXTRA_INTENT);

        Assert.assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Assert.assertEquals(BambuHandyHandoff.CHOOSER_TITLE,
                chooser.getStringExtra(Intent.EXTRA_TITLE));
        Assert.assertNotNull(generic);
        Assert.assertEquals(Intent.ACTION_SEND, generic.getAction());
        Assert.assertEquals(artifact, generic.getParcelableExtra(Intent.EXTRA_STREAM));
        Assert.assertEquals(artifact, generic.getClipData().getItemAt(0).getUri());
        Assert.assertNotEquals(0, generic.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Assert.assertEquals(0, generic.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test public void missingResolverCannotBeTreatedAsHandyAvailability() {
        Uri artifact = Uri.parse("content://com.mbaliga.alloy.artifactshare/artifact/demo.gcode.3mf");
        Assert.assertFalse(BambuHandyHandoff.canHandle(null,
                BambuHandyHandoff.targetedShare(artifact)));
        Assert.assertFalse(BambuHandyHandoff.canHandle(null,
                BambuHandyHandoff.genericShare(artifact)));
    }
}
