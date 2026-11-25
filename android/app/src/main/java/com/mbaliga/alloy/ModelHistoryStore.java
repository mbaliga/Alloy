package com.mbaliga.alloy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

/**
 * Durable, bounded undo/redo history for modeling and plate-preparation edits.
 *
 * The history stores validated plate snapshots, not live mesh objects. Model
 * bytes remain in ModelStore's content-addressed cache and are protected by
 * referencedPlates(), so an undo can be performed after an Activity or process
 * restart without re-reading the original document-provider URI.
 */
public final class ModelHistoryStore {
    private static final int VERSION = 1;
    private static final int MAX_DOCUMENTS = PlateStore.MAX_PLATES;
    private static final int MAX_ENTRIES = 32;
    private static final int MAX_LABEL = 120;
    private static final int MAX_STORAGE_BYTES = 512 * 1024;
    private static final String STATE = "state";

    private final android.content.SharedPreferences preferences;

    public ModelHistoryStore(android.content.SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("model history preferences are required");
        this.preferences = preferences;
    }

    /** Start a new history timeline for one plate, discarding only that plate's old edits. */
    public synchronized void resetDocument(int plateIndex, PlateStore.Plate plate, String label) {
        validatePlateIndex(plateIndex);
        if (plate == null || plate.index != plateIndex) throw new IllegalArgumentException("history plate is invalid");
        ArrayList<Document> documents = readDocuments();
        Document document = documentFor(documents, plateIndex, true);
        document.entries.clear();
        document.cursor = 0;
        document.entries.add(new Entry(copy(plate), normalizeLabel(label), System.currentTimeMillis()));
        saveDocuments(documents);
    }

    /** Clear a plate's modeling timeline without touching other plates. */
    public synchronized void clearDocument(int plateIndex) {
        validatePlateIndex(plateIndex);
        ArrayList<Document> documents = readDocuments();
        for (int index = documents.size() - 1; index >= 0; index--) {
            if (documents.get(index).plateIndex == plateIndex) documents.remove(index);
        }
        saveDocuments(documents);
    }

    /** Clear all modeling timelines, used when a genuinely new project is opened. */
    public synchronized void clearAll() {
        if (!preferences.edit().remove(STATE).commit())
            throw new IllegalStateException("model history could not be cleared");
    }

    /**
     * Ensure the persisted cursor describes the live plate. This is used on
     * restore and deliberately resets a stale/corrupt legacy state rather than
     * guessing how to reconcile two different documents.
     */
    public synchronized boolean ensureCurrent(int plateIndex, PlateStore.Plate current, String label) {
        validatePlateIndex(plateIndex);
        if (current == null || current.index != plateIndex) throw new IllegalArgumentException("history plate is invalid");
        ArrayList<Document> documents = readDocuments();
        Document document = documentFor(documents, plateIndex, false);
        if (document == null || document.entries.isEmpty() || document.cursor < 0
                || document.cursor >= document.entries.size()
                || !same(document.entries.get(document.cursor).plate, current)) {
            resetInMemory(documents, plateIndex, current, label);
            saveDocuments(documents);
            return true;
        }
        return false;
    }

    /** Append a completed edit, truncating the redo branch as standard undo stacks do. */
    public synchronized boolean append(int plateIndex, PlateStore.Plate current, String label) {
        validatePlateIndex(plateIndex);
        if (current == null || current.index != plateIndex) throw new IllegalArgumentException("history plate is invalid");
        ArrayList<Document> documents = readDocuments();
        Document document = documentFor(documents, plateIndex, false);
        if (document == null || document.entries.isEmpty()) {
            resetInMemory(documents, plateIndex, current, label);
            saveDocuments(documents);
            return true;
        }
        Entry atCursor = document.entries.get(Math.max(0, Math.min(document.cursor, document.entries.size() - 1)));
        if (same(atCursor.plate, current)) return false;
        while (document.entries.size() > document.cursor + 1) document.entries.remove(document.entries.size() - 1);
        document.entries.add(new Entry(copy(current), normalizeLabel(label), System.currentTimeMillis()));
        document.cursor = document.entries.size() - 1;
        trim(document);
        saveDocuments(documents);
        return true;
    }

    public synchronized PlateStore.Plate undo(int plateIndex) {
        validatePlateIndex(plateIndex);
        ArrayList<Document> documents = readDocuments();
        Document document = documentFor(documents, plateIndex, false);
        if (document == null || document.cursor <= 0) return null;
        document.cursor--;
        saveDocuments(documents);
        return copy(document.entries.get(document.cursor).plate);
    }

    public synchronized PlateStore.Plate redo(int plateIndex) {
        validatePlateIndex(plateIndex);
        ArrayList<Document> documents = readDocuments();
        Document document = documentFor(documents, plateIndex, false);
        if (document == null || document.cursor + 1 >= document.entries.size()) return null;
        document.cursor++;
        saveDocuments(documents);
        return copy(document.entries.get(document.cursor).plate);
    }

    public synchronized boolean canUndo(int plateIndex) {
        Document document = documentFor(readDocuments(), plateIndex, false);
        return document != null && document.cursor > 0;
    }

    public synchronized boolean canRedo(int plateIndex) {
        Document document = documentFor(readDocuments(), plateIndex, false);
        return document != null && document.cursor + 1 < document.entries.size();
    }

    public synchronized String summary(int plateIndex) {
        Document document = documentFor(readDocuments(), plateIndex, false);
        if (document == null || document.entries.isEmpty()) return "No edit history";
        return "Undo " + document.cursor + "  ·  Redo " + (document.entries.size() - document.cursor - 1);
    }

    /** Human-readable newest-first entries for the in-app history inspector. */
    public synchronized ArrayList<String> labels(int plateIndex) {
        ArrayList<String> result = new ArrayList<>();
        Document document = documentFor(readDocuments(), plateIndex, false);
        if (document == null) return result;
        for (int index = document.entries.size() - 1; index >= 0; index--) {
            Entry entry = document.entries.get(index);
            String marker = index == document.cursor ? "CURRENT" : index < document.cursor ? "UNDO" : "REDO";
            result.add(marker + "  ·  " + entry.label);
        }
        return result;
    }

    /** Keep every historical file-backed model alive while it can still be undone. */
    public synchronized ArrayList<PlateStore.Plate> referencedPlates() {
        ArrayList<PlateStore.Plate> result = new ArrayList<>();
        for (Document document : readDocuments()) {
            for (Entry entry : document.entries) result.add(copy(entry.plate));
        }
        return result;
    }

    /** Export validated timelines for inclusion in a portable project archive. */
    public synchronized ArrayList<Timeline> exportTimelines() {
        ArrayList<Timeline> result = new ArrayList<>();
        for (Document document : readDocuments()) {
            ArrayList<HistoryEntry> entries = new ArrayList<>();
            for (Entry entry : document.entries)
                entries.add(new HistoryEntry(copy(entry.plate), entry.label, entry.at));
            result.add(new Timeline(document.plateIndex, document.cursor, entries));
        }
        return result;
    }

    /** Replace local timelines with an archive's validated history. */
    public synchronized void importTimelines(ArrayList<Timeline> timelines) {
        if (timelines == null || timelines.isEmpty()) {
            clearAll();
            return;
        }
        if (timelines.size() > MAX_DOCUMENTS)
            throw new IllegalArgumentException("project history has too many plate timelines");
        ArrayList<Document> documents = new ArrayList<>();
        java.util.HashSet<Integer> seen = new java.util.HashSet<>();
        for (Timeline timeline : timelines) {
            if (timeline == null || !seen.add(timeline.plateIndex))
                throw new IllegalArgumentException("project history timeline is invalid");
            validatePlateIndex(timeline.plateIndex);
            if (timeline.entries == null || timeline.entries.isEmpty()
                    || timeline.entries.size() > MAX_ENTRIES
                    || timeline.cursor < 0 || timeline.cursor >= timeline.entries.size())
                throw new IllegalArgumentException("project history cursor is invalid");
            Document document = new Document(timeline.plateIndex);
            document.cursor = timeline.cursor;
            for (HistoryEntry entry : timeline.entries) {
                if (entry == null || entry.plate == null || entry.plate.index != timeline.plateIndex
                        || entry.at <= 0L || entry.label == null || entry.label.trim().length() == 0
                        || entry.label.length() > MAX_LABEL)
                    throw new IllegalArgumentException("project history entry is invalid");
                document.entries.add(new Entry(copy(entry.plate), normalizeLabel(entry.label), entry.at));
            }
            documents.add(document);
        }
        saveDocuments(documents);
    }

    private static void resetInMemory(ArrayList<Document> documents, int plateIndex,
                                      PlateStore.Plate plate, String label) {
        Document document = documentFor(documents, plateIndex, true);
        document.entries.clear();
        document.cursor = 0;
        document.entries.add(new Entry(copy(plate), normalizeLabel(label), System.currentTimeMillis()));
    }

    private ArrayList<Document> readDocuments() {
        ArrayList<Document> result = new ArrayList<>();
        String encoded = preferences.getString(STATE, null);
        if (encoded == null || encoded.length() == 0 || encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STORAGE_BYTES)
            return result;
        try {
            JSONObject root = new JSONObject(encoded);
            if (root.optInt("version", -1) != VERSION) return result;
            JSONObject byPlate = root.optJSONObject("documents");
            if (byPlate == null || byPlate.length() > MAX_DOCUMENTS) return result;
            java.util.Iterator<String> keys = byPlate.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                int plateIndex;
                try { plateIndex = Integer.parseInt(key); } catch (NumberFormatException invalid) { return new ArrayList<>(); }
                if (plateIndex < 0 || plateIndex >= MAX_DOCUMENTS) return new ArrayList<>();
                JSONObject encodedDocument = byPlate.optJSONObject(key);
                if (encodedDocument == null) return new ArrayList<>();
                JSONArray encodedEntries = encodedDocument.optJSONArray("entries");
                int cursor = encodedDocument.optInt("cursor", -1);
                if (encodedEntries == null || encodedEntries.length() == 0 || encodedEntries.length() > MAX_ENTRIES
                        || cursor < 0 || cursor >= encodedEntries.length()) return new ArrayList<>();
                Document document = new Document(plateIndex);
                document.cursor = cursor;
                for (int index = 0; index < encodedEntries.length(); index++) {
                    JSONObject encodedEntry = encodedEntries.optJSONObject(index);
                    if (encodedEntry == null) return new ArrayList<>();
                    PlateStore.Plate plate = PlateStore.decodeSnapshot(encodedEntry.optJSONObject("plate"));
                    long at = encodedEntry.optLong("at", 0L);
                    String label = normalizeLabel(encodedEntry.optString("label", "Edit"));
                    if (plate == null || plate.index != plateIndex || at <= 0L || label.length() == 0)
                        return new ArrayList<>();
                    document.entries.add(new Entry(plate, label, at));
                }
                result.add(document);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private void saveDocuments(ArrayList<Document> documents) {
        try {
            JSONObject root = new JSONObject().put("version", VERSION);
            JSONObject byPlate = new JSONObject();
            for (Document document : documents) {
                if (document == null || document.entries.isEmpty()) continue;
                trim(document);
                JSONArray entries = new JSONArray();
                for (Entry entry : document.entries) {
                    entries.put(new JSONObject().put("label", entry.label).put("at", entry.at)
                            .put("plate", PlateStore.encodeSnapshot(entry.plate)));
                }
                byPlate.put(Integer.toString(document.plateIndex), new JSONObject()
                        .put("cursor", document.cursor).put("entries", entries));
            }
            root.put("documents", byPlate);
            String encoded = root.toString();
            while (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STORAGE_BYTES) {
                boolean removed = false;
                for (Document document : documents) {
                    if (document.entries.size() > 1) {
                        int removeIndex = document.cursor > 0 ? 0 : 1;
                        document.entries.remove(removeIndex);
                        if (document.cursor > removeIndex) document.cursor--;
                        else if (document.cursor >= document.entries.size()) document.cursor = document.entries.size() - 1;
                        removed = true;
                        break;
                    }
                }
                if (!removed) throw new IllegalStateException("model history exceeds its storage limit");
                root = new JSONObject().put("version", VERSION);
                byPlate = new JSONObject();
                for (Document document : documents) {
                    if (document == null || document.entries.isEmpty()) continue;
                    JSONArray entries = new JSONArray();
                    for (Entry entry : document.entries) {
                        entries.put(new JSONObject().put("label", entry.label).put("at", entry.at)
                                .put("plate", PlateStore.encodeSnapshot(entry.plate)));
                    }
                    byPlate.put(Integer.toString(document.plateIndex), new JSONObject()
                            .put("cursor", document.cursor).put("entries", entries));
                }
                root.put("documents", byPlate);
                encoded = root.toString();
            }
            if (!preferences.edit().putString(STATE, encoded).commit())
                throw new IllegalStateException("model history could not be saved");
        } catch (org.json.JSONException error) {
            throw new IllegalStateException("model history could not be encoded", error);
        }
    }

    private static Document documentFor(ArrayList<Document> documents, int plateIndex, boolean create) {
        if (documents == null) return null;
        for (Document document : documents) if (document.plateIndex == plateIndex) return document;
        if (!create) return null;
        if (documents.size() >= MAX_DOCUMENTS) throw new IllegalStateException("model history has too many plates");
        Document created = new Document(plateIndex);
        documents.add(created);
        return created;
    }

    private static void trim(Document document) {
        while (document.entries.size() > MAX_ENTRIES) {
            document.entries.remove(0);
            document.cursor = Math.max(0, document.cursor - 1);
        }
        if (document.entries.isEmpty()) document.cursor = 0;
        else document.cursor = Math.max(0, Math.min(document.cursor, document.entries.size() - 1));
    }

    private static PlateStore.Plate copy(PlateStore.Plate plate) {
        return new PlateStore.Plate(plate.index, plate.name, new ArrayList<>(plate.uris),
                new ArrayList<>(plate.names), plate.scale, plate.rotationDegrees, plate.tiltXDegrees,
                plate.tiltYDegrees, plate.selectedPart, new ArrayList<>(plate.partTransforms),
                plate.geometryRepairEnabled);
    }

    private static boolean same(PlateStore.Plate left, PlateStore.Plate right) {
        try {
            // Part focus is view state, not a modeling edit. Excluding it from
            // the identity keeps a harmless selection tap from resetting the
            // undo branch while still preserving the selection in snapshots.
            JSONObject leftValue = PlateStore.encodeSnapshot(left);
            JSONObject rightValue = PlateStore.encodeSnapshot(right);
            leftValue.remove("selected_part");
            rightValue.remove("selected_part");
            return leftValue.toString().equals(rightValue.toString());
        }
        catch (Exception ignored) { return false; }
    }

    private static String normalizeLabel(String value) {
        String normalized = value == null ? "Edit" : value.trim().replace('\n', ' ').replace('\r', ' ');
        if (normalized.length() == 0) normalized = "Edit";
        return normalized.length() > MAX_LABEL ? normalized.substring(0, MAX_LABEL) : normalized;
    }

    private static void validatePlateIndex(int plateIndex) {
        if (plateIndex < 0 || plateIndex >= MAX_DOCUMENTS) throw new IllegalArgumentException("history plate index is invalid");
    }

    private static final class Document {
        final int plateIndex;
        int cursor;
        final ArrayList<Entry> entries = new ArrayList<>();
        Document(int plateIndex) { this.plateIndex = plateIndex; }
    }

    private static final class Entry {
        final PlateStore.Plate plate;
        final String label;
        final long at;
        Entry(PlateStore.Plate plate, String label, long at) {
            this.plate = plate; this.label = label; this.at = at;
        }
    }

    public static final class Timeline {
        public final int plateIndex;
        public final int cursor;
        public final ArrayList<HistoryEntry> entries;

        Timeline(int plateIndex, int cursor, ArrayList<HistoryEntry> entries) {
            this.plateIndex = plateIndex;
            this.cursor = cursor;
            this.entries = entries == null ? new ArrayList<>() : new ArrayList<>(entries);
        }
    }

    public static final class HistoryEntry {
        public final PlateStore.Plate plate;
        public final String label;
        public final long at;

        HistoryEntry(PlateStore.Plate plate, String label, long at) {
            this.plate = plate;
            this.label = label;
            this.at = at;
        }
    }
}
