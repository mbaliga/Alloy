package com.mbaliga.alloy;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Bounded local history for portable projects; it never stores printer secrets. */
public final class ProjectHistoryStore {
    private static final String HISTORY = "history";
    private static final int MAX_RECORDS = 24;
    private static final int MAX_NAME = 150;
    private static final int MAX_URI = 4_096;
    private static final int MAX_MODELS = 256;
    private static final int MAX_PLATES = 8;
    private static final int MAX_STORAGE = 96 * 1024;
    private final SharedPreferences preferences;

    public ProjectHistoryStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("project history preferences are required");
        this.preferences = preferences;
    }

    public synchronized ArrayList<Record> records() {
        ArrayList<Record> result = new ArrayList<>();
        String encoded = preferences.getString(HISTORY, null);
        if (encoded == null || encoded.length() > MAX_STORAGE) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > MAX_RECORDS) return new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) return new ArrayList<>();
                String name = normalizeName(value.optString("name", ""));
                String uri = normalizeUri(value.optString("uri", ""));
                long openedAt = value.optLong("opened_at", 0L);
                int modelCount = value.optInt("model_count", 0);
                int plateCount = value.optInt("plate_count", 0);
                if (name.length() == 0 || openedAt <= 0L || modelCount < 0 || modelCount > MAX_MODELS
                        || plateCount < 0 || plateCount > MAX_PLATES || !seen.add(identity(uri, name)))
                    return new ArrayList<>();
                result.add(new Record(name, uri, openedAt, modelCount, plateCount));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    public synchronized void record(String name, Uri archiveUri, int modelCount, int plateCount) {
        String safeName = normalizeName(name);
        String safeUri = normalizeUri(archiveUri == null ? "" : archiveUri.toString());
        if (safeName.length() == 0) throw new IllegalArgumentException("project history name is required");
        if (modelCount < 0 || modelCount > MAX_MODELS || plateCount < 0 || plateCount > MAX_PLATES)
            throw new IllegalArgumentException("project history counts are invalid");
        ArrayList<Record> next = records();
        String identity = identity(safeUri, safeName);
        for (int index = next.size() - 1; index >= 0; index--) {
            Record existing = next.get(index);
            if (identity.equals(identity(existing.archiveUri, existing.name))) next.remove(index);
        }
        next.add(0, new Record(safeName, safeUri, System.currentTimeMillis(), modelCount, plateCount));
        while (next.size() > MAX_RECORDS) next.remove(next.size() - 1);
        JSONArray values = new JSONArray();
        try {
            for (Record item : next) {
                values.put(new JSONObject().put("name", item.name).put("uri", item.archiveUri)
                        .put("opened_at", item.openedAt).put("model_count", item.modelCount)
                        .put("plate_count", item.plateCount));
            }
        } catch (Exception error) {
            throw new IllegalStateException("project history could not be encoded", error);
        }
        if (values.toString().length() > MAX_STORAGE)
            throw new IllegalStateException("project history exceeds its storage limit");
        if (!preferences.edit().putString(HISTORY, values.toString()).commit())
            throw new IllegalStateException("project history could not be saved");
    }

    public synchronized void clear() {
        preferences.edit().remove(HISTORY).commit();
    }

    private static String identity(String uri, String name) {
        return uri.length() == 0 ? "name:" + name : "uri:" + uri;
    }

    private static String normalizeUri(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.length() > MAX_URI) throw new IllegalArgumentException("project history URI is too long");
        if (normalized.length() == 0) return "";
        String scheme = Uri.parse(normalized).getScheme();
        if (!("content".equalsIgnoreCase(scheme) || "file".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("project history URI is unsupported");
        return normalized;
    }

    private static String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim().replace("\n", " ").replace("\r", " ");
        return normalized.length() > MAX_NAME ? normalized.substring(0, MAX_NAME) : normalized;
    }

    public static final class Record {
        public final String name;
        public final String archiveUri;
        public final long openedAt;
        public final int modelCount;
        public final int plateCount;

        Record(String name, String archiveUri, long openedAt, int modelCount, int plateCount) {
            this.name = name;
            this.archiveUri = archiveUri;
            this.openedAt = openedAt;
            this.modelCount = modelCount;
            this.plateCount = plateCount;
        }

        public String summary() {
            String detail = modelCount + " model" + (modelCount == 1 ? "" : "s");
            if (plateCount > 1) detail += "  ·  " + plateCount + " plates";
            return name + "\n" + detail + "  ·  " + new java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.US)
                    .format(new java.util.Date(openedAt));
        }
    }
}
