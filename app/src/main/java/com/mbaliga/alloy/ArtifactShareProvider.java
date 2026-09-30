package com.mbaliga.alloy;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only, one-file-at-a-time share boundary for staged Alloy packages.
 *
 * Android's share sheet must receive a content URI rather than a private file
 * path.  This provider deliberately exposes only app-private, safe-named
 * .gcode.3mf artifacts and only while MainActivity grants a recipient URI
 * permission. It neither accepts writes nor exposes models, credentials, logs
 * or any arbitrary path below the app storage root.
 */
public final class ArtifactShareProvider extends ContentProvider {
    static final String AUTHORITY_SUFFIX = ".artifactshare";
    private static final String SEGMENT = "artifact";

    static Uri uriFor(String packageName, String displayName) {
        if (!validName(displayName)) throw new IllegalArgumentException("Unsafe artifact name");
        return new Uri.Builder().scheme("content").authority(packageName + AUTHORITY_SUFFIX)
                .appendPath(SEGMENT).appendPath(displayName).build();
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        file(uri);
        return "application/octet-stream";
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Artifact sharing is read-only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        File value = file(uri);
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) row.add(value.getName());
            else if (OpenableColumns.SIZE.equals(column)) row.add(value.length());
            else row.add(null);
        }
        return cursor;
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException("Read-only"); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read-only"); }

    private File file(Uri uri) {
        if (uri == null || getContext() == null || !getContext().getPackageName().concat(AUTHORITY_SUFFIX).equals(uri.getAuthority())
                || uri.getPathSegments().size() != 2 || !SEGMENT.equals(uri.getPathSegments().get(0)))
            throw new IllegalArgumentException("Unknown artifact URI");
        String name = uri.getPathSegments().get(1);
        if (!validName(name)) throw new IllegalArgumentException("Unsafe artifact URI");
        File root = getContext().getFilesDir();
        File result = new File(root, name);
        try {
            if (!result.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()) || !result.isFile() || !result.canRead())
                throw new IllegalArgumentException("Artifact is unavailable");
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Artifact path could not be checked", error);
        }
        return result;
    }

    private static boolean validName(String value) {
        return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.gcode\\.3mf")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && !value.contains("..");
    }
}
