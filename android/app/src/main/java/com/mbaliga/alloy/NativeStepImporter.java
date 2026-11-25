package com.mbaliga.alloy;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;

import ru.ytkab0bp.slicebeam.slic3r.Native;
import ru.ytkab0bp.slicebeam.slic3r.Slic3rRuntimeError;

/**
 * Converts a bounded, content-addressed STEP source through the bundled OCCT
 * reader into Alloy's ordinary 3MF mesh boundary. The Java renderer and
 * project store intentionally consume the converted 3MF, so reopening a
 * project never depends on a still-readable document-provider URI or on
 * keeping a native model handle alive.
 */
final class NativeStepImporter {
    private static final long MAX_OUTPUT_BYTES = ModelStore.MAX_MODEL_BYTES;

    private NativeStepImporter() { }

    static ModelStore.Materialized convertTo3mf(File appFilesDir,
                                                 ModelStore.Materialized source,
                                                 Slicer.Config config,
                                                 String displayName) throws IOException {
        if (!BuildConfig.NATIVE_ENGINE_ENABLED)
            throw new IOException("STEP import needs the native OCCT runtime");
        if (appFilesDir == null || source == null || source.file == null)
            throw new IOException("STEP import inputs are missing");
        if (!".step".equals(source.extension))
            throw new IOException("The selected file is not a STEP model");
        File root = cacheRoot(appFilesDir);
        validateSource(root, source.file);
        if (config == null) config = new Slicer.Config();

        File configFile = new File(root, ".step-config-" + UUID.randomUUID() + ".part");
        File outputFile = new File(root, ".step-export-" + UUID.randomUUID() + ".3mf.part");
        if (PrinterTransport.isSymbolicLink(configFile) || PrinterTransport.isSymbolicLink(outputFile))
            throw new IOException("STEP conversion files must not be symbolic links");
        long modelPtr = 0L;
        try {
            NativeSlicerEngine.writeConfig(config, configFile);
            try {
                modelPtr = Native.model_read_from_file(source.file.getCanonicalPath(), safeBaseName(displayName), 0);
                if (modelPtr == 0L) throw new IOException("OCCT returned an empty STEP model");
                Native.model_export_3mf(modelPtr, configFile.getCanonicalPath(), outputFile.getCanonicalPath());
            } catch (Slic3rRuntimeError error) {
                throw new IOException("OCCT could not convert the STEP model: " + message(error), error);
            }
            if (PrinterTransport.isSymbolicLink(outputFile) || !outputFile.isFile())
                throw new IOException("OCCT produced no 3MF model");
            if (outputFile.length() <= 0L || outputFile.length() > MAX_OUTPUT_BYTES)
                throw new IOException("Converted 3MF exceeds the 64 MB offline cache limit");
            ModelStore.Materialized converted = ModelStore.materializeGenerated(appFilesDir, readBounded(outputFile));
            // The native loader can legally return a model container even when
            // a malformed/unsupported STEP shape produced no facets. Reject
            // that state here instead of presenting an apparently successful
            // but empty plate to the user.
            try (FileInputStream input = new FileInputStream(converted.file)) {
                MeshModel mesh = MeshModel.read(ModelStore.parserName(displayName, converted.extension), input);
                if (mesh.triangles.length == 0) throw new IOException("STEP contained no renderable solids");
            }
            return converted;
        } finally {
            if (modelPtr != 0L) Native.model_release(modelPtr);
            if (configFile.exists() && !PrinterTransport.isSymbolicLink(configFile)) configFile.delete();
            if (outputFile.exists() && !PrinterTransport.isSymbolicLink(outputFile)) outputFile.delete();
        }
    }

    private static File cacheRoot(File appFilesDir) throws IOException {
        if (PrinterTransport.isSymbolicLink(appFilesDir))
            throw new IOException("Model storage root must not be a symbolic link");
        File root = new File(appFilesDir, "models");
        if (PrinterTransport.isSymbolicLink(root) || !root.isDirectory())
            throw new IOException("Model cache is unavailable");
        return root.getCanonicalFile();
    }

    private static void validateSource(File root, File source) throws IOException {
        if (PrinterTransport.isSymbolicLink(source) || !source.isFile() || source.length() <= 0L
                || source.length() > ModelStore.MAX_MODEL_BYTES)
            throw new IOException("STEP source is not a valid cached file");
        File canonical = source.getCanonicalFile();
        String rootPath = root.getPath();
        if (!canonical.getPath().startsWith(rootPath + File.separator)
                || !canonical.getName().matches("[0-9a-f]{64}\\.step"))
            throw new IOException("STEP source must be a content-addressed cache file");
        String expectedHash = canonical.getName().substring(0, 64);
        if (!expectedHash.equals(ModelStore.sha256(canonical)))
            throw new IOException("STEP source cache hash does not match its name");
    }

    private static byte[] readBounded(File file) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) Math.min(file.length(), 4L * 1024L * 1024L));
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_OUTPUT_BYTES) throw new IOException("Converted 3MF is too large");
                bytes.write(buffer, 0, read);
            }
        }
        return bytes.toByteArray();
    }

    private static String safeBaseName(String name) {
        String value = name == null ? "step-model" : name.trim();
        if (value.length() == 0) value = "step-model";
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0) value = value.substring(slash + 1);
        value = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        return value.length() == 0 ? "step-model" : value;
    }

    private static String message(Throwable error) {
        String value = error == null ? "" : error.getMessage();
        return value == null || value.trim().length() == 0 ? "native error" : value.trim();
    }
}
