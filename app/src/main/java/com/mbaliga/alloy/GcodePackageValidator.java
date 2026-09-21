package com.mbaliga.alloy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Bounded structure check for the package that leaves the phone. */
public final class GcodePackageValidator {
    private static final long MAX_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final long MAX_PACKAGE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_VALIDATION_TEXT_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_THUMBNAIL_BYTES = 1L * 1024L * 1024L;
    private static final int MAX_ENTRY_COUNT = 4096;
    private GcodePackageValidator() { }

    public static void validate(File file) throws IOException {
        validate(file, null);
    }

    /**
     * Validate a package with the same build-volume and safe-shutdown policy
     * used when it was staged. This overload is used at physical-send and
     * recovery boundaries; the legacy overload remains structural-only for
     * inspecting arbitrary Bambu packages.
     */
    public static void validate(File file, Slicer.Config safetyConfig) throws IOException {
        validateInternal(file, safetyConfig, false);
    }

    /**
     * Validate the package at the irreversible physical-printer boundary.
     * Structural and motion safety are necessary but not sufficient: the
     * package must also carry a verified engine claim, and any requested
     * support must have been produced by the approved support engine.
     */
    public static void validateForPhysicalPrint(File file, Slicer.Config safetyConfig) throws IOException {
        validateInternal(file, safetyConfig, true);
    }

    private static void validateInternal(File file, Slicer.Config safetyConfig,
                                         boolean physicalPrintBoundary) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0) throw new IOException("Package is missing or empty");
        if (file.length() > MAX_PACKAGE_BYTES) throw new IOException("Package exceeds the 256 MB limit");
        Set<String> names = new HashSet<>();
        Set<String> required = new HashSet<>();
        required.add("[Content_Types].xml");
        required.add("_rels/.rels");
        required.add("3D/_rels/3dmodel.model.rels");
        required.add("3D/3dmodel.model");
        required.add("3D/Objects/object_1.model");
        required.add("Metadata/plate_1.gcode");
        required.add("Metadata/plate_1.json");
        required.add("Metadata/slice_info.config");
        required.add("Metadata/model_settings.config");
        required.add("Metadata/cut_information.xml");
        required.add("Metadata/filament_sequence.json");
        String actualGcodeMd5 = null;
        String expectedGcodeMd5 = null;
        try (ZipInputStream zip = new ZipInputStream(new FileInputStream(file), StandardCharsets.UTF_8)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int entryCount = 0;
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRY_COUNT) throw new IOException("Package contains too many ZIP entries");
                String name = entry.getName();
                if (name == null || name.length() > 512 || name.startsWith("/") || name.contains("..")
                        || name.indexOf('\\') >= 0 || hasControlCharacter(name) || !names.add(name))
                    throw new IOException("Package contains an unsafe or duplicate ZIP entry");
                long entryBytes = 0L;
                boolean gcodeEntry = "Metadata/plate_1.gcode".equals(name);
                boolean md5Entry = "Metadata/plate_1.gcode.md5".equals(name);
                boolean pngEntry = name.toLowerCase(java.util.Locale.US).endsWith(".png");
                byte[] pngHeader = pngEntry ? new byte[8] : null;
                int pngHeaderBytes = 0;
                StringBuilder text = !gcodeEntry && isTextEntry(name) ? new StringBuilder() : null;
                GcodeSafetyValidator.Stream gcodeScanner = gcodeEntry
                        ? new GcodeSafetyValidator.Stream(safetyConfig) : null;
                MessageDigest gcodeDigest = gcodeEntry ? md5Digest() : null;
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    entryBytes += read;
                    total += read;
                    if (entryBytes > MAX_ENTRY_BYTES || total > MAX_PACKAGE_BYTES)
                        throw new IOException("Package decompression limit exceeded");
                    if (pngEntry) {
                        if (entryBytes > MAX_THUMBNAIL_BYTES)
                            throw new IOException("Package thumbnail exceeds the 1 MB validation limit");
                        int copy = Math.min(read, pngHeader.length - pngHeaderBytes);
                        if (copy > 0) {
                            System.arraycopy(buffer, 0, pngHeader, pngHeaderBytes, copy);
                            pngHeaderBytes += copy;
                        }
                    }
                    if (gcodeEntry) {
                        gcodeScanner.accept(buffer, 0, read);
                        gcodeDigest.update(buffer, 0, read);
                    } else if (text != null) {
                        if (entryBytes > MAX_VALIDATION_TEXT_BYTES)
                            throw new IOException("Package text metadata exceeds the 4 MB validation limit");
                        text.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
                if (pngEntry && !isPng(pngHeader, pngHeaderBytes))
                    throw new IOException("Package thumbnail is not a PNG: " + name);
                if (required.contains(name)) {
                    if (entryBytes == 0) throw new IOException("Required package entry is empty: " + name);
                    if (gcodeEntry) {
                        GcodeSafetyValidator.Report report = gcodeScanner.finish();
                        if (!report.isValid()) throw new IOException("Package G-code safety preflight failed: " + report.summary());
                        actualGcodeMd5 = hex(gcodeDigest.digest());
                    }
                    if (text != null && "[Content_Types].xml".equals(name)) validateContentTypes(text.toString());
                    if (text != null && "_rels/.rels".equals(name)) validateRootRelationships(text.toString());
                    if (text != null && "3D/_rels/3dmodel.model.rels".equals(name)) validateModelRelationships(text.toString());
                    if (text != null && "3D/3dmodel.model".equals(name)) validatePackageModel(text.toString());
                    if (text != null && "3D/Objects/object_1.model".equals(name)) validateObjectModel(text.toString());
                    if (text != null && "Metadata/plate_1.json".equals(name)) validateMetadata(text.toString(), physicalPrintBoundary);
                    if (text != null && "Metadata/slice_info.config".equals(name)) validateConfig(text.toString());
                    if (text != null && "Metadata/model_settings.config".equals(name)) validateModelSettings(text.toString());
                    if (text != null && "Metadata/cut_information.xml".equals(name)) validateCutInformation(text.toString());
                    if (text != null && "Metadata/filament_sequence.json".equals(name)) validateFilamentSequence(text.toString());
                    required.remove(name);
                }
                if (md5Entry) expectedGcodeMd5 = text == null ? null : text.toString().trim();
                if (text != null && "Metadata/model_settings.config".equals(name)) validateModelSettings(text.toString());
                if (text != null && "Metadata/project_settings.config".equals(name)) validateProjectSettings(text.toString());
                if (text != null && "Metadata/print_profile.config".equals(name)) validatePrintProfile(text.toString());
            }
        }
        if (!required.isEmpty()) throw new IOException("Package is missing required entries: " + required);
        if (expectedGcodeMd5 != null && (!expectedGcodeMd5.matches("[0-9a-fA-F]{32}")
                || actualGcodeMd5 == null || !expectedGcodeMd5.equalsIgnoreCase(actualGcodeMd5)))
            throw new IOException("Package G-code MD5 does not match its toolpath");
    }

    private static boolean isTextEntry(String name) {
        return name.endsWith(".gcode") || name.endsWith(".json") || name.endsWith(".config")
                || name.endsWith(".xml") || name.endsWith(".model") || name.endsWith(".rels")
                || name.endsWith(".md5");
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 || character == 0x7f) return true;
        }
        return false;
    }

    private static boolean isPng(byte[] value, int length) {
        return value != null && length >= 8
                && (value[0] & 0xff) == 0x89 && value[1] == 0x50 && value[2] == 0x4e && value[3] == 0x47
                && value[4] == 0x0d && value[5] == 0x0a && value[6] == 0x1a && value[7] == 0x0a;
    }

    private static void validateContentTypes(String contentTypes) throws IOException {
        boolean modelType = contentTypes.contains("3dmodel.model")
                || contentTypes.contains("Extension=\"model\"");
        boolean gcodeType = contentTypes.contains("Metadata/plate_1.gcode")
                || contentTypes.contains("Extension=\"gcode\"");
        if (!modelType || !gcodeType || !contentTypes.contains("application/vnd.ms-package.3dmanufacturing-3dmodel+xml"))
            throw new IOException("Package content types do not describe the model and G-code entries");
    }

    private static void validateRootRelationships(String relationships) throws IOException {
        if (!relationships.contains("3dmodel") || !relationships.contains("/3D/3dmodel.model"))
            throw new IOException("Package root relationships do not target the 3D model");
    }

    private static void validateModelRelationships(String relationships) throws IOException {
        if (!relationships.contains("Relationships") || !relationships.contains("/3D/Objects/object_1.model")
                || !relationships.contains("3dmodel"))
            throw new IOException("Package model relationships are malformed");
    }

    private static void validatePackageModel(String model) throws IOException {
        if (!model.contains("<model") || !model.contains("<resources") || !model.contains("<object")
                || !model.contains("<components") || !model.contains("/3D/Objects/object_1.model")
                || !model.contains("<build") || !model.contains("<item"))
            throw new IOException("Package 3D model wrapper is structurally incomplete");
    }

    private static void validateObjectModel(String model) throws IOException {
        if (!model.contains("<model") || !model.contains("<resources") || !model.contains("<object")
                || !model.contains("<mesh") || !model.contains("<vertices") || !model.contains("<triangles")
                || !model.contains("<build"))
            throw new IOException("Package related object model is structurally incomplete");
    }

    private static void validateMetadata(String metadata, boolean physicalPrintBoundary) throws IOException {
        String[] fields = {"\"engine_verified\":", "\"printer\":", "\"material\":",
                "\"layer_height_mm\":", "\"first_layer_height_mm\":", "\"infill_percent\":",
                "\"perimeters\":", "\"top_layers\":", "\"bottom_layers\":", "\"supports\":",
                "\"support_threshold_degrees\":", "\"nozzle_temperature_c\":",
                "\"first_layer_nozzle_temperature_c\":", "\"bed_temperature_c\":",
                "\"first_layer_bed_temperature_c\":", "\"extrusion_multiplier\":",
                "\"max_volumetric_speed_mm3_s\":"};
        for (String field : fields) if (!metadata.contains(field)) throw new IOException("Package metadata is missing " + field);
        if (metadata.contains("\"source\":\"orca-mobile-native\"")
                || metadata.contains("\"source\":\"prusa-slicebeam-native\""))
            if (!metadata.contains("\"template_policy\":\"" + A1MiniTemplatePolicy.ID + "\""))
                throw new IOException("Native package is missing the reviewed A1 Mini template policy identity");
        if (physicalPrintBoundary) {
            if (!metadata.contains("\"engine_verified\":true"))
                throw new IOException("Physical printing requires a package from a verified slicer engine");
            if (!metadata.contains("\"support_parity_verified\":true"))
                throw new IOException("Physical printing requires verified support-engine metadata");
            if (metadata.contains("\"supports\":true")
                    && !metadata.contains("\"support_engine\":\"" + SupportEngineStatus.BAMBU_TREESUPPORT3D_ENGINE_ID + "\""))
                throw new IOException("Support-enabled package is not produced by the approved Bambu TreeSupport3D engine");
        }
    }

    private static void validateConfig(String config) throws IOException {
        if (config.contains("<config") && config.contains("<plate")
                && config.contains("key=\"index\"") && config.contains("key=\"gcode_file\"")
                && config.contains("Metadata/plate_1.gcode")) return;
        if (!config.contains("[print]")) throw new IOException("Package config is missing canonical slice-info XML");
        String[] fields = {"engine_verified=", "layer_height_mm=", "first_layer_height_mm=",
                "infill_percent=", "perimeters=", "top_layers=", "bottom_layers=", "supports=",
                "support_threshold_degrees=", "nozzle_temperature_c=", "first_layer_nozzle_temperature_c=",
                "bed_temperature_c=", "first_layer_bed_temperature_c=", "extrusion_multiplier=",
                "max_volumetric_speed_mm3_s="};
        for (String field : fields) if (!config.contains(field)) throw new IOException("Package config is missing " + field);
    }

    private static void validateModelSettings(String config) throws IOException {
        if (!config.contains("<config") || !config.contains("<object") || !config.contains("<part"))
            throw new IOException("Package model settings are malformed");
    }

    private static void validateCutInformation(String config) throws IOException {
        if (!config.contains("<objects") || !config.contains("<object") || !config.contains("<cut_id")
                || !config.contains("connectors_cnt=\"0\""))
            throw new IOException("Package cut information is malformed");
    }

    private static void validateFilamentSequence(String json) throws IOException {
        if (!json.contains("\"plate_1\"") || !json.contains("\"sequence\"")
                || !json.contains("\"nozzle_sequence\"") || !json.contains("\"optimal_assignment\""))
            throw new IOException("Package filament sequence is malformed");
    }

    private static void validateProjectSettings(String config) throws IOException {
        if (!config.contains("\"type\":\"project\"") || !config.contains("\"printer_model\":"))
            throw new IOException("Package project settings are malformed");
    }

    private static void validatePrintProfile(String config) throws IOException {
        if (!config.contains("layer_height") || !config.contains("nozzle_diameter")
                || !config.contains("filament_diameter"))
            throw new IOException("Package print profile is incomplete");
    }

    private static MessageDigest md5Digest() throws IOException {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("MD5 is unavailable", error);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return out.toString();
    }
}
