package com.mbaliga.alloy;

import android.content.ContentResolver;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable app-private batch request, including offline materialized sources. */
public final class BatchSliceRequestStore {
    private static final String DIRECTORY = "batch-slice-jobs";
    private static final String REQUEST = "request.json";
    private static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024;
    private static final int MAX_TEXT = 150;

    private BatchSliceRequestStore() { }

    public static String newJobId() { return UUID.randomUUID().toString(); }

    /**
     * Materialize every source before the service starts. The service then has
     * no dependency on a cloud provider remaining online or on a document grant
     * surviving Activity recreation.
     */
    public static String write(File filesDir, ContentResolver resolver, String jobId,
                               ArrayList<PlateStore.Plate> plates, Slicer.Config config,
                               String displayName) throws IOException {
        if (filesDir == null || resolver == null || !validJobId(jobId) || plates == null || config == null)
            throw new IllegalArgumentException("batch request inputs are invalid");
        if (PrinterTransport.isSymbolicLink(filesDir)) throw new IOException("Batch storage root must not be a symbolic link");
        ArrayList<PlateStore.Plate> nonEmpty = new ArrayList<>();
        for (PlateStore.Plate plate : plates) {
            if (plate != null && plate.uris != null && !plate.uris.isEmpty()) nonEmpty.add(plate);
        }
        if (nonEmpty.isEmpty() || nonEmpty.size() > PlateStore.MAX_PLATES)
            throw new IOException("Batch must contain between one and eight populated plates");
        Collections.sort(nonEmpty, Comparator.comparingInt(value -> value.index));

        root(filesDir);
        File directory = jobDirectory(filesDir, jobId);
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Could not create batch request directory");
        if (!directory.isDirectory()) throw new IOException("Batch request directory is invalid");

        JSONArray encodedPlates = new JSONArray();
        for (PlateStore.Plate plate : nonEmpty) {
            ArrayList<Uri> cachedUris = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            for (int index = 0; index < plate.uris.size(); index++) {
                String name = index < plate.names.size() ? plate.names.get(index) : "model.stl";
                ModelStore.Materialized materialized = ModelStore.materialize(filesDir, resolver, plate.uris.get(index), name);
                cachedUris.add(materialized.uri);
                names.add(name);
            }
            try {
                encodedPlates.put(encodePlate(new PlateStore.Plate(plate.index, plate.name, cachedUris, names,
                        plate.scale, plate.rotationDegrees, plate.tiltXDegrees, plate.tiltYDegrees,
                        plate.selectedPart, plate.partTransforms)));
            } catch (Exception error) {
                throw new IOException("Batch plate could not be encoded", error);
            }
        }

        JSONObject value = new JSONObject();
        try {
            value.put("format", "alloy-batch-request").put("version", 1)
                    .put("job_id", jobId).put("display_name", boundedText(displayName))
                    .put("project_sha256", fingerprint(plates, config))
                    .put("recipe", encodeConfig(config)).put("plates", encodedPlates);
        } catch (Exception error) {
            throw new IOException("Batch request could not be encoded", error);
        }
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_REQUEST_BYTES) throw new IOException("Batch request exceeds its size limit");
        writeAtomic(new File(directory, REQUEST), bytes);
        return fingerprint(plates, config);
    }

    public static Request read(File filesDir, String jobId) throws IOException {
        File directory = jobDirectory(filesDir, jobId);
        File file = new File(directory, REQUEST);
        if (PrinterTransport.isSymbolicLink(file) || !file.isFile() || file.length() <= 0L || file.length() > MAX_REQUEST_BYTES)
            throw new IOException("Batch request is missing or invalid");
        try {
            JSONObject value = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (!"alloy-batch-request".equals(value.getString("format")) || value.getInt("version") != 1
                    || !jobId.equals(value.getString("job_id"))) throw new IOException("Batch request identity is invalid");
            String displayName = boundedText(value.getString("display_name"));
            String projectSha256 = value.getString("project_sha256");
            if (!projectSha256.matches("[0-9a-fA-F]{64}")) throw new IOException("Batch project identity is invalid");
            Slicer.Config config = parseConfig(value.getJSONObject("recipe"));
            JSONArray values = value.getJSONArray("plates");
            if (values.length() <= 0 || values.length() > PlateStore.MAX_PLATES) throw new IOException("Batch plate count is invalid");
            ArrayList<PlateStore.Plate> plates = new ArrayList<>();
            HashSet<Integer> seenIndexes = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                PlateStore.Plate plate = parsePlate(values.getJSONObject(index));
                if (plate == null || plate.uris.isEmpty() || !seenIndexes.add(plate.index)) throw new IOException("Batch plate is invalid");
                validateCachedSources(filesDir, plate);
                plates.add(plate);
            }
            Collections.sort(plates, Comparator.comparingInt(value1 -> value1.index));
            return new Request(jobId, displayName, projectSha256.toLowerCase(Locale.US), config, plates);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Batch request is invalid", error);
        }
    }

    public static File jobDirectory(File filesDir, String jobId) throws IOException {
        if (filesDir == null || !validJobId(jobId)) throw new IOException("Batch job path is invalid");
        File root = root(filesDir);
        File directory = new File(root, jobId.toLowerCase(Locale.US));
        if (PrinterTransport.isSymbolicLink(directory)) throw new IOException("Batch job directory must not be a symbolic link");
        return directory;
    }

    /** Keep materialized sources alive while a durable batch request/result exists. */
    static void addProtectedModelPaths(File filesDir, HashSet<String> protectedPaths) {
        if (filesDir == null || protectedPaths == null || PrinterTransport.isSymbolicLink(filesDir)) return;
        File jobs = new File(filesDir, DIRECTORY);
        if (PrinterTransport.isSymbolicLink(jobs) || !jobs.isDirectory()) return;
        File modelRoot = new File(filesDir, "models");
        if (PrinterTransport.isSymbolicLink(modelRoot) || !modelRoot.isDirectory()) return;
        try {
            String rootPath = modelRoot.getCanonicalPath();
            File[] directories = jobs.listFiles(file -> file != null && !PrinterTransport.isSymbolicLink(file) && file.isDirectory()
                    && validJobId(file.getName()));
            if (directories == null) return;
            for (File directory : directories) {
                File request = new File(directory, REQUEST);
                if (PrinterTransport.isSymbolicLink(request) || !request.isFile() || request.length() > MAX_REQUEST_BYTES) continue;
                try {
                    JSONObject root = new JSONObject(new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8));
                    JSONArray plates = root.optJSONArray("plates");
                    if (plates == null) continue;
                    for (int index = 0; index < plates.length(); index++) {
                        JSONArray uris = plates.optJSONObject(index) == null ? null : plates.optJSONObject(index).optJSONArray("uris");
                        if (uris == null) continue;
                        for (int item = 0; item < uris.length(); item++) {
                            Uri uri = Uri.parse(uris.optString(item, ""));
                            if (!"file".equalsIgnoreCase(uri.getScheme())) continue;
                            File source = new File(uri.getPath() == null ? "" : uri.getPath());
                            if (PrinterTransport.isSymbolicLink(source) || !source.isFile()
                                    || !source.getName().matches("[0-9a-f]{64}\\.(stl|obj|3mf|step)")) continue;
                            String sourcePath = source.getCanonicalPath();
                            if (sourcePath.startsWith(rootPath + File.separator)) protectedPaths.add(sourcePath);
                        }
                    }
                } catch (Exception ignored) {
                    // An unreadable request cannot safely identify files to retain.
                }
            }
        } catch (Exception ignored) {
            // The cache pruner is best-effort; it never follows an untrusted path.
        }
    }

    /** Fingerprint the user-visible project recipe and original plate snapshot. */
    public static String fingerprint(ArrayList<PlateStore.Plate> plates, Slicer.Config config) throws IOException {
        if (plates == null || config == null) throw new IOException("Batch fingerprint inputs are invalid");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ArrayList<PlateStore.Plate> sorted = new ArrayList<>();
            for (PlateStore.Plate plate : plates) if (plate != null) sorted.add(plate);
            Collections.sort(sorted, Comparator.comparingInt(value -> value.index));
            updateInt(digest, sorted.size());
            for (PlateStore.Plate plate : sorted) {
                updateInt(digest, plate.index); updateText(digest, plate.name);
                updateInt(digest, plate.uris.size());
                for (int index = 0; index < plate.uris.size(); index++) {
                    updateText(digest, plate.uris.get(index).toString());
                    updateText(digest, index < plate.names.size() ? plate.names.get(index) : "model.stl");
                }
                updateFloat(digest, plate.scale); updateFloat(digest, plate.rotationDegrees);
                updateFloat(digest, plate.tiltXDegrees); updateFloat(digest, plate.tiltYDegrees);
                updateInt(digest, plate.selectedPart); updateInt(digest, plate.partTransforms.size());
                for (MeshModel.PartTransform transform : plate.partTransforms) {
                    if (transform == null) throw new IOException("Batch part transform is missing");
                    updateFloat(digest, transform.scale); updateFloat(digest, transform.rotationDegrees);
                    updateFloat(digest, transform.tiltXDegrees); updateFloat(digest, transform.tiltYDegrees);
                    updateFloat(digest, transform.offsetX); updateFloat(digest, transform.offsetY);
                }
            }
            updateConfig(digest, config);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static File root(File filesDir) throws IOException {
        File root = new File(filesDir, DIRECTORY);
        if (PrinterTransport.isSymbolicLink(root)) throw new IOException("Batch storage directory must not be a symbolic link");
        if (!root.exists() && !root.mkdirs()) throw new IOException("Could not create batch storage directory");
        if (!root.isDirectory()) throw new IOException("Batch storage directory is invalid");
        return root;
    }

    private static JSONObject encodePlate(PlateStore.Plate plate) throws Exception {
        JSONArray uris = new JSONArray(); JSONArray names = new JSONArray();
        for (int index = 0; index < plate.uris.size(); index++) {
            uris.put(plate.uris.get(index).toString());
            names.put(index < plate.names.size() ? boundedText(plate.names.get(index)) : "model.stl");
        }
        JSONArray transforms = new JSONArray();
        for (MeshModel.PartTransform transform : plate.partTransforms) {
            if (transform == null) throw new IOException("Batch part transform is missing");
            transforms.put(new JSONObject().put("scale", transform.scale).put("rotation", transform.rotationDegrees)
                    .put("tilt_x", transform.tiltXDegrees).put("tilt_y", transform.tiltYDegrees)
                    .put("offset_x", transform.offsetX).put("offset_y", transform.offsetY));
        }
        return new JSONObject().put("index", plate.index).put("name", boundedText(plate.name)).put("uris", uris)
                .put("names", names).put("scale", plate.scale).put("rotation", plate.rotationDegrees)
                .put("tilt_x", plate.tiltXDegrees).put("tilt_y", plate.tiltYDegrees)
                .put("selected_part", plate.selectedPart).put("part_transforms", transforms);
    }

    private static PlateStore.Plate parsePlate(JSONObject value) throws IOException, JSONException {
        int index = value.getInt("index");
        if (index < 0 || index >= PlateStore.MAX_PLATES) throw new IOException("Batch plate index is invalid");
        String name = boundedText(value.getString("name"));
        JSONArray uriValues = value.getJSONArray("uris");
        JSONArray nameValues = value.getJSONArray("names");
        if (uriValues.length() <= 0 || uriValues.length() > 32 || nameValues.length() != uriValues.length())
            throw new IOException("Batch plate sources are invalid");
        ArrayList<Uri> uris = new ArrayList<>(); ArrayList<String> names = new ArrayList<>();
        for (int item = 0; item < uriValues.length(); item++) {
            Uri uri = Uri.parse(uriValues.getString(item));
            if (!"file".equalsIgnoreCase(uri.getScheme())) throw new IOException("Batch source is not app-private");
            uris.add(uri); names.add(boundedText(nameValues.getString(item)));
        }
        JSONArray transformValues = value.optJSONArray("part_transforms");
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        if (transformValues != null) {
            if (transformValues.length() > 32) throw new IOException("Batch transform list is too large");
            for (int item = 0; item < transformValues.length(); item++) {
                JSONObject transform = transformValues.getJSONObject(item);
                transforms.add(new MeshModel.PartTransform(number(transform, "scale", 1f, 0.1f, 10f),
                        number(transform, "rotation", 0f, -360f, 360f), number(transform, "tilt_x", 0f, -360f, 360f),
                        number(transform, "tilt_y", 0f, -360f, 360f), number(transform, "offset_x", 0f, -1000f, 1000f),
                        number(transform, "offset_y", 0f, -1000f, 1000f)));
            }
        }
        int selectedPart = value.optInt("selected_part", -1);
        if (selectedPart < -1 || selectedPart >= 1_000_000) throw new IOException("Batch selected part is invalid");
        return new PlateStore.Plate(index, name, uris, names,
                number(value, "scale", 1f, MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE), number(value, "rotation", 0f, -360f, 360f),
                number(value, "tilt_x", 0f, -360f, 360f), number(value, "tilt_y", 0f, -360f, 360f),
                selectedPart, transforms);
    }

    private static void validateCachedSources(File filesDir, PlateStore.Plate plate) throws IOException {
        File root = new File(filesDir, "models");
        if (PrinterTransport.isSymbolicLink(root) || !root.isDirectory()) throw new IOException("Batch model cache is unavailable");
        String rootPath = root.getCanonicalPath();
        for (Uri uri : plate.uris) {
            if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) throw new IOException("Batch source is not app-private");
            File source = new File(uri.getPath() == null ? "" : uri.getPath());
            if (PrinterTransport.isSymbolicLink(source) || !source.isFile()
                    || !source.getName().matches("[0-9a-f]{64}\\.(stl|obj|3mf|step)"))
                throw new IOException("Batch source cache identity is invalid");
            String sourcePath = source.getCanonicalPath();
            if (!sourcePath.startsWith(rootPath + File.separator)) throw new IOException("Batch source is outside the model cache");
        }
    }

    private static JSONObject encodeConfig(Slicer.Config config) throws Exception {
        return new JSONObject().put("layer_height", config.layerHeight).put("first_layer_height", config.firstLayerHeight)
                .put("nozzle", config.nozzle).put("filament_diameter", config.filamentDiameter).put("infill", config.infill)
                .put("bed_x", config.bedX).put("bed_y", config.bedY).put("bed_z", config.bedZ)
                .put("printer", boundedText(config.printer)).put("filament", boundedText(config.filament))
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
    }

    private static Slicer.Config parseConfig(JSONObject value) throws IOException, JSONException {
        Slicer.Config config = new Slicer.Config();
        config.layerHeight = number(value, "layer_height", config.layerHeight, 0.001f, 2f);
        config.firstLayerHeight = number(value, "first_layer_height", config.firstLayerHeight, 0.001f, 2f);
        config.nozzle = number(value, "nozzle", config.nozzle, 0.01f, 2f);
        config.filamentDiameter = number(value, "filament_diameter", config.filamentDiameter, 1f, 4f);
        config.infill = number(value, "infill", config.infill, 0f, 1f);
        config.bedX = number(value, "bed_x", config.bedX, 1f, 5000f); config.bedY = number(value, "bed_y", config.bedY, 1f, 5000f); config.bedZ = number(value, "bed_z", config.bedZ, 1f, 5000f);
        config.printer = boundedText(value.getString("printer")); config.filament = boundedText(value.getString("filament"));
        config.nozzleTemperature = number(value, "nozzle_temperature", config.nozzleTemperature, 0f, 400f);
        config.firstLayerNozzleTemperature = number(value, "first_layer_nozzle_temperature", config.firstLayerNozzleTemperature, 0f, 400f);
        config.bedTemperature = number(value, "bed_temperature", config.bedTemperature, 0f, 150f);
        config.firstLayerBedTemperature = number(value, "first_layer_bed_temperature", config.firstLayerBedTemperature, 0f, 150f);
        config.extrusionMultiplier = number(value, "extrusion_multiplier", config.extrusionMultiplier, 0.01f, 2f);
        config.maxVolumetricSpeed = number(value, "max_volumetric_speed", config.maxVolumetricSpeed, 0.01f, 200f);
        config.travelSpeed = number(value, "travel_speed", config.travelSpeed, 0.01f, 2000f);
        config.outerWallSpeed = number(value, "outer_wall_speed", config.outerWallSpeed, 0.01f, 1000f);
        config.innerWallSpeed = number(value, "inner_wall_speed", config.innerWallSpeed, 0.01f, 1000f);
        config.infillSpeed = number(value, "infill_speed", config.infillSpeed, 0.01f, 1000f);
        config.initialLayerSpeed = number(value, "initial_layer_speed", config.initialLayerSpeed, 0.01f, 1000f);
        config.fanMinPercent = number(value, "fan_min", config.fanMinPercent, 0f, 100f); config.fanMaxPercent = number(value, "fan_max", config.fanMaxPercent, 0f, 100f);
        try { config.supports = value.getBoolean("supports"); config.perimeters = value.getInt("perimeters"); config.topLayers = value.getInt("top_layers"); config.bottomLayers = value.getInt("bottom_layers"); }
        catch (Exception error) { throw new IOException("Batch recipe counts are invalid", error); }
        config.supportThresholdDegrees = number(value, "support_threshold", config.supportThresholdDegrees, 0f, 90f);
        config.nativeSettings.putAll(NativeSettings.parseField(value, "native_settings", false));
        if (config.fanMinPercent > config.fanMaxPercent || config.perimeters < 1 || config.perimeters > 20
                || config.topLayers < 0 || config.topLayers > 100 || config.bottomLayers < 0 || config.bottomLayers > 100)
            throw new IOException("Batch recipe counts or cooling values are invalid");
        return config;
    }

    private static float number(JSONObject value, String key, float fallback, float min, float max) throws IOException {
        try {
            double number = value.getDouble(key);
            if (Double.isNaN(number) || Double.isInfinite(number) || number < min || number > max) throw new IOException("Batch recipe number is invalid: " + key);
            return (float) number;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Batch recipe number is invalid: " + key, error); }
    }

    private static String boundedText(String value) throws IOException {
        if (value == null || value.trim().length() == 0 || value.length() > MAX_TEXT) throw new IOException("Batch text is invalid");
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) throw new IOException("Batch text is invalid");
        return value.trim();
    }

    private static void writeAtomic(File target, byte[] bytes) throws IOException {
        File temporary = new File(target.getParentFile(), "." + target.getName() + ".part");
        try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.flush(); output.getFD().sync(); }
        try { Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException | FileAlreadyExistsException unsupported) { Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
        if (PrinterTransport.isSymbolicLink(target) || !target.isFile() || target.length() != bytes.length) throw new IOException("Batch request write could not be verified");
    }

    private static void updateConfig(MessageDigest digest, Slicer.Config config) {
        updateFloat(digest, config.layerHeight); updateFloat(digest, config.firstLayerHeight); updateFloat(digest, config.nozzle); updateFloat(digest, config.filamentDiameter); updateFloat(digest, config.infill);
        updateFloat(digest, config.bedX); updateFloat(digest, config.bedY); updateFloat(digest, config.bedZ); updateText(digest, config.printer); updateText(digest, config.filament);
        updateFloat(digest, config.nozzleTemperature); updateFloat(digest, config.firstLayerNozzleTemperature); updateFloat(digest, config.bedTemperature); updateFloat(digest, config.firstLayerBedTemperature);
        updateFloat(digest, config.extrusionMultiplier); updateFloat(digest, config.maxVolumetricSpeed); updateFloat(digest, config.travelSpeed); updateFloat(digest, config.outerWallSpeed); updateFloat(digest, config.innerWallSpeed); updateFloat(digest, config.infillSpeed); updateFloat(digest, config.initialLayerSpeed); updateFloat(digest, config.fanMinPercent); updateFloat(digest, config.fanMaxPercent);
        updateInt(digest, config.supports ? 1 : 0); updateFloat(digest, config.supportThresholdDegrees); updateInt(digest, config.perimeters); updateInt(digest, config.topLayers); updateInt(digest, config.bottomLayers);
        Map<String, String> nativeSettings = new TreeMap<>(config.nativeSettings);
        updateInt(digest, nativeSettings.size());
        for (Map.Entry<String, String> entry : nativeSettings.entrySet()) {
            updateText(digest, entry.getKey());
            updateText(digest, entry.getValue());
        }
    }

    private static void updateText(MessageDigest digest, String value) { byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8); updateInt(digest, bytes.length); digest.update(bytes); }
    private static void updateFloat(MessageDigest digest, float value) { updateInt(digest, Float.floatToIntBits(value)); }
    private static void updateInt(MessageDigest digest, int value) { digest.update((byte) (value >>> 24)); digest.update((byte) (value >>> 16)); digest.update((byte) (value >>> 8)); digest.update((byte) value); }
    private static String hex(byte[] values) { StringBuilder result = new StringBuilder(values.length * 2); for (byte value : values) result.append(String.format(Locale.US, "%02x", value & 0xff)); return result.toString(); }
    private static boolean validJobId(String value) { return value != null && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"); }

    public static final class Request {
        public final String jobId, displayName, projectSha256;
        public final Slicer.Config config;
        public final ArrayList<PlateStore.Plate> plates;
        Request(String jobId, String displayName, String projectSha256, Slicer.Config config, ArrayList<PlateStore.Plate> plates) {
            this.jobId = jobId; this.displayName = displayName; this.projectSha256 = projectSha256; this.config = config; this.plates = new ArrayList<>(plates);
        }
    }
}
