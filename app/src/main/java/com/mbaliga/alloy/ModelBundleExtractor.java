package com.mbaliga.alloy;

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
        return extract(appFilesDir, input, MAX_ENTRIES, MAX_ENTRY_BYTES, MAX_TOTAL_BYTES);
    }

    /** Limits are injectable only within this package so edge cases can be tested without huge fixtures. */
    static ArrayList<Extracted> extract(java.io.File appFilesDir, InputStream input,
                                        int maxEntries, long maxEntryBytes, long maxTotalBytes) throws IOException {
        if (appFilesDir == null || input == null) throw new IllegalArgumentException("model bundle inputs are required");
        if (maxEntries < 1 || maxEntryBytes < 1 || maxTotalBytes < 1)
            throw new IllegalArgumentException("model bundle limits must be positive");

        ArrayList<Extracted> result = new ArrayList<>();
        long[] totalBytes = {0L};
        int entries = 0;
        boolean projectArchive = false;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > maxEntries) throw new IOException("Model bundle has too many entries");
                String path = validateEntryPath(entry.getName());
                String lowerPath = path.toLowerCase(Locale.US);
                if (lowerPath.equals("alloy-project.json") || lowerPath.endsWith("/alloy-project.json"))
                    projectArchive = true;

                boolean modelEntry = !entry.isDirectory() && isSupportedMesh(path);
                BoundedEntryInputStream bounded =
                        new BoundedEntryInputStream(zip, maxEntryBytes, maxTotalBytes, totalBytes);
                if (modelEntry) {
                    String name = displayName(path);
                    ModelStore.Materialized materialized =
                            ModelStore.materializeGenerated(appFilesDir, bounded, name);
                    result.add(new Extracted(name, materialized));
                } else {
                    while (bounded.read(buffer, 0, buffer.length) != -1) {
                        if (Thread.currentThread().isInterrupted())
                            throw new java.util.concurrent.CancellationException("Model bundle import cancelled");
                    }
                }
                zip.closeEntry();
            }
            if (projectArchive)
                throw new IOException("This is an Alloy project archive; use Open project archive");
        } catch (IOException | RuntimeException failure) {
            deleteMaterialized(result);
            throw failure;
        }

        if (result.isEmpty()) throw new IOException("ZIP contains no STL, OBJ, 3MF or STEP model");
        return result;
    }

    private static void deleteMaterialized(ArrayList<Extracted> extracted) {
        for (Extracted item : extracted) {
            if (item.materialized != null && item.materialized.file != null
                    && item.materialized.file.exists())
                item.materialized.file.delete();
        }
        extracted.clear();
    }

    /** Counts decompressed bytes from every ZIP entry without owning the ZIP stream. */
    private static final class BoundedEntryInputStream extends InputStream {
        private final ZipInputStream input;
        private final long maxEntryBytes;
        private final long maxTotalBytes;
        private final long[] totalBytes;
        private long entryBytes;

        BoundedEntryInputStream(ZipInputStream input, long maxEntryBytes,
                                long maxTotalBytes, long[] totalBytes) {
            this.input = input;
            this.maxEntryBytes = maxEntryBytes;
            this.maxTotalBytes = maxTotalBytes;
            this.totalBytes = totalBytes;
        }

        @Override public int read() throws IOException {
            int value = input.read();
            if (value >= 0) account(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int count = input.read(buffer, offset, length);
            if (count > 0) account(count);
            return count;
        }

        private void account(int count) throws IOException {
            if (count > maxEntryBytes - entryBytes || count > maxTotalBytes - totalBytes[0])
                throw new IOException("Model bundle exceeds its decompressed size limit");
            entryBytes += count;
            totalBytes[0] += count;
        }

        /** ZipInputStream is owned by the extractor and must remain open for following entries. */
        @Override public void close() { }
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
