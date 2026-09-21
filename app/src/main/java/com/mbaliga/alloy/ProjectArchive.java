package com.mbaliga.alloy;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Portable, bounded project container for phone-first work. The archive keeps
 * the source models, plate/recipe state and optional modeling history so a
 * document-provider URI is not the only copy of a project.
 */
public final class ProjectArchive {
    public static final String MIME_TYPE = "application/zip";
    private static final String MANIFEST = "alloy/project.json";
    private static final String THUMBNAIL = "alloy/thumbnail.png";
    private static final int VERSION = 3;
    private static final int MAX_ENTRIES = 640;
    private static final int MAX_MODEL_FILES = PlateStore.MAX_PLATES * 32 * 2;
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_MODEL_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_TOTAL_MODEL_BYTES = 256L * 1024L * 1024L;
    private static final int MAX_STORED_PROJECTS = 8;
    private static final long MAX_STORED_PROJECT_BYTES = 512L * 1024L * 1024L;
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;
    private static final int MAX_THUMBNAIL_BYTES = 1 * 1024 * 1024;
    private static final int MAX_NAME = 150;
    private static final int MAX_PATH = 128;

    private ProjectArchive() { }

    public interface SourceProvider {
        InputStream open(Uri uri) throws IOException;
    }

    public static void write(OutputStream output, int activePlateIndex,
                             ArrayList<PlateStore.Plate> plates, Slicer.Config config,
                             SourceProvider provider) throws IOException {
        write(output, activePlateIndex, plates, config, provider, null);
    }

    public static void write(OutputStream output, int activePlateIndex,
                             ArrayList<PlateStore.Plate> plates, Slicer.Config config,
                             SourceProvider provider, JSONArray inventorySnapshot) throws IOException {
        write(output, activePlateIndex, plates, config, provider, inventorySnapshot, null);
    }

    public static void write(OutputStream output, int activePlateIndex,
                             ArrayList<PlateStore.Plate> plates, Slicer.Config config,
                             SourceProvider provider, JSONArray inventorySnapshot,
                             byte[] thumbnailPng) throws IOException {
        write(output, activePlateIndex, plates, config, provider, inventorySnapshot, thumbnailPng, null);
    }

    /** Write a portable project including its bounded modeling edit timelines. */
    public static void write(OutputStream output, int activePlateIndex,
                             ArrayList<PlateStore.Plate> plates, Slicer.Config config,
                             SourceProvider provider, JSONArray inventorySnapshot,
                             byte[] thumbnailPng,
                             ArrayList<ModelHistoryStore.Timeline> history) throws IOException {
        if (output == null || plates == null || plates.isEmpty() || plates.size() > PlateStore.MAX_PLATES
                || config == null || provider == null || activePlateIndex < 0 || activePlateIndex >= PlateStore.MAX_PLATES)
            throw new IllegalArgumentException("project archive inputs are invalid");
        if (inventorySnapshot != null) InventoryStore.validateSnapshot(inventorySnapshot);
        if (thumbnailPng != null && (!isPng(thumbnailPng) || thumbnailPng.length > MAX_THUMBNAIL_BYTES))
            throw new IllegalArgumentException("project thumbnail is invalid");

        HashMap<String, ModelFile> filesByUri = new HashMap<>();
        ArrayList<ModelFile> files = new ArrayList<>();
        for (PlateStore.Plate plate : plates) {
            indexModelFiles(plate, filesByUri, files);
        }
        if (history != null) {
            if (history.size() > PlateStore.MAX_PLATES)
                throw new IllegalArgumentException("project history has too many plate timelines");
            HashSet<Integer> seenHistoryPlates = new HashSet<>();
            for (ModelHistoryStore.Timeline timeline : history) {
                if (timeline == null || !seenHistoryPlates.add(timeline.plateIndex)
                        || timeline.entries == null || timeline.entries.isEmpty()
                        || timeline.entries.size() > 32 || timeline.cursor < 0
                        || timeline.cursor >= timeline.entries.size())
                    throw new IllegalArgumentException("project history timeline is invalid");
                for (ModelHistoryStore.HistoryEntry entry : timeline.entries) {
                    if (entry == null || entry.plate == null || entry.plate.index != timeline.plateIndex
                            || entry.at <= 0L || entry.label == null || entry.label.trim().length() == 0
                            || entry.label.length() > 120)
                        throw new IllegalArgumentException("project history entry is invalid");
                    indexModelFiles(entry.plate, filesByUri, files);
                }
            }
        }

        CountingOutputStream boundedOutput = new CountingOutputStream(output, MAX_ARCHIVE_BYTES);
        ZipOutputStream zip = new ZipOutputStream(boundedOutput);
        long totalModelBytes = 0L;
        byte[] buffer = new byte[32 * 1024];
        for (ModelFile file : files) {
            MessageDigest digest = sha256();
            long fileBytes = 0L;
            try (InputStream input = provider.open(Uri.parse(file.uri))) {
                if (input == null) throw new IOException("Could not open model source " + file.name);
                ZipEntry entry = new ZipEntry(file.path);
                zip.putNextEntry(entry);
                int read;
                while ((read = input.read(buffer)) != -1) {
                    if (read == 0) continue;
                    fileBytes += read;
                    totalModelBytes += read;
                    if (fileBytes > MAX_MODEL_BYTES || totalModelBytes > MAX_TOTAL_MODEL_BYTES)
                        throw new IOException("Project model data exceeds the archive limit");
                    digest.update(buffer, 0, read);
                    zip.write(buffer, 0, read);
                }
                if (fileBytes == 0L) throw new IOException("Project model source is empty: " + file.name);
                zip.closeEntry();
            }
            file.sizeBytes = fileBytes;
            file.sha256 = hex(digest.digest());
        }
        // The manifest is written after model streams so it can bind every
        // embedded source to its exact byte count and SHA-256 digest. ZIP
        // readers do not require the manifest to be the first entry.
        byte[] manifest = manifest(activePlateIndex, plates, filesByUri, config, inventorySnapshot, history).toString().getBytes(StandardCharsets.UTF_8);
        put(zip, MANIFEST, manifest);
        if (thumbnailPng != null) put(zip, THUMBNAIL, thumbnailPng);
        zip.finish();
    }

    public static ImportedProject read(InputStream input, File destinationRoot) throws IOException {
        if (input == null || destinationRoot == null) throw new IllegalArgumentException("project archive inputs are required");
        if (isSymbolicLink(destinationRoot)) throw new IOException("Project storage root must not be a symbolic link");
        if (!destinationRoot.exists() && !destinationRoot.mkdirs()) throw new IOException("Could not create project storage");
        File projectDirectory = new File(destinationRoot, "project-" + UUID.randomUUID());
        if (!projectDirectory.mkdirs()) throw new IOException("Could not create project directory");
        HashMap<String, ExtractedModel> modelFiles = new HashMap<>();
        byte[] manifest = null;
        byte[] thumbnail = null;
        long totalBytes = 0L;
        int entries = 0;
        BoundedInputStream boundedInput = new BoundedInputStream(input, MAX_ARCHIVE_BYTES);
        try (ZipInputStream zip = new ZipInputStream(boundedInput)) {
            ZipEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES || entry.isDirectory()) throw new IOException("Project archive has too many entries");
                String path = safePath(entry.getName());
                if (MANIFEST.equals(path)) {
                    if (manifest != null) throw new IOException("Project archive has duplicate manifest");
                    manifest = readBounded(zip, MAX_MANIFEST_BYTES);
                } else if (THUMBNAIL.equals(path)) {
                    if (thumbnail != null) throw new IOException("Project archive has duplicate thumbnail");
                    thumbnail = readBounded(zip, MAX_THUMBNAIL_BYTES);
                    if (!isPng(thumbnail)) throw new IOException("Project thumbnail is invalid");
                } else {
                    if (!path.startsWith("models/") || !validModelPath(path) || modelFiles.containsKey(path))
                        throw new IOException("Project archive contains an unexpected entry");
                    if (modelFiles.size() >= MAX_MODEL_FILES) throw new IOException("Project archive contains too many model files");
                    File file = new File(projectDirectory, path.substring("models/".length()));
                    MessageDigest digest = sha256();
                    long copied;
                    try (FileOutputStream output = new FileOutputStream(file)) {
                        copied = copyBounded(zip, output, MAX_MODEL_BYTES, digest);
                    }
                    if (copied == 0L) throw new IOException("Project model file is empty");
                    totalBytes += copied;
                    if (totalBytes > MAX_TOTAL_MODEL_BYTES) throw new IOException("Project model data exceeds the archive limit");
                    modelFiles.put(path, new ExtractedModel(file, copied, hex(digest.digest())));
                }
                zip.closeEntry();
            }
            if (boundedInput.limitReached()) throw new IOException("Project archive compressed data exceeds the 256 MB limit");
        } catch (Exception error) {
            deleteTree(projectDirectory);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Project archive could not be read", error);
        }
        try {
            if (manifest == null) throw new IOException("Project archive manifest is missing");
            ImportedProject result = parseManifest(new String(manifest, StandardCharsets.UTF_8), modelFiles, thumbnail);
            if (result.plates.isEmpty()) throw new IOException("Project archive contains no plates");
            return result;
        } catch (Exception error) {
            deleteTree(projectDirectory);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Project archive manifest is invalid", error);
        }
    }

    /**
     * Bound extracted archive copies without deleting model files that the
     * current plate snapshots still reference. These folders are an internal
     * cache of portable-project sources; the user-owned original archive is
     * never touched.
     */
    public static void pruneStoredProjects(File destinationRoot,
                                            ArrayList<PlateStore.Plate> protectedPlates) {
        if (destinationRoot == null || isSymbolicLink(destinationRoot) || !destinationRoot.isDirectory()) return;
        HashSet<String> protectedFiles = new HashSet<>();
        if (protectedPlates != null) {
            for (PlateStore.Plate plate : protectedPlates) {
                if (plate == null || plate.uris == null) continue;
                for (Uri uri : plate.uris) {
                    if (uri == null || !"file".equalsIgnoreCase(uri.getScheme())) continue;
                    try { protectedFiles.add(new File(uri.getPath()).getCanonicalPath()); }
                    catch (Exception ignored) { /* uncertain paths are handled conservatively below */ }
                }
            }
        }
        File[] directories = destinationRoot.listFiles(file -> file != null
                && !isSymbolicLink(file) && file.isDirectory() && file.getName().startsWith("project-"));
        if (directories == null || directories.length == 0) return;
        Arrays.sort(directories, (left, right) -> Long.compare(left.lastModified(), right.lastModified()));
        int protectedCount = 0;
        for (File directory : directories) if (containsProtectedFile(directory, protectedFiles)) protectedCount++;
        int unprotectedBudget = Math.max(0, MAX_STORED_PROJECTS - protectedCount);
        long totalBytes = 0L;
        int retainedUnprotected = 0;
        for (File directory : directories) {
            long size = directorySize(directory);
            boolean protectedProject = containsProtectedFile(directory, protectedFiles);
            long boundedSize = Math.min(size, MAX_STORED_PROJECT_BYTES);
            boolean overBudget = size > MAX_STORED_PROJECT_BYTES
                    || retainedUnprotected >= unprotectedBudget
                    || totalBytes > MAX_STORED_PROJECT_BYTES - boundedSize;
            if (!protectedProject && overBudget) {
                deleteTree(directory);
                continue;
            }
            if (!protectedProject) retainedUnprotected++;
            totalBytes = totalBytes > Long.MAX_VALUE - size ? Long.MAX_VALUE : totalBytes + size;
        }
    }

    private static void indexModelFiles(PlateStore.Plate plate,
                                        HashMap<String, ModelFile> filesByUri,
                                        ArrayList<ModelFile> files) throws IOException {
        if (plate == null || plate.uris == null || plate.names == null || plate.uris.size() != plate.names.size())
            throw new IllegalArgumentException("project plate is invalid");
        for (int index = 0; index < plate.uris.size(); index++) {
            Uri uri = plate.uris.get(index);
            if (uri == null) throw new IllegalArgumentException("project model URI is missing");
            String key = uri.toString();
            if (!isSupportedUri(key)) throw new IllegalArgumentException("project model URI scheme is unsupported");
            if (!filesByUri.containsKey(key)) {
                if (files.size() >= MAX_MODEL_FILES) throw new IllegalArgumentException("project contains too many model files");
                ModelFile file = new ModelFile("models/" + String.format(Locale.US, "%04d", files.size() + 1)
                        + extension(plate.names.get(index)), key, normalizeName(plate.names.get(index)));
                filesByUri.put(key, file);
                files.add(file);
            }
        }
    }

    private static JSONObject manifest(int activePlateIndex, ArrayList<PlateStore.Plate> plates,
                                       HashMap<String, ModelFile> filesByUri, Slicer.Config config,
                                       JSONArray inventorySnapshot,
                                       ArrayList<ModelHistoryStore.Timeline> history) throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("format", "alloy-project");
            root.put("version", VERSION);
            root.put("active_plate", activePlateIndex);
            root.put("recipe", recipe(config));
            if (inventorySnapshot != null) root.put("inventory", inventorySnapshot);
            JSONArray encodedPlates = new JSONArray();
            for (PlateStore.Plate plate : plates) encodedPlates.put(encodePlate(plate, filesByUri));
            root.put("plates", encodedPlates);
            if (history != null && !history.isEmpty()) {
                JSONArray encodedHistory = new JSONArray();
                for (ModelHistoryStore.Timeline timeline : history) {
                    JSONObject encodedTimeline = new JSONObject().put("plate", timeline.plateIndex)
                            .put("cursor", timeline.cursor);
                    JSONArray encodedEntries = new JSONArray();
                    for (ModelHistoryStore.HistoryEntry entry : timeline.entries) {
                        encodedEntries.put(new JSONObject().put("label", normalizeName(entry.label))
                                .put("at", entry.at).put("snapshot", encodePlate(entry.plate, filesByUri)));
                    }
                    encodedTimeline.put("entries", encodedEntries);
                    encodedHistory.put(encodedTimeline);
                }
                root.put("history", encodedHistory);
            }
            return root;
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Project manifest could not be written", error);
        }
    }

    private static JSONObject encodePlate(PlateStore.Plate plate,
                                           HashMap<String, ModelFile> filesByUri) throws IOException {
        if (plate == null || plate.uris == null || plate.names == null || plate.uris.size() != plate.names.size())
            throw new IOException("Project plate is invalid");
        try {
            JSONObject encoded = new JSONObject();
            encoded.put("index", plate.index);
            encoded.put("name", normalizeName(plate.name));
            encoded.put("scale", plate.scale);
            encoded.put("rotation", plate.rotationDegrees);
            encoded.put("tilt_x", plate.tiltXDegrees);
            encoded.put("tilt_y", plate.tiltYDegrees);
            encoded.put("selected_part", plate.selectedPart);
            encoded.put("geometry_repair", plate.geometryRepairEnabled);
            JSONArray models = new JSONArray();
            for (int index = 0; index < plate.uris.size(); index++) {
                ModelFile file = filesByUri.get(plate.uris.get(index).toString());
                if (file == null) throw new IOException("Project model source was not indexed");
                if (file.sizeBytes <= 0L || file.sha256 == null) throw new IOException("Project model digest is missing");
                models.put(new JSONObject().put("path", file.path).put("name", normalizeName(plate.names.get(index)))
                        .put("bytes", file.sizeBytes).put("sha256", file.sha256));
            }
            encoded.put("models", models);
            JSONArray transforms = new JSONArray();
            for (MeshModel.PartTransform transform : plate.partTransforms) {
                if (transform == null) throw new IOException("Project part transform is invalid");
                transforms.put(new JSONObject().put("scale", transform.scale)
                        .put("rotation", transform.rotationDegrees)
                        .put("tilt_x", transform.tiltXDegrees)
                        .put("tilt_y", transform.tiltYDegrees)
                        .put("offset_x", transform.offsetX).put("offset_y", transform.offsetY));
            }
            encoded.put("part_transforms", transforms);
            return encoded;
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Project plate could not be encoded", error);
        }
    }

    private static JSONObject recipe(Slicer.Config config) throws Exception {
        JSONObject value = new JSONObject();
        value.put("layer_height", config.layerHeight).put("first_layer_height", config.firstLayerHeight)
                .put("nozzle", config.nozzle).put("filament_diameter", config.filamentDiameter)
                .put("infill", config.infill).put("bed_x", config.bedX).put("bed_y", config.bedY)
                .put("bed_z", config.bedZ).put("printer", normalizeName(config.printer))
                .put("filament", normalizeName(config.filament)).put("nozzle_temperature", config.nozzleTemperature)
                .put("first_layer_nozzle_temperature", config.firstLayerNozzleTemperature)
                .put("bed_temperature", config.bedTemperature)
                .put("first_layer_bed_temperature", config.firstLayerBedTemperature)
                .put("extrusion_multiplier", config.extrusionMultiplier)
                .put("max_volumetric_speed", config.maxVolumetricSpeed).put("travel_speed", config.travelSpeed)
                .put("outer_wall_speed", config.outerWallSpeed).put("inner_wall_speed", config.innerWallSpeed)
                .put("infill_speed", config.infillSpeed).put("initial_layer_speed", config.initialLayerSpeed)
                .put("fan_min_percent", config.fanMinPercent).put("fan_max_percent", config.fanMaxPercent)
                .put("supports", config.supports).put("support_threshold", config.supportThresholdDegrees)
                .put("perimeters", config.perimeters).put("top_layers", config.topLayers)
                .put("bottom_layers", config.bottomLayers)
                .put("native_settings", NativeSettings.encode(config.nativeSettings));
        return value;
    }

    private static ImportedProject parseManifest(String text, HashMap<String, ExtractedModel> modelFiles, byte[] thumbnail) throws Exception {
        if (text == null || text.length() > MAX_MANIFEST_BYTES) throw new IOException("Project manifest is too large");
        JSONObject root = new JSONObject(text);
        int version = root.optInt("version", -1);
        if (!"alloy-project".equals(root.optString("format")) || version < 1 || version > VERSION)
            throw new IOException("Unsupported Alloy project format");
        int active = root.optInt("active_plate", 0);
        if (active < 0 || active >= PlateStore.MAX_PLATES) throw new IOException("Active plate is invalid");
        JSONArray inventory = root.optJSONArray("inventory");
        if (root.has("inventory") && inventory == null) throw new IOException("Project inventory is invalid");
        if (inventory != null) InventoryStore.validateSnapshot(inventory);
        JSONArray encodedPlates = root.optJSONArray("plates");
        if (encodedPlates == null || encodedPlates.length() == 0 || encodedPlates.length() > PlateStore.MAX_PLATES)
            throw new IOException("Project plate list is invalid");
        HashSet<Integer> seen = new HashSet<>();
        HashSet<String> referencedPaths = new HashSet<>();
        ArrayList<PlateStore.Plate> plates = new ArrayList<>();
        for (int index = 0; index < encodedPlates.length(); index++) {
            JSONObject encoded = encodedPlates.optJSONObject(index);
            if (encoded == null) throw new IOException("Project plate is invalid");
            int plateIndex = encoded.optInt("index", -1);
            if (plateIndex < 0 || plateIndex >= PlateStore.MAX_PLATES || !seen.add(plateIndex))
                throw new IOException("Project plate index is invalid");
            plates.add(parsePlate(encoded, modelFiles, referencedPaths, version));
        }
        ArrayList<ModelHistoryStore.Timeline> history = new ArrayList<>();
        JSONArray encodedHistory = root.optJSONArray("history");
        if (root.has("history") && encodedHistory == null)
            throw new IOException("Project history is invalid");
        if (encodedHistory != null) {
            if (encodedHistory.length() > PlateStore.MAX_PLATES)
                throw new IOException("Project history has too many timelines");
            HashSet<Integer> historySeen = new HashSet<>();
            for (int timelineIndex = 0; timelineIndex < encodedHistory.length(); timelineIndex++) {
                JSONObject timeline = encodedHistory.optJSONObject(timelineIndex);
                if (timeline == null) throw new IOException("Project history timeline is invalid");
                int plateIndex = boundedInt(timeline, "plate", -1, 0, PlateStore.MAX_PLATES - 1);
                if (!historySeen.add(plateIndex)) throw new IOException("Project history plate is duplicated");
                JSONArray entries = timeline.optJSONArray("entries");
                int cursor = boundedInt(timeline, "cursor", -1, 0, 31);
                if (entries == null || entries.length() == 0 || entries.length() > 32
                        || cursor >= entries.length()) throw new IOException("Project history entries are invalid");
                ArrayList<ModelHistoryStore.HistoryEntry> decodedEntries = new ArrayList<>();
                for (int entryIndex = 0; entryIndex < entries.length(); entryIndex++) {
                    JSONObject entry = entries.optJSONObject(entryIndex);
                    if (entry == null) throw new IOException("Project history entry is invalid");
                    String label = entry.optString("label", "").trim();
                    if (label.length() == 0 || label.length() > 120)
                        throw new IOException("Project history label is invalid");
                    long at = boundedLong(entry, "at", 1L, Long.MAX_VALUE);
                    PlateStore.Plate snapshot = parsePlate(entry.optJSONObject("snapshot"), modelFiles,
                            referencedPaths, version);
                    if (snapshot.index != plateIndex)
                        throw new IOException("Project history snapshot has the wrong plate");
                    decodedEntries.add(new ModelHistoryStore.HistoryEntry(snapshot, label, at));
                }
                if (!seen.contains(plateIndex)) throw new IOException("Project history references a missing plate");
                history.add(new ModelHistoryStore.Timeline(plateIndex, cursor, decodedEntries));
            }
        }
        if (version >= 2 && referencedPaths.size() != modelFiles.size())
            throw new IOException("Project archive contains an unreferenced model entry");
        if (!seen.contains(active)) active = plates.get(0).index;
        return new ImportedProject(plates, active, parseRecipe(root.optJSONObject("recipe")), inventory, thumbnail, history);
    }

    private static Slicer.Config parseRecipe(JSONObject value) throws Exception {
        Slicer.Config config = new Slicer.Config();
        if (value == null) return config;
        config.layerHeight = number(value, "layer_height", config.layerHeight, 0.08f, 0.40f);
        config.firstLayerHeight = number(value, "first_layer_height", config.firstLayerHeight, 0.08f, 0.40f);
        config.nozzle = number(value, "nozzle", config.nozzle, 0.1f, 1f);
        config.filamentDiameter = number(value, "filament_diameter", config.filamentDiameter, 1f, 3f);
        config.infill = number(value, "infill", config.infill, 0f, 1f);
        config.bedX = number(value, "bed_x", config.bedX, 1f, 1_000f);
        config.bedY = number(value, "bed_y", config.bedY, 1f, 1_000f);
        config.bedZ = number(value, "bed_z", config.bedZ, 1f, 1_000f);
        config.printer = normalizeName(value.optString("printer", config.printer));
        config.filament = normalizeName(value.optString("filament", config.filament));
        config.nozzleTemperature = number(value, "nozzle_temperature", config.nozzleTemperature, 0f, 400f);
        config.firstLayerNozzleTemperature = number(value, "first_layer_nozzle_temperature", config.firstLayerNozzleTemperature, 0f, 400f);
        config.bedTemperature = number(value, "bed_temperature", config.bedTemperature, 0f, 150f);
        config.firstLayerBedTemperature = number(value, "first_layer_bed_temperature", config.firstLayerBedTemperature, 0f, 150f);
        config.extrusionMultiplier = number(value, "extrusion_multiplier", config.extrusionMultiplier, 0.5f, 2f);
        config.maxVolumetricSpeed = number(value, "max_volumetric_speed", config.maxVolumetricSpeed, 0.1f, 200f);
        config.travelSpeed = number(value, "travel_speed", config.travelSpeed, 1f, 2_000f);
        config.outerWallSpeed = number(value, "outer_wall_speed", config.outerWallSpeed, 1f, 1_000f);
        config.innerWallSpeed = number(value, "inner_wall_speed", config.innerWallSpeed, 1f, 1_000f);
        config.infillSpeed = number(value, "infill_speed", config.infillSpeed, 1f, 1_000f);
        config.initialLayerSpeed = number(value, "initial_layer_speed", config.initialLayerSpeed, 1f, 1_000f);
        config.fanMinPercent = number(value, "fan_min_percent", config.fanMinPercent, 0f, 100f);
        config.fanMaxPercent = number(value, "fan_max_percent", config.fanMaxPercent, config.fanMinPercent, 100f);
        Object supports = value.opt("supports");
        if (supports != null && supports != JSONObject.NULL && !(supports instanceof Boolean)) throw new IOException("Recipe supports flag is invalid");
        if (supports instanceof Boolean) config.supports = (Boolean) supports;
        config.supportThresholdDegrees = number(value, "support_threshold", config.supportThresholdDegrees, 0f, 90f);
        config.perimeters = boundedInt(value, "perimeters", config.perimeters, 1, 20);
        config.topLayers = boundedInt(value, "top_layers", config.topLayers, 0, 100);
        config.bottomLayers = boundedInt(value, "bottom_layers", config.bottomLayers, 0, 100);
        config.nativeSettings.putAll(NativeSettings.parseField(value, "native_settings", false));
        return config;
    }

    private static PlateStore.Plate parsePlate(JSONObject encoded,
                                               HashMap<String, ExtractedModel> modelFiles,
                                               HashSet<String> referencedPaths,
                                               int version) throws Exception {
        if (encoded == null) throw new IOException("Project plate is invalid");
        int plateIndex = boundedInt(encoded, "index", -1, 0, PlateStore.MAX_PLATES - 1);
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        JSONArray models = encoded.optJSONArray("models");
        if (models == null || models.length() > 32) throw new IOException("Project model list is invalid");
        HashSet<String> platePaths = new HashSet<>();
        for (int modelIndex = 0; modelIndex < models.length(); modelIndex++) {
            JSONObject model = models.optJSONObject(modelIndex);
            if (model == null) throw new IOException("Project model record is invalid");
            String path = safePath(model.optString("path", ""));
            ExtractedModel file = modelFiles.get(path);
            if (file == null || !platePaths.add(path))
                throw new IOException("Project model path is missing or duplicated");
            referencedPaths.add(path);
            if (version >= 2) {
                long bytes = boundedLong(model, "bytes", 1L, MAX_MODEL_BYTES);
                String digest = model.optString("sha256", "").toLowerCase(Locale.US);
                if (bytes != file.sizeBytes || !digest.matches("[0-9a-f]{64}") || !digest.equals(file.sha256))
                    throw new IOException("Project model integrity check failed: " + path);
            }
            uris.add(Uri.fromFile(file.file));
            names.add(normalizeName(model.optString("name", "model.stl")));
        }
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        JSONArray transformValues = encoded.optJSONArray("part_transforms");
        if (transformValues != null) {
            if (transformValues.length() > 32) throw new IOException("Project transform list is too large");
            for (int transformIndex = 0; transformIndex < transformValues.length(); transformIndex++) {
                JSONObject transform = transformValues.optJSONObject(transformIndex);
                if (transform == null) throw new IOException("Project transform is invalid");
                transforms.add(new MeshModel.PartTransform(
                        number(transform, "scale", 1f, 0.1f, 10f),
                        number(transform, "rotation", 0f, -360f, 360f),
                        number(transform, "tilt_x", 0f, -360f, 360f),
                        number(transform, "tilt_y", 0f, -360f, 360f),
                        number(transform, "offset_x", 0f, -1_000f, 1_000f),
                        number(transform, "offset_y", 0f, -1_000f, 1_000f)));
            }
        }
        return new PlateStore.Plate(plateIndex, normalizeName(encoded.optString("name", "Plate " + (plateIndex + 1))),
                uris, names, number(encoded, "scale", 1f, MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE),
                number(encoded, "rotation", 0f, -360f, 360f),
                number(encoded, "tilt_x", 0f, -360f, 360f),
                number(encoded, "tilt_y", 0f, -360f, 360f),
                boundedInt(encoded, "selected_part", -1, -1, 1_000_000), transforms,
                booleanField(encoded, "geometry_repair", false));
    }

    private static float number(JSONObject object, String key, float fallback, float min, float max) throws IOException {
        Object raw = object.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Number) || raw instanceof Boolean) throw new IOException("Recipe value is not numeric: " + key);
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) throw new IOException("Recipe value is out of range: " + key);
        return (float) value;
    }

    private static int boundedInt(JSONObject object, String key, int fallback, int min, int max) throws IOException {
        Object raw = object.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Number) || raw instanceof Boolean) throw new IOException("Project integer is invalid: " + key);
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value) || value < min || value > max)
            throw new IOException("Project integer is out of range: " + key);
        return (int) value;
    }

    private static boolean booleanField(JSONObject object, String key, boolean fallback) throws IOException {
        Object raw = object.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Boolean)) throw new IOException("Project boolean is invalid: " + key);
        return (Boolean) raw;
    }

    private static long boundedLong(JSONObject object, String key, long min, long max) throws IOException {
        Object raw = object.opt(key);
        if (!(raw instanceof Number) || raw instanceof Boolean)
            throw new IOException("Project long is invalid: " + key);
        double value = ((Number) raw).doubleValue();
        if (Double.isNaN(value) || Double.isInfinite(value) || value != Math.rint(value)
                || value < min || value > max)
            throw new IOException("Project long is out of range: " + key);
        return (long) value;
    }

    private static void put(ZipOutputStream zip, String path, byte[] value) throws IOException {
        zip.putNextEntry(new ZipEntry(path));
        zip.write(value);
        zip.closeEntry();
    }

    private static byte[] readBounded(InputStream input, int max) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copyBounded(input, output, max);
        return output.toByteArray();
    }

    private static long copyBounded(InputStream input, File target, long max) throws IOException {
        try (FileOutputStream output = new FileOutputStream(target)) {
            return copyBounded(input, output, max);
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long max) throws IOException {
        return copyBounded(input, output, max, null);
    }

    private static long copyBounded(InputStream input, OutputStream output, long max, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException("Project archive entry is too large");
            if (digest != null) digest.update(buffer, 0, read);
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String hex(byte[] digest) {
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static String safePath(String path) throws IOException {
        if (path == null || path.length() == 0 || path.length() > MAX_PATH || path.startsWith("/")
                || path.indexOf('\\') >= 0 || path.indexOf("..") >= 0 || path.indexOf('\0') >= 0)
            throw new IOException("Project archive path is unsafe");
        for (int index = 0; index < path.length(); index++) if (Character.isISOControl(path.charAt(index)))
            throw new IOException("Project archive path contains control characters");
        return path;
    }

    private static boolean validModelPath(String path) {
        return path.matches("models/[0-9]{4}\\.(stl|obj|3mf|step)");
    }

    private static boolean isPng(byte[] value) {
        return value != null && value.length >= 8
                && (value[0] & 0xff) == 0x89 && value[1] == 0x50 && value[2] == 0x4e && value[3] == 0x47
                && value[4] == 0x0d && value[5] == 0x0a && value[6] == 0x1a && value[7] == 0x0a;
    }

    private static String extension(String name) {
        if (name == null) return ".stl";
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".3mf")) return ".3mf";
        if (lower.endsWith(".obj")) return ".obj";
        if (lower.endsWith(".step") || lower.endsWith(".stp")) return ".step";
        return ".stl";
    }

    private static boolean isSupportedUri(String value) {
        if (value == null || value.length() == 0 || value.length() > 4096) return false;
        String scheme = Uri.parse(value).getScheme();
        return "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
    }

    private static String normalizeName(String value) {
        if (value == null || value.trim().length() == 0) return "model.stl";
        String normalized = value.trim();
        return normalized.length() > MAX_NAME ? normalized.substring(0, MAX_NAME) : normalized;
    }

    private static void deleteTree(File file) {
        if (file == null) return;
        // A project cache is disposable, but cleanup must never follow a link
        // planted inside it into another app-private or shared directory.
        if (isSymbolicLink(file)) {
            file.delete();
            return;
        }
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }

    private static long directorySize(File directory) {
        if (directory == null || isSymbolicLink(directory) || !directory.exists()) return 0L;
        if (directory.isFile()) return Math.max(0L, directory.length());
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        long total = 0L;
        for (File child : children) {
            long size = directorySize(child);
            total = total > Long.MAX_VALUE - size ? Long.MAX_VALUE : total + size;
        }
        return total;
    }

    private static boolean containsProtectedFile(File directory, HashSet<String> protectedFiles) {
        if (directory == null) return false;
        // A link is an unclassifiable boundary. Preserve the containing
        // project rather than resolving it while deciding what may be pruned.
        if (isSymbolicLink(directory)) return true;
        File[] children = directory.listFiles();
        if (children == null) return false;
        for (File child : children) {
            if (isSymbolicLink(child)) {
                return true;
            } else if (child.isDirectory()) {
                if (containsProtectedFile(child, protectedFiles)) return true;
            } else if (!protectedFiles.isEmpty()) {
                try {
                    if (protectedFiles.contains(child.getCanonicalPath())) return true;
                } catch (Exception ignored) {
                    // If a path cannot be resolved, preserve the project.
                    // Cleanup must never delete data it cannot classify.
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isSymbolicLink(File file) {
        if (file == null) return false;
        try {
            return java.nio.file.Files.isSymbolicLink(file.toPath());
        } catch (Exception ignored) {
            // If link inspection is unavailable, fail closed and retain the
            // cache entry instead of risking traversal during cleanup.
            return true;
        }
    }

    public static final class ImportedProject {
        public final ArrayList<PlateStore.Plate> plates;
        public final int activePlateIndex;
        public final Slicer.Config config;
        public final JSONArray inventorySnapshot;
        public final byte[] thumbnailPng;
        public final ArrayList<ModelHistoryStore.Timeline> history;

        ImportedProject(ArrayList<PlateStore.Plate> plates, int activePlateIndex, Slicer.Config config,
                        JSONArray inventorySnapshot, byte[] thumbnailPng,
                        ArrayList<ModelHistoryStore.Timeline> history) {
            this.plates = plates;
            this.activePlateIndex = activePlateIndex;
            this.config = config;
            this.inventorySnapshot = inventorySnapshot;
            this.thumbnailPng = thumbnailPng;
            this.history = history == null ? new ArrayList<>() : new ArrayList<>(history);
        }
    }

    private static final class ModelFile {
        final String path;
        final String uri;
        final String name;
        long sizeBytes;
        String sha256;

        ModelFile(String path, String uri, String name) {
            this.path = path;
            this.uri = uri;
            this.name = name;
        }
    }

    private static final class ExtractedModel {
        final File file;
        final long sizeBytes;
        final String sha256;

        ExtractedModel(File file, long sizeBytes, String sha256) {
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.sha256 = sha256;
        }
    }

    /** Limit compressed bytes consumed before ZipInputStream can expand them. */
    private static final class BoundedInputStream extends InputStream {
        private final InputStream input;
        private final long max;
        private long count;
        private boolean limitReached;

        BoundedInputStream(InputStream input, long max) {
            this.input = input;
            this.max = max;
        }

        @Override public int read() throws IOException {
            if (count >= max) { limitReached = true; return -1; }
            int value = input.read();
            if (value >= 0) count++;
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (buffer == null) throw new NullPointerException("buffer");
            if (offset < 0 || length < 0 || offset > buffer.length - length) throw new IndexOutOfBoundsException();
            if (length == 0) return 0;
            if (count >= max) { limitReached = true; return -1; }
            int allowed = (int) Math.min((long) length, max - count);
            int read = input.read(buffer, offset, allowed);
            if (read > 0) count += read;
            return read;
        }

        @Override public long skip(long length) throws IOException {
            if (length <= 0L) return 0L;
            if (count >= max) { limitReached = true; return 0L; }
            long skipped = input.skip(Math.min(length, max - count));
            if (skipped > 0L) count += skipped;
            return skipped;
        }

        @Override public void close() throws IOException { input.close(); }

        boolean limitReached() { return limitReached; }
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream output;
        private final long max;
        private long count;

        CountingOutputStream(OutputStream output, long max) {
            this.output = output;
            this.max = max;
        }

        @Override public void write(int value) throws IOException {
            ensure(1);
            output.write(value);
        }

        @Override public void write(byte[] values, int offset, int length) throws IOException {
            ensure(length);
            output.write(values, offset, length);
        }

        private void ensure(int length) throws IOException {
            if (length < 0 || count > max - length) throw new IOException("Project archive exceeds the size limit");
            count += length;
        }
    }
}
