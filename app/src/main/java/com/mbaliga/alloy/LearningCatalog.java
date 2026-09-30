package com.mbaliga.alloy;

import android.content.res.AssetManager;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Versioned, offline learning records. UI code renders records; it does not own safety copy. */
public final class LearningCatalog {
    private static final String ASSET = "learn/catalog-v1.json";
    private static final int MAX_BYTES = 192 * 1024;
    private static final int MAX_ARTICLES = 64;
    private static final int MAX_ARTWORK_EDGE_PX = 4096;
    private static final int MIN_START_ARTICLES = 7;
    private static final int MIN_CHEATSHEET_ARTICLES = 12;
    private static final int MIN_TROUBLESHOOT_ARTICLES = 24;
    private static final int MIN_HARD_STOP_ARTICLES = 5;
    private static final Set<String> KINDS = kinds();

    public static final class Article {
        public final String id, kind, title, summary, safety, scope, source, artwork;
        public final List<String> keywords, steps;

        Article(JSONObject value) {
            id = required(value, "id"); kind = required(value, "kind"); title = required(value, "title");
            summary = required(value, "summary"); safety = required(value, "safety");
            scope = required(value, "scope"); source = required(value, "source");
            artwork = value.optString("artwork", "");
            if (!id.matches("[a-z0-9][a-z0-9-]{1,63}"))
                throw new IllegalArgumentException("Invalid learning article id: " + id);
            if (!KINDS.contains(kind)) throw new IllegalArgumentException("Unsupported learning article kind: " + kind);
            if (title.length() > 120 || summary.length() > 800 || scope.length() > 1000
                    || source.length() > 1000 || safety.length() > 80)
                throw new IllegalArgumentException("Learning article text exceeds offline UI limits: " + id);
            if (artwork.length() > 0 && !artwork.matches("learn/[a-z0-9][a-z0-9-]{0,63}\\.png"))
                throw new IllegalArgumentException("Invalid learning artwork path: " + artwork);
            keywords = strings(value.optJSONArray("keywords"));
            steps = strings(value.optJSONArray("steps"));
            if (steps.isEmpty()) throw new IllegalArgumentException("Learning article needs a safe next step: " + id);
            if (keywords.size() > 20 || steps.size() > 8)
                throw new IllegalArgumentException("Learning article has too many fields: " + id);
            for (String keyword : keywords) if (keyword.length() > 80)
                throw new IllegalArgumentException("Learning keyword exceeds UI limit: " + id);
            for (String step : steps) if (step.length() > 500)
                throw new IllegalArgumentException("Learning step exceeds UI limit: " + id);
        }

        public boolean matches(String query) {
            String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
            if (q.length() == 0) return true;
            String haystack = (title + " " + summary + " " + kind + " " + keywords).toLowerCase(Locale.US);
            return haystack.contains(q);
        }
    }

    public final int version;
    public final List<Article> articles;

    private LearningCatalog(int version, List<Article> articles) {
        this.version = version;
        this.articles = Collections.unmodifiableList(articles);
    }

    public static LearningCatalog load(AssetManager assets) throws IOException {
        if (assets == null) throw new IOException("Learning assets are unavailable");
        LearningCatalog catalog;
        try (InputStream input = assets.open(ASSET)) { catalog = parse(read(input)); }
        for (Article article : catalog.articles) {
            if (article.artwork.length() == 0) continue;
            try (InputStream input = assets.open(article.artwork)) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(input, null, bounds);
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                        || bounds.outWidth > MAX_ARTWORK_EDGE_PX
                        || bounds.outHeight > MAX_ARTWORK_EDGE_PX)
                    throw new IOException("Learning artwork is invalid for " + article.id);
                // Inspecting image bounds keeps an incomplete or corrupt offline visual
                // from surfacing later as an empty teaching card.
            } catch (IOException artworkError) {
                throw new IOException("Learning artwork could not be loaded for " + article.id + ": "
                        + article.artwork, artworkError);
            }
        }
        return catalog;
    }

    static LearningCatalog parse(byte[] bytes) throws IOException {
        try {
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            int version = root.getInt("schema_version");
            if (version != 1) throw new IOException("Unsupported learning catalog version: " + version);
            required(root, "scope");
            JSONArray values = root.getJSONArray("articles");
            if (values.length() == 0 || values.length() > MAX_ARTICLES)
                throw new IOException("Learning catalog article count is out of range");
            ArrayList<Article> articles = new ArrayList<>();
            HashSet<String> ids = new HashSet<>();
            for (int i = 0; i < values.length(); i++) {
                Article article = new Article(values.getJSONObject(i));
                if (!ids.add(article.id)) throw new IOException("Duplicate learning article id: " + article.id);
                articles.add(article);
            }
            validateReleaseContract(articles, ids);
            return new LearningCatalog(version, articles);
        } catch (Exception error) {
            throw new IOException("Invalid learning catalog: " + error.getMessage(), error);
        }
    }

    public List<Article> ofKind(String kind) {
        ArrayList<Article> results = new ArrayList<>();
        for (Article article : articles) if (kind.equals(article.kind)) results.add(article);
        return results;
    }

    private static String required(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.length() == 0) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static Set<String> kinds() {
        HashSet<String> result = new HashSet<>();
        result.add("start");
        result.add("cheatsheet");
        result.add("troubleshoot");
        return Collections.unmodifiableSet(result);
    }

    /**
     * v1 is a beginner safety feature, not a generic article feed. Keeping its
     * coverage floor in the runtime parser prevents an incomplete content
     * update from degrading the offline experience after CI has already run.
     */
    private static void validateReleaseContract(List<Article> articles, Set<String> ids)
            throws IOException {
        int starts = 0, cheatsheets = 0, troubleshooting = 0, hardStops = 0;
        for (Article article : articles) {
            if ("start".equals(article.kind)) starts++;
            if ("cheatsheet".equals(article.kind)) cheatsheets++;
            if ("troubleshoot".equals(article.kind)) troubleshooting++;
            if ("Hard stop".equals(article.safety)) hardStops++;
        }
        if (starts < MIN_START_ARTICLES || cheatsheets < MIN_CHEATSHEET_ARTICLES
                || troubleshooting < MIN_TROUBLESHOOT_ARTICLES || hardStops < MIN_HARD_STOP_ARTICLES
                || !ids.contains("universal-hard-stop") || !ids.contains("connection-not-confirmed")) {
            throw new IOException("Learning catalog does not meet the v1 safety coverage contract");
        }
    }

    private static List<String> strings(JSONArray values) {
        ArrayList<String> result = new ArrayList<>();
        if (values == null) return result;
        for (int i = 0; i < values.length(); i++) {
            String value = values.optString(i, "").trim();
            if (value.length() > 0) result.add(value);
        }
        return Collections.unmodifiableList(result);
    }

    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096]; int total = 0, count;
        while ((count = input.read(buffer)) >= 0) {
            total += count;
            if (total > MAX_BYTES) throw new IOException("Learning catalog exceeds size limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
