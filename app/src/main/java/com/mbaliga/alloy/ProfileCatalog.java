package com.mbaliga.alloy;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads versioned printer/process/filament data without embedding presets in UI code. */
public final class ProfileCatalog {
    private static final String DEFAULT_ASSET = "profiles/a1-mini-0.4-pla-basic.json";
    private static final String[] INITIAL_ASSETS = {
            DEFAULT_ASSET, "profiles/a1-0.4-pla-basic.json", "profiles/p1s-0.4-pla-basic.json"
    };
    private static final int MAX_PROFILE_BYTES = 256 * 1024;

    private ProfileCatalog() { }

    public static Profile loadDefault(AssetManager assets) throws IOException {
        try (InputStream input = assets.open(DEFAULT_ASSET)) {
            return load(input);
        }
    }

    /**
     * The initial planning scope is intentionally finite and source-labelled:
     * A1 mini, A1 and P1S at their supplied 0.4 mm / PLA Basic starting
     * recipes. These profiles support fitting, arrangement and inspection;
     * their unverified state prevents them from qualifying a physical send.
     */
    public static List<Profile> loadInitial(AssetManager assets) throws IOException {
        ArrayList<Profile> profiles = new ArrayList<>();
        for (String asset : INITIAL_ASSETS) {
            try (InputStream input = assets.open(asset)) { profiles.add(load(input)); }
        }
        if (profiles.size() != 3
                || !"bambu.a1-mini".equals(profiles.get(0).printerId)
                || !"bambu.a1".equals(profiles.get(1).printerId)
                || !"bambu.p1s".equals(profiles.get(2).printerId))
            throw new IOException("Initial planning profiles must contain A1 mini, A1 and P1S in order");
        return Collections.unmodifiableList(profiles);
    }

    public static Profile loadInitialByPrinterId(AssetManager assets, String printerId) throws IOException {
        String requested = printerId == null ? "" : printerId.trim();
        for (Profile profile : loadInitial(assets)) if (profile.printerId.equals(requested)) return profile;
        return loadDefault(assets);
    }

    /** Load an Alloy-normalized profile from the app's private profile store. */
    public static Profile load(InputStream input) throws IOException {
        byte[] data = readAll(input);
        try {
            return parse(new JSONObject(new String(data, StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IOException("Invalid printer profile: " + error.getMessage(), error);
        }
    }

    /** Read a bounded user-selected preset before it reaches the importer. */
    public static byte[] readProfileDocument(InputStream input) throws IOException {
        if (input == null) throw new IOException("Profile document is missing");
        return readAll(input);
    }

    /**
     * Import one or more Bambu preset JSON documents without importing their
     * executable G-code templates or arbitrary settings. Presets are layered
     * over the shipped profile so a machine-only/process-only export remains
     * usable while the missing inherited values stay explicit and bounded.
     */
    public static Profile importBambu(Profile baseline, byte[][] documents) throws IOException {
        if (baseline == null) throw new IllegalArgumentException("A baseline profile is required");
        if (documents == null || documents.length == 0 || documents.length > 32)
            throw new IOException("Select one to thirty-two Bambu JSON presets");
        LinkedHashMap<String, Object> all = new LinkedHashMap<>();
        LinkedHashMap<String, Object> machine = new LinkedHashMap<>();
        LinkedHashMap<String, Object> process = new LinkedHashMap<>();
        LinkedHashMap<String, Object> filament = new LinkedHashMap<>();
        LinkedHashMap<String, JSONObject> presets = new LinkedHashMap<>();
        ArrayList<JSONObject> roots = new ArrayList<>();
        JSONObject projectSettings = null;
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (Exception error) {
            throw new IOException("SHA-1 is unavailable", error);
        }
        int totalBytes = 0;
        for (byte[] document : documents) {
            if (document == null || document.length == 0 || document.length > MAX_PROFILE_BYTES)
                throw new IOException("A selected profile is empty or exceeds the 256 KB limit");
            totalBytes += document.length;
            if (totalBytes > 4 * 1024 * 1024) throw new IOException("Imported profiles exceed the size limit");
            digest.update(document);
            try {
                JSONObject root = new JSONObject(new String(document, StandardCharsets.UTF_8));
                String type = scalar(root.opt("type")).toLowerCase(Locale.US);
                String name = scalar(root.opt("name"));
                // Bambu exports the effective project as Metadata/project_settings.config.
                // Unlike a preset, it has no type and contains the resolved scalar
                // values directly. Accept exactly one such document so a user can
                // share/import a project from the phone without first splitting it
                // back into machine, process and filament presets.
                if (type.length() == 0 && isProjectSettings(root)) {
                    if (projectSettings != null)
                        throw new IOException("Only one Bambu project settings document may be imported");
                    projectSettings = root;
                    continue;
                }
                roots.add(root);
                if (!"machine".equals(type) && !"process".equals(type) && !"filament".equals(type))
                    throw new IOException("Unsupported Bambu preset type: " + (type.length() == 0 ? "missing" : type));
                if (name.length() == 0) throw new IOException("Bambu " + type + " preset name is missing");
                String identity = type + ":" + name;
                if (presets.containsKey(identity))
                    throw new IOException("Duplicate Bambu " + type + " preset: " + name);
                presets.put(identity, root);
            } catch (Exception error) {
                throw new IOException("Invalid Bambu JSON preset: " + error.getMessage(), error);
            }
        }

        // Bambu's user-facing presets are intentionally small overlays. When
        // the matching inherited files are among the selected documents,
        // resolve them in parent-first order; missing system parents remain
        // bounded fallbacks instead of being guessed from an arbitrary file.
        LinkedHashMap<String, Object> resolvedMachine = resolveSelected("machine", roots, presets);
        LinkedHashMap<String, Object> resolvedProcess = resolveSelected("process", roots, presets);
        LinkedHashMap<String, Object> resolvedFilament = resolveSelected("filament", roots, presets);
        machine.putAll(resolvedMachine);
        process.putAll(resolvedProcess);
        filament.putAll(resolvedFilament);
        all.putAll(machine);
        all.putAll(process);
        all.putAll(filament);
        if (projectSettings != null) {
            // The project file is already resolved, so it is the authoritative
            // fallback for values not present in any separately selected preset.
            // Native projection remains allowlisted below; arbitrary templates,
            // scripts and metadata never cross this boundary.
            java.util.Iterator<String> keys = projectSettings.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                all.put(key, projectSettings.opt(key));
            }
        }

        String printerName = projectSettings == null
                ? boundedText(text(machine, all, "name"), baseline.name)
                : boundedText(text(machine, all, "printer_model"), baseline.name);
        String printerModel = boundedText(text(machine, all, "printer_model"), printerName);
        String filamentName = projectSettings == null
                ? boundedText(text(filament, all, "name"), baseline.filamentName)
                : boundedText(text(filament, all, "default_filament_profile", "filament_name", "name"), baseline.filamentName);
        String material = boundedText(text(filament, all, "filament_type", "material"), baseline.material);
        String revision = hex(digest.digest());
        String profileName = printerName;
        String processName = projectSettings == null
                ? boundedText(text(process, all, "name"), "")
                : boundedText(text(process, all, "default_print_profile", "process_name", "name"), "");
        if (processName.length() > 0 && !processName.equals(printerName)) profileName += " · " + processName;
        if (filamentName.length() > 0 && !filamentName.equals(printerName)) profileName += " · " + filamentName;

        double[] bed = printableArea(machine, all, baseline.bedX, baseline.bedY);
        double nozzle = number(machine, all, baseline.nozzle, "nozzle_diameter", "nozzle_diameter_mm");
        double buildZ = number(machine, all, baseline.buildZ, "printable_height", "build_z_mm");
        double layer = number(process, all, baseline.layerHeight, "layer_height", "layer_height_mm");
        double firstLayer = number(process, all, baseline.firstLayerHeight,
                "initial_layer_print_height", "first_layer_height", "initial_layer_height");
        double infill = percent(process, all, baseline.infill * 100d, "sparse_infill_density", "fill_density", "infill_percent");
        int perimeters = integer(process, all, baseline.perimeters, "wall_loops", "perimeters");
        int topLayers = integer(process, all, baseline.topLayers, "top_shell_layers", "top_solid_layers", "top_layers");
        int bottomLayers = integer(process, all, baseline.bottomLayers, "bottom_shell_layers", "bottom_solid_layers", "bottom_layers");
        boolean supports = bool(process, all, baseline.supports, "enable_support", "support_material", "supports");
        double threshold = number(process, all, baseline.supportThresholdDegrees,
                "support_threshold_angle", "support_material_threshold", "support_threshold_degrees");
        double travel = number(process, all, baseline.travelSpeed, "travel_speed", "travel_speed_mm_s");
        double outer = number(process, all, baseline.outerWallSpeed, "outer_wall_speed", "outer_wall_speed_mm_s");
        double inner = number(process, all, baseline.innerWallSpeed, "inner_wall_speed", "inner_wall_speed_mm_s");
        double infillSpeed = number(process, all, baseline.infillSpeed, "sparse_infill_speed", "infill_speed", "infill_speed_mm_s");
        double initialSpeed = number(process, all, baseline.initialLayerSpeed, "initial_layer_speed", "initial_layer_speed_mm_s");
        double diameter = number(filament, all, baseline.filamentDiameter, "filament_diameter", "diameter_mm");
        double nozzleTemp = number(filament, all, baseline.nozzleTemperature,
                "nozzle_temperature", "nozzle_temperature_c");
        double firstNozzleTemp = number(filament, all, baseline.firstLayerNozzleTemperature,
                "nozzle_temperature_initial_layer", "first_layer_nozzle_temperature", "first_layer_nozzle_temperature_c");
        double bedTemp = number(filament, all, baseline.bedTemperature,
                "hot_plate_temp", "bed_temperature", "bed_temperature_c");
        double firstBedTemp = number(filament, all, baseline.firstLayerBedTemperature,
                "hot_plate_temp_initial_layer", "first_layer_bed_temperature", "first_layer_bed_temperature_c");
        double flow = number(filament, all, baseline.flowRatio, "filament_flow_ratio", "flow_ratio");
        double maxVolumetric = number(filament, all, baseline.maxVolumetricSpeed,
                "filament_max_volumetric_speed", "max_volumetric_speed_mm3_s");
        double fanMin = number(filament, all, baseline.fanMinPercent, "fan_min_speed", "fan_min_percent");
        double fanMax = number(filament, all, baseline.fanMaxPercent, "fan_max_speed", "fan_max_percent");

        LinkedHashMap<String, String> nativeSettings = new LinkedHashMap<>();
        String printerLower = printerModel.toLowerCase(Locale.US);
        // The packaged A1 values are useful when a user imports a process or
        // filament overlay for the A1 Mini, but must not silently become the
        // machine limits of a different printer.
        if (printerLower.contains("a1 mini") || printerName.toLowerCase(Locale.US).contains("a1 mini"))
            nativeSettings.putAll(baseline.nativeSettings);
        for (Map.Entry<String, Object> entry : all.entrySet()) {
            String nativeKey = importedNativeKey(entry.getKey(), all);
            if (nativeKey == null || !NativeSettings.isApprovedKey(nativeKey)) continue;
            String value = importedNativeValue(nativeKey, entry.getKey(), entry.getValue(), all);
            if (value.length() <= 4_096 && value.indexOf('\n') < 0 && value.indexOf('\r') < 0)
                nativeSettings.put(nativeKey, value);
        }
        String printerId = "bambu.imported." + slug(printerModel);
        String filamentId = "bambu.imported." + slug(filamentName);
        return new Profile(
                "bambu.imported." + revision.substring(0, 12), profileName, false,
                "Bambu JSON preset import", revision, printerId, bed[0], bed[1], buildZ, nozzle,
                layer, firstLayer, infill, perimeters, topLayers, bottomLayers, supports, threshold,
                travel, outer, inner, infillSpeed, initialSpeed, filamentId, filamentName, material,
                diameter, nozzleTemp, firstNozzleTemp, bedTemp, firstBedTemp, flow, maxVolumetric,
                fanMin, fanMax, nativeSettings);
    }

    private static boolean isProjectSettings(JSONObject root) {
        if (root == null) return false;
        String name = scalar(root.opt("name")).toLowerCase(Locale.US);
        return "project_settings".equals(name)
                || (root.has("printer_model")
                && (root.has("default_print_profile") || root.has("default_filament_profile")
                || root.has("printer_settings_id") || root.has("print_settings_id")
                || root.has("filament_settings_id")));
    }

    /** Serialize an imported or built-in profile for the private app profile store. */
    public static byte[] serialize(Profile profile) throws IOException {
        if (profile == null) throw new IllegalArgumentException("Profile is missing");
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", 1);
            root.put("id", profile.id);
            root.put("name", profile.name);
            root.put("verified", profile.verified);
            JSONObject provenance = new JSONObject();
            provenance.put("source", profile.provenanceSource);
            provenance.put("revision", profile.provenanceRevision);
            root.put("provenance", provenance);
            JSONObject printer = new JSONObject();
            printer.put("id", profile.printerId);
            printer.put("bed_x_mm", profile.bedX);
            printer.put("bed_y_mm", profile.bedY);
            printer.put("build_z_mm", profile.buildZ);
            printer.put("nozzle_mm", profile.nozzle);
            root.put("printer", printer);
            JSONObject process = new JSONObject();
            process.put("layer_height_mm", profile.layerHeight);
            process.put("first_layer_height_mm", profile.firstLayerHeight);
            process.put("infill_percent", profile.infill * 100f);
            process.put("perimeters", profile.perimeters);
            process.put("top_layers", profile.topLayers);
            process.put("bottom_layers", profile.bottomLayers);
            process.put("support_enabled", profile.supports);
            process.put("support_threshold_degrees", profile.supportThresholdDegrees);
            process.put("travel_speed_mm_s", profile.travelSpeed);
            process.put("outer_wall_speed_mm_s", profile.outerWallSpeed);
            process.put("inner_wall_speed_mm_s", profile.innerWallSpeed);
            process.put("infill_speed_mm_s", profile.infillSpeed);
            process.put("initial_layer_speed_mm_s", profile.initialLayerSpeed);
            root.put("process", process);
            JSONObject filament = new JSONObject();
            filament.put("id", profile.filamentId);
            filament.put("name", profile.filamentName);
            filament.put("material", profile.material);
            filament.put("diameter_mm", profile.filamentDiameter);
            filament.put("nozzle_temperature_c", profile.nozzleTemperature);
            filament.put("first_layer_nozzle_temperature_c", profile.firstLayerNozzleTemperature);
            filament.put("bed_temperature_c", profile.bedTemperature);
            filament.put("first_layer_bed_temperature_c", profile.firstLayerBedTemperature);
            filament.put("flow_ratio", profile.flowRatio);
            filament.put("max_volumetric_speed_mm3_s", profile.maxVolumetricSpeed);
            filament.put("fan_min_percent", profile.fanMinPercent);
            filament.put("fan_max_percent", profile.fanMaxPercent);
            root.put("filament", filament);
            root.put("native_settings", NativeSettings.encode(profile.nativeSettings));
            return root.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IOException("Could not serialize profile: " + error.getMessage(), error);
        }
    }

    private static Profile parse(JSONObject root) throws JSONException {
        JSONObject printer = root.getJSONObject("printer");
        JSONObject process = root.getJSONObject("process");
        JSONObject filament = root.getJSONObject("filament");
        JSONObject provenance = root.getJSONObject("provenance");
        int schema = root.getInt("schema_version");
        if (schema != 1) throw new IllegalArgumentException("Unsupported profile schema " + schema);
        String provenanceSource = required(provenance, "source");
        String provenanceRevision = required(provenance, "revision");
        if (!provenanceRevision.matches("[0-9a-fA-F]{40}"))
            throw new IllegalArgumentException("Profile provenance revision must be a commit SHA");
        Map<String, String> nativeSettings = parseNativeSettings(root.optJSONObject("native_settings"));
        return new Profile(
                root.getString("id"),
                root.getString("name"),
                root.getBoolean("verified"),
                provenanceSource,
                provenanceRevision,
                printer.getString("id"),
                printer.getDouble("bed_x_mm"),
                printer.getDouble("bed_y_mm"),
                printer.getDouble("build_z_mm"),
                printer.getDouble("nozzle_mm"),
                process.getDouble("layer_height_mm"),
                process.getDouble("first_layer_height_mm"),
                process.getDouble("infill_percent"),
                process.getInt("perimeters"),
                process.getInt("top_layers"),
                process.getInt("bottom_layers"),
                process.getBoolean("support_enabled"),
                process.getDouble("support_threshold_degrees"),
                process.getDouble("travel_speed_mm_s"),
                process.getDouble("outer_wall_speed_mm_s"),
                process.getDouble("inner_wall_speed_mm_s"),
                process.getDouble("infill_speed_mm_s"),
                process.getDouble("initial_layer_speed_mm_s"),
                filament.getString("id"),
                filament.getString("name"),
                filament.getString("material"),
                filament.getDouble("diameter_mm"),
                filament.getDouble("nozzle_temperature_c"),
                filament.getDouble("first_layer_nozzle_temperature_c"),
                filament.getDouble("bed_temperature_c"),
                filament.getDouble("first_layer_bed_temperature_c"),
                filament.getDouble("flow_ratio"),
                filament.getDouble("max_volumetric_speed_mm3_s"),
                filament.getDouble("fan_min_percent"),
                filament.getDouble("fan_max_percent"),
                nativeSettings);
    }

    /**
     * Read only scalar settings that are safe to project into the
     * Prusa/SliceBeam config. Machine and layer G-code templates deliberately
     * stay Alloy-owned so Bambu-specific commands cannot leak into a generic
     * native job.
     */
    private static Map<String, String> parseNativeSettings(JSONObject object) throws JSONException {
        return NativeSettings.parse(object, true);
    }

    /**
     * Translate the current Bambu/Orca vocabulary into the bounded legacy
     * names consumed by Alloy's typed native adapter. The importer must do
     * this explicitly: silently dropping these keys makes an imported Bambu
     * support recipe look valid while changing its generated toolpaths.
     */
    private static String importedNativeKey(String key, Map<String, Object> all) {
        if (key == null) return null;
        if (NativeSettings.isApprovedKey(key)) {
            // A current Bambu preset's "support_style=default" is not a
            // standalone style. With tree(auto), Orca resolves it to its
            // organic implementation; retain that meaning in Alloy's
            // legacy profile field.
            if ("support_style".equals(key)) {
                String value = scalar(all.get(key)).trim().toLowerCase(Locale.US);
                if ("default".equals(value)) {
                    String type = scalar(all.get("support_type")).trim().toLowerCase(Locale.US);
                    if (type.startsWith("tree")) return "support_material_style";
                }
            }
            return key;
        }
        switch (key) {
            case "support_type": {
                // When the provider supplies both keys, support_style is the
                // more specific value. Avoid making JSON member order change
                // the resulting recipe.
                if (all.containsKey("support_style")) return null;
                String value = scalar(all.get(key)).trim().toLowerCase(Locale.US);
                return value.startsWith("tree") ? "support_material_style" : null;
            }
            case "support_style": {
                String value = scalar(all.get(key)).trim().toLowerCase(Locale.US);
                if ("default".equals(value)) {
                    String type = scalar(all.get("support_type")).trim().toLowerCase(Locale.US);
                    if (type.startsWith("tree")) return "support_material_style";
                    return null;
                }
                if ("tree_slim".equals(value) || "tree_strong".equals(value)
                        || "tree_hybrid".equals(value)) return "support_material_style";
                if ("tree_organic".equals(value)) return "support_material_style";
                if ("snug".equals(value) || "grid".equals(value)
                        || "organic".equals(value) || "tree".equals(value))
                    return "support_material_style";
                return null;
            }
            case "support_base_pattern": return "support_material_pattern";
            case "support_base_pattern_spacing": return "support_material_spacing";
            case "support_interface_pattern": return "support_material_interface_pattern";
            case "support_interface_spacing": return "support_material_interface_spacing";
            case "support_interface_speed": return "support_material_interface_speed";
            case "support_interface_top_layers": return "support_material_interface_layers";
            case "support_interface_bottom_layers": return "support_material_bottom_interface_layers";
            case "support_top_z_distance": return "support_material_contact_distance";
            case "support_bottom_z_distance": return "support_material_bottom_contact_distance";
            case "support_line_width": return "support_material_extrusion_width";
            case "support_object_xy_distance": return "support_material_xy_spacing";
            case "support_on_build_plate_only": return "support_material_buildplate_only";
            case "support_speed": return "support_material_speed";
            case "tree_support_branch_angle": return "support_tree_angle";
            case "tree_support_branch_diameter": return "support_tree_branch_diameter";
            case "tree_support_branch_distance": return "support_tree_branch_distance";
            case "tree_support_branch_diameter_angle": return "support_tree_branch_diameter_angle";
            case "tree_support_wall_count": return "support_tree_branch_diameter_double_wall";
            case "support_remove_small_overhang": return "support_remove_small_overhang";
            case "sparse_infill_pattern": return "fill_pattern";
            case "top_surface_pattern": return "top_fill_pattern";
            case "bottom_surface_pattern": return "bottom_fill_pattern";
            case "line_width": return "extrusion_width";
            case "outer_wall_line_width": return "external_perimeter_extrusion_width";
            case "inner_wall_line_width": return "perimeter_extrusion_width";
            case "sparse_infill_line_width": return "infill_extrusion_width";
            case "internal_solid_infill_line_width": return "solid_infill_extrusion_width";
            case "top_surface_line_width": return "top_infill_extrusion_width";
            case "initial_layer_line_width": return "first_layer_extrusion_width";
            case "internal_solid_infill_speed": return "solid_infill_speed";
            case "top_surface_speed": return "top_solid_infill_speed";
            case "initial_layer_flow_ratio": return "first_layer_flow_ratio";
            case "bridge_flow": return "bridge_flow_ratio";
            case "bridge_no_support": return "dont_support_bridges";
            case "gap_infill_speed": return "gap_fill_speed";
            case "minimum_sparse_infill_area": return "solid_infill_below_area";
            case "enable_overhang_speed": return "enable_dynamic_overhang_speeds";
            case "overhang_1_4_speed": return "overhang_speed_0";
            case "overhang_2_4_speed": return "overhang_speed_1";
            case "overhang_3_4_speed": return "overhang_speed_2";
            case "overhang_4_4_speed": return "overhang_speed_3";
            case "fan_cooling_layer_time": return "fan_below_layer_time";
            case "slow_down_min_speed": return "min_print_speed";
            case "retract_when_changing_layer": return "retract_layer_change";
            case "reduce_infill_retraction_mode": return "reduce_infill_retraction";
            case "retraction_length": return "retract_length";
            case "retraction_speed": return "retract_speed";
            case "z_hop": return "retract_lift";
            case "wipe_distance": return "wipe_distance";
            case "skirt_loops": return "skirts";
            case "wall_generator": return "perimeter_generator";
            case "infill_direction": return "fill_angle";
            case "sparse_infill_anchor": return "infill_anchor";
            case "sparse_infill_anchor_max": return "infill_anchor_max";
            case "top_shell_thickness": return "top_solid_min_thickness";
            case "bottom_shell_thickness": return "bottom_solid_min_thickness";
            case "detect_thin_wall": return "thin_walls";
            case "machine_max_speed_x": return "machine_max_feedrate_x";
            case "machine_max_speed_y": return "machine_max_feedrate_y";
            case "machine_max_speed_z": return "machine_max_feedrate_z";
            case "machine_max_speed_e": return "machine_max_feedrate_e";
            default: return null;
        }
    }

    private static String importedNativeValue(String nativeKey, String sourceKey,
                                              Object raw, Map<String, Object> all) {
        String value = arrayText(raw);
        if ("fill_pattern".equals(nativeKey) || "top_fill_pattern".equals(nativeKey)
                || "bottom_fill_pattern".equals(nativeKey)
                || "internal_solid_infill_pattern".equals(nativeKey)) {
            String normalized = value.trim().toLowerCase(Locale.US);
            if ("zig-zag".equals(normalized)) return "rectilinear";
            if ("monotoniclines".equals(normalized)) return "monotonicline";
            return normalized;
        }
        if ("reduce_infill_retraction".equals(nativeKey)) {
            String normalized = value.trim().toLowerCase(Locale.US);
            return "auto".equals(normalized) || "always".equals(normalized)
                    || "true".equals(normalized) || "1".equals(normalized) ? "1" : "0";
        }
        if (!"support_material_style".equals(nativeKey)) return value;
        String normalized = value.trim().toLowerCase(Locale.US);
        if ("support_type".equals(sourceKey) || "default".equals(normalized)) {
            String type = scalar(all.get("support_type")).trim().toLowerCase(Locale.US);
            if (type.startsWith("tree")) return "organic";
            return "grid";
        }
        if ("tree_slim".equals(normalized) || "tree_strong".equals(normalized)
                || "tree_hybrid".equals(normalized) || "tree".equals(normalized)) return "tree";
        if ("tree_organic".equals(normalized)) return "organic";
        return normalized;
    }

    private static String text(Map<String, Object> primary, Map<String, Object> fallback, String... keys) {
        for (String key : keys) {
            String value = scalar(primary.get(key));
            if (value.length() > 0) return value;
            value = scalar(fallback.get(key));
            if (value.length() > 0) return value;
        }
        return "";
    }

    private static LinkedHashMap<String, Object> resolveSelected(String type,
                                                                   ArrayList<JSONObject> roots,
                                                                   Map<String, JSONObject> presets) throws IOException {
        ArrayList<JSONObject> candidates = new ArrayList<>();
        java.util.HashSet<String> referenced = new java.util.HashSet<>();
        for (JSONObject root : roots) {
            if (!type.equalsIgnoreCase(scalar(root.opt("type")))) continue;
            candidates.add(root);
            addReferencedParents(type, root.opt("inherits"), referenced);
        }
        if (candidates.isEmpty()) return new LinkedHashMap<>();
        ArrayList<JSONObject> topLevel = new ArrayList<>();
        for (JSONObject candidate : candidates) {
            String name = scalar(candidate.opt("name"));
            if (!referenced.contains(type + ":" + name)) topLevel.add(candidate);
        }
        if (topLevel.size() != 1) {
            throw new IOException("Select exactly one active " + type + " preset; the import contains "
                    + topLevel.size() + " unrelated candidates");
        }
        JSONObject selected = topLevel.get(0);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        resolveInto(type, selected, presets, new java.util.HashSet<>(), result);
        return result;
    }

    /** Bambu exports use either a delimited string or a JSON array for parents. */
    private static void addReferencedParents(String type, Object rawParent, java.util.Set<String> referenced) {
        if (rawParent instanceof JSONArray) {
            JSONArray parents = (JSONArray) rawParent;
            for (int index = 0; index < parents.length(); index++) {
                String name = scalar(parents.opt(index));
                if (name.length() > 0) referenced.add(type + ":" + name);
            }
            return;
        }
        String parent = scalar(rawParent);
        for (String item : parent.split("[;,]")) {
            String name = item.trim();
            if (name.length() > 0) referenced.add(type + ":" + name);
        }
    }

    private static void resolveInto(String type, JSONObject root, Map<String, JSONObject> presets,
                                    java.util.Set<String> visiting, LinkedHashMap<String, Object> result) throws IOException {
        if (root == null) return;
        String name = scalar(root.opt("name"));
        String identity = type + ":" + name;
        if (!visiting.add(identity)) throw new IOException("Bambu " + type + " preset inheritance cycle at " + name);
        Object rawParent = root.opt("inherits");
        if (rawParent instanceof JSONArray) {
            JSONArray parents = (JSONArray) rawParent;
            for (int index = 0; index < parents.length(); index++)
                resolveInto(type, presets.get(type + ":" + scalar(parents.opt(index))), presets, visiting, result);
        } else {
            String parent = scalar(rawParent);
            for (String item : parent.split("[;,]")) {
                String parentName = item.trim();
                if (parentName.length() > 0)
                    resolveInto(type, presets.get(type + ":" + parentName), presets, visiting, result);
            }
        }
        java.util.Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"inherits".equals(key)) result.put(key, root.opt(key));
        }
        visiting.remove(identity);
    }

    private static double number(Map<String, Object> primary, Map<String, Object> fallback, double defaultValue, String... keys) {
        String value = text(primary, fallback, keys).replace("mm/s", "").replace("mm", "").trim();
        if (value.endsWith("%")) value = value.substring(0, value.length() - 1).trim();
        try { return Double.parseDouble(value); } catch (Exception ignored) { return defaultValue; }
    }

    private static double percent(Map<String, Object> primary, Map<String, Object> fallback, double defaultValue, String... keys) {
        String value = text(primary, fallback, keys).trim();
        boolean marked = value.endsWith("%");
        if (marked) value = value.substring(0, value.length() - 1).trim();
        try {
            double parsed = Double.parseDouble(value);
            if (!marked && parsed >= 0d && parsed <= 1d) parsed *= 100d;
            return parsed;
        } catch (Exception ignored) { return defaultValue; }
    }

    private static int integer(Map<String, Object> primary, Map<String, Object> fallback, int defaultValue, String... keys) {
        return (int) Math.round(number(primary, fallback, defaultValue, keys));
    }

    private static boolean bool(Map<String, Object> primary, Map<String, Object> fallback, boolean defaultValue, String... keys) {
        String value = text(primary, fallback, keys).toLowerCase(Locale.US);
        if ("1".equals(value) || "true".equals(value) || "yes".equals(value) || "on".equals(value)) return true;
        if ("0".equals(value) || "false".equals(value) || "no".equals(value) || "off".equals(value)) return false;
        return defaultValue;
    }

    private static double[] printableArea(Map<String, Object> primary, Map<String, Object> fallback, double defaultX, double defaultY) {
        Object raw = primary.get("printable_area");
        if (raw == null) raw = fallback.get("printable_area");
        double maxX = 0d, maxY = 0d;
        if (raw instanceof JSONArray) {
            JSONArray values = (JSONArray) raw;
            for (int index = 0; index < values.length(); index++) {
                double[] point = point(values.opt(index));
                if (point != null) { maxX = Math.max(maxX, point[0]); maxY = Math.max(maxY, point[1]); }
            }
        } else {
            String[] values = scalar(raw).split(",");
            for (String value : values) {
                double[] point = point(value);
                if (point != null) { maxX = Math.max(maxX, point[0]); maxY = Math.max(maxY, point[1]); }
            }
        }
        return new double[]{maxX > 1d ? maxX : defaultX, maxY > 1d ? maxY : defaultY};
    }

    private static double[] point(Object value) {
        String text = scalar(value).toLowerCase(Locale.US).trim();
        int split = text.indexOf('x');
        if (split <= 0) return null;
        try { return new double[]{Math.abs(Double.parseDouble(text.substring(0, split))), Math.abs(Double.parseDouble(text.substring(split + 1)))}; }
        catch (Exception ignored) { return null; }
    }

    private static String arrayText(Object raw) {
        if (!(raw instanceof JSONArray)) return scalar(raw);
        JSONArray array = (JSONArray) raw;
        StringBuilder value = new StringBuilder();
        for (int index = 0; index < array.length(); index++) {
            if (index > 0) value.append(',');
            value.append(scalar(array.opt(index)));
        }
        return value.toString();
    }

    private static String scalar(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return "";
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            return array.length() == 0 ? "" : scalar(array.opt(0));
        }
        return String.valueOf(raw).trim();
    }

    private static String slug(String value) {
        String result = value == null ? "profile" : value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "-");
        result = result.replaceAll("^-+|-+$", "");
        return result.length() == 0 ? "profile" : result.substring(0, Math.min(48, result.length()));
    }

    private static String boundedText(String value, String fallback) {
        String result = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (result.length() == 0) result = fallback == null ? "" : fallback;
        return result.length() > 160 ? result.substring(0, 160) : result;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() > MAX_PROFILE_BYTES - count)
                throw new IOException("Printer profile exceeds the 256 KB limit");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String required(JSONObject object, String key) {
        String value = object.optString(key, "").trim();
        if (value.length() == 0) throw new IllegalArgumentException("Profile provenance field is missing: " + key);
        return value;
    }

    public static final class Profile {
        public final String id;
        public final String name;
        public final boolean verified;
        public final String provenanceSource;
        public final String provenanceRevision;
        public final String printerId;
        public final float bedX;
        public final float bedY;
        public final float buildZ;
        public final float nozzle;
        public final float layerHeight;
        public final float firstLayerHeight;
        public final float infill;
        public final int perimeters;
        public final int topLayers;
        public final int bottomLayers;
        public final boolean supports;
        public final float supportThresholdDegrees;
        public final float travelSpeed;
        public final float outerWallSpeed;
        public final float innerWallSpeed;
        public final float infillSpeed;
        public final float initialLayerSpeed;
        public final String filamentId;
        public final String filamentName;
        public final String material;
        public final float filamentDiameter;
        public final float nozzleTemperature;
        public final float firstLayerNozzleTemperature;
        public final float bedTemperature;
        public final float firstLayerBedTemperature;
        public final float flowRatio;
        public final float maxVolumetricSpeed;
        public final float fanMinPercent;
        public final float fanMaxPercent;
        /** Safe scalar settings projected from the pinned upstream profile chain. */
        public final Map<String, String> nativeSettings;

        Profile(String id, String name, boolean verified, String provenanceSource, String provenanceRevision,
                String printerId, double bedX, double bedY, double buildZ, double nozzle,
                double layerHeight, double firstLayerHeight, double infillPercent, int perimeters, int topLayers,
                int bottomLayers, boolean supports, double supportThresholdDegrees, double travelSpeed,
                double outerWallSpeed, double innerWallSpeed, double infillSpeed, double initialLayerSpeed,
                String filamentId, String filamentName, String material, double filamentDiameter,
                double nozzleTemperature, double firstLayerNozzleTemperature, double bedTemperature,
                double firstLayerBedTemperature, double flowRatio, double maxVolumetricSpeed,
                double fanMinPercent, double fanMaxPercent, Map<String, String> nativeSettings) {
            if (id == null || name == null || id.trim().length() == 0 || name.trim().length() == 0)
                throw new IllegalArgumentException("Profile identity is empty");
            requireText("printer id", printerId);
            requireText("filament id", filamentId);
            requireText("filament name", filamentName);
            requireText("material", material);
            requireRange("bed X", bedX, 1d, 5_000d);
            requireRange("bed Y", bedY, 1d, 5_000d);
            requireRange("build Z", buildZ, 1d, 5_000d);
            requireRange("nozzle", nozzle, 0.05d, 2d);
            requireRange("layer height", layerHeight, 0.01d, 2d);
            requireRange("first-layer height", firstLayerHeight, 0.01d, 2d);
            requireRange("infill", infillPercent, 0d, 100d);
            if (perimeters < 1 || perimeters > 20 || topLayers < 0 || topLayers > 100
                    || bottomLayers < 0 || bottomLayers > 100)
                throw new IllegalArgumentException("Profile wall or solid-layer count is invalid");
            requireRange("support threshold", supportThresholdDegrees, 0d, 90d);
            requireRange("travel speed", travelSpeed, 0.1d, 2_000d);
            requireRange("outer wall speed", outerWallSpeed, 0.1d, 1_000d);
            requireRange("inner wall speed", innerWallSpeed, 0.1d, 1_000d);
            requireRange("infill speed", infillSpeed, 0.1d, 1_000d);
            requireRange("initial layer speed", initialLayerSpeed, 0.1d, 1_000d);
            requireRange("filament diameter", filamentDiameter, 1d, 4d);
            requireRange("nozzle temperature", nozzleTemperature, 0d, 400d);
            requireRange("first-layer nozzle temperature", firstLayerNozzleTemperature, 0d, 400d);
            requireRange("bed temperature", bedTemperature, 0d, 150d);
            requireRange("first-layer bed temperature", firstLayerBedTemperature, 0d, 150d);
            requireRange("flow ratio", flowRatio, 0.01d, 2d);
            requireRange("max volumetric speed", maxVolumetricSpeed, 0.01d, 200d);
            requireRange("minimum fan", fanMinPercent, 0d, 100d);
            requireRange("maximum fan", fanMaxPercent, 0d, 100d);
            if (fanMinPercent > fanMaxPercent)
                throw new IllegalArgumentException("Profile minimum fan exceeds maximum fan");
            this.id = id;
            this.name = name;
            this.verified = verified;
            this.provenanceSource = provenanceSource;
            this.provenanceRevision = provenanceRevision;
            this.printerId = printerId;
            this.bedX = (float) bedX;
            this.bedY = (float) bedY;
            this.buildZ = (float) buildZ;
            this.nozzle = (float) nozzle;
            this.layerHeight = (float) layerHeight;
            this.firstLayerHeight = (float) firstLayerHeight;
            this.infill = (float) (infillPercent / 100.0);
            this.perimeters = perimeters;
            this.topLayers = topLayers;
            this.bottomLayers = bottomLayers;
            this.supports = supports;
            this.supportThresholdDegrees = (float) supportThresholdDegrees;
            this.travelSpeed = (float) travelSpeed;
            this.outerWallSpeed = (float) outerWallSpeed;
            this.innerWallSpeed = (float) innerWallSpeed;
            this.infillSpeed = (float) infillSpeed;
            this.initialLayerSpeed = (float) initialLayerSpeed;
            this.filamentId = filamentId;
            this.filamentName = filamentName;
            this.material = material;
            this.filamentDiameter = (float) filamentDiameter;
            this.nozzleTemperature = (float) nozzleTemperature;
            this.firstLayerNozzleTemperature = (float) firstLayerNozzleTemperature;
            this.bedTemperature = (float) bedTemperature;
            this.firstLayerBedTemperature = (float) firstLayerBedTemperature;
            this.flowRatio = (float) flowRatio;
            this.maxVolumetricSpeed = (float) maxVolumetricSpeed;
            this.fanMinPercent = (float) fanMinPercent;
            this.fanMaxPercent = (float) fanMaxPercent;
            this.nativeSettings = Collections.unmodifiableMap(new LinkedHashMap<>(nativeSettings));
        }

        public void applyTo(Slicer.Config config) {
            config.layerHeight = layerHeight;
            config.firstLayerHeight = firstLayerHeight;
            config.nozzle = nozzle;
            config.filamentDiameter = filamentDiameter;
            config.infill = infill;
            config.perimeters = perimeters;
            config.topLayers = topLayers;
            config.bottomLayers = bottomLayers;
            config.supports = supports;
            config.supportThresholdDegrees = supportThresholdDegrees;
            config.travelSpeed = travelSpeed;
            config.outerWallSpeed = outerWallSpeed;
            config.innerWallSpeed = innerWallSpeed;
            config.infillSpeed = infillSpeed;
            config.initialLayerSpeed = initialLayerSpeed;
            config.nozzleTemperature = nozzleTemperature;
            config.firstLayerNozzleTemperature = firstLayerNozzleTemperature;
            config.bedTemperature = bedTemperature;
            config.firstLayerBedTemperature = firstLayerBedTemperature;
            config.extrusionMultiplier = flowRatio;
            config.maxVolumetricSpeed = maxVolumetricSpeed;
            config.fanMinPercent = fanMinPercent;
            config.fanMaxPercent = fanMaxPercent;
            config.bedX = bedX;
            config.bedY = bedY;
            config.bedZ = buildZ;
            config.printer = name;
            // The native core expects the material family (for example PLA),
            // while the display name remains the user-facing preset label.
            config.filament = material;
            config.nativeSettings.clear();
            config.nativeSettings.putAll(nativeSettings);
        }

        public String buildVolumeLabel() {
            return String.format(java.util.Locale.US, "%.0f × %.0f × %.0f mm", bedX, bedY, buildZ);
        }

        public String shortLabel() {
            return String.format(java.util.Locale.US, "%.1f mm nozzle · %s", nozzle, material);
        }
    }

    private static void requireText(String label, String value) {
        if (value == null || value.trim().length() == 0) throw new IllegalArgumentException("Profile " + label + " is empty");
    }

    private static void requireRange(String label, double value, double min, double max) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < min || value > max)
            throw new IllegalArgumentException("Profile " + label + " is outside its allowed range");
    }
}
