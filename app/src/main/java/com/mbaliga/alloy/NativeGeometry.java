package com.mbaliga.alloy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.UUID;

import ru.ytkab0bp.slicebeam.slic3r.Native;
import ru.ytkab0bp.slicebeam.slic3r.Slic3rRuntimeError;

/**
 * App-private guard around the optional OCCT solid-modeling bridge.
 *
 * The bridge intentionally accepts only content-addressed STL files under the
 * Alloy model cache. This keeps JNI path handling narrow and makes a boolean
 * result pass through the same hash-addressed storage boundary as imports and
 * generated primitives.
 */
final class NativeGeometry {
    static final int FUSE = 0;
    static final int CUT = 1;
    static final int COMMON = 2;
    private static final long MAX_INPUT_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_OUTPUT_BYTES = ModelStore.MAX_MODEL_BYTES;

    private NativeGeometry() { }

    static ModelStore.Materialized apply(File appFilesDir, File first, File second, int operation)
            throws IOException {
        if (!BuildConfig.NATIVE_ENGINE_ENABLED)
            throw new IOException("Exact modeling needs the native OCCT runtime");
        if (operation < FUSE || operation > COMMON)
            throw new IOException("Unsupported boolean operation");
        File root = cacheRoot(appFilesDir);
        validateInput(root, first);
        validateInput(root, second);
        File output = new File(root, ".boolean-" + UUID.randomUUID() + ".part");
        if (PrinterTransport.isSymbolicLink(output))
            throw new IOException("Boolean output must not be a symbolic link");
        try {
            try {
                if (!Native.model_boolean_stl(first.getCanonicalPath(), second.getCanonicalPath(),
                        output.getCanonicalPath(), operation))
                    throw new IOException("Native boolean operation returned no result");
            } catch (Slic3rRuntimeError error) {
                throw new IOException("Native boolean operation failed: " + error.getMessage(), error);
            }
            if (PrinterTransport.isSymbolicLink(output) || !output.isFile())
                throw new IOException("Native boolean did not produce a regular file");
            if (output.length() <= 0L || output.length() > MAX_OUTPUT_BYTES)
                throw new IOException("Native boolean output exceeds the offline cache limit");
            return ModelStore.materializeGenerated(appFilesDir, readBounded(output));
        } finally {
            if (output.exists() && !PrinterTransport.isSymbolicLink(output)) output.delete();
        }
    }

    static String label(int operation) {
        switch (operation) {
            case FUSE: return "Union";
            case CUT: return "Subtract";
            case COMMON: return "Intersect";
            default: return "Boolean";
        }
    }

    private static File cacheRoot(File appFilesDir) throws IOException {
        if (appFilesDir == null || PrinterTransport.isSymbolicLink(appFilesDir))
            throw new IOException("Model storage root must not be a symbolic link");
        File root = new File(appFilesDir, "models");
        if (PrinterTransport.isSymbolicLink(root) || !root.isDirectory())
            throw new IOException("Model cache is unavailable");
        return root.getCanonicalFile();
    }

    private static void validateInput(File root, File file) throws IOException {
        if (file == null || PrinterTransport.isSymbolicLink(file) || !file.isFile())
            throw new IOException("Boolean input model is not a regular file");
        if (file.length() <= 0L || file.length() > MAX_INPUT_BYTES)
            throw new IOException("Boolean input must be at most 32 MB");
        File canonical = file.getCanonicalFile();
        String rootPath = root.getPath();
        if (!canonical.getPath().startsWith(rootPath + File.separator)
                || !canonical.getName().matches("[0-9a-f]{64}\\.stl"))
            throw new IOException("Boolean inputs must be content-addressed STL cache files");
        String expectedHash = canonical.getName().substring(0, 64);
        if (!expectedHash.equals(ModelStore.sha256(canonical)))
            throw new IOException("Boolean input cache hash does not match its name");
    }

    private static byte[] readBounded(File file) throws IOException {
        if (file.length() <= 0L || file.length() > MAX_OUTPUT_BYTES)
            throw new IOException("Boolean output is too large");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(file.length(), 4L * 1024L * 1024L));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            long total = 0L;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_OUTPUT_BYTES) throw new IOException("Boolean output is too large");
                bytes.write(buffer, 0, read);
            }
        }
        return bytes.toByteArray();
    }
}
