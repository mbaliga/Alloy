package com.mbaliga.alloy;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

/** Bounded local snapshots for independent print plates in one phone project. */
public final class PlateStore {
    public static final int MAX_PLATES = 8;
    private static final int MAX_MODELS = 32;
    private static final int MAX_TRANSFORMS = 32;
    private static final int MAX_URI_LENGTH = 4096;
    private static final int MAX_NAME_LENGTH = 64;
    private static final String PLATES = "plates";
    private static final String ACTIVE = "active_plate";

    private final SharedPreferences preferences;

    public PlateStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("plate preferences are required");
        this.preferences = preferences;
    }

    public ArrayList<Plate> plates() {
        ArrayList<Plate> result = new ArrayList<>();
        String encoded = preferences.getString(PLATES, null);
        if (encoded == null || encoded.length() > MAX_PLATES * 32_768) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > MAX_PLATES) return new ArrayList<>();
            HashSet<Integer> seen = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) return new ArrayList<>();
                Plate plate = parse(value);
                if (plate == null || !seen.add(plate.index)) return new ArrayList<>();
                result.add(plate);
            }
            Collections.sort(result, (left, right) -> Integer.compare(left.index, right.index));
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    public Plate activePlate() {
        return plateAt(activeIndex());
    }

    public Plate plateAt(int index) {
        if (index < 0 || index >= MAX_PLATES) throw new IllegalArgumentException("plate index is invalid");
        for (Plate plate : plates()) if (plate.index == index) return plate;
        return Plate.empty(index);
    }

    public int activeIndex() {
        int value = preferences.getInt(ACTIVE, 0);
        return value < 0 || value >= MAX_PLATES ? 0 : value;
    }

    public void setActiveIndex(int index) {
        if (index < 0 || index >= MAX_PLATES) throw new IllegalArgumentException("plate index is invalid");
        preferences.edit().putInt(ACTIVE, index).apply();
    }

    public Plate createNext() {
        ArrayList<Plate> existing = plates();
        for (int index = 0; index < MAX_PLATES; index++) {
            boolean used = false;
            for (Plate plate : existing) if (plate.index == index) { used = true; break; }
            if (!used) {
                Plate created = Plate.empty(index);
                save(created);
                setActiveIndex(index);
                return created;
            }
        }
        throw new IllegalStateException("A project may contain at most " + MAX_PLATES + " plates");
    }

    public void save(Plate plate) {
        validate(plate);
        ArrayList<Plate> values = plates();
        boolean replaced = false;
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).index == plate.index) {
                values.set(index, plate);
                replaced = true;
                break;
            }
        }
        if (!replaced) values.add(plate);
        JSONArray encoded = new JSONArray();
        try {
            for (Plate value : values) encoded.put(encode(value));
        } catch (Exception error) {
            throw new IllegalArgumentException("plate snapshot could not be encoded", error);
        }
        preferences.edit().putString(PLATES, encoded.toString()).apply();
    }

    public void clear() {
        preferences.edit().remove(PLATES).remove(ACTIVE).apply();
    }

    private static JSONObject encode(Plate plate) throws org.json.JSONException {
        JSONObject value = new JSONObject();
        value.put("index", plate.index);
        value.put("name", plate.name);
        JSONArray uris = new JSONArray();
        JSONArray names = new JSONArray();
        for (int index = 0; index < plate.uris.size(); index++) {
            uris.put(plate.uris.get(index).toString());
            names.put(plate.names.get(index));
        }
        value.put("uris", uris);
        value.put("names", names);
        value.put("scale", plate.scale);
        value.put("rotation", plate.rotationDegrees);
        value.put("tilt_x", plate.tiltXDegrees);
        value.put("tilt_y", plate.tiltYDegrees);
        value.put("selected_part", plate.selectedPart);
        value.put("geometry_repair", plate.geometryRepairEnabled);
        JSONArray transforms = new JSONArray();
        for (MeshModel.PartTransform transform : plate.partTransforms) {
            transforms.put(new JSONObject()
                    .put("scale", transform.scale)
                    .put("rotation", transform.rotationDegrees)
                    .put("tilt_x", transform.tiltXDegrees)
                    .put("tilt_y", transform.tiltYDegrees)
                    .put("offset_x", transform.offsetX)
                    .put("offset_y", transform.offsetY));
        }
        value.put("part_transforms", transforms);
        return value;
    }

    /** Package-private bridge used by the bounded modeling history store. */
    static JSONObject encodeSnapshot(Plate plate) throws org.json.JSONException {
        validate(plate);
        return encode(plate);
    }

    /** Package-private bridge used by the bounded modeling history store. */
    static Plate decodeSnapshot(JSONObject value) {
        Plate plate = parse(value);
        if (plate == null) return null;
        try {
            validate(plate);
            return plate;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static Plate parse(JSONObject value) {
        int index = value.optInt("index", -1);
        if (index < 0 || index >= MAX_PLATES) return null;
        String name = normalizeName(value.optString("name", "Plate " + (index + 1)));
        JSONArray urisValue = value.optJSONArray("uris");
        JSONArray namesValue = value.optJSONArray("names");
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        if (urisValue != null) {
            if (urisValue.length() > MAX_MODELS || (namesValue != null && namesValue.length() > MAX_MODELS)) return null;
            for (int item = 0; item < urisValue.length(); item++) {
                String uriValue = urisValue.optString(item, "");
                if (!validUri(uriValue)) return null;
                uris.add(Uri.parse(uriValue));
                names.add(normalizeName(namesValue == null ? "model.stl" : namesValue.optString(item, "model.stl")));
            }
        }
        float scale = boundedFloat(value, "scale", 1f, MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE);
        float rotation = boundedFloat(value, "rotation", 0f, -360f, 360f);
        float tiltX = boundedFloat(value, "tilt_x", 0f, -360f, 360f);
        float tiltY = boundedFloat(value, "tilt_y", 0f, -360f, 360f);
        int selected = value.optInt("selected_part", -1);
        if (selected < -1 || selected >= 1_000_000) selected = -1;
        Object repairValue = value.opt("geometry_repair");
        if (repairValue != null && repairValue != org.json.JSONObject.NULL && !(repairValue instanceof Boolean)) return null;
        boolean geometryRepair = repairValue instanceof Boolean && (Boolean) repairValue;
        ArrayList<MeshModel.PartTransform> transforms = new ArrayList<>();
        JSONArray transformValues = value.optJSONArray("part_transforms");
        if (transformValues != null) {
            if (transformValues.length() > MAX_TRANSFORMS) return null;
            for (int item = 0; item < transformValues.length(); item++) {
                JSONObject transform = transformValues.optJSONObject(item);
                if (transform == null) return null;
                transforms.add(new MeshModel.PartTransform(
                        boundedFloat(transform, "scale", 1f, 0.1f, 10f),
                        boundedFloat(transform, "rotation", 0f, -360f, 360f),
                        boundedFloat(transform, "tilt_x", 0f, -360f, 360f),
                        boundedFloat(transform, "tilt_y", 0f, -360f, 360f),
                        boundedFloat(transform, "offset_x", 0f, -1_000f, 1_000f),
                        boundedFloat(transform, "offset_y", 0f, -1_000f, 1_000f)));
            }
        }
        return new Plate(index, name, uris, names, scale, rotation, tiltX, tiltY, selected, transforms, geometryRepair);
    }

    private static void validate(Plate plate) {
        if (plate == null || plate.index < 0 || plate.index >= MAX_PLATES
                || plate.uris == null || plate.names == null || plate.uris.size() != plate.names.size()
                || plate.uris.size() > MAX_MODELS || plate.partTransforms == null
                || plate.partTransforms.size() > MAX_TRANSFORMS)
            throw new IllegalArgumentException("plate snapshot is invalid");
        if (!finite(plate.scale) || plate.scale < MeshModel.MIN_MODEL_SCALE || plate.scale > MeshModel.MAX_MODEL_SCALE || !finite(plate.rotationDegrees)
                || plate.rotationDegrees < -360f || plate.rotationDegrees > 360f
                || !finite(plate.tiltXDegrees) || plate.tiltXDegrees < -360f || plate.tiltXDegrees > 360f
                || !finite(plate.tiltYDegrees) || plate.tiltYDegrees < -360f || plate.tiltYDegrees > 360f)
            throw new IllegalArgumentException("plate transform is invalid");
        for (int index = 0; index < plate.uris.size(); index++) {
            if (plate.uris.get(index) == null || !validUri(plate.uris.get(index).toString()))
                throw new IllegalArgumentException("plate source URI is invalid");
            normalizeName(plate.names.get(index));
        }
        for (MeshModel.PartTransform transform : plate.partTransforms) {
            if (transform == null || !finite(transform.scale) || transform.scale <= 0f || transform.scale > 10f
                || !finite(transform.rotationDegrees) || transform.rotationDegrees < -360f || transform.rotationDegrees > 360f
                    || !finite(transform.tiltXDegrees) || transform.tiltXDegrees < -360f || transform.tiltXDegrees > 360f
                    || !finite(transform.tiltYDegrees) || transform.tiltYDegrees < -360f || transform.tiltYDegrees > 360f
                    || !finite(transform.offsetX) || !finite(transform.offsetY)
                    || Math.abs(transform.offsetX) > 1_000f || Math.abs(transform.offsetY) > 1_000f)
                throw new IllegalArgumentException("plate part transform is invalid");
        }
        normalizeName(plate.name);
    }

    private static float boundedFloat(JSONObject object, String key, float fallback, float min, float max) {
        double value = object.optDouble(key, fallback);
        return Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max ? fallback : (float) value;
    }

    private static boolean validUri(String value) {
        if (value == null || value.length() == 0 || value.length() > MAX_URI_LENGTH) return false;
        String scheme = Uri.parse(value).getScheme();
        return "content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme);
    }

    private static String normalizeName(String value) {
        if (value == null || value.trim().length() == 0) return "model.stl";
        String normalized = value.trim();
        return normalized.length() > MAX_NAME_LENGTH ? normalized.substring(0, MAX_NAME_LENGTH) : normalized;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Plate {
        public final int index;
        public final String name;
        public final ArrayList<Uri> uris;
        public final ArrayList<String> names;
        public final float scale;
        public final float rotationDegrees;
        public final float tiltXDegrees;
        public final float tiltYDegrees;
        public final int selectedPart;
        public final ArrayList<MeshModel.PartTransform> partTransforms;
        public final boolean geometryRepairEnabled;

        public Plate(int index, String name, ArrayList<Uri> uris, ArrayList<String> names,
                     float scale, float rotationDegrees, int selectedPart,
                     ArrayList<MeshModel.PartTransform> partTransforms) {
            this(index, name, uris, names, scale, rotationDegrees, 0f, 0f, selectedPart, partTransforms);
        }

        public Plate(int index, String name, ArrayList<Uri> uris, ArrayList<String> names,
                     float scale, float rotationDegrees, float tiltXDegrees, float tiltYDegrees,
                     int selectedPart, ArrayList<MeshModel.PartTransform> partTransforms) {
            this(index, name, uris, names, scale, rotationDegrees, tiltXDegrees, tiltYDegrees,
                    selectedPart, partTransforms, false);
        }

        public Plate(int index, String name, ArrayList<Uri> uris, ArrayList<String> names,
                     float scale, float rotationDegrees, float tiltXDegrees, float tiltYDegrees,
                     int selectedPart, ArrayList<MeshModel.PartTransform> partTransforms,
                     boolean geometryRepairEnabled) {
            this.index = index;
            this.name = normalizeName(name == null ? "Plate " + (index + 1) : name);
            this.uris = uris == null ? new ArrayList<>() : new ArrayList<>(uris);
            this.names = names == null ? new ArrayList<>() : new ArrayList<>(names);
            this.scale = scale;
            this.rotationDegrees = rotationDegrees;
            this.tiltXDegrees = tiltXDegrees;
            this.tiltYDegrees = tiltYDegrees;
            this.selectedPart = selectedPart;
            this.partTransforms = partTransforms == null ? new ArrayList<>() : new ArrayList<>(partTransforms);
            this.geometryRepairEnabled = geometryRepairEnabled;
        }

        public static Plate empty(int index) {
            return new Plate(index, "Plate " + (index + 1), new ArrayList<>(), new ArrayList<>(), 1f, 0f, -1, new ArrayList<>());
        }

        public String summary() {
            return name + (uris.isEmpty() ? "  ·  empty" : "  ·  " + uris.size() + " model source" + (uris.size() == 1 ? "" : "s"));
        }
    }
}
