package com.mbaliga.alloy;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Bounded serialization for the scalar settings forwarded to the native slicer. */
final class NativeSettings {
    private static final int MAX_ENTRIES = 1_024;
    private static final int MAX_VALUE_CHARS = 4_096;
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_]+");
    /** Source-verified projections plus explicitly bounded parity experiments. */
    private static final Set<String> APPROVED_KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "arc_fitting", "bottom_fill_pattern", "bottom_solid_min_thickness", "bridge_fan_speed", "bridge_flow_ratio", "bridge_speed", "brim_object_gap", "brim_separation", "brim_type", "brim_width",
            "chamber_temperature", "complete_objects", "cool_plate_temp", "cool_plate_temp_initial_layer", "curr_bed_type", "default_acceleration", "deretract_speed", "dont_support_bridges", "draft_shield",
            "elefant_foot_compensation", "enable_dynamic_overhang_speeds", "external_perimeter_acceleration", "external_perimeter_extrusion_width", "extrusion_width", "filament_cost", "filament_density",
            "filament_max_volumetric_speed", "filament_minimal_purge_on_wipe_tower", "filament_soluble", "filament_vendor", "fill_angle", "fill_pattern", "set_other_flow_ratios",
            "fan_below_layer_time", "first_layer_acceleration", "initial_layer_acceleration", "initial_layer_infill_speed", "initial_layer_travel_acceleration", "first_layer_extrusion_width", "full_fan_speed_layer", "gap_fill_speed", "gcode_flavor", "infill_overlap",
            "interface_shells", "infill_extrusion_width", "internal_solid_infill_pattern", "ironing", "ironing_flowrate", "ironing_spacing", "ironing_speed", "machine_max_acceleration_e",
            "machine_max_acceleration_extruding", "machine_max_acceleration_retracting", "machine_max_acceleration_travel",
            "machine_max_acceleration_x", "machine_max_acceleration_y", "machine_max_acceleration_z", "infill_acceleration", "infill_anchor", "infill_anchor_max",
            "machine_max_feedrate_e", "machine_max_feedrate_x", "machine_max_feedrate_y", "machine_max_feedrate_z",
            "machine_max_jerk_e", "machine_max_jerk_x", "machine_max_jerk_y", "machine_max_jerk_z",
            "machine_min_extruding_rate", "machine_min_travel_rate", "max_layer_height", "min_layer_height", "print_flow_ratio", "first_layer_flow_ratio", "outer_wall_flow_ratio", "inner_wall_flow_ratio", "overhang_flow_ratio", "sparse_infill_flow_ratio", "internal_solid_infill_flow_ratio", "gap_fill_flow_ratio", "top_solid_infill_flow_ratio", "bottom_solid_infill_flow_ratio",
            // Bambu resolves role-specific jerk values separately from the
            // machine axis ceilings. Keep them bounded and explicit when an
            // imported project supplies them rather than falling back to the
            // native process defaults.
            "outer_wall_jerk", "inner_wall_jerk", "infill_jerk", "top_surface_jerk", "initial_layer_jerk", "travel_jerk",
            "min_print_speed", "overhang_speed_0", "overhang_speed_1", "overhang_speed_2", "overhang_speed_3", "perimeter_acceleration", "perimeter_extrusion_width", "perimeter_generator", "printer_model",
            "printer_variant", "raft_layers", "resolution", "retract_before_travel", "retract_before_wipe", "retract_length",
            "retract_length_toolchange", "retract_lift", "retract_lift_above", "retract_lift_below", "retract_restart_extra", "z_hop_types",
            "retract_restart_extra_toolchange", "retract_layer_change", "retract_speed", "reduce_infill_retraction", "seam_position", "silent_mode",
            "single_extruder_multi_material", "skirt_distance", "skirt_height", "skirts", "slowdown_below_layer_time",
            "eng_plate_temp", "eng_plate_temp_initial_layer", "hot_plate_temp", "hot_plate_temp_initial_layer",
            "inner_wall_acceleration", "outer_wall_acceleration", "sparse_infill_acceleration", "top_surface_acceleration", "internal_solid_infill_acceleration",
            "small_perimeter_speed", "solid_infill_below_area", "solid_infill_extrusion_width", "solid_infill_speed", "standby_temperature_delta",
            "spiral_vase", "support_material_buildplate_only", "support_material_bottom_contact_distance", "support_material_bottom_interface_layers",
            "support_material_contact_distance", "support_material_interface_layers", "support_material_interface_pattern",
            "support_material_interface_spacing", "support_material_interface_speed", "support_material_extrusion_width", "support_material_speed",
            "support_material_xy_spacing", "support_tree_angle", "support_tree_branch_diameter",
            // Native tree-support key; only controlled parity experiments use it
            // until the pinned Orca process snapshot exposes a source value.
            "support_tree_branch_distance", "support_tree_branch_diameter_angle", "support_material_pattern", "support_material_spacing", "support_material_style", "support_tree_branch_diameter_double_wall", "support_interface_spacing", "support_bottom_interface_spacing", "support_remove_small_overhang", "support_object_first_layer_gap", "support_interface_not_for_body", "thin_walls", "top_fill_pattern", "top_infill_extrusion_width",
            "top_one_perimeter_type", "top_solid_infill_acceleration", "top_solid_infill_speed", "top_solid_min_thickness", "travel_acceleration", "travel_slope", "travel_speed_z",
            "supertack_plate_temp", "supertack_plate_temp_initial_layer", "textured_plate_temp", "textured_plate_temp_initial_layer",
            "wipe", "wipe_distance", "wipe_tower_no_sparse_layers")));

    private NativeSettings() { }

    static JSONObject encode(Map<String, String> settings) throws JSONException {
        if (settings == null || settings.size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Native settings are missing or too large");
        JSONObject encoded = new JSONObject();
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            validate(entry.getKey(), entry.getValue());
            encoded.put(entry.getKey(), entry.getValue());
        }
        return encoded;
    }

    static boolean isApprovedKey(String key) {
        return key != null && APPROVED_KEYS.contains(key);
    }

    static LinkedHashMap<String, String> parse(JSONObject object, boolean required) throws JSONException {
        if (object == null) {
            if (required) throw new IllegalArgumentException("Native settings are missing");
            return new LinkedHashMap<>();
        }
        if (object.length() == 0 && required)
            throw new IllegalArgumentException("Native settings are empty");
        if (object.length() > MAX_ENTRIES)
            throw new IllegalArgumentException("Native settings are too large");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object raw = object.get(key);
            if (!(raw instanceof String))
                throw new IllegalArgumentException("Native setting must be a string: " + key);
            String value = (String) raw;
            validate(key, value);
            result.put(key, value);
        }
        return result;
    }

    static LinkedHashMap<String, String> parseField(JSONObject parent, String key, boolean required) throws JSONException {
        if (parent == null) throw new IllegalArgumentException("Native settings parent is missing");
        Object raw = parent.opt(key);
        if (raw == null || raw == JSONObject.NULL) {
            if (required) throw new IllegalArgumentException("Native settings are missing");
            return new LinkedHashMap<>();
        }
        if (!(raw instanceof JSONObject))
            throw new IllegalArgumentException("Native settings must be an object");
        return parse((JSONObject) raw, required);
    }

    private static void validate(String key, String value) {
        if (key == null || !KEY.matcher(key).matches())
            throw new IllegalArgumentException("Native setting key is invalid: " + key);
        if (!isApprovedKey(key))
            throw new IllegalArgumentException("Native setting is not approved: " + key);
        if (value == null || value.length() > MAX_VALUE_CHARS
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Native setting is too long or multiline: " + key);
    }
}
