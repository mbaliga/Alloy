package com.mbaliga.alloy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Bounded model-bundle intake for downloaded ZIPs. STL, OBJ, 3MF and STEP
 * entries become model sources; materials, images, documentation and scripts
 * are intentionally ignored. No ZIP entry is ever used as a filesystem path.
 */
public final class ModelBundleExtractor {
    public static final int MAX_ENTRIES = 64;
    public static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final int ZIP_SIGNATURE_BYTES = 4;

    private ModelBundleExtractor() { }

    /**
     * A 3MF is itself a ZIP package. Use the document name/type to keep a
     * direct 3MF on the MeshModel reader path instead of treating its internal
     * .model and metadata entries as an ordinary multi-file download bundle.
     * The content parser still validates the package structure after intake.
     */
    static boolean isDirect3mf(String displayName, String mimeType) {
        String name = displayName == null ? "" : displayName.trim().toLowerCase(Locale.US);
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.US);
        return name.endsWith(".3mf")
                || "model/3mf".equals(mime)
                || "application/vnd.ms-package.3dmanufacturing-3dmodel+xml".equals(mime);
    }

    /** Reads and rewinds four bytes so a caller can safely probe a stream. */
    public static boolean isZip(InputStream input) throws IOException {
        if (input == null) throw new IOException("model source could not be opened");
        if (!input.markSupported()) throw new IOException("model source does not support bounded probing");
        input.mark(ZIP_SIGNATURE_BYTES);
        byte[] signature = new byte[ZIP_SIGNATURE_BYTES];
        int offset = 0;
        while (offset < signature.length) {
            int read = input.read(signature, offset, signature.length - offset);
            if (read < 0) break;
            if (read == 0) continue;
            offset += read;
        }
        input.reset();
        return offset >= ZIP_SIGNATURE_BYTES && signature[0] == 'P' && signature[1] == 'K'
                && ((signature[2] == 3 && signature[3] == 4)
                || (signature[2] == 5 && signature[3] == 6)
                || (signature[2] == 7 && signature[3] == 8));
    }

    public static ArrayList<Extracted> extract(java.io.File appFilesDir, InputStream input) throws IOException {
        if (appFilesDir == null || input == null) throw new IllegalArgumentException("model bundle inputs are required");
        ArrayList<Extracted> result = new ArrayList<>();
        long totalBytes = 0L;
        int entries = 0;
        boolean projectArchive = false;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) throw new IOException("Model bundle has too many entries");
                String path = validateEntryPath(entry.getName());
                if (path.equalsIgnoreCase("alloy-project.json") || path.endsWith("/alloy-project.json"))
                    projectArchive = true;
                if (entry.isDirectory() || !isSupportedMesh(path)) {
                    zip.closeEntry();
                    continue;
                }
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                long entryBytes = 0L;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    if (read == 0) continue;
                    entryBytes += read;
                    totalBytes += read;
                    if (entryBytes > MAX_ENTRY_BYTES || totalBytes > MAX_TOTAL_BYTES)
                        throw new IOException("Model bundle exceeds its size limit");
                    if (Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException("Model bundle import cancelled");
                    bytes.write(buffer, 0, read);
                }
                if (entryBytes == 0L) throw new IOException("Model bundle contains an empty model");
                String name = displayName(path);
                result.add(new Extracted(name, ModelStore.materializeGenerated(appFilesDir, bytes.toByteArray(), name)));
                zip.closeEntry();
            }
        }
        if (projectArchive) {
            for (Extracted extracted : result) if (extracted.materialized.file.exists()) extracted.materialized.file.delete();
            throw new IOException("This is an Alloy project archive; use Open project archive");
        }
        if (result.isEmpty()) throw new IOException("ZIP contains no STL, OBJ, 3MF or STEP model");
        return result;
    }

    private static String validateEntryPath(String raw) throws IOException {
        String path = raw == null ? "" : raw.replace('\\', '/').trim();
        if (path.length() == 0 || path.length() > 512 || path.startsWith("/") || path.indexOf('\0') >= 0)
            throw new IOException("Model bundle contains an invalid entry path");
        String[] segments = path.split("/");
        for (String segment : segments) {
            if (segment.equals("..") || segment.length() == 0) throw new IOException("Model bundle contains an unsafe entry path");
        }
        return path;
    }

    private static boolean isSupportedMesh(String path) {
        String lower = path.toLowerCase(Locale.US);
        return lower.endsWith(".stl") || lower.endsWith(".obj") || lower.endsWith(".3mf")
                || lower.endsWith(".step") || lower.endsWith(".stp");
    }

    private static String displayName(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.length() > 160 ? name.substring(name.length() - 160) : name;
    }

    public static final class Extracted {
        public final String displayName;
        public final ModelStore.Materialized materialized;

        private Extracted(String displayName, ModelStore.Materialized materialized) {
            this.displayName = displayName;
            this.materialized = materialized;
        }
    }
}
