package com.mbaliga.alloy;

import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;

/**
 * Small durable index for model sources imported on this phone.
 *
 * The bytes remain in ModelStore's content-addressed cache. This index only
 * keeps a friendly name and cache URI so the Model Atlas can reopen an import
 * without retaining a fragile document-provider grant or copying model bytes
 * a second time.
 */
public final class ImportedModelStore {
    private static final int VERSION = 2;
    private static final int MAX_ENTRIES = 24;
    private static final int MAX_NAME_LENGTH = 150;
    private static final int MAX_URI_LENGTH = 4096;
    private static final String STATE = "entries";
    private static final String SAFE_NAME = "[0-9a-f]{64}\\.(stl|obj|3mf|step)";

    private final SharedPreferences preferences;

    public ImportedModelStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("model library preferences are required");
        this.preferences = preferences;
    }

    /** Add or move imported cache files to the front of the recent shelf. */
    public synchronized void remember(File appFilesDir, ArrayList<Uri> uris, ArrayList<String> names) {
        if (appFilesDir == null || uris == null || names == null || uris.size() != names.size()) return;
        ArrayList<Entry> current = read(appFilesDir);
        long now = Math.max(1L, System.currentTimeMillis());
        for (int index = 0; index < uris.size(); index++) {
            Uri uri = uris.get(index);
            File file = validCacheFile(appFilesDir, uri);
            if (file == null) continue;
            String digest = cacheDigest(file);
            if (digest == null) continue;
            String uriValue = Uri.fromFile(file).toString();
            for (int existing = current.size() - 1; existing >= 0; existing--) {
                if (uriValue.equals(current.get(existing).uri.toString())) current.remove(existing);
            }
            current.add(0, new Entry(Uri.fromFile(file), normalizeName(names.get(index)), now,
                    file.length(), file.lastModified(), digest));
            while (current.size() > MAX_ENTRIES) current.remove(current.size() - 1);
        }
        write(current);
    }

    /** Return newest-first entries whose cache files still pass local bounds. */
    public synchronized ArrayList<Entry> entries(File appFilesDir) {
        ArrayList<Entry> result = read(appFilesDir);
        write(result);
        return result;
    }

    private ArrayList<Entry> read(File appFilesDir) {
        ArrayList<Entry> result = new ArrayList<>();
        if (appFilesDir == null) return result;
        String encoded = preferences.getString(STATE, null);
        if (encoded == null || encoded.length() == 0 || encoded.length() > 128 * 1024) return result;
        try {
            JSONObject root = new JSONObject(encoded);
            if (root.optInt("version", -1) != VERSION) return result;
            JSONArray values = root.optJSONArray("values");
            if (values == null || values.length() > MAX_ENTRIES) return result;
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) continue;
                Uri uri = Uri.parse(value.optString("uri", ""));
                File file = validCacheFile(appFilesDir, uri);
                String name = normalizeName(value.optString("name", "model"));
                long at = value.optLong("last_opened", 0L);
                long size = value.optLong("size_bytes", -1L);
                long modified = value.optLong("last_modified", -1L);
                String digest = value.optString("sha256", "").toLowerCase(java.util.Locale.US);
                if (file == null || name.length() == 0 || at <= 0L || size <= 0L || modified < 0L
                        || !digest.matches("[0-9a-f]{64}")) continue;
                if (size != file.length() || modified != file.lastModified()
                        || !digest.equals(file.getName().substring(0, 64))) {
                    digest = cacheDigest(file);
                    if (digest == null) continue;
                    size = file.length();
                    modified = file.lastModified();
                }
                result.add(new Entry(Uri.fromFile(file), name, at, size, modified, digest));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        Collections.sort(result, Comparator.comparingLong((Entry value) -> value.lastOpened).reversed());
        return deduplicate(result);
    }

    private static ArrayList<Entry> deduplicate(ArrayList<Entry> values) {
        ArrayList<Entry> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (Entry value : values) {
            if (value != null && seen.add(value.uri.toString())) {
                result.add(value);
                if (result.size() == MAX_ENTRIES) break;
            }
        }
        return result;
    }

    private void write(ArrayList<Entry> values) {
        try {
            JSONObject root = new JSONObject().put("version", VERSION);
            JSONArray encoded = new JSONArray();
            for (Entry value : deduplicate(values)) {
                encoded.put(new JSONObject()
                        .put("uri", value.uri.toString())
                        .put("name", normalizeName(value.name))
                        .put("last_opened", Math.max(1L, value.lastOpened))
                        .put("size_bytes", value.sizeBytes)
                        .put("last_modified", value.lastModified)
                        .put("sha256", value.sha256));
            }
            preferences.edit().putString(STATE, root.put("values", encoded).toString()).commit();
        } catch (Exception error) {
            throw new IllegalStateException("model library could not be saved", error);
        }
    }

    private static File validCacheFile(File appFilesDir, Uri uri) {
        if (appFilesDir == null || uri == null || !"file".equalsIgnoreCase(uri.getScheme())) return null;
        String path = uri.getPath();
        if (path == null || path.length() == 0 || path.length() > MAX_URI_LENGTH) return null;
        File root = new File(appFilesDir, "models");
        File file = new File(path);
        try {
            if (PrinterTransport.isSymbolicLink(appFilesDir) || PrinterTransport.isSymbolicLink(root)
                    || PrinterTransport.isSymbolicLink(file) || !file.isFile() || !file.canRead()) return null;
            String rootPath = root.getCanonicalPath();
            String filePath = file.getCanonicalPath();
            if (!filePath.startsWith(rootPath + File.separator) || !file.getName().matches(SAFE_NAME)) return null;
            if (file.length() <= 0L || file.length() > ModelStore.MAX_MODEL_BYTES) return null;
            return file;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeName(String value) {
        if (value == null || value.trim().length() == 0) return "model";
        String normalized = value.trim();
        return normalized.length() > MAX_NAME_LENGTH ? normalized.substring(0, MAX_NAME_LENGTH) : normalized;
    }

    private static String cacheDigest(File file) {
        try {
            String expected = file.getName().substring(0, 64);
            String actual = ModelStore.sha256(file);
            return expected.equals(actual) ? actual : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static final class Entry {
        public final Uri uri;
        public final String name;
        public final long lastOpened;
        public final long sizeBytes;
        public final long lastModified;
        public final String sha256;

        private Entry(Uri uri, String name, long lastOpened, long sizeBytes,
                      long lastModified, String sha256) {
            this.uri = uri;
            this.name = name;
            this.lastOpened = lastOpened;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
            this.sha256 = sha256;
        }
    }
}
