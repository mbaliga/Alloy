package com.mbaliga.alloy;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/** Durable, idempotent queue for completion-time inventory reconciliation. */
public final class InventoryReconciliationStore {
    private static final String PENDING = "pending";
    private static final int MAX_PENDING = 64;
    private static final int MAX_JOB_ID = 128;
    private static final int MAX_FILAMENT = 80;
    private final SharedPreferences preferences;

    public InventoryReconciliationStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("reconciliation preferences are required");
        this.preferences = preferences;
    }

    /** Enqueue before attempting the inventory write; duplicate callbacks are harmless. */
    public synchronized void enqueue(String jobId, String filament, float filamentMm, float diameterMm) {
        String id = boundedRequired(jobId, MAX_JOB_ID, "print job id");
        if (!finite(filamentMm) || filamentMm <= 0f || filamentMm > 100_000_000f)
            throw new IllegalArgumentException("filament usage is invalid");
        if (!finite(diameterMm) || diameterMm < 1f || diameterMm > 4f)
            throw new IllegalArgumentException("filament diameter is invalid");
        ArrayList<Entry> entries = entries();
        for (Entry entry : entries) if (id.equals(entry.jobId)) return;
        entries.add(new Entry(id, bounded(filament, MAX_FILAMENT), filamentMm, diameterMm));
        while (entries.size() > MAX_PENDING) entries.remove(0);
        persist(entries);
    }

    /** Retry all durable records; failed records remain queued for the next launch. */
    public synchronized int drain(InventoryStore inventory) {
        if (inventory == null) throw new IllegalArgumentException("inventory is required");
        ArrayList<Entry> entries = entries();
        int completed = 0;
        for (int index = entries.size() - 1; index >= 0; index--) {
            Entry entry = entries.get(index);
            try {
                inventory.recordCompletedPrint(entry.jobId, entry.filament, entry.filamentMm, entry.diameterMm);
                entries.remove(index);
                persist(entries);
                completed++;
            } catch (Exception ignored) {
                // Keep the record. A transient or unavailable inventory store
                // must never turn a confirmed print into unaccounted material.
            }
        }
        return completed;
    }

    public synchronized int pendingCount() { return entries().size(); }

    private ArrayList<Entry> entries() {
        ArrayList<Entry> result = new ArrayList<>();
        String encoded = preferences.getString(PENDING, null);
        if (encoded == null || encoded.length() > MAX_PENDING * 320) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > MAX_PENDING) return result;
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                String jobId = boundedRequired(value.getString("jobId"), MAX_JOB_ID, "print job id");
                if (!seen.add(jobId)) return new ArrayList<>();
                float filamentMm = (float) value.getDouble("filamentMm");
                float diameterMm = (float) value.getDouble("diameterMm");
                if (!finite(filamentMm) || filamentMm <= 0f || filamentMm > 100_000_000f
                        || !finite(diameterMm) || diameterMm < 1f || diameterMm > 4f)
                    return new ArrayList<>();
                result.add(new Entry(jobId, bounded(value.optString("filament", ""), MAX_FILAMENT),
                        filamentMm, diameterMm));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private void persist(ArrayList<Entry> entries) {
        JSONArray values = new JSONArray();
        try {
            for (Entry entry : entries) {
                JSONObject value = new JSONObject();
                value.put("jobId", entry.jobId);
                value.put("filament", entry.filament);
                value.put("filamentMm", entry.filamentMm);
                value.put("diameterMm", entry.diameterMm);
                values.put(value);
            }
        } catch (Exception error) {
            throw new IllegalStateException("could not encode inventory reconciliation", error);
        }
        if (!preferences.edit().putString(PENDING, values.toString()).commit())
            throw new IllegalStateException("could not persist inventory reconciliation");
    }

    private static final class Entry {
        final String jobId, filament;
        final float filamentMm, diameterMm;
        Entry(String jobId, String filament, float filamentMm, float diameterMm) {
            this.jobId = jobId; this.filament = filament; this.filamentMm = filamentMm; this.diameterMm = diameterMm;
        }
    }

    private static String boundedRequired(String value, int max, String label) {
        String result = bounded(value, max);
        if (result.length() == 0) throw new IllegalArgumentException(label + " is required");
        return result;
    }
    private static String bounded(String value, int max) {
        String result = value == null ? "" : value.trim();
        return result.length() <= max ? result : result.substring(0, max);
    }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
}
