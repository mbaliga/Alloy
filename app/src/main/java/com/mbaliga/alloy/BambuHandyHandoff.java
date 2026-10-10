package com.mbaliga.alloy;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * Explicit Android handoff for a user who chooses to try a staged Alloy
 * package in Bambu Handy.
 *
 * <p>This is deliberately only an Android intent boundary. Resolving the
 * current Play-distributed Bambu Handy package proves that it advertises a
 * compatible receiving activity on this phone; it does <em>not</em> prove
 * that Bambu Handy accepts this particular .gcode.3mf or that a printer will
 * accept it. Those are separate, on-device acceptance gates.</p>
 */
final class BambuHandyHandoff {
    /** Current Google Play application id for Bambu Handy, reviewed 2026-10-01. */
    static final String PACKAGE_NAME = "bbl.intl.bambulab.com";
    static final String MIME_TYPE = "application/octet-stream";
    static final String CHOOSER_TITLE = "Share validated package — confirm recipient compatibility";

    private BambuHandyHandoff() { }

    static Intent genericShare(Uri artifactUri) {
        validateArtifactUri(artifactUri);
        Intent send = new Intent(Intent.ACTION_SEND).setType(MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, artifactUri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        // ClipData is required on several Android releases for a receiver to
        // inherit the same one-time content-URI read permission as EXTRA_STREAM.
        send.setClipData(ClipData.newRawUri("Alloy printer package", artifactUri));
        return send;
    }

    static Intent targetedShare(Uri artifactUri) {
        return genericShare(artifactUri).setPackage(PACKAGE_NAME);
    }

    static Intent chooserShare(Uri artifactUri) {
        return Intent.createChooser(genericShare(artifactUri), CHOOSER_TITLE);
    }

    /** Resolve before launch so an absent or non-importing Handy install falls back to Android's chooser. */
    static boolean canHandle(PackageManager packages, Intent targetedIntent) {
        return packages != null && targetedIntent != null
                && PACKAGE_NAME.equals(targetedIntent.getPackage())
                && packages.resolveActivity(targetedIntent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }

    private static void validateArtifactUri(Uri uri) {
        if (uri == null || !"content".equalsIgnoreCase(uri.getScheme()))
            throw new IllegalArgumentException("A content URI is required for package sharing");
    }
}
