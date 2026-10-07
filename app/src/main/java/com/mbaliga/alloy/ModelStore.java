package com.mbaliga.alloy;

import android.content.ContentResolver;
import android.content.res.AssetManager;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/**
 * App-private cache for imported model sources.
 *
 * Android document-provider grants are useful for intake but are not a durable
 * offline project boundary: a cloud provider, browser download or revoked
 * grant can make a previously saved URI unreadable. Models are therefore
 * copied into a content-addressed app-private directory before they become
 * part of a saved plate. The original URI is never modified or deleted.
 */
public final class ModelStore {
    public static final int MAX_MODELS = 32;
    public static final long MAX_MODEL_BYTES = 64L * 1024L * 1024L;
    public static final long MAX_TOTAL_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_RECENT_PROTECTED_BYTES = MAX_TOTAL_BYTES / 4L;
    private static final String DIRECTORY = "models";
    private static final String SAFE_NAME = "[0-9a-f]{64}\\.(stl|obj|3mf|step)";

    private ModelStore() { }

    public static Materialized materialize(File appFilesDir, ContentResolver resolver,
                                           Uri source, String displayName) throws IOException {
        if (appFilesDir == null || resolver == null || source == null)
            throw new IllegalArgumentException("model cache inputs are required");
        String scheme = source.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme))
            throw new IOException("Model source URI scheme is unsupported");
        if (PrinterTransport.isSymbolicLink(appFilesDir))
            throw new IOException("Model storage root must not be a symbolic link");

        File root = cacheRoot(appFilesDir);

        File sourceFile = null;
        if ("file".equalsIgnoreCase(scheme)) {
            String path = source.getPath();
            if (path == null || path.length() == 0) throw new IOException("Model file URI has no path");
            sourceFile = new File(path);
            if (PrinterTransport.isSymbolicLink(sourceFile))
                throw new IOException("Model source must not be a symbolic link");
            if (!sourceFile.isFile() || !sourceFile.canRead())
                throw new IOException("Model source is not readable");
            if (isInside(root, sourceFile)) {
                if (!sourceFile.getName().matches(SAFE_NAME))
                    throw new IOException("Model cache file name is invalid");
                Materialized existing = describe(sourceFile);
                String expected = sourceFile.getName().substring(0, 64);
                if (!expected.equals(existing.sha256))
                    throw new IOException("Model cache content hash does not match its name");
                return existing;
            }
        }

        try (InputStream input = sourceFile == null ? resolver.openInputStream(source) : new FileInputStream(sourceFile)) {
            return materializeStream(root, input, extensionHint(displayName));
        }
    }

    /** Materialize a trusted, bundled example so it can be saved in a portable project. */
    public static Materialized materializeAsset(File appFilesDir, AssetManager assets,
                                                String assetPath) throws IOException {
        if (appFilesDir == null || assets == null || assetPath == null)
            throw new IllegalArgumentException("bundled model inputs are required");
        String path = assetPath.trim().replace('\\', '/');
        if (path.length() == 0 || path.startsWith("/") || path.contains(".."))
            throw new IOException("Bundled model path is invalid");
        File root = cacheRoot(appFilesDir);
        try (InputStream input = assets.open(path)) {
            return materializeStream(root, input, extensionHint(path));
        }
    }

    /** Materialize validated, locally generated geometry into the same durable cache. */
    public static Materialized materializeGenerated(File appFilesDir, byte[] source) throws IOException {
        if (appFilesDir == null || source == null || source.length == 0 || source.length > MAX_MODEL_BYTES)
            throw new IOException("Generated model is empty or exceeds the offline cache limit");
        return materializeStream(cacheRoot(appFilesDir), new java.io.ByteArrayInputStream(source), null);
    }

    /** Materialize a bounded bundle entry while retaining its trusted entry-name hint. */
    public static Materialized materializeGenerated(File appFilesDir, byte[] source,
                                                     String displayName) throws IOException {
        if (appFilesDir == null || source == null || source.length == 0 || source.length > MAX_MODEL_BYTES)
            throw new IOException("Generated model is empty or exceeds the offline cache limit");
        return materializeStream(cacheRoot(appFilesDir), new java.io.ByteArrayInputStream(source),
                extensionHint(displayName));
    }

    /** Stream a bounded ZIP member directly into the content-addressed cache; caller owns source. */
    static Materialized materializeGenerated(File appFilesDir, InputStream source,
                                              String displayName) throws IOException {
        if (appFilesDir == null || source == null)
            throw new IOException("Generated model inputs are required");
        return materializeStream(cacheRoot(appFilesDir), source, extensionHint(displayName));
    }

    private static File cacheRoot(File appFilesDir) throws IOException {
        if (PrinterTransport.isSymbolicLink(appFilesDir))
            throw new IOException("Model storage root must not be a symbolic link");
        File root = new File(appFilesDir, DIRECTORY);
        if (PrinterTransport.isSymbolicLink(root))
            throw new IOException("Model cache directory must not be a symbolic link");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create model cache");
        if (!root.isDirectory()) throw new IOException("Model cache is not a directory");
        return root;
    }

    private static Materialized materializeStream(File root, InputStream input,
                                                  String extensionHint) throws IOException {
        if (root == null || input == null) throw new IOException("Model source could not be opened");
        File temporary = new File(root, "." + UUID.randomUUID() + ".part");
        long bytes = 0L;
        String digest;
        try {
            MessageDigest hash = sha256Digest();
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException("Model import cancelled");
                    if (read == 0) continue;
                    bytes += read;
                    if (bytes > MAX_MODEL_BYTES) throw new IOException("Model exceeds the 64 MB offline cache limit");
                    hash.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
                output.flush();
                output.getFD().sync();
            }
            if (bytes == 0L) throw new IOException("Model source is empty");
            digest = hex(hash.digest());
            // Provider names are metadata, not a trustworthy content type. A
            // browser download can call an OBJ "model.stl", while a cloud
            // provider can omit the extension entirely. Detect the bytes so
            // the cached suffix also drives the correct parser later.
            String extension = detectExtension(temporary, extensionHint);
            File target = new File(root, digest + extension);
            if (PrinterTransport.isSymbolicLink(target))
                throw new IOException("Model cache target must not be a symbolic link");
            if (target.exists()) {
                if (!target.isFile() || target.length() != bytes || !digest.equals(sha256(target)))
                    throw new IOException("Model cache contains a corrupted content-addressed file");
                temporary.delete();
                return new Materialized(target, bytes, digest, extension, false);
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (PrinterTransport.isSymbolicLink(target) || !target.isFile() || target.length() != bytes)
                throw new IOException("Model cache write could not be verified");
            return new Materialized(target, bytes, digest, extension, true);
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    /** Keep the cache bounded while preserving every model referenced by saved plates. */
    public static void prune(File appFilesDir, ArrayList<PlateStore.Plate> protectedPlates) {
        prune(appFilesDir, protectedPlates, null, null);
    }

    /** Keep historical model snapshots alive while an undo/redo cursor can still reach them. */
    public static void prune(File appFilesDir, ArrayList<PlateStore.Plate> protectedPlates,
                             ArrayList<PlateStore.Plate> historicalPlates) {
        prune(appFilesDir, protectedPlates, historicalPlates, null);
    }

    /** Keep validated recent-shelf entries alive as well as project/history sources. */
    public static void prune(File appFilesDir, ArrayList<PlateStore.Plate> protectedPlates,
                             ArrayList<PlateStore.Plate> historicalPlates,
                             ArrayList<Uri> recentModelUris) {
        if (appFilesDir == null || PrinterTransport.isSymbolicLink(appFilesDir)) return;
        File root = new File(appFilesDir, DIRECTORY);
        if (PrinterTransport.isSymbolicLink(root) || !root.isDirectory()) return;
        File[] temporaryFiles = root.listFiles(file -> file != null && !PrinterTransport.isSymbolicLink(file)
                && file.isFile() && file.getName().startsWith(".") && file.getName().endsWith(".part"));
        if (temporaryFiles != null) for (File file : temporaryFiles) file.delete();
        HashSet<String> protectedPaths = new HashSet<>();
        addProtectedPaths(root, protectedPaths, protectedPlates);
        addProtectedPaths(root, protectedPaths, historicalPlates);
        addProtectedUris(root, protectedPaths, recentModelUris);
        BatchSliceRequestStore.addProtectedModelPaths(appFilesDir, protectedPaths);
        File[] files = root.listFiles(file -> file != null && !PrinterTransport.isSymbolicLink(file)
                && file.isFile() && file.getName().matches(SAFE_NAME));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        long total = 0L;
        int retained = 0;
        for (File file : files) {
            long size = Math.max(0L, file.length());
            boolean protectedFile = protectedPaths.contains(canonical(file));
            boolean overBudget = size > MAX_TOTAL_BYTES || retained >= MAX_MODELS
                    || total > MAX_TOTAL_BYTES - Math.min(size, MAX_TOTAL_BYTES);
            if (!protectedFile && overBudget) {
                file.delete();
                continue;
            }
            retained++;
            total = total > Long.MAX_VALUE - size ? Long.MAX_VALUE : total + size;
        }
    }

    private static void addProtectedPaths(File root, HashSet<String> protectedPaths,
                                          ArrayList<PlateStore.Plate> plates) {
        if (plates == null) return;
        for (PlateStore.Plate plate : plates) {
            if (plate == null || plate.uris == null) continue;
            for (Uri uri : plate.uris) {
                if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) continue;
                try {
                    File file = new File(uri.getPath());
                    if (isInside(root, file)) protectedPaths.add(file.getCanonicalPath());
                } catch (Exception ignored) { }
            }
        }
    }

    private static void addProtectedUris(File root, HashSet<String> protectedPaths,
                                         ArrayList<Uri> uris) {
        if (uris == null) return;
        long reserved = 0L;
        for (Uri uri : uris) {
            if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) continue;
            try {
                File file = new File(uri.getPath());
                if (isInside(root, file) && file.isFile() && !PrinterTransport.isSymbolicLink(file)
                        && file.getName().matches(SAFE_NAME)) {
                    long size = Math.max(0L, file.length());
                    if (size <= MAX_RECENT_PROTECTED_BYTES
                            && reserved <= MAX_RECENT_PROTECTED_BYTES - size) {
                        protectedPaths.add(file.getCanonicalPath());
                        reserved += size;
                    }
                }
            } catch (Exception ignored) { }
        }
    }

    static String sha256(File file) throws IOException {
        if (file == null || PrinterTransport.isSymbolicLink(file) || !file.isFile())
            throw new IOException("Model file is not readable");
        MessageDigest digest = sha256Digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static Materialized describe(File file) throws IOException {
        if (file.length() <= 0L || file.length() > MAX_MODEL_BYTES)
            throw new IOException("Model cache file size is invalid");
        // Re-detect cached files as well. This keeps projects created by an
        // older filename-based cache readable after an app upgrade.
        return new Materialized(file, file.length(), sha256(file), detectExtension(file));
    }

    static String parserName(String displayName, String detectedExtension) {
        String name = displayName == null || displayName.trim().length() == 0 ? "model" : displayName.trim();
        String extension = detectedExtension == null ? ".stl" : detectedExtension.toLowerCase(Locale.US);
        if (!extension.equals(".stl") && !extension.equals(".obj")
                && !extension.equals(".3mf") && !extension.equals(".step")) extension = ".stl";
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".step")) name = name.substring(0, name.length() - 5);
        else if (lower.endsWith(".3mf") || lower.endsWith(".obj") || lower.endsWith(".stl"))
            name = name.substring(0, name.length() - 4);
        return name + extension;
    }

    /** Content signature used by both direct imports and bounded ZIP entries. */
    static String detectExtension(File data) throws IOException {
        return detectExtension(data, null);
    }

    private static String detectExtension(File data, String hintedExtension) throws IOException {
        byte[] sample = new byte[64 * 1024];
        int length = 0;
        try (InputStream input = new FileInputStream(data)) {
            while (length < sample.length) {
                int read = input.read(sample, length, sample.length - length);
                if (read < 0) break;
                if (read == 0) continue;
                length += read;
            }
        }
        if (length >= 4 && sample[0] == 'P' && sample[1] == 'K') return ".3mf";
        String text = new String(sample, 0, length, StandardCharsets.UTF_8);
        // STEP Part 21 files are text-based and frequently arrive without a
        // useful provider extension. Require the two structural sections so a
        // plain ASCII STL/OBJ is not accidentally sent to the OCCT importer.
        String upper = text.toUpperCase(Locale.US);
        if (upper.contains("ISO-10303-21;") && upper.contains("HEADER;") && upper.contains("DATA;"))
            return ".step";
        // Do not limit OBJ detection to the initial probe. Exporters commonly
        // write millions of vertices before the first face (the supplied
        // Olympic recurve bow places its first face roughly 3.2 MB in). Scan
        // bounded lines from the already-cached file and stop as soon as both
        // OBJ directives are found. No line is allowed to grow without bound.
        if (containsObjDirectives(data)) return ".obj";
        if (hintedExtension != null) return hintedExtension;
        return ".stl";
    }

    private static boolean containsObjDirectives(File data) throws IOException {
        boolean vertex = false;
        boolean face = false;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                new FileInputStream(data), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 1 * 1024 * 1024) return false;
                String value = line.trim();
                if (value.startsWith("v ") || value.startsWith("v\t")) vertex = true;
                if (value.startsWith("f ") || value.startsWith("f\t")) face = true;
                if (vertex && face) return true;
            }
        }
        return false;
    }

    private static String extensionHint(String name) {
        if (name == null) return null;
        String lower = name.trim().toLowerCase(Locale.US);
        if (lower.endsWith(".obj")) return ".obj";
        if (lower.endsWith(".3mf")) return ".3mf";
        if (lower.endsWith(".step") || lower.endsWith(".stp")) return ".step";
        if (lower.endsWith(".stl")) return ".stl";
        return null;
    }

    private static boolean isInside(File root, File candidate) {
        if (candidate == null) return false;
        String rootPath = canonical(root);
        String candidatePath = canonical(candidate);
        return candidatePath.equals(rootPath) || candidatePath.startsWith(rootPath + File.separator);
    }

    private static String canonical(File file) {
        try { return file.getCanonicalPath(); }
        catch (IOException error) { return file.getAbsolutePath(); }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception error) { throw new IOException("SHA-256 is unavailable", error); }
    }

    private static String hex(byte[] digest) {
        StringBuilder output = new StringBuilder(digest.length * 2);
        for (byte value : digest) output.append(String.format(Locale.US, "%02x", value & 0xff));
        return output.toString();
    }

    public static final class Materialized {
        public final Uri uri;
        public final File file;
        public final long sizeBytes;
        public final String sha256;
        /** The content-detected parser suffix, independent of provider metadata. */
        public final String extension;
        /** True only when this operation created a new content-addressed cache entry. */
        final boolean cacheEntryCreated;

        private Materialized(File file, long sizeBytes, String sha256) {
            this(file, sizeBytes, sha256, null, false);
        }

        private Materialized(File file, long sizeBytes, String sha256, String detectedExtension) {
            this(file, sizeBytes, sha256, detectedExtension, false);
        }

        private Materialized(File file, long sizeBytes, String sha256,
                             String detectedExtension, boolean cacheEntryCreated) {
            this.file = file.getAbsoluteFile();
            this.uri = Uri.fromFile(this.file);
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
            this.cacheEntryCreated = cacheEntryCreated;
            String name = detectedExtension == null ? this.file.getName().toLowerCase(Locale.US) : detectedExtension;
            this.extension = name.endsWith(".3mf") ? ".3mf"
                    : name.endsWith(".obj") ? ".obj"
                    : name.endsWith(".step") || name.endsWith(".stp") ? ".step" : ".stl";
        }
    }}
