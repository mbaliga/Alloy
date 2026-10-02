package com.mbaliga.alloy;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Source-labelled printer/material capability data. "Compatible" never means Alloy may send a job. */
public final class PrinterCapabilityCatalog {
    private static final String ASSET = "capabilities/bambu-initial-v1.json";

    public static final class Material {
        public final String id, name, bambuStatus, directSendState, note, sourceUrl;
        Material(JSONObject o) {
            id = need(o, "id"); name = need(o, "name"); bambuStatus = need(o, "bambu_status");
            directSendState = need(o, "alloy_direct_send"); note = need(o, "note");
            sourceUrl = officialSourceUrl(o, "source_url");
        }
    }
    public static final class FeedRoute {
        public final String id, title, state, note;
        FeedRoute(JSONObject o) { id = need(o, "id"); title = need(o, "title"); state = need(o, "state"); note = need(o, "note"); }
    }
    /** A physical spool/refill form; this is distinct from its material and feed route. */
    public static final class SpoolForm {
        public final String id, title, form, compatibleRoutes, geometry, state, note, source, sourceUrl;
        SpoolForm(JSONObject o) {
            id = need(o, "id"); title = need(o, "title"); form = need(o, "form");
            compatibleRoutes = need(o, "compatible_routes"); geometry = need(o, "geometry");
            state = need(o, "state"); note = need(o, "note"); source = need(o, "source");
            sourceUrl = officialSourceUrl(o, "source_url");
        }
    }
    public static final class Printer {
        public final String id, name, buildVolume, nozzle, bed, source, sourceUrl, reviewed, directSendState;
        public final List<Material> materials; public final List<FeedRoute> feedRoutes;
        public final List<SpoolForm> spoolForms; public final List<String> spoolGuidance;
        Printer(JSONObject o) {
            id = need(o, "id"); name = need(o, "name"); buildVolume = need(o, "build_volume");
            nozzle = need(o, "nozzle"); bed = need(o, "bed"); source = need(o, "source");
            sourceUrl = officialSourceUrl(o, "source_url");
            reviewed = need(o, "reviewed"); directSendState = need(o, "alloy_direct_send");
            materials = materialList(o.optJSONArray("materials")); feedRoutes = routeList(o.optJSONArray("feed_routes"));
            spoolForms = spoolFormList(o.optJSONArray("spool_forms"));
            spoolGuidance = textList(o.optJSONArray("spool_guidance"), "spool guidance");
        }
    }
    public final int version; public final List<Printer> printers;
    private PrinterCapabilityCatalog(int version, List<Printer> printers) { this.version = version; this.printers = Collections.unmodifiableList(printers); }

    public static PrinterCapabilityCatalog load(AssetManager assets) throws IOException {
        try (InputStream input = assets.open(ASSET)) {
            JSONObject root = new JSONObject(new String(read(input), StandardCharsets.UTF_8));
            int version = root.getInt("schema_version");
            if (version != 1) throw new IOException("Unsupported capability catalog version: " + version);
            JSONArray values = root.getJSONArray("printers"); ArrayList<Printer> printers = new ArrayList<>();
            for (int i = 0; i < values.length(); i++) printers.add(new Printer(values.getJSONObject(i)));
            if (printers.size() != 3 || !has(printers, "a1-mini") || !has(printers, "a1") || !has(printers, "p1s"))
                throw new IOException("Initial catalog must contain exactly A1 mini, A1 and P1S");
            return new PrinterCapabilityCatalog(version, printers);
        } catch (IOException error) { throw error;
        } catch (Exception error) { throw new IOException("Invalid capability catalog: " + error.getMessage(), error); }
    }
    public Printer byId(String id) { for (Printer printer : printers) if (printer.id.equals(id)) return printer; return null; }
    private static boolean has(List<Printer> printers, String id) { for (Printer p : printers) if (p.id.equals(id)) return true; return false; }
    private static String need(JSONObject o, String key) { String value = o.optString(key, "").trim(); if (value.length() == 0) throw new IllegalArgumentException("Missing " + key); return value; }
    /** Capability claims must carry a direct, HTTPS Bambu source—not a vague documentation pointer. */
    private static String officialSourceUrl(JSONObject o, String key) {
        String value = need(o, key);
        android.net.Uri uri = android.net.Uri.parse(value);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.US);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !(host.equals("bambulab.com") || host.endsWith(".bambulab.com")))
            throw new IllegalArgumentException("Invalid official source URL");
        return value;
    }
    private static List<Material> materialList(JSONArray values) { if (values == null) throw new IllegalArgumentException("Missing materials"); ArrayList<Material> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) { JSONObject value = values.optJSONObject(i); if (value == null) throw new IllegalArgumentException("Invalid material"); result.add(new Material(value)); } return Collections.unmodifiableList(result); }
    private static List<FeedRoute> routeList(JSONArray values) { if (values == null) throw new IllegalArgumentException("Missing feed routes"); ArrayList<FeedRoute> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) { JSONObject value = values.optJSONObject(i); if (value == null) throw new IllegalArgumentException("Invalid feed route"); result.add(new FeedRoute(value)); } return Collections.unmodifiableList(result); }
    private static List<SpoolForm> spoolFormList(JSONArray values) { if (values == null || values.length() == 0) throw new IllegalArgumentException("Missing spool forms"); ArrayList<SpoolForm> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) { JSONObject value = values.optJSONObject(i); if (value == null) throw new IllegalArgumentException("Invalid spool form"); result.add(new SpoolForm(value)); } return Collections.unmodifiableList(result); }
    private static List<String> textList(JSONArray values, String label) { if (values == null || values.length() == 0) throw new IllegalArgumentException("Missing " + label); ArrayList<String> result = new ArrayList<>(); for (int i = 0; i < values.length(); i++) { String value = values.optString(i, "").trim(); if (value.length() == 0 || value.length() > 512) throw new IllegalArgumentException("Invalid " + label); result.add(value); } return Collections.unmodifiableList(result); }
    private static byte[] read(InputStream input) throws IOException { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[4096]; int n, total = 0; while ((n = input.read(b)) >= 0) { total += n; if (total > 128 * 1024) throw new IOException("Capability catalog exceeds size limit"); out.write(b, 0, n); } return out.toByteArray(); }
}
