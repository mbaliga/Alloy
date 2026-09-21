package com.mbaliga.alloy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Portable container for the independently validated artifacts of a batch. */
public final class BatchArtifactArchive {
    public static final String MIME_TYPE = "application/zip";
    public static final int MAX_PLATES = PlateStore.MAX_PLATES;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = MAX_PLATES + 1;
    private static final String MANIFEST = "alloy-batch.json";

    private BatchArtifactArchive() { }

    public static void write(OutputStream output, BatchSliceJobController.BatchResult batch) throws IOException {
        if (output == null || batch == null || batch.plates == null || batch.plates.isEmpty()
                || batch.plates.size() > MAX_PLATES)
            throw new IllegalArgumentException("Batch archive inputs are invalid");
        HashSet<Integer> indexes = new HashSet<>();
        JSONArray manifestPlates = new JSONArray();
        ZipOutputStream zip = new ZipOutputStream(new BoundedOutputStream(output, MAX_ARCHIVE_BYTES));
        byte[] buffer = new byte[32 * 1024];
        long total = 0L;
        try {
            for (BatchSliceJobController.PlateResult result : batch.plates) {
                if (result == null || result.plate == null || result.artifact == null
                        || !indexes.add(result.plate.index) || result.plate.index < 0
                        || result.plate.index >= MAX_PLATES)
                    throw new IOException("Batch contains an invalid or duplicate plate");
                PrinterTransport.Artifact artifact = result.artifact;
                File source = artifact.sourceFile;
                if (source == null || PrinterTransport.isSymbolicLink(source) || !source.isFile()
                        || source.length() != artifact.sizeBytes
                        || !ArtifactStore.sha256(source).equalsIgnoreCase(artifact.sha256))
                    throw new IOException("Batch artifact identity could not be verified");
                GcodePackageValidator.validate(source);
                String path = entryPath(result.plate.index);
                zip.putNextEntry(new ZipEntry(path));
                try (InputStream input = new FileInputStream(source)) {
                    long bytes = 0L;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (read == 0) continue;
                        bytes += read;
                        total += read;
                        if (bytes > MAX_ENTRY_BYTES || total > MAX_ARCHIVE_BYTES)
                            throw new IOException("Batch archive exceeds its size limit");
                        zip.write(buffer, 0, read);
                    }
                    if (bytes != artifact.sizeBytes) throw new IOException("Batch artifact changed while archiving");
                }
                zip.closeEntry();
                JSONObject encoded = new JSONObject();
                try {
                    encoded.put("index", result.plate.index)
                            .put("name", normalizeName(result.plate.name))
                            .put("entry", path)
                            .put("bytes", artifact.sizeBytes)
                            .put("sha256", artifact.sha256)
                            .put("layers", result.slice == null || result.slice.layers == null ? 0 : result.slice.layers.size())
                            .put("filament_mm", result.slice == null ? -1f : result.slice.filamentMm)
                            .put("print_time_seconds", result.slice == null ? -1f : result.slice.printTimeSeconds)
                            .put("engine", result.slice == null ? "unknown" : result.slice.engineId)
                            .put("engine_verified", result.slice != null && result.slice.engineVerified);
                } catch (Exception error) {
                    throw new IOException("Batch manifest could not be encoded", error);
                }
                manifestPlates.put(encoded);
            }
            JSONObject manifest = new JSONObject();
            try {
                manifest.put("format", "alloy-batch").put("version", 1).put("plates", manifestPlates);
            } catch (Exception error) {
                throw new IOException("Batch manifest could not be encoded", error);
            }
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Batch manifest is too large");
            zip.putNextEntry(new ZipEntry(MANIFEST));
            zip.write(bytes);
            zip.closeEntry();
            zip.finish();
        } finally {
            // The caller owns the destination stream. ZipOutputStream.finish()
            // is used above so a SAF output stream is not closed unexpectedly.
        }
    }

    /** Validate an exported batch without trusting its manifest or nested ZIPs. */
    public static void validate(File archive) throws IOException {
        if (archive == null || PrinterTransport.isSymbolicLink(archive) || !archive.isFile()
                || archive.length() <= 0L || archive.length() > MAX_ARCHIVE_BYTES)
            throw new IOException("Batch archive is missing or exceeds the size limit");
        HashSet<String> entries = new HashSet<>();
        HashMap<String, ActualEntry> actual = new HashMap<>();
        byte[] manifest = null;
        long total = 0L;
        int count = 0;
        File temporaryRoot = archive.getAbsoluteFile().getParentFile();
        if (temporaryRoot == null || PrinterTransport.isSymbolicLink(temporaryRoot))
            throw new IOException("Batch archive temporary storage is invalid");
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(archive), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ENTRIES || entry.isDirectory()) throw new IOException("Batch archive entry list is invalid");
                String path = safePath(entry.getName());
                if (!entries.add(path)) throw new IOException("Batch archive contains a duplicate entry");
                if (MANIFEST.equals(path)) {
                    manifest = readBounded(zip, MAX_MANIFEST_BYTES);
                } else if (path.matches("plates/plate-[0-9]{2}\\.gcode\\.3mf")) {
                    File temporary = File.createTempFile("alloy-batch-", ".gcode.3mf", temporaryRoot);
                    try {
                        long bytes = copyBounded(zip, temporary, MAX_ENTRY_BYTES);
                        total += bytes;
                        if (total > MAX_ARCHIVE_BYTES) throw new IOException("Batch archive exceeds the decompressed size limit");
                        GcodePackageValidator.validate(temporary);
                        actual.put(path, new ActualEntry(bytes, ArtifactStore.sha256(temporary)));
                    } finally {
                        if (!temporary.delete()) temporary.deleteOnExit();
                    }
                } else {
                    throw new IOException("Batch archive contains an unexpected entry");
                }
                zip.closeEntry();
            }
        }
        if (manifest == null) throw new IOException("Batch manifest is missing");
        validateManifest(manifest, actual);
    }

    private static void validateManifest(byte[] bytes, HashMap<String, ActualEntry> actual) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"alloy-batch".equals(root.optString("format")) || root.optInt("version", -1) != 1)
                throw new IOException("Batch manifest format is unsupported");
            JSONArray plates = root.optJSONArray("plates");
            if (plates == null || plates.length() == 0 || plates.length() > MAX_PLATES)
                throw new IOException("Batch manifest plate list is invalid");
            HashSet<Integer> indexes = new HashSet<>();
            HashSet<String> referenced = new HashSet<>();
            for (int index = 0; index < plates.length(); index++) {
                JSONObject plate = plates.optJSONObject(index);
                if (plate == null) throw new IOException("Batch manifest plate is invalid");
                int plateIndex = plate.optInt("index", -1);
                String entry = safePath(plate.optString("entry", ""));
                if (plateIndex < 0 || plateIndex >= MAX_PLATES || !indexes.add(plateIndex)
                        || !entry.equals(entryPath(plateIndex)) || !referenced.add(entry))
                    throw new IOException("Batch manifest plate identity is invalid");
                ActualEntry actualEntry = actual.get(entry);
                if (actualEntry == null) throw new IOException("Batch manifest references a missing artifact");
                long bytesValue = boundedLong(plate, "bytes", 1L, MAX_ENTRY_BYTES);
                String digest = plate.optString("sha256", "").toLowerCase(Locale.US);
                if (bytesValue != actualEntry.bytes || !digest.matches("[0-9a-f]{64}")
                        || !digest.equals(actualEntry.sha256))
                    throw new IOException("Batch artifact digest does not match its manifest");
            }
            if (referenced.size() != actual.size()) throw new IOException("Batch archive contains an unreferenced artifact");
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Batch manifest is invalid", error);
        }
    }

    private static String entryPath(int index) { return String.format(Locale.US, "plates/plate-%02d.gcode.3mf", index + 1); }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copyBounded(input, output, max);
        return output.toByteArray();
    }

    private static long copyBounded(InputStream input, File target, long max) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) { return copyBounded(input, output, max); }
    }

    private static long copyBounded(InputStream input, OutputStream output, long max) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException("Batch archive entry is too large");
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static long boundedLong(JSONObject object, String key, long min, long max) throws IOException {
        Object raw = object.opt(key);
        if (!(raw instanceof Number) || raw instanceof Boolean) throw new IOException("Batch manifest number is invalid");
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value) || value < min || value > max)
            throw new IOException("Batch manifest number is out of range");
        return (long) value;
    }

    private static String safePath(String path) throws IOException {
        if (path == null || path.length() == 0 || path.length() > 128 || path.startsWith("/")
                || path.contains("..") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("Batch archive path is unsafe");
        return path;
    }

    private static String normalizeName(String name) {
        if (name == null || name.trim().isEmpty()) return "Plate";
        String value = name.trim();
        return value.length() > 150 ? value.substring(0, 150) : value;
    }

    private static final class ActualEntry {
        final long bytes;
        final String sha256;
        ActualEntry(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final OutputStream output;
        private final long max;
        private long count;
        BoundedOutputStream(OutputStream output, long max) { this.output = output; this.max = max; }
        @Override public void write(int value) throws IOException { ensure(1); output.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException {
            ensure(length); output.write(value, offset, length);
        }
        private void ensure(long length) throws IOException {
            if (length < 0L || count > max - length) throw new IOException("Batch archive exceeds the size limit");
            count += length;
        }
    }
}
