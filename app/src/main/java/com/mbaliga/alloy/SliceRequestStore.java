package com.mbaliga.alloy;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable, app-private input snapshot consumed by SliceJobService. */
public final class SliceRequestStore {
    private static final String DIRECTORY = "slice-jobs";
    private static final String MODEL = "model.stl";
    private static final String CONFIG = "config.json";
    private static final long MAX_REQUEST_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_CONFIG_BYTES = 64 * 1024;

    private SliceRequestStore() { }

    public static String newJobId() { return UUID.randomUUID().toString(); }

    public static String write(File filesDir, String jobId, MeshModel model, Slicer.Config config, String displayName) throws IOException {
        if (filesDir == null || model == null || config == null || !validJobId(jobId))
            throw new IllegalArgumentException("slice request inputs are invalid");
        Slicer.validate(model, config);
        if (PrinterTransport.isSymbolicLink(filesDir)) throw new IOException("Slice storage root must not be a symbolic link");
        File root = new File(filesDir, DIRECTORY);
        ensureDirectory(root, "Slice storage directory");
        File jobDir = jobDirectory(filesDir, jobId);
        ensureDirectory(jobDir, "Slice request directory");
        File modelTarget = new File(jobDir, MODEL);
        File configTarget = new File(jobDir, CONFIG);
        if (PrinterTransport.isSymbolicLink(modelTarget) || PrinterTransport.isSymbolicLink(configTarget))
            throw new IOException("Slice request target must not be a symbolic link");
        writeBinaryStl(model, modelTarget);
        writeConfig(config, displayName, fingerprint(model), configFingerprint(config), configTarget);
        return fingerprint(model);
    }

    public static Request read(File filesDir, String jobId) throws IOException {
        if (filesDir == null || !validJobId(jobId)) throw new IOException("Slice request identity is invalid");
        File jobDir = jobDirectory(filesDir, jobId);
        if (PrinterTransport.isSymbolicLink(jobDir) || !jobDir.isDirectory()) throw new IOException("Slice request is missing");
        File modelFile = new File(jobDir, MODEL);
        File configFile = new File(jobDir, CONFIG);
        if (PrinterTransport.isSymbolicLink(modelFile) || PrinterTransport.isSymbolicLink(configFile)
                || !modelFile.isFile() || !configFile.isFile() || modelFile.length() <= 84L
                || modelFile.length() > MAX_REQUEST_BYTES || configFile.length() <= 0L || configFile.length() > MAX_CONFIG_BYTES)
            throw new IOException("Slice request files are invalid");
        MeshModel model;
        try (InputStream input = new BufferedInputStream(new FileInputStream(modelFile))) {
            model = MeshModel.read("model.stl", input);
        }
        JSONObject encoded;
        try {
            encoded = new JSONObject(new String(readBytes(configFile, MAX_CONFIG_BYTES), StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IOException("Slice request recipe is invalid", error);
        }
        String displayName = boundedName(encoded.optString("display_name", "model"));
        String expectedHash = encoded.optString("model_sha256", "");
        if (!expectedHash.matches("[0-9a-fA-F]{64}") || !expectedHash.equalsIgnoreCase(fingerprint(model)))
            throw new IOException("Slice request model identity does not match");
        Slicer.Config config = parseConfig(encoded);
        Slicer.validate(model, config);
        String expectedRecipeHash = encoded.optString("recipe_sha256", "");
        if (expectedRecipeHash.length() > 0) {
            if (!expectedRecipeHash.matches("[0-9a-fA-F]{64}")
                    || !expectedRecipeHash.equalsIgnoreCase(configFingerprint(config)))
                throw new IOException("Slice request recipe identity does not match");
        } else {
            // Requests written before recipe identity was introduced remain
            // readable; all newly written requests include the digest.
            expectedRecipeHash = configFingerprint(config);
        }
        return new Request(model, config, displayName, expectedHash.toLowerCase(java.util.Locale.US),
                expectedRecipeHash.toLowerCase(java.util.Locale.US));
    }

    public static File jobDirectory(File filesDir, String jobId) throws IOException {
        if (filesDir == null || !validJobId(jobId)) throw new IOException("Slice job path is invalid");
        File root = new File(filesDir, DIRECTORY);
        if (PrinterTransport.isSymbolicLink(root)) throw new IOException("Slice storage directory must not be a symbolic link");
        File directory = new File(root, jobId.toLowerCase(java.util.Locale.US));
        if (PrinterTransport.isSymbolicLink(directory)) throw new IOException("Slice job directory must not be a symbolic link");
        return directory;
    }

    public static String fingerprint(MeshModel model) throws IOException {
        if (model == null || model.vertices == null || model.triangles == null) throw new IOException("Model is missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
            updateInt(digest, buffer, model.vertices.length);
            for (float value : model.vertices) updateInt(digest, buffer, Float.floatToIntBits(value));
            updateInt(digest, buffer, model.triangles.length);
            for (int value : model.triangles) updateInt(digest, buffer, value);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    /** Stable identity for the typed recipe plus the projected native profile map. */
    static String configFingerprint(Slicer.Config config) throws IOException {
        if (config == null) throw new IOException("Slice recipe is missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFloat(digest, config.layerHeight); updateFloat(digest, config.firstLayerHeight);
            updateFloat(digest, config.nozzle); updateFloat(digest, config.filamentDiameter);
            updateFloat(digest, config.infill); updateFloat(digest, config.bedX);
            updateFloat(digest, config.bedY); updateFloat(digest, config.bedZ);
            updateText(digest, config.printer); updateText(digest, config.filament);
            updateFloat(digest, config.nozzleTemperature); updateFloat(digest, config.firstLayerNozzleTemperature);
            updateFloat(digest, config.bedTemperature); updateFloat(digest, config.firstLayerBedTemperature);
            updateFloat(digest, config.extrusionMultiplier); updateFloat(digest, config.maxVolumetricSpeed);
            updateFloat(digest, config.travelSpeed); updateFloat(digest, config.outerWallSpeed);
            updateFloat(digest, config.innerWallSpeed); updateFloat(digest, config.infillSpeed);
            updateFloat(digest, config.initialLayerSpeed); updateFloat(digest, config.fanMinPercent);
            updateFloat(digest, config.fanMaxPercent); updateInt(digest, config.supports ? 1 : 0);
            updateFloat(digest, config.supportThresholdDegrees); updateInt(digest, config.perimeters);
            updateInt(digest, config.topLayers); updateInt(digest, config.bottomLayers);
            Map<String, String> nativeSettings = new TreeMap<>(config.nativeSettings);
            updateInt(digest, nativeSettings.size());
            for (Map.Entry<String, String> entry : nativeSettings.entrySet()) {
                updateText(digest, entry.getKey());
                updateText(digest, entry.getValue());
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static void writeBinaryStl(MeshModel model, File target) throws IOException {
        long triangles = model.triangles.length / 3L;
        if (triangles <= 0L || 84L > MAX_REQUEST_BYTES - triangles * 50L)
            throw new IOException("Slice request model is too large");
        File temporary = new File(target.getParentFile(), "." + target.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(new byte[80]);
            writeIntLE(output, (int) triangles);
            for (int index = 0; index < model.triangles.length; index += 3) {
                int ia = model.triangles[index] * 3;
                int ib = model.triangles[index + 1] * 3;
                int ic = model.triangles[index + 2] * 3;
                float ax = model.vertices[ia], ay = model.vertices[ia + 1], az = model.vertices[ia + 2];
                float bx = model.vertices[ib], by = model.vertices[ib + 1], bz = model.vertices[ib + 2];
                float cx = model.vertices[ic], cy = model.vertices[ic + 1], cz = model.vertices[ic + 2];
                float ux = bx - ax, uy = by - ay, uz = bz - az;
                float vx = cx - ax, vy = cy - ay, vz = cz - az;
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0f) { nx /= length; ny /= length; nz /= length; }
                writeFloatLE(output, nx); writeFloatLE(output, ny); writeFloatLE(output, nz);
                writeFloatLE(output, ax); writeFloatLE(output, ay); writeFloatLE(output, az);
                writeFloatLE(output, bx); writeFloatLE(output, by); writeFloatLE(output, bz);
                writeFloatLE(output, cx); writeFloatLE(output, cy); writeFloatLE(output, cz);
                output.write(0); output.write(0);
            }
            output.flush(); output.getFD().sync();
        }
        moveIntoPlace(temporary, target);
    }

    private static void writeConfig(Slicer.Config config, String displayName, String modelHash,
                                    String recipeHash, File target) throws IOException {
        JSONObject value = new JSONObject();
        try {
            value.put("display_name", boundedName(displayName));
            value.put("model_sha256", modelHash);
            value.put("recipe_sha256", recipeHash);
            value.put("layer_height", config.layerHeight).put("first_layer_height", config.firstLayerHeight)
                    .put("nozzle", config.nozzle).put("filament_diameter", config.filamentDiameter)
                    .put("infill", config.infill).put("bed_x", config.bedX).put("bed_y", config.bedY).put("bed_z", config.bedZ)
                    .put("printer", config.printer).put("filament", config.filament)
                    .put("nozzle_temperature", config.nozzleTemperature).put("first_layer_nozzle_temperature", config.firstLayerNozzleTemperature)
                    .put("bed_temperature", config.bedTemperature).put("first_layer_bed_temperature", config.firstLayerBedTemperature)
                    .put("extrusion_multiplier", config.extrusionMultiplier).put("max_volumetric_speed", config.maxVolumetricSpeed)
                    .put("travel_speed", config.travelSpeed).put("outer_wall_speed", config.outerWallSpeed)
                    .put("inner_wall_speed", config.innerWallSpeed).put("infill_speed", config.infillSpeed)
                    .put("initial_layer_speed", config.initialLayerSpeed).put("fan_min", config.fanMinPercent)
                    .put("fan_max", config.fanMaxPercent).put("supports", config.supports)
                    .put("support_threshold", config.supportThresholdDegrees).put("perimeters", config.perimeters)
                    .put("top_layers", config.topLayers).put("bottom_layers", config.bottomLayers)
                    .put("native_settings", NativeSettings.encode(config.nativeSettings));
        } catch (Exception error) { throw new IOException("Slice request recipe could not be encoded", error); }
        File temporary = new File(target.getParentFile(), "." + target.getName() + ".part");
        try (FileOutputStream raw = new FileOutputStream(temporary);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8))) {
            output.write(value.toString()); output.flush(); raw.getFD().sync();
        }
        moveIntoPlace(temporary, target);
    }

    private static Slicer.Config parseConfig(JSONObject value) throws IOException {
        Slicer.Config config = new Slicer.Config();
        config.layerHeight = number(value, "layer_height", config.layerHeight);
        config.firstLayerHeight = number(value, "first_layer_height", config.firstLayerHeight);
        config.nozzle = number(value, "nozzle", config.nozzle);
        config.filamentDiameter = number(value, "filament_diameter", config.filamentDiameter);
        config.infill = number(value, "infill", config.infill);
        config.bedX = number(value, "bed_x", config.bedX); config.bedY = number(value, "bed_y", config.bedY); config.bedZ = number(value, "bed_z", config.bedZ);
        config.printer = boundedText(value.optString("printer", "")); config.filament = boundedText(value.optString("filament", ""));
        config.nozzleTemperature = number(value, "nozzle_temperature", config.nozzleTemperature);
        config.firstLayerNozzleTemperature = number(value, "first_layer_nozzle_temperature", config.firstLayerNozzleTemperature);
        config.bedTemperature = number(value, "bed_temperature", config.bedTemperature);
        config.firstLayerBedTemperature = number(value, "first_layer_bed_temperature", config.firstLayerBedTemperature);
        config.extrusionMultiplier = number(value, "extrusion_multiplier", config.extrusionMultiplier);
        config.maxVolumetricSpeed = number(value, "max_volumetric_speed", config.maxVolumetricSpeed);
        config.travelSpeed = number(value, "travel_speed", config.travelSpeed);
        config.outerWallSpeed = number(value, "outer_wall_speed", config.outerWallSpeed);
        config.innerWallSpeed = number(value, "inner_wall_speed", config.innerWallSpeed);
        config.infillSpeed = number(value, "infill_speed", config.infillSpeed);
        config.initialLayerSpeed = number(value, "initial_layer_speed", config.initialLayerSpeed);
        config.fanMinPercent = number(value, "fan_min", config.fanMinPercent); config.fanMaxPercent = number(value, "fan_max", config.fanMaxPercent);
        try {
            config.supports = value.getBoolean("supports");
            config.perimeters = value.getInt("perimeters");
            config.topLayers = value.getInt("top_layers");
            config.bottomLayers = value.getInt("bottom_layers");
        } catch (Exception error) {
            throw new IOException("Slice request recipe integer or boolean is invalid", error);
        }
        config.supportThresholdDegrees = number(value, "support_threshold", config.supportThresholdDegrees);
        try {
            config.nativeSettings.putAll(NativeSettings.parseField(value, "native_settings", false));
        } catch (Exception error) {
            throw new IOException("Slice request native settings are invalid", error);
        }
        return config;
    }

    private static float number(JSONObject value, String key, float fallback) throws IOException {
        try {
            double number = value.getDouble(key);
            if (Double.isNaN(number) || Double.isInfinite(number) || number < -1_000_000d || number > 1_000_000d)
                throw new IOException("Slice request recipe number is invalid: " + key);
            return (float) number;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Slice request recipe number is invalid: " + key, error);
        }
    }

    private static byte[] readBytes(File file, int max) throws IOException {
        if (file.length() > max) throw new IOException("Slice request config is too large");
        byte[] data = new byte[(int) file.length()];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0, read;
            while (offset < data.length && (read = input.read(data, offset, data.length - offset)) != -1) offset += read;
            if (offset != data.length) throw new IOException("Slice request config is truncated");
        }
        return data;
    }

    private static void ensureDirectory(File directory, String label) throws IOException {
        if (PrinterTransport.isSymbolicLink(directory)) throw new IOException(label + " must not be a symbolic link");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create " + label.toLowerCase());
        if (!directory.isDirectory()) throw new IOException(label + " is not a directory");
    }

    private static void moveIntoPlace(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) {
            Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (PrinterTransport.isSymbolicLink(target) || !target.isFile()) throw new IOException("Slice request write could not be verified");
    }

    private static void updateInt(MessageDigest digest, ByteBuffer buffer, int value) {
        buffer.clear(); buffer.putInt(value); digest.update(buffer.array());
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8)); digest.update((byte) value);
    }

    private static void updateFloat(MessageDigest digest, float value) {
        updateInt(digest, Float.floatToIntBits(value));
    }

    private static void updateText(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length); digest.update(bytes);
    }

    private static void writeIntLE(FileOutputStream output, int value) throws IOException {
        output.write(value & 0xff); output.write((value >>> 8) & 0xff); output.write((value >>> 16) & 0xff); output.write((value >>> 24) & 0xff);
    }

    private static void writeFloatLE(FileOutputStream output, float value) throws IOException { writeIntLE(output, Float.floatToIntBits(value)); }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static String boundedName(String value) throws IOException {
        String result = value == null ? "model" : value.trim();
        if (result.length() == 0 || result.length() > 150) throw new IOException("Slice display name is invalid");
        for (int index = 0; index < result.length(); index++)
            if (Character.isISOControl(result.charAt(index))) throw new IOException("Slice display name is invalid");
        return result;
    }

    private static String boundedText(String value) throws IOException {
        if (value == null || value.trim().length() == 0 || value.length() > 150) throw new IOException("Slice recipe text is invalid");
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) throw new IOException("Slice recipe text is invalid");
        return value.trim();
    }

    private static boolean validJobId(String value) {
        return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    public static final class Request {
        public final MeshModel model;
        public final Slicer.Config config;
        public final String displayName;
        public final String modelSha256;
        public final String recipeSha256;
        Request(MeshModel model, Slicer.Config config, String displayName, String modelSha256, String recipeSha256) {
            this.model = model; this.config = config; this.displayName = displayName; this.modelSha256 = modelSha256;
            this.recipeSha256 = recipeSha256;
        }
    }
}
