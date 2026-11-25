package com.mbaliga.alloy;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Reads the auditable, bundled model catalog instead of duplicating model metadata in the UI. */
public final class ModelCatalog {
    private static final int MAX_CATALOG_BYTES = 256 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final long MAX_MODEL_BYTES = 256L * 1024L * 1024L;

    private ModelCatalog() { }

    public static final class Entry {
        public final String assetPath;
        public final String name;
        public final String author;
        public final String license;
        public final String source;
        public final String sha256;

        private Entry(String assetPath, String name, String author, String license,
                      String source, String sha256) {
            this.assetPath = assetPath;
            this.name = name;
            this.author = author;
            this.license = license;
            this.source = source;
            this.sha256 = sha256;
        }

        public String provenanceLabel() {
            return author + "  ·  " + license + "\n" + source;
        }

        @Override public String toString() { return name; }
    }

    public static ArrayList<Entry> load(AssetManager assets) throws IOException {
        if (assets == null) throw new IOException("Asset manager is unavailable");
        ArrayList<Entry> entries = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        Set<String> names = new HashSet<>();
        readCatalog(assets, "models/catalog.json", entries, paths, names, true);
        // Owner-provided visual assets live in the debug source set and are
        // copied into a release APK only by the explicit private visual-review
        // build flag. Keeping the overlay optional prevents user-owned CAD
        // from silently becoming part of an ordinary/public release while
        // still letting the owner browse it through the same audited Atlas.
        try {
            readCatalog(assets, "models/catalog-private.json", entries, paths, names, false);
        } catch (FileNotFoundException absent) {
            // Expected for ordinary/public builds.
        }
        return entries;
    }

    private static void readCatalog(AssetManager assets, String assetPath,
                                    ArrayList<Entry> entries, Set<String> paths,
                                    Set<String> names, boolean mandatory) throws IOException {
        JSONObject root;
        try (InputStream input = assets.open(assetPath)) {
            root = new JSONObject(new String(readBounded(input), "UTF-8"));
        } catch (FileNotFoundException absent) {
            if (mandatory) throw new IOException("Model catalog is missing", absent);
            throw absent;
        } catch (Exception error) {
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Model catalog is invalid", error);
        }
        if (root.optInt("schema_version", -1) != 1)
            throw new IOException("Unsupported model catalog schema");
        JSONArray values = root.optJSONArray("entries");
        if (values == null || values.length() == 0 || entries.size() + values.length() > MAX_ENTRIES)
            throw new IOException("Model catalog has no valid entries");

        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IOException("Model catalog entry is not an object");
            String path = normalizeAssetPath(value.optString("path", ""));
            String name = required(value, "name");
            String author = required(value, "author");
            String license = required(value, "license");
            String source = required(value, "source");
            String sha256 = required(value, "sha256").toLowerCase(Locale.US);
            if (!sha256.matches("[0-9a-f]{64}"))
                throw new IOException("Model catalog entry has an invalid SHA-256: " + name);
            if (!path.endsWith(".stl") && !path.endsWith(".obj") && !path.endsWith(".3mf")
                    && !path.endsWith(".step"))
                throw new IOException("Model catalog entry has an unsupported format: " + name);
            if (!paths.add(path) || !names.add(name.toLowerCase(Locale.US)))
                throw new IOException("Model catalog contains a duplicate entry: " + name);
            entries.add(new Entry(path, name, author, license, source, sha256));
        }
    }

    /** Verify a catalog entry before showing it as a trusted bundled example. */
    public static void verify(AssetManager assets, Entry entry) throws IOException {
        if (assets == null || entry == null) throw new IOException("Model catalog entry is missing");
        String digest;
        try (InputStream input = assets.open(entry.assetPath)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[32 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > MAX_MODEL_BYTES) throw new IOException("Bundled model is too large");
                hash.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : hash.digest()) hex.append(String.format(Locale.US, "%02x", value & 0xff));
            digest = hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        if (!entry.sha256.equals(digest))
            throw new IOException("Bundled model checksum mismatch: " + entry.name);
    }

    private static String required(JSONObject value, String key) throws IOException {
        String result = value.optString(key, "").trim();
        if (result.length() == 0) throw new IOException("Model catalog field is missing: " + key);
        return result;
    }

    private static String normalizeAssetPath(String path) throws IOException {
        String result = path == null ? "" : path.trim().replace('\\', '/');
        if (result.startsWith("app/src/main/assets/"))
            result = result.substring("app/src/main/assets/".length());
        if (result.length() == 0 || result.startsWith("/") || result.contains("..") || !result.startsWith("models/"))
            throw new IOException("Model catalog path is outside the bundled model directory");
        return result;
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() > MAX_CATALOG_BYTES - read)
                throw new IOException("Model catalog is too large");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
