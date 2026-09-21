package com.mbaliga.alloy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Stages a complete local artifact for export and future LAN upload. */
public final class ArtifactStore {
    private static final int MAX_STAGED_ARTIFACTS = 12;
    private static final long MAX_STAGED_BYTES = 512L * 1024L * 1024L;
    private static final String ARTIFACT_SUFFIX = ".gcode.3mf";
    private ArtifactStore() { }

    public static PrinterTransport.Artifact stage(File storageDir, MeshModel mesh, Slicer.Result result,
                                                   Slicer.Config config, String displayName) throws IOException {
        return stage(storageDir, mesh, result, config, displayName, null);
    }

    public static PrinterTransport.Artifact stage(File storageDir, MeshModel mesh, Slicer.Result result,
                                                   Slicer.Config config, String displayName,
                                                   byte[] thumbnailPng) throws IOException {
        if (storageDir == null || mesh == null || result == null || config == null)
            throw new IllegalArgumentException("artifact inputs are required");
        if (PrinterTransport.isSymbolicLink(storageDir))
            throw new IOException("Artifact storage root must not be a symbolic link");
        ArtifactValidator.Report report = ArtifactValidator.validate(mesh, result, config);
        if (!report.isValid()) throw new IOException("Artifact preflight failed: " + report.summary());
        if (!storageDir.exists() && !storageDir.mkdirs()) throw new IOException("Could not create artifact storage");
        if (!storageDir.isDirectory()) throw new IOException("Artifact storage is not a directory");
        String safeName = safeName(displayName) + ARTIFACT_SUFFIX;
        File temp = new File(storageDir, "." + safeName + "." + UUID.randomUUID() + ".part");
        File target = new File(storageDir, safeName);
        if (PrinterTransport.isSymbolicLink(target))
            throw new IOException("Artifact target must not be a symbolic link");
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                GcodePackageWriter.write(mesh, result, config, output, thumbnailPng);
                output.flush();
                output.getFD().sync();
            }
            GcodePackageValidator.validate(temp, config);
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            long size = target.length();
            if (size <= 0) throw new IOException("Staged artifact is empty");
            PrinterTransport.Artifact artifact = new PrinterTransport.Artifact(target, safeName, size, sha256(target));
            prune(storageDir, target);
            return artifact;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }

    /**
     * Return whether a staged package contains the Bambu-recognized main
     * thumbnail. Foreground services may create a structurally valid artifact
     * without a GLES surface; the Activity can use this seam to re-stage one
     * with the current preview before export or LAN upload.
     */
    public static boolean hasThumbnail(File artifact) throws IOException {
        if (artifact == null || !artifact.isFile() || artifact.length() <= 0L) return false;
        try (ZipFile zip = new ZipFile(artifact)) {
            ZipEntry entry = zip.getEntry("Metadata/plate_1.png");
            if (entry == null || entry.isDirectory() || entry.getSize() <= 0L
                    || entry.getSize() > 1L * 1024L * 1024L) return false;
            try (java.io.InputStream input = zip.getInputStream(entry)) {
                byte[] signature = new byte[8];
                int offset = 0;
                while (offset < signature.length) {
                    int read = input.read(signature, offset, signature.length - offset);
                    if (read < 0) break;
                    if (read == 0) continue;
                    offset += read;
                }
                return offset == signature.length
                        && (signature[0] & 0xff) == 0x89 && signature[1] == 0x50
                        && signature[2] == 0x4e && signature[3] == 0x47
                        && signature[4] == 0x0d && signature[5] == 0x0a
                        && signature[6] == 0x1a && signature[7] == 0x0a;
            }
        } catch (java.util.zip.ZipException error) {
            return false;
        }
    }

    /** Recover a durable staged artifact only when its immutable identity matches the checkpoint. */
    public static PrinterTransport.Artifact recover(File storageDir, String displayName,
                                                     long expectedSize, String expectedSha256) throws IOException {
        if (storageDir == null || !validArtifactName(displayName) || expectedSize <= 0L
                || expectedSha256 == null || !expectedSha256.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Staged artifact checkpoint is invalid");
        if (PrinterTransport.isSymbolicLink(storageDir))
            throw new IOException("Artifact storage root must not be a symbolic link");
        File target = new File(storageDir, displayName);
        if (PrinterTransport.isSymbolicLink(target) || !target.isFile() || !target.canRead() || target.length() != expectedSize)
            throw new IOException("The staged artifact is no longer available");
        // Recovery is an A1 Mini physical-send boundary. Re-run strict
        // package safety checks before returning an artifact to a caller.
        GcodePackageValidator.validate(target, new Slicer.Config());
        String actual = sha256(target);
        if (!actual.equalsIgnoreCase(expectedSha256)) throw new IOException("The staged artifact digest does not match");
        return new PrinterTransport.Artifact(target, displayName, expectedSize, actual);
    }

    /** Recover an artifact only for the physical-printer boundary. */
    public static PrinterTransport.Artifact recoverForPhysicalPrint(File storageDir, String displayName,
                                                                     long expectedSize, String expectedSha256) throws IOException {
        PrinterTransport.Artifact artifact = recover(storageDir, displayName, expectedSize, expectedSha256);
        GcodePackageValidator.validateForPhysicalPrint(artifact.sourceFile, new Slicer.Config());
        return artifact;
    }

    public static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.US, "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String safeName(String name) {
        String value = name == null ? "alloy-job" : name.replaceAll("(?i)(\\.(stl|3mf|gcode))+$", "");
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        value = value.replace("..", "_").replaceAll("^[^A-Za-z0-9]+", "");
        if (value.length() == 0) return "alloy-job";
        return value.length() > 150 ? value.substring(0, 150) : value;
    }

    private static boolean validArtifactName(String value) {
        return value != null && value.length() <= 180 && value.matches("[A-Za-z0-9][A-Za-z0-9._-]*\\.gcode\\.3mf")
                && value.indexOf('/') < 0 && value.indexOf('\\') < 0 && !value.contains("..");
    }

    /** Retain a small durable history without ever deleting files outside the app-owned directory. */
    private static void prune(File storageDir, File keep) {
        if (PrinterTransport.isSymbolicLink(storageDir)) return;
        File[] files = storageDir.listFiles((directory, name) -> name != null && name.endsWith(ARTIFACT_SUFFIX));
        if (files == null || files.length == 0) return;
        Arrays.sort(files, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        long total = 0L;
        int retained = 0;
        for (File file : files) {
            if (PrinterTransport.isSymbolicLink(file)) continue;
            if (file.equals(keep)) {
                total += Math.max(0L, file.length());
                retained++;
                continue;
            }
            long size = Math.max(0L, file.length());
            if (retained >= MAX_STAGED_ARTIFACTS || total > MAX_STAGED_BYTES - size) {
                file.delete();
            } else {
                total += size;
                retained++;
            }
        }
    }
}
