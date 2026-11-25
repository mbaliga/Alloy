package com.mbaliga.alloy;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;

import java.util.ArrayList;

/**
 * Small local project checkpoint for the active plate. PlateStore owns the
 * bounded multi-plate snapshot layer; Room remains the next step for rich
 * history, thumbnails and searchable project metadata.
 */
public final class ProjectStore {
    private static final int MAX_SAVED_MODELS = 32;
    private static final int MAX_SAVED_URI_LENGTH = 4096;
    private static final int MAX_SAVED_NAME_LENGTH = 150;
    private static final String MODEL_URI = "model_uri";
    private static final String MODEL_NAME = "model_name";
    private static final String MODEL_URIS = "model_uris";
    private static final String MODEL_NAMES = "model_names";
    private static final String MODEL_SCALE = "model_scale";
    private static final String MODEL_ROTATION = "model_rotation";
    private static final String MODEL_TILT_X = "model_tilt_x";
    private static final String MODEL_TILT_Y = "model_tilt_y";
    private static final String SELECTED_PART = "selected_part";
    private static final String LAYER_HEIGHT = "layer_height";
    private static final String FIRST_LAYER_HEIGHT = "first_layer_height";
    private static final String INFILL = "infill";
    private static final String NOZZLE_TEMPERATURE = "nozzle_temperature";
    private static final String FIRST_LAYER_NOZZLE_TEMPERATURE = "first_layer_nozzle_temperature";
    private static final String BED_TEMPERATURE = "bed_temperature";
    private static final String FIRST_LAYER_BED_TEMPERATURE = "first_layer_bed_temperature";
    private static final String EXTRUSION_MULTIPLIER = "extrusion_multiplier";
    private static final String MAX_VOLUMETRIC_SPEED = "max_volumetric_speed";
    private static final String TRAVEL_SPEED = "travel_speed";
    private static final String OUTER_WALL_SPEED = "outer_wall_speed";
    private static final String INNER_WALL_SPEED = "inner_wall_speed";
    private static final String INFILL_SPEED = "infill_speed";
    private static final String INITIAL_LAYER_SPEED = "initial_layer_speed";
    private static final String FAN_MIN_PERCENT = "fan_min_percent";
    private static final String FAN_MAX_PERCENT = "fan_max_percent";
    private static final String SUPPORTS = "supports";
    private static final String SUPPORT_THRESHOLD = "support_threshold";
    private static final String PERIMETERS = "perimeters";
    private static final String TOP_LAYERS = "top_layers";
    private static final String BOTTOM_LAYERS = "bottom_layers";
    private static final String NATIVE_SETTINGS = "native_settings";
    private static final String PART_TRANSFORMS = "part_transforms";
    private static final String GEOMETRY_REPAIR = "geometry_repair";
    private static final int MAX_PART_TRANSFORMS = 32;

    private final SharedPreferences preferences;

    public ProjectStore(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    public void saveModel(Uri uri, String displayName) {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        ArrayList<String> names = new ArrayList<>();
        names.add(displayName);
        saveModels(uris, names);
    }

    public void saveModels(ArrayList<Uri> uris, ArrayList<String> names) {
        if (uris == null || uris.isEmpty() || names == null || names.size() != uris.size())
            throw new IllegalArgumentException("project sources are invalid");
        if (uris.size() > MAX_SAVED_MODELS) throw new IllegalArgumentException("project contains too many model sources");
        JSONArray uriArray = new JSONArray();
        JSONArray nameArray = new JSONArray();
        for (int index = 0; index < uris.size(); index++) {
            if (uris.get(index) == null) throw new IllegalArgumentException("project source URI is missing");
            String uriValue = uris.get(index).toString();
            validateUri(uriValue);
            uriArray.put(uriValue);
            nameArray.put(normalizeName(names.get(index)));
        }
        String firstName = normalizeName(names.get(0));
        preferences.edit()
                .putString(MODEL_URIS, uriArray.toString())
                .putString(MODEL_NAMES, nameArray.toString())
                .putString(MODEL_URI, uris.get(0).toString())
                .putString(MODEL_NAME, firstName)
                .apply();
    }

    public void saveTransform(float scale, float rotationDegrees) {
        saveTransform(scale, rotationDegrees, 0f, 0f);
    }

    public void saveTransform(float scale, float rotationDegrees, float tiltXDegrees, float tiltYDegrees) {
        if (!finite(scale) || scale < MeshModel.MIN_MODEL_SCALE || scale > MeshModel.MAX_MODEL_SCALE
                || !finite(rotationDegrees) || rotationDegrees < -360f || rotationDegrees > 360f
                || !finite(tiltXDegrees) || tiltXDegrees < -360f || tiltXDegrees > 360f
                || !finite(tiltYDegrees) || tiltYDegrees < -360f || tiltYDegrees > 360f)
            throw new IllegalArgumentException("model transform is invalid");
        preferences.edit()
                .putFloat(MODEL_SCALE, scale)
                .putFloat(MODEL_ROTATION, rotationDegrees)
                .putFloat(MODEL_TILT_X, tiltXDegrees)
                .putFloat(MODEL_TILT_Y, tiltYDegrees)
                .apply();
    }

    public float savedScale() {
        return boundedFloat(MODEL_SCALE, 1f, MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE);
    }
    public float savedRotation() { return preferences.getFloat(MODEL_ROTATION, 0f); }
    public float savedTiltX() { return boundedFloat(MODEL_TILT_X, 0f, -360f, 360f); }
    public float savedTiltY() { return boundedFloat(MODEL_TILT_Y, 0f, -360f, 360f); }
    public int savedSelectedPart() { return preferences.getInt(SELECTED_PART, -1); }

    public boolean savedGeometryRepair() { return preferences.getBoolean(GEOMETRY_REPAIR, false); }

    public void saveGeometryRepair(boolean enabled) {
        preferences.edit().putBoolean(GEOMETRY_REPAIR, enabled).apply();
    }

    public void saveSelectedPart(int part) {
        preferences.edit().putInt(SELECTED_PART, part).apply();
    }

    public void savePartTransforms(ArrayList<MeshModel.PartTransform> transforms) {
        if (transforms == null || transforms.size() > MAX_PART_TRANSFORMS)
            throw new IllegalArgumentException("part transform list is invalid");
        JSONArray values = new JSONArray();
        for (MeshModel.PartTransform transform : transforms) {
            if (transform == null || !finite(transform.scale) || transform.scale <= 0f || transform.scale > 10f
                    || !finite(transform.rotationDegrees) || transform.rotationDegrees < -360f || transform.rotationDegrees > 360f
                    || !finite(transform.tiltXDegrees) || transform.tiltXDegrees < -360f || transform.tiltXDegrees > 360f
                    || !finite(transform.tiltYDegrees) || transform.tiltYDegrees < -360f || transform.tiltYDegrees > 360f
                    || !finite(transform.offsetX) || !finite(transform.offsetY)
                    || Math.abs(transform.offsetX) > 1_000f || Math.abs(transform.offsetY) > 1_000f)
                throw new IllegalArgumentException("part transform is invalid");
                try {
                    values.put(new org.json.JSONObject()
                            .put("scale", transform.scale)
                            .put("rotation", transform.rotationDegrees)
                            .put("tilt_x", transform.tiltXDegrees)
                            .put("tilt_y", transform.tiltYDegrees)
                            .put("offset_x", transform.offsetX)
                            .put("offset_y", transform.offsetY));
            } catch (org.json.JSONException error) {
                throw new IllegalArgumentException("part transform could not be encoded", error);
            }
        }
        preferences.edit().putString(PART_TRANSFORMS, values.toString()).apply();
    }

    public ArrayList<MeshModel.PartTransform> savedPartTransforms(int partCount) {
        ArrayList<MeshModel.PartTransform> result = new ArrayList<>();
        if (partCount <= 0 || partCount > MAX_PART_TRANSFORMS) return result;
        String encoded = preferences.getString(PART_TRANSFORMS, null);
        if (encoded == null || encoded.length() > MAX_PART_TRANSFORMS * 160) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > partCount) return result;
            for (int index = 0; index < values.length(); index++) {
                org.json.JSONObject value = values.optJSONObject(index);
                if (value == null) return new ArrayList<>();
                float scale = boundedFloat(value, "scale", 1f, 0.1f, 10f);
                float rotation = boundedFloat(value, "rotation", 0f, -360f, 360f);
                float tiltX = boundedFloat(value, "tilt_x", 0f, -360f, 360f);
                float tiltY = boundedFloat(value, "tilt_y", 0f, -360f, 360f);
                float offsetX = boundedFloat(value, "offset_x", 0f, -1_000f, 1_000f);
                float offsetY = boundedFloat(value, "offset_y", 0f, -1_000f, 1_000f);
                result.add(new MeshModel.PartTransform(scale, rotation, tiltX, tiltY, offsetX, offsetY));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    public void restoreRecipe(Slicer.Config config) {
        config.layerHeight = boundedFloat(LAYER_HEIGHT, config.layerHeight, 0.08f, 0.40f);
        config.firstLayerHeight = boundedFloat(FIRST_LAYER_HEIGHT, config.firstLayerHeight, 0.08f, 0.40f);
        config.infill = boundedFloat(INFILL, config.infill, 0f, 1f);
        config.nozzleTemperature = boundedFloat(NOZZLE_TEMPERATURE, config.nozzleTemperature, 0f, 400f);
        config.firstLayerNozzleTemperature = boundedFloat(FIRST_LAYER_NOZZLE_TEMPERATURE, config.firstLayerNozzleTemperature, 0f, 400f);
        config.bedTemperature = boundedFloat(BED_TEMPERATURE, config.bedTemperature, 0f, 150f);
        config.firstLayerBedTemperature = boundedFloat(FIRST_LAYER_BED_TEMPERATURE, config.firstLayerBedTemperature, 0f, 150f);
        config.extrusionMultiplier = boundedFloat(EXTRUSION_MULTIPLIER, config.extrusionMultiplier, 0.5f, 2f);
        config.maxVolumetricSpeed = boundedFloat(MAX_VOLUMETRIC_SPEED, config.maxVolumetricSpeed, 0.1f, 200f);
        config.travelSpeed = boundedFloat(TRAVEL_SPEED, config.travelSpeed, 1f, 2_000f);
        config.outerWallSpeed = boundedFloat(OUTER_WALL_SPEED, config.outerWallSpeed, 1f, 1_000f);
        config.innerWallSpeed = boundedFloat(INNER_WALL_SPEED, config.innerWallSpeed, 1f, 1_000f);
        config.infillSpeed = boundedFloat(INFILL_SPEED, config.infillSpeed, 1f, 1_000f);
        config.initialLayerSpeed = boundedFloat(INITIAL_LAYER_SPEED, config.initialLayerSpeed, 1f, 1_000f);
        config.fanMinPercent = boundedFloat(FAN_MIN_PERCENT, config.fanMinPercent, 0f, 100f);
        config.fanMaxPercent = Math.max(config.fanMinPercent, boundedFloat(FAN_MAX_PERCENT, config.fanMaxPercent, 0f, 100f));
        config.supports = preferences.getBoolean(SUPPORTS, config.supports);
        config.supportThresholdDegrees = boundedFloat(SUPPORT_THRESHOLD, config.supportThresholdDegrees, 0f, 90f);
        config.perimeters = boundedInt(PERIMETERS, config.perimeters, 1, 20);
        config.topLayers = boundedInt(TOP_LAYERS, config.topLayers, 0, 100);
        config.bottomLayers = boundedInt(BOTTOM_LAYERS, config.bottomLayers, 0, 100);
        String encodedNativeSettings = preferences.getString(NATIVE_SETTINGS, null);
        if (encodedNativeSettings != null && encodedNativeSettings.length() <= 256 * 1024) {
            try {
                java.util.LinkedHashMap<String, String> restored = NativeSettings.parse(
                        new org.json.JSONObject(encodedNativeSettings), true);
                config.nativeSettings.clear();
                config.nativeSettings.putAll(restored);
            } catch (Exception ignored) {
                // Keep the validated packaged profile projection when an old
                // or damaged local preference cannot be restored.
            }
        }
    }

    public void saveRecipe(Slicer.Config config) {
        if (config == null) throw new IllegalArgumentException("recipe is required");
        final String encodedNativeSettings;
        try {
            encodedNativeSettings = NativeSettings.encode(config.nativeSettings).toString();
        } catch (Exception error) {
            throw new IllegalArgumentException("native settings could not be saved", error);
        }
        preferences.edit()
                .putFloat(LAYER_HEIGHT, config.layerHeight)
                .putFloat(FIRST_LAYER_HEIGHT, config.firstLayerHeight)
                .putFloat(INFILL, config.infill)
                .putFloat(NOZZLE_TEMPERATURE, config.nozzleTemperature)
                .putFloat(FIRST_LAYER_NOZZLE_TEMPERATURE, config.firstLayerNozzleTemperature)
                .putFloat(BED_TEMPERATURE, config.bedTemperature)
                .putFloat(FIRST_LAYER_BED_TEMPERATURE, config.firstLayerBedTemperature)
                .putFloat(EXTRUSION_MULTIPLIER, config.extrusionMultiplier)
                .putFloat(MAX_VOLUMETRIC_SPEED, config.maxVolumetricSpeed)
                .putFloat(TRAVEL_SPEED, config.travelSpeed)
                .putFloat(OUTER_WALL_SPEED, config.outerWallSpeed)
                .putFloat(INNER_WALL_SPEED, config.innerWallSpeed)
                .putFloat(INFILL_SPEED, config.infillSpeed)
                .putFloat(INITIAL_LAYER_SPEED, config.initialLayerSpeed)
                .putFloat(FAN_MIN_PERCENT, config.fanMinPercent)
                .putFloat(FAN_MAX_PERCENT, config.fanMaxPercent)
                .putBoolean(SUPPORTS, config.supports)
                .putFloat(SUPPORT_THRESHOLD, config.supportThresholdDegrees)
                .putInt(PERIMETERS, config.perimeters)
                .putInt(TOP_LAYERS, config.topLayers)
                .putInt(BOTTOM_LAYERS, config.bottomLayers)
                .putString(NATIVE_SETTINGS, encodedNativeSettings)
                .apply();
    }

    private float boundedFloat(String key, float fallback, float min, float max) {
        float value = preferences.getFloat(key, fallback);
        return Float.isNaN(value) || Float.isInfinite(value) || value < min || value > max ? fallback : value;
    }

    private static float boundedFloat(org.json.JSONObject object, String key, float fallback, float min, float max) {
        double value = object.optDouble(key, fallback);
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max) return fallback;
        return (float) value;
    }

    private int boundedInt(String key, int fallback, int min, int max) {
        int value = preferences.getInt(key, fallback);
        return value < min || value > max ? fallback : value;
    }

    public SavedProject savedProject() {
        String encodedUris = preferences.getString(MODEL_URIS, null);
        String encodedNames = preferences.getString(MODEL_NAMES, null);
        if (encodedUris != null && encodedUris.length() <= MAX_SAVED_MODELS * MAX_SAVED_URI_LENGTH
                && (encodedNames == null || encodedNames.length() <= MAX_SAVED_MODELS * MAX_SAVED_NAME_LENGTH)) {
            try {
                JSONArray uriArray = new JSONArray(encodedUris);
                JSONArray nameArray = encodedNames == null ? new JSONArray() : new JSONArray(encodedNames);
                if (uriArray.length() > MAX_SAVED_MODELS) throw new IllegalArgumentException("saved project contains too many model sources");
                ArrayList<Uri> uris = new ArrayList<>();
                ArrayList<String> names = new ArrayList<>();
                for (int index = 0; index < uriArray.length(); index++) {
                    String value = uriArray.getString(index);
                    validateUri(value);
                    uris.add(Uri.parse(value));
                    names.add(index < nameArray.length() ? normalizeName(nameArray.optString(index, "model.stl")) : "model.stl");
                }
                if (!uris.isEmpty()) return new SavedProject(uris, names);
            } catch (Exception ignored) {
                // Fall through to the legacy single-source checkpoint.
            }
        }
        String uri = preferences.getString(MODEL_URI, null);
        String name = normalizeName(preferences.getString(MODEL_NAME, "model.stl"));
        if (uri == null) return null;
        try { validateUri(uri); } catch (IllegalArgumentException ignored) { return null; }
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(Uri.parse(uri));
        ArrayList<String> names = new ArrayList<>();
        names.add(name);
        return new SavedProject(uris, names);
    }

    public void clearModel() {
        preferences.edit().remove(MODEL_URI).remove(MODEL_NAME).remove(MODEL_URIS).remove(MODEL_NAMES)
                .remove(MODEL_SCALE).remove(MODEL_ROTATION).remove(MODEL_TILT_X).remove(MODEL_TILT_Y)
                .remove(SELECTED_PART).remove(PART_TRANSFORMS).remove(GEOMETRY_REPAIR).apply();
    }

    public static final class SavedProject {
        public final ArrayList<Uri> uris;
        public final ArrayList<String> names;

        SavedProject(ArrayList<Uri> uris, ArrayList<String> names) {
            this.uris = uris;
            this.names = names;
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.trim().length() == 0) return "model.stl";
        String normalized = value.trim();
        return normalized.length() > MAX_SAVED_NAME_LENGTH
                ? normalized.substring(0, MAX_SAVED_NAME_LENGTH) : normalized;
    }

    private static void validateUri(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_SAVED_URI_LENGTH)
            throw new IllegalArgumentException("saved project URI is invalid");
        String scheme = Uri.parse(value).getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme))
            throw new IllegalArgumentException("saved project URI scheme is unsupported");
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
