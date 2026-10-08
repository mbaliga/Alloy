package com.mbaliga.alloy;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Small local inventory store; the persistence boundary can later move to Room. */
public final class InventoryStore {
    private static final int MAX_QUANTITY = 100_000;
    private static final int MAX_CUSTOM_ITEMS = 64;
    private static final int MAX_NAME = 80;
    private static final int MAX_CATEGORY = 40;
    private static final int MAX_UNIT = 24;
    private static final int MAX_CARE = 240;
    private static final int MAX_SERVICE_INTERVAL_DAYS = 3_650;
    private static final long MAX_FILAMENT_USAGE_MM = 100_000_000L;
    private static final int MAX_CONSUMED_JOBS = 128;
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;
    private static final long SERVICE_SOON_WINDOW_MILLIS = 14L * DAY_MILLIS;
    private static final String CUSTOM_ITEMS = "custom_items";
    private static final String FILAMENT_USAGE_MM = "filament_usage_mm.";
    private static final String LAST_USED = "last_used.";
    private static final String CONSUMED_JOBS = "consumed_jobs";
    private final SharedPreferences preferences;

    public InventoryStore(SharedPreferences preferences) {
        if (preferences == null) throw new IllegalArgumentException("inventory preferences are required");
        this.preferences = preferences;
    }

    public ArrayList<Item> items() {
        ArrayList<Item> result = new ArrayList<>();
        for (Item definition : definitions()) {
            result.add(new Item(
                    definition.id,
                    definition.name,
                    definition.category,
                    definition.unit,
                    clampQuantity(preferences.getInt(quantityKey(definition.id), definition.quantity)),
                    definition.minimum,
                    serviceDue(definition),
                    definition.care,
                    preferences.getLong(lastServicedKey(definition.id), definition.lastServicedAt),
                    preferences.getLong(nextServiceKey(definition.id), definition.nextServiceAt),
                    definition.serviceIntervalDays,
                    boundedUsage(preferences.getLong(FILAMENT_USAGE_MM + definition.id, definition.filamentUsageMm)),
                    boundedTimestamp(preferences.getLong(LAST_USED + definition.id, definition.lastUsedAt)), true));
        }
        result.addAll(customItems());
        return result;
    }

    /** Return a display copy with actionable maintenance states before routine stock. */
    public static ArrayList<Item> orderedForAttention(List<Item> source) {
        ArrayList<Item> ordered = new ArrayList<>();
        if (source != null) ordered.addAll(source);
        Collections.sort(ordered, (left, right) -> {
            int rank = Integer.compare(left.attentionRank(), right.attentionRank());
            return rank != 0 ? rank : String.CASE_INSENSITIVE_ORDER.compare(left.name, right.name);
        });
        return ordered;
    }

    /** Export the mutable workshop state so a portable project can carry it. */
    public synchronized JSONArray snapshot() {
        JSONArray values = new JSONArray();
        try {
            for (Item item : items()) values.put(encode(item));
        } catch (Exception error) {
            throw new IllegalStateException("inventory could not be encoded", error);
        }
        return values;
    }

    /** Validate an archive snapshot without changing local inventory state. */
    public static void validateSnapshot(JSONArray values) {
        parseSnapshot(values);
    }

    /** Replace local inventory state with a validated portable snapshot. */
    public synchronized void restoreSnapshot(JSONArray values) {
        ArrayList<Item> restored = parseSnapshot(values);
        JSONArray custom = new JSONArray();
        try {
            for (Item item : restored) if (!item.builtIn) custom.put(encode(item));
        } catch (Exception error) {
            throw new IllegalArgumentException("inventory snapshot could not be encoded", error);
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (Item current : items()) {
            editor.remove(quantityKey(current.id)).remove(serviceKey(current.id))
                    .remove(lastServicedKey(current.id)).remove(nextServiceKey(current.id))
                    .remove(FILAMENT_USAGE_MM + current.id).remove(LAST_USED + current.id);
        }
        for (Item item : restored) {
            editor.putInt(quantityKey(item.id), item.quantity)
                    .putBoolean(serviceKey(item.id), item.serviceDue)
                    .putLong(lastServicedKey(item.id), item.lastServicedAt)
                    .putLong(nextServiceKey(item.id), item.nextServiceAt)
                    .putLong(FILAMENT_USAGE_MM + item.id, item.filamentUsageMm)
                    .putLong(LAST_USED + item.id, item.lastUsedAt);
        }
        editor.putString(CUSTOM_ITEMS, custom.toString());
        editor.remove(CONSUMED_JOBS);
        if (!editor.commit()) throw new IllegalStateException("could not persist inventory snapshot");
    }

    /** Add a user-owned tool, consumable, spare or maintenance item. */
    public synchronized Item addCustom(String name, String category, String unit, int quantity,
                                       int minimum, int serviceIntervalDays, String care) {
        ArrayList<Item> existing = customItems();
        if (existing.size() >= MAX_CUSTOM_ITEMS) throw new IllegalArgumentException("inventory has reached its item limit");
        String itemName = boundedRequired(name, MAX_NAME, "item name");
        String itemCategory = boundedRequired(category, MAX_CATEGORY, "item category");
        String itemUnit = boundedRequired(unit, MAX_UNIT, "item unit");
        String itemCare = bounded(care, MAX_CARE);
        int safeQuantity = boundedQuantity(quantity);
        int safeMinimum = boundedQuantity(minimum);
        int safeInterval = boundedInterval(serviceIntervalDays);
        long nextService = safeInterval == 0 ? 0L : System.currentTimeMillis() + safeInterval * DAY_MILLIS;
        Item item = new Item("custom-" + UUID.randomUUID().toString(), itemName, itemCategory, itemUnit,
                safeQuantity, safeMinimum, false, itemCare, 0L, nextService, safeInterval, false);
        existing.add(item);
        saveCustomItems(existing);
        return item;
    }

    /** Remove only user-created records; built-in safety/maintenance items cannot be deleted. */
    public synchronized boolean delete(Item item) {
        if (item == null || item.builtIn) return false;
        ArrayList<Item> existing = customItems();
        boolean removed = false;
        for (int index = existing.size() - 1; index >= 0; index--) {
            if (item.id.equals(existing.get(index).id)) {
                existing.remove(index);
                removed = true;
            }
        }
        if (removed) {
            saveCustomItems(existing);
            preferences.edit().remove(quantityKey(item.id)).remove(serviceKey(item.id))
                    .remove(lastServicedKey(item.id)).remove(nextServiceKey(item.id))
                    .remove(FILAMENT_USAGE_MM + item.id).remove(LAST_USED + item.id).commit();
        }
        return removed;
    }

    public synchronized void addOne(Item item) {
        addQuantity(item, 1);
    }

    public synchronized void useOne(Item item) {
        useQuantity(item, 1);
    }

    public synchronized void addQuantity(Item item, int amount) {
        if (item == null || amount < 0) throw new IllegalArgumentException("inventory quantity is invalid");
        synchronized (preferences) {
            long next = Math.min((long) MAX_QUANTITY, (long) currentQuantity(item) + amount);
            setQuantity(item, (int) next);
        }
    }

    public synchronized void useQuantity(Item item, int amount) {
        if (item == null || amount < 0) throw new IllegalArgumentException("inventory quantity is invalid");
        synchronized (preferences) {
            setQuantity(item, Math.max(0, currentQuantity(item) - amount));
        }
    }

    /** Item rows are immutable UI snapshots; never use their stale quantity as the write base. */
    private int currentQuantity(Item item) {
        return clampQuantity(preferences.getInt(quantityKey(item.id), item.quantity));
    }

    /**
     * Record material only after a printer has reported a completed job.
     * The job ID makes retries and duplicate telemetry harmless. Filament
     * stock is tracked in grams; other inventory units remain manual because
     * a slice cannot prove that a physical tool or spare was consumed.
     */
    public synchronized Usage recordCompletedPrint(String jobId, String material,
                                                    float filamentMm, float filamentDiameterMm) {
        synchronized (preferences) {
            return recordCompletedPrintLocked(jobId, material, filamentMm, filamentDiameterMm);
        }
    }

    /** Shared-preferences monitor serializes mutations from Activity and service store instances. */
    private Usage recordCompletedPrintLocked(String jobId, String material,
                                             float filamentMm, float filamentDiameterMm) {
        String id = boundedRequired(jobId, 128, "print job id");
        if (!finite(filamentMm) || filamentMm <= 0f || filamentMm > MAX_FILAMENT_USAGE_MM)
            throw new IllegalArgumentException("filament usage is invalid");
        if (!finite(filamentDiameterMm) || filamentDiameterMm < 1f || filamentDiameterMm > 4f)
            throw new IllegalArgumentException("filament diameter is invalid");
        if (consumedJobs().contains(id)) return Usage.alreadyRecorded();

        Item filament = matchingFilament(material);
        if (filament == null || !isGramUnit(filament.unit)) return Usage.notTracked();
        int grams = gramsFor(filamentMm, filamentDiameterMm, material);
        int nextQuantity = Math.max(0, filament.quantity - grams);
        long totalUsage = boundedUsage(filament.filamentUsageMm + filamentMmRounded(filamentMm));
        long now = System.currentTimeMillis();
        ArrayList<String> jobs = consumedJobs();
        jobs.add(id);
        while (jobs.size() > MAX_CONSUMED_JOBS) jobs.remove(0);
        JSONArray encodedJobs = new JSONArray();
        for (String job : jobs) encodedJobs.put(job);
        boolean committed = preferences.edit()
                .putInt(quantityKey(filament.id), nextQuantity)
                .putLong(FILAMENT_USAGE_MM + filament.id, totalUsage)
                .putLong(LAST_USED + filament.id, now)
                .putString(CONSUMED_JOBS, encodedJobs.toString())
                .commit();
        if (!committed) throw new IllegalStateException("could not persist completed print usage");
        return new Usage(true, false, filament.name, grams, nextQuantity, totalUsage);
    }

    public synchronized void markServiced(Item item) {
        long now = System.currentTimeMillis();
        long next = item.serviceIntervalDays <= 0 ? 0L : now + item.serviceIntervalDays * DAY_MILLIS;
        if (!preferences.edit()
                .putBoolean(serviceKey(item.id), false)
                .putLong(lastServicedKey(item.id), now)
                .putLong(nextServiceKey(item.id), next)
                .commit()) {
            throw new IllegalStateException("could not persist inventory service update");
        }
    }

    private void setQuantity(Item item, int quantity) {
        /*
         * A row action is a high-frequency, non-transactional UI mutation: a
         * user can repeatedly tap +/− and the print-completion service can
         * update another row at the same time. SharedPreferences.apply()
         * updates its in-memory value synchronously (so currentQuantity() and
         * another InventoryStore instance see this write immediately) while
         * batching the disk work off the caller thread. Using commit() here
         * serialised every tap behind a filesystem flush and could make two
         * otherwise-correct writers time out on a slow emulator. Critical,
         * idempotent completed-print accounting remains a checked commit in
         * recordCompletedPrintLocked().
         */
        preferences.edit().putInt(quantityKey(item.id), clampQuantity(quantity)).apply();
    }

    private Item matchingFilament(String material) {
        String wanted = material == null ? "" : material.trim().toLowerCase(Locale.US);
        for (Item item : items()) {
            if (!"filament".equalsIgnoreCase(item.category)) continue;
            String candidate = item.name.toLowerCase(Locale.US);
            if (wanted.length() > 0 && (candidate.contains(wanted) || wanted.contains(candidate))) return item;
        }
        return null;
    }

    private static boolean isGramUnit(String unit) {
        if (unit == null) return false;
        String normalized = unit.trim().toLowerCase(Locale.US);
        return "g".equals(normalized) || "gram".equals(normalized) || "grams".equals(normalized);
    }

    private static int gramsFor(float filamentMm, float diameterMm, String material) {
        double density = 1.24d;
        String value = material == null ? "" : material.toLowerCase(Locale.US);
        if (value.contains("petg")) density = 1.27d;
        else if (value.contains("abs")) density = 1.04d;
        else if (value.contains("asa")) density = 1.07d;
        else if (value.contains("tpu")) density = 1.21d;
        double volumeCm3 = filamentMm * Math.PI * Math.pow(diameterMm / 2d, 2d) / 1_000d;
        return (int) Math.max(1d, Math.ceil(volumeCm3 * density));
    }

    private static long filamentMmRounded(float filamentMm) {
        return Math.max(1L, Math.round(filamentMm));
    }

    private ArrayList<String> consumedJobs() {
        ArrayList<String> result = new ArrayList<>();
        String encoded = preferences.getString(CONSUMED_JOBS, null);
        if (encoded == null || encoded.length() > MAX_CONSUMED_JOBS * 160) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > MAX_CONSUMED_JOBS) return result;
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                String id = values.optString(index, "").trim();
                if (id.length() == 0 || id.length() > 128 || !seen.add(id)) return new ArrayList<>();
                result.add(id);
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private static int clampQuantity(int quantity) {
        return Math.max(0, Math.min(MAX_QUANTITY, quantity));
    }

    private static String quantityKey(String id) {
        return "quantity." + id;
    }

    private static String serviceKey(String id) {
        return "service_due." + id;
    }

    private static String lastServicedKey(String id) {
        return "last_serviced." + id;
    }

    private static String nextServiceKey(String id) {
        return "next_service." + id;
    }

    private boolean serviceDue(Item definition) {
        boolean explicitDue = preferences.getBoolean(serviceKey(definition.id), definition.serviceDue);
        long next = preferences.getLong(nextServiceKey(definition.id), definition.nextServiceAt);
        return explicitDue || (next > 0L && next <= System.currentTimeMillis());
    }

    private ArrayList<Item> customItems() {
        ArrayList<Item> result = new ArrayList<>();
        String encoded = preferences.getString(CUSTOM_ITEMS, null);
        if (encoded == null || encoded.length() > MAX_CUSTOM_ITEMS * 1_024) return result;
        try {
            JSONArray values = new JSONArray(encoded);
            if (values.length() > MAX_CUSTOM_ITEMS) return result;
            Set<String> seen = new HashSet<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.optJSONObject(index);
                if (value == null) return new ArrayList<>();
                String id = value.optString("id", "").trim();
                if (!id.matches("custom-[0-9a-fA-F-]{36}") || !seen.add(id)) return new ArrayList<>();
                String name = boundedRequired(value.optString("name", ""), MAX_NAME, "item name");
                String category = boundedRequired(value.optString("category", ""), MAX_CATEGORY, "item category");
                String unit = boundedRequired(value.optString("unit", ""), MAX_UNIT, "item unit");
                String care = bounded(value.optString("care", ""), MAX_CARE);
                int quantity = boundedQuantity(value.optInt("quantity", 0));
                int minimum = boundedQuantity(value.optInt("minimum", 0));
                int interval = boundedInterval(value.optInt("service_interval_days", 0));
                quantity = boundedQuantity(preferences.getInt(quantityKey(id), quantity));
                boolean due = preferences.getBoolean(serviceKey(id), value.optBoolean("service_due", false));
                long last = preferences.getLong(lastServicedKey(id), value.optLong("last_serviced_at", 0L));
                long next = preferences.getLong(nextServiceKey(id), value.optLong("next_service_at", 0L));
                long usage = boundedUsage(preferences.getLong(FILAMENT_USAGE_MM + id,
                        boundedUsage(value.optLong("filament_usage_mm", 0L))));
                long lastUsed = boundedTimestamp(preferences.getLong(LAST_USED + id,
                        value.optLong("last_used_at", 0L)));
                result.add(new Item(id, name, category, unit, quantity, minimum, due, care,
                        last, next, interval, usage, lastUsed, false));
            }
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private static ArrayList<Item> parseSnapshot(JSONArray values) {
        if (values == null || values.length() > definitions().length + MAX_CUSTOM_ITEMS)
            throw new IllegalArgumentException("inventory snapshot is invalid");
        ArrayList<Item> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            JSONObject value = values.optJSONObject(index);
            if (value == null) throw new IllegalArgumentException("inventory record is invalid");
            String id = value.optString("id", "").trim();
            if (id.length() == 0 || !seen.add(id)) throw new IllegalArgumentException("inventory record id is invalid");
            boolean builtIn = booleanValue(value, "built_in", false);
            String name = boundedRequired(value.optString("name", ""), MAX_NAME, "item name");
            String category = boundedRequired(value.optString("category", ""), MAX_CATEGORY, "item category");
            String unit = boundedRequired(value.optString("unit", ""), MAX_UNIT, "item unit");
            String care = bounded(value.optString("care", ""), MAX_CARE);
            int quantity = boundedQuantity(value.optInt("quantity", -1));
            int minimum = boundedQuantity(value.optInt("minimum", -1));
            int interval = boundedInterval(value.optInt("service_interval_days", -1));
            boolean due = booleanValue(value, "service_due", false);
            long last = boundedTimestamp(value, "last_serviced_at", 0L);
            long next = boundedTimestamp(value, "next_service_at", 0L);
            long usage = boundedUsage(value, "filament_usage_mm", 0L);
            long lastUsed = boundedTimestamp(value, "last_used_at", 0L);
            Item definition = findDefinition(id);
            if (builtIn) {
                if (definition == null) throw new IllegalArgumentException("unknown built-in inventory item");
                // Inventory snapshots written before automatic filament
                // accounting used two 1 kg spools as the PLA quantity. Keep
                // those portable projects meaningful after the unit change.
                if ("pla-basic".equals(id) && "spools".equalsIgnoreCase(unit)) {
                    if (quantity > MAX_QUANTITY / 1_000) throw new IllegalArgumentException("filament quantity is invalid");
                    quantity *= 1_000;
                }
                result.add(new Item(definition.id, definition.name, definition.category, definition.unit,
                        quantity, definition.minimum, due, definition.care, last, next,
                        definition.serviceIntervalDays, usage, lastUsed, true));
            } else {
                if (definition != null || !id.matches("custom-[0-9a-fA-F-]{36}"))
                    throw new IllegalArgumentException("custom inventory id is invalid");
                result.add(new Item(id, name, category, unit, quantity, minimum, due, care,
                        last, next, interval, usage, lastUsed, false));
            }
        }
        return result;
    }

    private static Item findDefinition(String id) {
        for (Item item : definitions()) if (item.id.equals(id)) return item;
        return null;
    }

    private static JSONObject encode(Item item) throws Exception {
        return new JSONObject().put("id", item.id).put("name", item.name)
                .put("category", item.category).put("unit", item.unit)
                .put("quantity", item.quantity).put("minimum", item.minimum)
                .put("service_due", item.serviceDue).put("care", item.care)
                .put("last_serviced_at", item.lastServicedAt)
                .put("next_service_at", item.nextServiceAt)
                .put("service_interval_days", item.serviceIntervalDays)
                .put("filament_usage_mm", item.filamentUsageMm)
                .put("last_used_at", item.lastUsedAt)
                .put("built_in", item.builtIn);
    }

    private static boolean booleanValue(JSONObject value, String key, boolean fallback) {
        Object raw = value.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Boolean)) throw new IllegalArgumentException("inventory boolean is invalid: " + key);
        return (Boolean) raw;
    }

    private static long boundedTimestamp(JSONObject value, String key, long fallback) {
        Object raw = value.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Number) || raw instanceof Boolean)
            throw new IllegalArgumentException("inventory timestamp is invalid: " + key);
        double number = ((Number) raw).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.rint(number)
                || number < 0d || number > Long.MAX_VALUE)
            throw new IllegalArgumentException("inventory timestamp is out of range: " + key);
        return ((Number) raw).longValue();
    }

    private static long boundedTimestamp(long value) {
        return value < 0L ? 0L : value;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static long boundedUsage(JSONObject value, String key, long fallback) {
        Object raw = value.opt(key);
        if (raw == null || raw == JSONObject.NULL) return fallback;
        if (!(raw instanceof Number) || raw instanceof Boolean)
            throw new IllegalArgumentException("filament usage is invalid");
        double number = ((Number) raw).doubleValue();
        if (Double.isNaN(number) || Double.isInfinite(number) || number != Math.rint(number)
                || number < 0d || number > MAX_FILAMENT_USAGE_MM)
            throw new IllegalArgumentException("filament usage is out of range");
        return ((Number) raw).longValue();
    }

    private static long boundedUsage(long value) {
        if (value < 0L || value > MAX_FILAMENT_USAGE_MM)
            throw new IllegalArgumentException("filament usage is out of range");
        return value;
    }

    private void saveCustomItems(ArrayList<Item> items) {
        JSONArray values = new JSONArray();
        try {
            for (Item item : items) values.put(encode(item));
        } catch (Exception error) {
            throw new IllegalStateException("inventory item could not be encoded", error);
        }
        if (!preferences.edit().putString(CUSTOM_ITEMS, values.toString()).commit())
            throw new IllegalStateException("could not persist custom inventory item");
    }

    private static int boundedQuantity(int quantity) {
        if (quantity < 0 || quantity > MAX_QUANTITY) throw new IllegalArgumentException("inventory quantity is invalid");
        return quantity;
    }

    private static int boundedInterval(int days) {
        if (days < 0 || days > MAX_SERVICE_INTERVAL_DAYS) throw new IllegalArgumentException("service interval is invalid");
        return days;
    }

    private static String boundedRequired(String value, int max, String label) {
        String normalized = bounded(value, max);
        if (normalized.length() == 0) throw new IllegalArgumentException(label + " is required");
        return normalized;
    }

    private static String bounded(String value, int max) {
        if (value == null) return "";
        String normalized = value.trim().replace("\n", " ").replace("\r", " ");
        return normalized.length() > max ? normalized.substring(0, max) : normalized;
    }

    private static Item[] definitions() {
        return new Item[]{
                new Item("nozzle-04", "0.4 mm nozzle", "Consumable", "spare", 1, 1, false, "Replace after wear or a clog", 0),
                new Item("pei-plate", "Textured PEI plate", "Build surface", "in rotation", 1, 1, true, "Clean and inspect adhesion surface", 30),
                new Item("ptfe-tube", "PTFE tube", "Feed path", "spare", 0, 1, false, "Add one before the next service", 0),
                new Item("lubricant", "Silicone lubricant", "Maintenance", "bottle", 1, 1, true, "Motion-system service due soon", 90),
                new Item("pla-basic", "Bambu PLA Basic", "Filament", "g", 2_000, 250, false, "Two 1 kg spools · dry storage · external spool", 0),
                new Item("petg-basic", "Bambu PETG", "Filament", "g", 0, 250, false, "Add measured stock · confirm plate, route and dry storage", 0),
                new Item("tpu", "Bambu TPU", "Filament", "g", 0, 250, false, "Add measured stock · external/direct path only; do not assume AMS lite compatibility", 0),
                new Item("pva", "Bambu PVA", "Filament", "g", 0, 250, false, "Add measured stock · external/direct path only; keep dry and confirm the exact recipe", 0),
                new Item("support-pla", "Support material for PLA", "Filament", "g", 0, 250, false, "Add measured stock · confirm the selected support material and feed route", 0),
                new Item("support-petg", "Support material for PETG", "Filament", "g", 0, 250, false, "Add measured stock · confirm the selected support material and feed route", 0),
                new Item("abs", "ABS · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check the active printer before use", 0),
                new Item("asa", "ASA · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check the active printer before use", 0),
                new Item("pc", "PC · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check the active printer before use", 0),
                new Item("pa", "PA / Nylon · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check the active printer before use", 0),
                new Item("pet", "PET · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check the active printer before use", 0),
                new Item("pla-cf", "PLA-CF · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check nozzle and active printer before use", 0),
                new Item("petg-cf", "PETG-CF · printer-specific review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; check nozzle and active printer before use", 0),
                new Item("cf-gf-filled", "Other CF/GF-filled polymer · review", "Filament", "g", 0, 0, false, "Track only · not recommended on A1 Mini; abrasive materials need printer/nozzle review", 0)
        };
    }

    public static final class Item {
        public final String id;
        public final String name;
        public final String category;
        public final String unit;
        public final int quantity;
        public final int minimum;
        public final boolean serviceDue;
        public final String care;
        public final long lastServicedAt;
        public final long nextServiceAt;
        public final int serviceIntervalDays;
        public final long filamentUsageMm;
        public final long lastUsedAt;
        public final boolean builtIn;

        Item(String id, String name, String category, String unit, int quantity, int minimum, boolean serviceDue,
             String care, int serviceIntervalDays) {
            this(id, name, category, unit, quantity, minimum, serviceDue, care, 0L, 0L, serviceIntervalDays, true);
        }

        Item(String id, String name, String category, String unit, int quantity, int minimum, boolean serviceDue,
             String care, long lastServicedAt, long nextServiceAt, int serviceIntervalDays) {
            this(id, name, category, unit, quantity, minimum, serviceDue, care, lastServicedAt, nextServiceAt, serviceIntervalDays, true);
        }

        Item(String id, String name, String category, String unit, int quantity, int minimum, boolean serviceDue,
             String care, long lastServicedAt, long nextServiceAt, int serviceIntervalDays, boolean builtIn) {
            this(id, name, category, unit, quantity, minimum, serviceDue, care, lastServicedAt, nextServiceAt,
                    serviceIntervalDays, 0L, 0L, builtIn);
        }

        Item(String id, String name, String category, String unit, int quantity, int minimum, boolean serviceDue,
             String care, long lastServicedAt, long nextServiceAt, int serviceIntervalDays,
             long filamentUsageMm, long lastUsedAt, boolean builtIn) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.unit = unit;
            this.quantity = quantity;
            this.minimum = minimum;
            this.serviceDue = serviceDue;
            this.care = care;
            this.lastServicedAt = lastServicedAt;
            this.nextServiceAt = nextServiceAt;
            this.serviceIntervalDays = Math.max(0, serviceIntervalDays);
            this.filamentUsageMm = boundedUsage(filamentUsageMm);
            this.lastUsedAt = boundedTimestamp(lastUsedAt);
            this.builtIn = builtIn;
        }

        public boolean isCustom() { return !builtIn; }

        public boolean usesGrams() { return isGramUnit(unit); }

        public boolean needsReorder() {
            return quantity < minimum;
        }

        public boolean serviceOverdue() {
            // The timestamp is authoritative even when an Item was restored
            // from a snapshot whose explicit flag had not been recomputed.
            return nextServiceAt > 0L && nextServiceAt <= System.currentTimeMillis();
        }

        public boolean serviceSoon() {
            long now = System.currentTimeMillis();
            return !serviceDue && nextServiceAt > now && nextServiceAt <= now + SERVICE_SOON_WINDOW_MILLIS;
        }

        public boolean needsServiceAttention() {
            return serviceDue || serviceSoon();
        }

        /** Lower ranks are more urgent; reorder wins when an item has multiple alerts. */
        public int attentionRank() {
            if (needsReorder()) return 0;
            if (serviceOverdue()) return 1;
            if (serviceDue) return 2;
            if (serviceSoon()) return 3;
            return 4;
        }

        public String statusLabel() {
            if (needsReorder()) return "REORDER";
            if (serviceOverdue()) return "SERVICE OVERDUE";
            if (serviceDue) return "SERVICE";
            if (serviceSoon()) return "SERVICE SOON";
            return "READY";
        }

        public String quantityLabel() {
            return quantity + " " + unit;
        }

        public String usageLabel() {
            if (!"filament".equalsIgnoreCase(category) || filamentUsageMm <= 0L) return "";
            return String.format(Locale.US, "Used %.1f m total", filamentUsageMm / 1_000f);
        }

        public String serviceLabel() {
            if (serviceOverdue()) return "SERVICE OVERDUE";
            if (serviceDue) return "SERVICE DUE";
            if (nextServiceAt <= 0L) return "No service schedule";
            String date = new java.text.SimpleDateFormat("dd MMM yyyy", Locale.US).format(new java.util.Date(nextServiceAt));
            return serviceSoon() ? "SERVICE SOON · " + date : "Next service · " + date;
        }

        public String glyph() {
            if (needsReorder()) return "!";
            if (needsServiceAttention()) return "⌁";
            return "✓";
        }
    }

    public static final class Usage {
        public final boolean recorded;
        public final boolean alreadyRecorded;
        public final String itemName;
        public final int grams;
        public final int remainingGrams;
        public final long totalUsageMm;

        private Usage(boolean recorded, boolean alreadyRecorded, String itemName, int grams,
                      int remainingGrams, long totalUsageMm) {
            this.recorded = recorded;
            this.alreadyRecorded = alreadyRecorded;
            this.itemName = itemName;
            this.grams = grams;
            this.remainingGrams = remainingGrams;
            this.totalUsageMm = totalUsageMm;
        }

        private static Usage alreadyRecorded() {
            return new Usage(false, true, "", 0, -1, 0L);
        }

        private static Usage notTracked() {
            return new Usage(false, false, "", 0, -1, 0L);
        }
    }
}
