package com.mbaliga.alloy;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ru.ytkab0bp.slicebeam.slic3r.Native;
import ru.ytkab0bp.slicebeam.slic3r.SliceListener;
import ru.ytkab0bp.slicebeam.slic3r.Slic3rRuntimeError;

/**
 * Adapter from Alloy's small Java seam to the pinned Orca-Mobile/Prusa JNI core.
 *
 * The native core intentionally receives files rather than Alloy objects. This
 * class owns the bounded staging directory, native handle lifetimes, progress
 * bridge, and the lightweight toolpath projection used by Alloy's GLES
 * inspector. Native G-code remains the export source; the projection is only
 * for fast phone inspection and estimate display.
 */
public final class NativeSlicerEngine implements SlicerEngine {
    static final String ORCA_NATIVE_ENGINE_ID = "orca-mobile-native";
    private static final String LEGACY_NATIVE_ENGINE_ID = "prusa-slicebeam-native";
    private static final long MAX_GCODE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_GCODE_LINE_CHARS = 1 * 1024 * 1024;
    private static final int MAX_PREVIEW_LAYERS = 100_000;
    private static final int MAX_PREVIEW_SEGMENTS = 1_000_000;
    private static final Pattern AXIS = Pattern.compile("([XYZEFIJKR])\\s*([-+]?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][-+]?\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAYER_NUMBER = Pattern.compile(";\\s*LAYER\\s*:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern Z_COMMENT = Pattern.compile(";\\s*Z\\s*:\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILAMENT_COMMENT = Pattern.compile(";\\s*filament\\s+used\\s+\\[mm\\]\\s*=\\s*([-+]?\\d*\\.?\\d+(?:[eE][-+]?\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_COMMENT = Pattern.compile(";\\s*estimated printing time.*?=\\s*(?:(\\d+)h\\s*)?(?:(\\d+)m\\s*)?(?:(\\d+)s)", Pattern.CASE_INSENSITIVE);
    private static final int TOOLPATH_OTHER = 0;
    private static final int TOOLPATH_PERIMETER = 1;
    private static final int TOOLPATH_SUPPORT = 2;

    private final File workRoot;
    private final boolean engineVerified;
    private final Object nativeHandleLock = new Object();
    private final AtomicLong activeModelPtr = new AtomicLong();

    public NativeSlicerEngine(File workRoot) {
        this(workRoot, false);
    }

    /**
     * Verification is a release-policy input, not an inference from a native
     * library loading successfully. It must only be enabled after the pinned
     * engine/profile/parity and hardware acceptance gates pass.
     */
    public NativeSlicerEngine(File workRoot, boolean engineVerified) {
        if (workRoot == null) throw new IllegalArgumentException("Native work directory is required");
        this.workRoot = workRoot;
        this.engineVerified = engineVerified;
    }

    @Override
    public Slicer.Result slice(MeshModel mesh, Slicer.Config config, Slicer.ProgressListener listener) {
        validate(mesh, config);
        if (PrinterTransport.isSymbolicLink(workRoot))
            throw new IllegalStateException("Native slice workspace must not be a symbolic link");
        if (!workRoot.exists() && !workRoot.mkdirs())
            throw new IllegalStateException("Could not create native slice root");
        if (!workRoot.isDirectory()) throw new IllegalStateException("Native slice workspace is not a directory");
        File jobDir = new File(workRoot, "alloy-native-" + UUID.randomUUID().toString());
        if (!jobDir.mkdirs()) throw new IllegalStateException("Could not create native slice workspace");
        long modelPtr = 0L;
        long resultPtr = 0L;
        try {
            File modelFile = new File(jobDir, "model.stl");
            File configFile = new File(jobDir, "config.ini");
            File gcodeFile = new File(jobDir, "plate.gcode");
            writeAsciiStl(mesh, config, modelFile);
            writeConfig(config, configFile);

            try {
                modelPtr = Native.model_read_from_file(modelFile.getAbsolutePath(), safeBaseName(mesh.displayName), 0);
                if (modelPtr == 0L) throw new IllegalStateException("Native engine returned an empty model handle");
                synchronized (nativeHandleLock) { activeModelPtr.set(modelPtr); }
                resultPtr = Native.model_slice(modelPtr, configFile.getAbsolutePath(), gcodeFile.getAbsolutePath(),
                        progressBridge(listener), 1, new int[]{0xFFFFFF}, 0, 0.0, 0.0, 0.0);
            } catch (Slic3rRuntimeError error) {
                throw new IllegalStateException("Native slicer rejected the recipe: " + message(error), error);
            }
            if (resultPtr == 0L || !gcodeFile.isFile() || gcodeFile.length() <= 0L)
                throw new IllegalStateException("Native slicer produced no G-code");
            if (gcodeFile.length() > MAX_GCODE_BYTES)
                throw new IllegalStateException("Native G-code exceeds the 128 MB limit");
            Slicer.Result parsed = parseGcode(gcodeFile, config, ORCA_NATIVE_ENGINE_ID, false);
            // Parsing is deliberately kept independent of release policy, but
            // the instance owns the policy decision made when this engine was
            // constructed. Carry it through to the result that gets packaged
            // and sent to a printer.
            Slicer.Result result = new Slicer.Result(parsed.gcode, parsed.layers, parsed.filamentMm,
                    parsed.warnings, parsed.engineId, engineVerified,
                    parsed.printTimeSeconds, parsed.travelMm);
            A1MiniTemplatePolicy.requireSafe(result.gcode);
            return result;
        } catch (IOException error) {
            throw new IllegalStateException("Native slice staging failed: " + message(error), error);
        } finally {
            if (resultPtr != 0L) Native.gcoderesult_release(resultPtr);
            synchronized (nativeHandleLock) {
                long handle = activeModelPtr.getAndSet(0L);
                if (handle != 0L) Native.model_release(handle);
            }
            deleteTree(jobDir);
        }
    }

    @Override public void cancel() {
        synchronized (nativeHandleLock) {
            long handle = activeModelPtr.get();
            if (handle != 0L) Native.model_cancel(handle);
        }
    }

    private static SliceListener progressBridge(final Slicer.ProgressListener listener) {
        return new SliceListener() {
            @Override public void onProgress(int percent, String phase) {
                if (listener != null) {
                    int safePercent = Math.max(0, Math.min(100, percent));
                    listener.onProgress(safePercent, phase == null ? "Native slicing" : phase);
                }
            }
        };
    }

    private static void writeAsciiStl(MeshModel mesh, Slicer.Config config, File target) throws IOException {
        float shiftX = config.bedX / 2f - (mesh.minX + mesh.maxX) / 2f;
        float shiftY = config.bedY / 2f - (mesh.minY + mesh.maxY) / 2f;
        float shiftZ = -mesh.minZ;
        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.US_ASCII))) {
            out.write("solid alloy");
            out.newLine();
            for (int i = 0; i < mesh.triangles.length; i += 3) {
                int ia = mesh.triangles[i] * 3;
                int ib = mesh.triangles[i + 1] * 3;
                int ic = mesh.triangles[i + 2] * 3;
                float ax = mesh.vertices[ia] + shiftX, ay = mesh.vertices[ia + 1] + shiftY, az = mesh.vertices[ia + 2] + shiftZ;
                float bx = mesh.vertices[ib] + shiftX, by = mesh.vertices[ib + 1] + shiftY, bz = mesh.vertices[ib + 2] + shiftZ;
                float cx = mesh.vertices[ic] + shiftX, cy = mesh.vertices[ic + 1] + shiftY, cz = mesh.vertices[ic + 2] + shiftZ;
                float ux = bx - ax, uy = by - ay, uz = bz - az;
                float vx = cx - ax, vy = cy - ay, vz = cz - az;
                float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length > 0f) { nx /= length; ny /= length; nz /= length; }
                out.write(String.format(Locale.US, "  facet normal %.7g %.7g %.7g", nx, ny, nz));
                out.newLine();
                out.write("    outer loop"); out.newLine();
                out.write(String.format(Locale.US, "      vertex %.7g %.7g %.7g", ax, ay, az)); out.newLine();
                out.write(String.format(Locale.US, "      vertex %.7g %.7g %.7g", bx, by, bz)); out.newLine();
                out.write(String.format(Locale.US, "      vertex %.7g %.7g %.7g", cx, cy, cz)); out.newLine();
                out.write("    endloop"); out.newLine();
                out.write("  endfacet"); out.newLine();
            }
            out.write("endsolid alloy");
            out.newLine();
        }
    }

    /**
     * Write the explicit, single-material FFF contract used by Alloy's native
     * job. Every value here is either a typed phone recipe field or a stable
     * machine-safe baseline; leaving a key to the native default would make a
     * slice depend on whichever SliceBeam preset registry happened to be
     * packaged into the APK.
     */
    static void writeConfig(Slicer.Config config, File target) throws IOException {
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        setting(settings, "printer_technology", "FFF");
        // The A1 Mini reference emits its reviewed machine envelope into the
        // artifact (M201/M203/M205). SliceBeam keeps the same limits for its
        // estimator, but only emits them when this option is explicit. Carry
        // the profile-backed envelope into the file as well so firmware does
        // not have to infer motion ceilings from an otherwise valid print.
        setting(settings, "machine_limits_usage", "emit_to_gcode");
        // Projected profile values are defaults/compatibility values. Add them
        // first so Alloy's typed recipe and owned G-code contract remain
        // authoritative if a profile contains an overlapping native key.
        for (Map.Entry<String, String> entry : config.nativeSettings.entrySet()) {
            // Direct in-memory callers may carry test or legacy keys, but
            // only the source-verified projection is allowed into native
            // config. Typed/owned fields are supplied authoritatively below.
            // Bambu calls this `brim_separation`; the current native core
            // registers the source-equivalent `brim_object_gap` spelling and
            // would otherwise ignore the imported value.
            if ("brim_separation".equals(entry.getKey())) continue;
            if (NativeSettings.isApprovedKey(entry.getKey()))
                setting(settings, entry.getKey(), entry.getValue());
        }
        // Bambu's resolved A1 Mini profile constrains the X/Y and travel
        // acceleration ceilings to default_acceleration even though the
        // inherited machine JSON advertises higher axis maxima. SliceBeam
        // otherwise consumes those raw maxima and predicts a faster job than
        // Bambu for the same resolved recipe. Preserve the source values for
        // audit/provenance, but pass the effective Bambu ceilings to native.
        String defaultAcceleration = config.nativeSettings.get("default_acceleration");
        if (defaultAcceleration != null && defaultAcceleration.trim().length() > 0
                && shouldApplyCompatibilityAcceleration(config, defaultAcceleration)) {
            String effectiveAcceleration = accelerationPair(defaultAcceleration);
            setting(settings, "machine_max_acceleration_x", effectiveAcceleration);
            setting(settings, "machine_max_acceleration_y", effectiveAcceleration);
            setting(settings, "machine_max_acceleration_travel", effectiveAcceleration);
        }
        // Keep the shipped profile deterministic while allowing an imported
        // Bambu profile to make an explicit, reviewed brim choice. The native
        // core contains Bambu's auto-brim implementation, but a missing enum
        // must not accidentally become the core's default outer-only brim.
        String explicitBrimType = config.nativeSettings.get("brim_type");
        if (explicitBrimType != null && explicitBrimType.trim().length() > 0) {
            String brimType = normalizedBrimType(explicitBrimType);
            setting(settings, "brim_type", brimType);
            // Prusa's classic generator reverses the first-layer perimeter
            // order whenever the object config still carries a positive brim
            // width, even when brim_type is no_brim. Zero the paired value so
            // an explicit no-brim choice remains internally consistent.
            if ("no_brim".equals(brimType)) setting(settings, "brim_width", "0");
        } else if (config.nativeSettings.containsKey("brim_width")) {
            setting(settings, "brim_type", "no_brim");
            setting(settings, "brim_width", "0");
        }
        String brimGap = config.nativeSettings.get("brim_object_gap");
        if (brimGap == null) brimGap = config.nativeSettings.get("brim_separation");
        // The compatibility no-brim route must not retain a source separation
        // that cannot affect geometry but would make the emitted profile look
        // different from the resolved Bambu artifact. Explicit imported brim
        // modes retain their requested gap.
        if (explicitBrimType == null || "no_brim".equalsIgnoreCase(explicitBrimType.trim()))
            brimGap = "0";
        if (brimGap != null && brimGap.trim().length() > 0)
            setting(settings, "brim_object_gap", brimGap);
        setting(settings, "bed_shape", String.format(Locale.US, "0x0,%.3fx0,%.3fx%.3f,0x%.3f", config.bedX, config.bedY, config.bedY, config.bedX));
        setting(settings, "max_print_height", number(config.bedZ));
        setting(settings, "nozzle_diameter", number(config.nozzle));
        setting(settings, "filament_diameter", number(config.filamentDiameter));
        setting(settings, "filament_type", config.filament);
        // Orca renamed the legacy Prusa machine_max_feedrate_* projection to
        // machine_max_speed_*. Translate the source-backed A1 values at the
        // boundary so the native writer emits the reviewed M203 envelope.
        for (String axis : new String[]{"x", "y", "z", "e"}) {
            String feedrate = config.nativeSettings.get("machine_max_feedrate_" + axis);
            if (feedrate != null && feedrate.trim().length() > 0)
                setting(settings, "machine_max_speed_" + axis, feedrate);
        }
        setting(settings, "layer_height", number(config.layerHeight));
        setting(settings, "first_layer_height", number(config.firstLayerHeight));
        // The pinned Orca config uses the newer names below for the same
        // typed recipe fields. Keep the legacy spelling above for stored
        // SliceBeam projects, but always project the authoritative value to
        // the keys consumed by the current engine.
        setting(settings, "initial_layer_print_height", number(config.firstLayerHeight));
        setting(settings, "fill_density", number(config.infill * 100f) + "%");
        setting(settings, "perimeters", Integer.toString(config.perimeters));
        setting(settings, "top_solid_layers", Integer.toString(config.topLayers));
        setting(settings, "bottom_solid_layers", Integer.toString(config.bottomLayers));
        setting(settings, "wall_loops", Integer.toString(config.perimeters));
        setting(settings, "top_shell_layers", Integer.toString(config.topLayers));
        setting(settings, "bottom_shell_layers", Integer.toString(config.bottomLayers));
        setting(settings, "sparse_infill_density", number(config.infill * 100f) + "%");
        setting(settings, "sparse_infill_pattern",
                currentSparseInfillPattern(config.nativeSettings.get("fill_pattern")));
        setting(settings, "infill_direction",
                config.nativeSettings.getOrDefault("fill_angle", "45"));
        setting(settings, "wall_generator",
                currentWallGenerator(config.nativeSettings.get("perimeter_generator")));
        // Project the source-facing compatibility names to the current
        // Orca config vocabulary as well. The importer intentionally stores
        // the reviewed legacy names, but the native core consumes the newer
        // Bambu/Orca spellings for these fields. Without this second
        // projection an imported project would look correct in the profile
        // record while silently using the core default during slicing.
        optionalSetting(settings, config.nativeSettings, "top_solid_min_thickness", "top_shell_thickness");
        optionalSetting(settings, config.nativeSettings, "bottom_solid_min_thickness", "bottom_shell_thickness");
        optionalSetting(settings, config.nativeSettings, "thin_walls", "detect_thin_wall");
        optionalSetting(settings, config.nativeSettings, "bridge_flow_ratio", "bridge_flow");
        optionalSetting(settings, config.nativeSettings, "dont_support_bridges", "bridge_no_support");
        optionalSetting(settings, config.nativeSettings, "enable_dynamic_overhang_speeds", "enable_overhang_speed");
        optionalSetting(settings, config.nativeSettings, "overhang_speed_0", "overhang_1_4_speed");
        optionalSetting(settings, config.nativeSettings, "overhang_speed_1", "overhang_2_4_speed");
        optionalSetting(settings, config.nativeSettings, "overhang_speed_2", "overhang_3_4_speed");
        optionalSetting(settings, config.nativeSettings, "overhang_speed_3", "overhang_4_4_speed");
        optionalSetting(settings, config.nativeSettings, "bridge_fan_speed", "overhang_fan_speed");
        optionalSetting(settings, config.nativeSettings, "retract_layer_change", "retract_when_changing_layer");
        setting(settings, "support_material", config.supports ? "1" : "0");
        setting(settings, "support_material_auto", config.supports ? "1" : "0");
        setting(settings, "support_material_threshold", number(config.supportThresholdDegrees));
        setting(settings, "support_material_angle", number(config.supportThresholdDegrees));
        // Current Orca consumes the newer support vocabulary. The legacy
        // support_material_* projection above is retained for older stored
        // recipes, but it is not sufficient to enable the current
        // TreeSupport3D route: support_material is no longer the enable key
        // and support_material_style is no longer the style key.
        setting(settings, "enable_support", config.supports ? "1" : "0");
        String requestedSupportStyle = config.nativeSettings.get("support_material_style");
        String normalizedSupportStyle = requestedSupportStyle == null
                ? "organic" : requestedSupportStyle.trim().toLowerCase(Locale.US);
        String currentSupportStyle;
        if ("grid".equals(normalizedSupportStyle) || "rectilinear".equals(normalizedSupportStyle)
                || "rectilinear-grid".equals(normalizedSupportStyle)) {
            currentSupportStyle = "grid";
        } else if ("snug".equals(normalizedSupportStyle)) {
            currentSupportStyle = "snug";
        } else if ("tree".equals(normalizedSupportStyle)) {
            currentSupportStyle = "tree_slim";
        } else if ("organic".equals(normalizedSupportStyle)) {
            currentSupportStyle = "organic";
        } else {
            currentSupportStyle = "default";
        }
        setting(settings, "support_type",
                config.supports && ("organic".equals(currentSupportStyle)
                        || currentSupportStyle.startsWith("tree_"))
                        ? "tree(auto)" : "normal(auto)");
        setting(settings, "support_style", currentSupportStyle);
        setting(settings, "support_threshold_angle", number(config.supportThresholdDegrees));
        setting(settings, "support_on_build_plate_only",
                config.nativeSettings.getOrDefault("support_material_buildplate_only", "0"));
        optionalSetting(settings, config.nativeSettings, "support_remove_small_overhang", "support_remove_small_overhang");
        setting(settings, "support_object_xy_distance",
                config.nativeSettings.getOrDefault("support_material_xy_spacing", "0.35"));
        setting(settings, "support_interface_speed",
                config.nativeSettings.getOrDefault("support_material_interface_speed", "80"));
        // Current Orca separates the legacy interface-layer and Z-gap names.
        // Emit both current values so the engine does not silently fall back
        // to its three-layer interface default when a pinned Bambu recipe
        // explicitly requests two layers.
        String supportInterfaceLayers = config.nativeSettings.getOrDefault("support_material_interface_layers", "2");
        String supportBottomInterfaceLayers = config.nativeSettings.getOrDefault(
                "support_material_bottom_interface_layers", supportInterfaceLayers);
        setting(settings, "support_interface_top_layers", supportInterfaceLayers);
        setting(settings, "support_interface_bottom_layers", supportBottomInterfaceLayers);
        setting(settings, "support_top_z_distance",
                config.nativeSettings.getOrDefault("support_material_contact_distance", number(Math.max(0.1f, config.layerHeight))));
        setting(settings, "support_bottom_z_distance",
                config.nativeSettings.getOrDefault("support_material_bottom_contact_distance", number(Math.max(0.1f, config.layerHeight))));
        // These Bambu support-policy values have the same registered native
        // meanings. Emit them explicitly so profile identity does not depend
        // on an implicit PrintConfig default.
        setting(settings, "support_object_first_layer_gap",
                config.nativeSettings.getOrDefault("support_object_first_layer_gap", "0.2"));
        setting(settings, "support_interface_not_for_body",
                config.nativeSettings.getOrDefault("support_interface_not_for_body", "1"));
        String supportBasePattern = config.nativeSettings.get("support_base_pattern");
        if (supportBasePattern == null) {
            supportBasePattern = config.nativeSettings.getOrDefault("support_material_pattern", "rectilinear");
            // The pinned Bambu A1 Mini profile resolves its legacy organic
            // support recipe to the current Orca `default` base pattern.
            // Leaving the old `rectilinear` spelling in this newer key makes
            // TreeSupport3D emit an additional base-infill route and changes
            // both support material and timing. Preserve an explicit current
            // key when a refreshed profile supplies one; otherwise apply the
            // source-backed organic translation only.
            if ("organic".equals(currentSupportStyle) && "rectilinear".equalsIgnoreCase(supportBasePattern))
                supportBasePattern = "default";
        }
        setting(settings, "support_base_pattern", supportBasePattern);
        setting(settings, "support_interface_pattern",
                config.nativeSettings.getOrDefault("support_material_interface_pattern", "auto"));
        String supportInterfaceSpacing = config.nativeSettings.getOrDefault(
                "support_interface_spacing", config.nativeSettings.getOrDefault(
                        "support_material_interface_spacing", number(Math.max(0.5f, config.nozzle))));
        String supportBottomInterfaceSpacing = config.nativeSettings.getOrDefault(
                "support_bottom_interface_spacing", supportInterfaceSpacing);
        setting(settings, "support_interface_spacing", supportInterfaceSpacing);
        setting(settings, "support_bottom_interface_spacing", supportBottomInterfaceSpacing);
        setting(settings, "support_base_pattern_spacing",
                config.nativeSettings.getOrDefault("support_material_spacing", "2.5"));
        setting(settings, "support_speed",
                config.nativeSettings.getOrDefault("support_material_speed", number(Math.min(config.infillSpeed, 80f))));
        if (config.nativeSettings.containsKey("support_tree_angle")) {
            String angle = config.nativeSettings.get("support_tree_angle");
            setting(settings, "tree_support_branch_angle_organic", angle);
        }
        if (config.nativeSettings.containsKey("support_tree_branch_distance")) {
            String distance = config.nativeSettings.get("support_tree_branch_distance");
            setting(settings, "tree_support_branch_distance_organic", distance);
        } else if (config.nativeSettings.containsKey("support_tree_branch_distance_organic")) {
            setting(settings, "tree_support_branch_distance_organic",
                    config.nativeSettings.get("support_tree_branch_distance_organic"));
        } else if ("organic".equals(currentSupportStyle)) {
            // Bambu's v02.08.02.61 A1 Mini TreeSupport3D profile resolves
            // the legacy tree branch distance to its 5 mm default. The
            // pinned Orca-Mobile core has a separate organic default of 1 mm;
            // leaving that value implicit creates a denser, materially
            // different support tree whenever the older profile projection
            // omits the newer organic-only key. Keep the Bambu-compatible
            // default explicit until the packaged profile is refreshed with
            // this source field and its provenance hash.
            setting(settings, "tree_support_branch_distance_organic", "5");
        }
        if (config.nativeSettings.containsKey("support_tree_branch_diameter")) {
            String diameter = config.nativeSettings.get("support_tree_branch_diameter");
            setting(settings, "tree_support_branch_diameter_organic", diameter);
        }
        if (config.nativeSettings.containsKey("support_tree_branch_diameter_angle")) {
            String diameterAngle = config.nativeSettings.get("support_tree_branch_diameter_angle");
            setting(settings, "tree_support_branch_diameter_angle", diameterAngle);
        }
        if (config.nativeSettings.containsKey("support_tree_branch_diameter_double_wall")) {
            String wallCount = config.nativeSettings.get("support_tree_branch_diameter_double_wall");
            setting(settings, "tree_support_wall_count", wallCount);
        }
        if (!hasApprovedNativeSetting(config, "support_material_contact_distance"))
            setting(settings, "support_material_contact_distance", number(Math.max(0.1f, config.layerHeight)));
        if (!hasApprovedNativeSetting(config, "support_material_bottom_contact_distance"))
            setting(settings, "support_material_bottom_contact_distance", number(Math.max(0.1f, config.layerHeight)));
        if (!hasApprovedNativeSetting(config, "support_material_interface_layers"))
            setting(settings, "support_material_interface_layers", "2");
        if (!hasApprovedNativeSetting(config, "support_material_interface_spacing"))
            setting(settings, "support_material_interface_spacing", number(Math.max(0.5f, config.nozzle)));
        if (!hasApprovedNativeSetting(config, "support_material_spacing"))
            setting(settings, "support_material_spacing", number(Math.max(1.0f, config.nozzle * 3f)));
        if (!hasApprovedNativeSetting(config, "support_material_speed"))
            setting(settings, "support_material_speed", number(Math.min(config.infillSpeed, 80f)));
        setting(settings, "travel_speed", number(config.travelSpeed));
        setting(settings, "external_perimeter_speed", number(config.outerWallSpeed));
        setting(settings, "perimeter_speed", number(config.innerWallSpeed));
        setting(settings, "infill_speed", number(config.infillSpeed));
        setting(settings, "outer_wall_speed", number(config.outerWallSpeed));
        setting(settings, "inner_wall_speed", number(config.innerWallSpeed));
        setting(settings, "sparse_infill_speed", number(config.infillSpeed));
        optionalSetting(settings, config.nativeSettings, "gap_fill_speed", "gap_infill_speed");
        // The current Orca schema no longer consumes the legacy `skirts` and
        // width/acceleration spellings. Project both vocabularies so a pinned
        // Bambu recipe does not silently regain a default skirt or fall back
        // to unrelated native defaults.
        setting(settings, "skirt_loops",
                config.nativeSettings.getOrDefault("skirts", "0"));
        String defaultLineWidth = config.nativeSettings.getOrDefault("extrusion_width", number(config.nozzle));
        setting(settings, "line_width", defaultLineWidth);
        setting(settings, "outer_wall_line_width",
                config.nativeSettings.getOrDefault("external_perimeter_extrusion_width", defaultLineWidth));
        setting(settings, "inner_wall_line_width",
                config.nativeSettings.getOrDefault("perimeter_extrusion_width", defaultLineWidth));
        setting(settings, "sparse_infill_line_width",
                config.nativeSettings.getOrDefault("infill_extrusion_width", defaultLineWidth));
        setting(settings, "internal_solid_infill_line_width",
                config.nativeSettings.getOrDefault("solid_infill_extrusion_width", defaultLineWidth));
        setting(settings, "top_surface_line_width",
                config.nativeSettings.getOrDefault("top_infill_extrusion_width", defaultLineWidth));
        setting(settings, "initial_layer_line_width",
                config.nativeSettings.getOrDefault("first_layer_extrusion_width", defaultLineWidth));
        setting(settings, "support_line_width",
                config.nativeSettings.getOrDefault("support_material_extrusion_width", defaultLineWidth));
        // These current Orca fields are optional in older pinned snapshots.
        // Emit them only when provenance carries the source value; omitting
        // them is safer than inventing a compatibility constant.
        optionalSetting(settings, config.nativeSettings, "initial_layer_infill_speed", "initial_layer_infill_speed");
        optionalSetting(settings, config.nativeSettings, "initial_layer_travel_acceleration", "initial_layer_travel_acceleration");
        optionalSetting(settings, config.nativeSettings, "initial_layer_acceleration", "first_layer_acceleration");
        optionalSetting(settings, config.nativeSettings, "initial_layer_acceleration", "initial_layer_acceleration");
        optionalSetting(settings, config.nativeSettings, "outer_wall_acceleration", "external_perimeter_acceleration");
        optionalSetting(settings, config.nativeSettings, "outer_wall_acceleration", "outer_wall_acceleration");
        optionalSetting(settings, config.nativeSettings, "inner_wall_acceleration", "perimeter_acceleration");
        optionalSetting(settings, config.nativeSettings, "inner_wall_acceleration", "inner_wall_acceleration");
        optionalSetting(settings, config.nativeSettings, "top_surface_acceleration", "top_solid_infill_acceleration");
        optionalSetting(settings, config.nativeSettings, "top_surface_acceleration", "top_surface_acceleration");
        optionalSetting(settings, config.nativeSettings, "infill_acceleration", "sparse_infill_acceleration");
        optionalSetting(settings, config.nativeSettings, "sparse_infill_acceleration", "sparse_infill_acceleration");
        optionalSetting(settings, config.nativeSettings, "internal_solid_infill_acceleration", "internal_solid_infill_acceleration");
        if (!hasApprovedNativeSetting(config, "solid_infill_speed"))
            setting(settings, "solid_infill_speed", number(config.innerWallSpeed));
        if (!hasApprovedNativeSetting(config, "top_solid_infill_speed"))
            setting(settings, "top_solid_infill_speed", number(config.outerWallSpeed));
        setting(settings, "internal_solid_infill_speed",
                config.nativeSettings.getOrDefault("solid_infill_speed", number(config.innerWallSpeed)));
        setting(settings, "top_surface_speed",
                config.nativeSettings.getOrDefault("top_solid_infill_speed", number(config.outerWallSpeed)));
        setting(settings, "first_layer_speed", number(config.initialLayerSpeed));
        setting(settings, "initial_layer_speed", number(config.initialLayerSpeed));
        setting(settings, "top_surface_pattern",
                currentSurfacePattern(config.nativeSettings.get("top_fill_pattern"), "monotonicline"));
        setting(settings, "bottom_surface_pattern",
                currentSurfacePattern(config.nativeSettings.get("bottom_fill_pattern"), "monotonic"));
        setting(settings, "ironing_flow",
                config.nativeSettings.getOrDefault("ironing_flowrate", "10%"));
        setting(settings, "spiral_mode",
                booleanValue(config.nativeSettings.getOrDefault("spiral_vase", "0")));
        // Orca separates the filament calibration multiplier from the
        // process/object and role-specific flow ratios. Keep the typed
        // filament value authoritative, then pass any imported per-role
        // overrides through only when the source profile supplied them.
        setting(settings, "filament_flow_ratio", number(config.extrusionMultiplier));
        setting(settings, "print_flow_ratio",
                config.nativeSettings.getOrDefault("print_flow_ratio", "1"));
        boolean hasRoleFlowOverrides = false;
        for (String flowKey : new String[]{"first_layer_flow_ratio", "outer_wall_flow_ratio",
                "inner_wall_flow_ratio", "overhang_flow_ratio", "sparse_infill_flow_ratio",
                "internal_solid_infill_flow_ratio", "gap_fill_flow_ratio",
                "top_solid_infill_flow_ratio", "bottom_solid_infill_flow_ratio"}) {
            if (config.nativeSettings.containsKey(flowKey)) {
                hasRoleFlowOverrides = true;
                optionalSetting(settings, config.nativeSettings, flowKey, flowKey);
            }
        }
        if (hasRoleFlowOverrides) setting(settings, "set_other_flow_ratios", "1");
        setting(settings, "extrusion_multiplier", number(config.extrusionMultiplier));
        // Bambu's resolved PLA profile carries the cap on the filament, not
        // on the global object. Preserve that distinction for SliceBeam while
        // keeping the typed value as a fallback for custom recipes.
        String filamentVolumetricCap = config.nativeSettings.get("filament_max_volumetric_speed");
        if (filamentVolumetricCap != null) {
            setting(settings, "filament_max_volumetric_speed", filamentVolumetricCap);
            setting(settings, "max_volumetric_speed", "0");
        } else {
            setting(settings, "max_volumetric_speed", number(config.maxVolumetricSpeed));
        }
        // Bambu's A1 output can explicitly select either legacy `marlin` or
        // the newer `marlin2` vocabulary. Keep the reviewed mobile default on
        // `marlin2`, but honor a source-backed project override so a parity
        // fixture can reproduce the exact desktop flavor without changing
        // ordinary phone jobs. Only the two registered engine values are
        // accepted; arbitrary slicer command text never crosses this boundary.
        String requestedGcodeFlavor = config.nativeSettings.get("gcode_flavor");
        if (!"marlin".equalsIgnoreCase(requestedGcodeFlavor)
                && !"marlin2".equalsIgnoreCase(requestedGcodeFlavor)) {
            requestedGcodeFlavor = "marlin2";
        }
        setting(settings, "gcode_flavor", requestedGcodeFlavor.toLowerCase(Locale.US));
        // Preserve an explicit source/profile choice for arc fitting. The
        // default remains linear until a profile opts in; the phone preview
        // discretizes G2/G3 moves into bounded display segments without
        // changing the native artifact that will be exported.
        if (!hasApprovedNativeSetting(config, "arc_fitting"))
            setting(settings, "arc_fitting", "disabled");
        // The current Orca engine consumes a boolean enable_arc_fitting key;
        // keep Alloy's source-facing arc_fitting value as the compatibility
        // projection and translate it explicitly for the native writer.
        String arcFitting = config.nativeSettings.get("arc_fitting");
        setting(settings, "enable_arc_fitting",
                "emit_center".equalsIgnoreCase(arcFitting == null ? "" : arcFitting.trim()) ? "1" : "0");
        setting(settings, "gcode_comments", "1");
        // The native exporter dereferences this option before checking
        // whether binary output is enabled; define it explicitly for the
        // standalone mobile recipe instead of relying on preset defaults.
        setting(settings, "binary_gcode", "0");
        setting(settings, "use_relative_e_distances", "1");
        setting(settings, "cooling", "1");
        // Current Orca stores temperatures as integer vectors, split between
        // nozzle and the selected plate. Emit both the current fields and
        // the compatibility fields used by older Alloy project snapshots.
        setting(settings, "temperature", number(config.nozzleTemperature));
        setting(settings, "first_layer_temperature", integerNumber(config.firstLayerNozzleTemperature));
        setting(settings, "nozzle_temperature", integerNumber(config.nozzleTemperature));
        setting(settings, "nozzle_temperature_initial_layer", integerNumber(config.firstLayerNozzleTemperature));
        // Bambu stores temperatures for several plate materials in the same
        // resolved process. The active plate is selected by curr_bed_type;
        // collapsing that choice to hot_plate_temp can make an imported phone
        // job heat the wrong surface. Keep the typed values as the fallback
        // for older Alloy projects that do not carry plate metadata.
        setting(settings, "bed_temperature", selectedBedTemperature(config, false));
        setting(settings, "first_layer_bed_temperature", selectedBedTemperature(config, true));
        setting(settings, "cool_plate_temp", plateTemperature(config, "cool_plate_temp", config.bedTemperature, false));
        setting(settings, "cool_plate_temp_initial_layer", plateTemperature(config, "cool_plate_temp_initial_layer", config.firstLayerBedTemperature, true));
        setting(settings, "min_fan_speed", number(config.fanMinPercent));
        setting(settings, "max_fan_speed", number(config.fanMaxPercent));
        String fanCoolingTime = config.nativeSettings.getOrDefault("fan_below_layer_time", "60");
        String slowDownTime = config.nativeSettings.getOrDefault("slowdown_below_layer_time", "8");
        String slowDownMinimumSpeed = config.nativeSettings.getOrDefault("min_print_speed", "10");
        setting(settings, "fan_below_layer_time", fanCoolingTime);
        setting(settings, "fan_cooling_layer_time", fanCoolingTime);
        setting(settings, "slowdown_below_layer_time", slowDownTime);
        setting(settings, "slow_down_layer_time", slowDownTime);
        setting(settings, "min_print_speed", slowDownMinimumSpeed);
        setting(settings, "slow_down_min_speed", slowDownMinimumSpeed);
        // Orca/Prusa uses machine_start_gcode/machine_end_gcode for these
        // hooks. The older SliceBeam names start_gcode/end_gcode are kept in
        // the helper method names for compatibility with the Java seam, but
        // emitting those legacy keys would leave the reviewed template out
        // of the native artifact.
        setting(settings, "machine_start_gcode", startGcode(config));
        setting(settings, "machine_end_gcode", endGcode(config));
        // SliceBeam requires an explicit E reset when relative extrusion is enabled.
        // Keep this per-layer so long jobs cannot accumulate floating-point drift.
        // Current Orca/Prusa libslic3r names this hook layer_change_gcode;
        // older SliceBeam snapshots called the equivalent hook layer_gcode.
        // Keep the legacy spelling in this comment for source-audit
        // compatibility, but emit only the reset body: the current writer
        // owns the LAYER_CHANGE/LAYER markers. Re-emitting those markers here
        // doubles the reported layer count and corrupts Bambu's header.
        setting(settings, "layer_change_gcode", "G92 E0");

        try (BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8))) {
            for (Map.Entry<String, String> entry : settings.entrySet())
                line(out, entry.getKey(), entry.getValue());
        }
    }

    private static String normalizedBrimType(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.US);
        if ("no_brim".equals(value) || "outer_only".equals(value)
                || "inner_only".equals(value) || "outer_and_inner".equals(value)
                || "auto_brim".equals(value) || "brim_ears".equals(value)
                || "painted".equals(value)) return value;
        throw new IllegalArgumentException("Unsupported brim_type: " + raw);
    }

    private static String selectedBedTemperature(Slicer.Config config, boolean initialLayer) {
        String bedType = config.nativeSettings.get("curr_bed_type");
        String key = plateKey(bedType, initialLayer);
        if (key != null && config.nativeSettings.containsKey(key))
            return integerNumber(parseTemperature(config.nativeSettings.get(key),
                    initialLayer ? config.firstLayerBedTemperature : config.bedTemperature));
        return integerNumber(initialLayer ? config.firstLayerBedTemperature : config.bedTemperature);
    }

    private static String plateTemperature(Slicer.Config config, String key, float fallback, boolean initialLayer) {
        String value = config.nativeSettings.get(key);
        if (value == null || value.trim().isEmpty()) return integerNumber(fallback);
        return integerNumber(parseTemperature(value, fallback));
    }

    private static String plateKey(String bedType, boolean initialLayer) {
        if (bedType == null) return null;
        String normalized = bedType.trim().toLowerCase(Locale.US);
        String suffix = initialLayer ? "_initial_layer" : "";
        if (normalized.contains("cool")) return "cool_plate_temp" + suffix;
        if (normalized.contains("engineering") || normalized.startsWith("eng")) return "eng_plate_temp" + suffix;
        if (normalized.contains("supertack") || normalized.contains("super tack")) return "supertack_plate_temp" + suffix;
        if (normalized.contains("textured")) return "textured_plate_temp" + suffix;
        if (normalized.contains("hot")) return "hot_plate_temp" + suffix;
        return null;
    }

    private static float parseTemperature(String raw, float fallback) {
        if (raw == null) return fallback;
        String value = raw.trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma).trim();
        try {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) && parsed >= 0f && parsed <= 150f ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** Keep the native artifact self-contained and printable on Marlin-like firmware. */
    private static String startGcode(Slicer.Config config) {
        float defaultAcceleration = parseFirstNumber(config.nativeSettings.get("default_acceleration"));
        String acceleration = Float.isFinite(defaultAcceleration) && defaultAcceleration > 0f
                ? String.format(Locale.US, "M204 S%.0f\\n", defaultAcceleration) : "";
        return String.format(Locale.US,
                "; Alloy native start · %s\\n; Alloy template policy: %s\\n%sM140 S%.0f\\nM104 S%.0f\\nM190 S%.0f\\nM109 S%.0f\\nG28\\nG90\\nM83\\nG92 E0\\nM107",
                config.printer, A1MiniTemplatePolicy.ID, acceleration, config.firstLayerBedTemperature, config.nozzleTemperature,
                config.firstLayerBedTemperature, config.firstLayerNozzleTemperature);
    }

    private static String endGcode(Slicer.Config config) {
        return "; Alloy native end\\nG91\\nG1 Z2 F600\\nG90\\nG1 X0 Y0 F9000\\nM104 S0\\nM140 S0\\nM107\\nM84";
    }

    private static void setting(LinkedHashMap<String, String> settings, String key, String value) {
        settings.put(key, value == null ? "" : value);
    }

    private static void optionalSetting(LinkedHashMap<String, String> settings,
                                        Map<String, String> source, String sourceKey, String nativeKey) {
        String value = source.get(sourceKey);
        if (value != null && value.trim().length() > 0) setting(settings, nativeKey, value);
    }

    private static String currentSparseInfillPattern(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("zig-zag".equals(normalized)) normalized = "zigzag";
        if ("monotoniclines".equals(normalized) || "monotonic".equals(normalized)) normalized = "grid";
        if ("rectilinear".equals(normalized) || "alignedrectilinear".equals(normalized)
                || "zigzag".equals(normalized) || "crosszag".equals(normalized)
                || "lockedzag".equals(normalized) || "line".equals(normalized)
                || "grid".equals(normalized) || "triangles".equals(normalized)
                || "tri-hexagon".equals(normalized) || "cubic".equals(normalized)
                || "adaptivecubic".equals(normalized) || "quartercubic".equals(normalized)
                || "supportcubic".equals(normalized) || "lightning".equals(normalized)
                || "honeycomb".equals(normalized)) return normalized;
        return "grid";
    }

    private static String currentSurfacePattern(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if ("monotoniclines".equals(normalized)) normalized = "monotonicline";
        if ("zig-zag".equals(normalized)) normalized = "rectilinear";
        if ("monotonic".equals(normalized) || "monotonicline".equals(normalized)
                || "rectilinear".equals(normalized) || "alignedrectilinear".equals(normalized)
                || "concentric".equals(normalized) || "hilbertcurve".equals(normalized)
                || "archimedeanchords".equals(normalized) || "octagramspiral".equals(normalized))
            return normalized;
        return fallback;
    }

    private static String currentWallGenerator(String value) {
        return "arachne".equalsIgnoreCase(value == null ? "" : value.trim()) ? "arachne" : "classic";
    }

    private static String booleanValue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value) ? "1" : "0";
    }

    private static boolean hasApprovedNativeSetting(Slicer.Config config, String key) {
        return config.nativeSettings.containsKey(key) && NativeSettings.isApprovedKey(key);
    }

    private static void line(BufferedWriter out, String key, String value) throws IOException {
        out.write(key); out.write(" = "); out.write(value == null ? "" : value.replace("\n", "\\n")); out.newLine();
    }

    private static String number(float value) { return String.format(Locale.US, "%.5f", value); }

    private static String integerNumber(float value) { return String.format(Locale.US, "%.0f", value); }

    private static String accelerationPair(String value) {
        String first = value.trim();
        int comma = first.indexOf(',');
        if (comma >= 0) first = first.substring(0, comma).trim();
        if (first.length() == 0) return value.trim();
        return first + "," + first;
    }

    /**
     * The stock Orca compatibility profile advertises larger inherited axis
     * maxima than Bambu's effective default acceleration, so the historical
     * adapter collapses those values to the default. A resolved Bambu
     * project, however, may explicitly lower one or more axes (and may use
     * asymmetric travel limits). Preserve that project choice instead of
     * silently replacing it with the stock compatibility pair.
     */
    private static boolean shouldApplyCompatibilityAcceleration(Slicer.Config config,
                                                                 String defaultAcceleration) {
        float defaultValue = parseFirstNumber(defaultAcceleration);
        if (!Float.isFinite(defaultValue) || defaultValue <= 0f) return false;
        String x = config.nativeSettings.get("machine_max_acceleration_x");
        String y = config.nativeSettings.get("machine_max_acceleration_y");
        String travel = config.nativeSettings.get("machine_max_acceleration_travel");
        // An explicit effective project limit is represented by a lower axis
        // ceiling or by asymmetric travel values. The shipped inherited
        // profile uses equal values above default_acceleration and therefore
        // still takes the compatibility route.
        if (hasLowerFirstValue(x, defaultValue) || hasLowerFirstValue(y, defaultValue)) return false;
        if (travel != null && !samePair(travel)) return false;
        return !hasLowerFirstValue(travel, defaultValue);
    }

    private static boolean hasLowerFirstValue(String raw, float threshold) {
        float value = parseFirstNumber(raw);
        return Float.isFinite(value) && value > 0f && value < threshold;
    }

    private static float parseFirstNumber(String raw) {
        if (raw == null) return Float.NaN;
        String value = raw.trim();
        int comma = value.indexOf(',');
        if (comma >= 0) value = value.substring(0, comma).trim();
        try { return Float.parseFloat(value); }
        catch (NumberFormatException ignored) { return Float.NaN; }
    }

    private static boolean samePair(String raw) {
        if (raw == null) return true;
        String value = raw.trim();
        int comma = value.indexOf(',');
        if (comma < 0) return true;
        String left = value.substring(0, comma).trim();
        String right = value.substring(comma + 1).trim();
        return left.equals(right);
    }

    // Package-visible for the Android parser regression test. This method is
    // independent of JNI so malformed or unusual native output can be tested
    // without loading the full slicer library.
    static Slicer.Result parseGcode(File file, Slicer.Config config) throws IOException {
        // Keep the legacy parser default for fixture/backward-compatibility
        // callers; a live native slice is explicitly identified as Orca below.
        return parseGcode(file, config, LEGACY_NATIVE_ENGINE_ID, false);
    }

    /** Rehydrate a service result while preserving the engine identity captured at slice time. */
    static Slicer.Result parseGcode(File file, Slicer.Config config, String resultEngineId,
                                    boolean resultEngineVerified) throws IOException {
        if (file == null || !file.isFile() || file.length() <= 0L)
            throw new IOException("Native G-code file is missing or empty");
        if (file.length() > MAX_GCODE_BYTES)
            throw new IOException("Native G-code exceeds the 128 MB limit");
        StringBuilder gcode = new StringBuilder();
        ArrayList<Slicer.Layer> layers = new ArrayList<>();
        Slicer.Layer current = new Slicer.Layer(0, config.layerHeight);
        layers.add(current);
        int layerIndex = 0;
        float x = 0f, y = 0f, e = 0f, pendingZ = config.layerHeight;
        boolean absoluteXYZ = true, absoluteE = true, sawMotion = false, sawLayerMarker = false;
        float computedFilament = 0f, reportedFilament = -1f, travelMm = 0f, reportedTimeSeconds = -1f;
        int warnings = 0;
        PreviewState preview = new PreviewState();
        int toolpathRole = TOOLPATH_OTHER;
        try (BoundedLineReader in = new BoundedLineReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            long bytes = 0L;
            while ((line = in.readLine()) != null) {
                bytes += line.length() + 1L;
                if (bytes > MAX_GCODE_BYTES) throw new IOException("Native G-code exceeds the 128 MB limit");
                gcode.append(line).append('\n');
                String trimmed = line.trim();
                String upper = trimmed.toUpperCase(Locale.US);
                if (upper.contains("WARNING")) warnings++;
                if (trimmed.startsWith(";")) {
                    int declaredRole = toolpathRole(upper);
                    if (declaredRole >= 0) toolpathRole = declaredRole;
                }
                Matcher filamentMatch = FILAMENT_COMMENT.matcher(trimmed);
                if (filamentMatch.find()) reportedFilament = finiteFloat(filamentMatch.group(1), reportedFilament);
                Matcher timeMatch = TIME_COMMENT.matcher(trimmed);
                if (timeMatch.find()) {
                    float hours = finiteFloat(timeMatch.group(1), 0f);
                    float minutes = finiteFloat(timeMatch.group(2), 0f);
                    float seconds = finiteFloat(timeMatch.group(3), 0f);
                    reportedTimeSeconds = hours * 3600f + minutes * 60f + seconds;
                }
                Matcher zMatch = Z_COMMENT.matcher(trimmed);
                if (zMatch.find()) pendingZ = finiteFloat(zMatch.group(1), pendingZ);

                Matcher layerMatch = LAYER_NUMBER.matcher(trimmed);
                if (layerMatch.find()) {
                    int requested = parseInt(layerMatch.group(1), layerIndex);
                    if (requested >= 0) {
                        if (requested >= MAX_PREVIEW_LAYERS) throw new IOException("Native G-code contains too many layers");
                        layerIndex = requested;
                        while (layers.size() <= layerIndex) layers.add(new Slicer.Layer(layers.size(), pendingZ));
                        current = layers.get(layerIndex);
                        sawLayerMarker = true;
                    }
                    continue;
                }
                if (upper.contains("LAYER_CHANGE") && !sawLayerMarker) {
                    sawLayerMarker = true;
                    if (current.segments.isEmpty()) {
                        current = new Slicer.Layer(0, pendingZ);
                        layers.set(0, current);
                    }
                    sawMotion = false;
                    continue;
                }
                if (upper.contains("LAYER_CHANGE") && (sawMotion || !current.segments.isEmpty())) {
                    layerIndex++;
                    if (layerIndex >= MAX_PREVIEW_LAYERS) throw new IOException("Native G-code contains too many layers");
                    current = new Slicer.Layer(layerIndex, pendingZ);
                    layers.add(current);
                    sawMotion = false;
                    continue;
                }
                if (trimmed.startsWith(";")) continue;

                String command = trimmed;
                int comment = command.indexOf(';');
                if (comment >= 0) command = command.substring(0, comment).trim();
                if (command.length() == 0) continue;
                if (upper.startsWith("G90")) { absoluteXYZ = true; continue; }
                if (upper.startsWith("G91")) { absoluteXYZ = false; continue; }
                if (upper.startsWith("M82")) { absoluteE = true; continue; }
                if (upper.startsWith("M83")) { absoluteE = false; continue; }
                if (upper.startsWith("G92")) {
                    float reset = axisValue(command, 'E', Float.NaN);
                    if (finite(reset)) e = reset;
                    continue;
                }
                String commandCode = command.split("\\s+", 2)[0].toUpperCase(Locale.US);
                boolean arcMove = "G2".equals(commandCode) || "G3".equals(commandCode);
                if (!("G0".equals(commandCode) || "G1".equals(commandCode) || arcMove)) continue;
                float oldX = x, oldY = y, oldE = e;
                float nextX = axisValue(command, 'X', Float.NaN);
                float nextY = axisValue(command, 'Y', Float.NaN);
                float nextE = axisValue(command, 'E', Float.NaN);
                if (finite(nextX)) x = absoluteXYZ ? nextX : x + nextX;
                if (finite(nextY)) y = absoluteXYZ ? nextY : y + nextY;
                float extrusion = 0f;
                if (finite(nextE)) {
                    e = absoluteE ? nextE : e + nextE;
                    extrusion = absoluteE ? e - oldE : nextE;
                }
                float pathLength;
                if (arcMove) {
                    // Preview geometry represents deposited filament, not
                    // the machine's non-printing repositioning moves. Keep
                    // travel accounting below, but only materialise the arc
                    // after the extrusion sign is known; this preserves the
                    // same Layer.segments contract as the linear parser.
                    pathLength = arcLength(oldX, oldY, x, y, command, "G2".equals(commandCode));
                    if (extrusion > 0.0001f) {
                        float previewLength = appendArcPreview(preview, current, toolpathRole,
                                oldX, oldY, x, y, command, "G2".equals(commandCode));
                        if (previewLength >= 0f) pathLength = previewLength;
                        else if (pathLength > 0.0001f)
                            preview.add(current, toolpathRole, oldX, oldY, x, y);
                    }
                } else {
                    pathLength = (float) Math.hypot(x - oldX, y - oldY);
                    if (extrusion > 0.0001f && pathLength > 0.0001f)
                        preview.add(current, toolpathRole, oldX, oldY, x, y);
                }
                if (pathLength > 0.0001f) {
                    if (extrusion > 0.0001f) computedFilament += extrusion;
                    else if (extrusion >= -0.0001f) travelMm += pathLength;
                }
                sawMotion = true;
            }
        }
        if (gcode.length() == 0) throw new IOException("Native G-code is empty");
        GcodeSafetyValidator.requireSafe(gcode.toString());
        if (preview.truncated) warnings++;
        if (reportedFilament < 0f) reportedFilament = computedFilament;
        if (!finite(reportedFilament) || reportedFilament < 0f) reportedFilament = 0f;
        if (!finite(reportedTimeSeconds) || reportedTimeSeconds < 0f) reportedTimeSeconds = -1f;
        return new Slicer.Result(gcode.toString(), layers, reportedFilament, warnings,
                resultEngineId == null ? "unknown-engine" : resultEngineId, resultEngineVerified,
                reportedTimeSeconds, travelMm);
    }

    static boolean isNativeEngineId(String engineId) {
        return ORCA_NATIVE_ENGINE_ID.equals(engineId) || LEGACY_NATIVE_ENGINE_ID.equals(engineId);
    }

    /**
     * Prusa/SliceBeam emits ;TYPE comments before each feature class. Unknown
     * feature names remain neutral instead of being mislabeled in the preview.
     */
    private static int toolpathRole(String upperComment) {
        if (upperComment == null || !upperComment.startsWith(";TYPE:")) return -1;
        if (upperComment.contains("SUPPORT")) return TOOLPATH_SUPPORT;
        if (upperComment.contains("PERIMETER") || upperComment.contains("WALL")) return TOOLPATH_PERIMETER;
        return TOOLPATH_OTHER;
    }

    /** Convert a native G2/G3 center-format move into bounded GLES preview segments. */
    private static float appendArcPreview(PreviewState preview, Slicer.Layer layer, int role,
                                          float startX, float startY, float endX, float endY,
                                          String command, boolean clockwise) {
        float i = axisValue(command, 'I', Float.NaN);
        float j = axisValue(command, 'J', Float.NaN);
        if (!finite(i) || !finite(j)) return -1f;
        float centerX = startX + i;
        float centerY = startY + j;
        float radius = (float) Math.hypot(startX - centerX, startY - centerY);
        if (!finite(radius) || radius <= 0.0001f) return -1f;
        float endRadius = (float) Math.hypot(endX - centerX, endY - centerY);
        if (!finite(endRadius) || endRadius <= 0.0001f) return -1f;

        float startAngle = (float) Math.atan2(startY - centerY, startX - centerX);
        float endAngle = (float) Math.atan2(endY - centerY, endX - centerX);
        float delta = endAngle - startAngle;
        final float twoPi = (float) (Math.PI * 2d);
        if (Math.abs(endX - startX) <= 0.0001f && Math.abs(endY - startY) <= 0.0001f) {
            delta = clockwise ? -twoPi : twoPi;
        } else if (clockwise) {
            while (delta >= 0f) delta -= twoPi;
        } else {
            while (delta <= 0f) delta += twoPi;
        }
        float arcLength = Math.abs(delta) * radius;
        if (!finite(arcLength) || arcLength <= 0.0001f) return -1f;
        int count = Math.max(1, Math.min(8192, (int) Math.ceil(arcLength / 2f)));
        float previousX = startX;
        float previousY = startY;
        float radiusForPoint = (radius + endRadius) * 0.5f;
        for (int step = 1; step <= count; step++) {
            float fraction = step / (float) count;
            float angle = startAngle + delta * fraction;
            float pointX = centerX + (float) Math.cos(angle) * radiusForPoint;
            float pointY = centerY + (float) Math.sin(angle) * radiusForPoint;
            if (step == count) {
                pointX = endX;
                pointY = endY;
            }
            preview.add(layer, role, previousX, previousY, pointX, pointY);
            previousX = pointX;
            previousY = pointY;
        }
        return arcLength;
    }

    /** Estimate a center-format arc length for travel accounting on retractions. */
    private static float arcLength(float startX, float startY, float endX, float endY,
                                   String command, boolean clockwise) {
        float i = axisValue(command, 'I', Float.NaN);
        float j = axisValue(command, 'J', Float.NaN);
        if (!finite(i) || !finite(j)) return (float) Math.hypot(endX - startX, endY - startY);
        float centerX = startX + i;
        float centerY = startY + j;
        float radius = (float) Math.hypot(startX - centerX, startY - centerY);
        float endRadius = (float) Math.hypot(endX - centerX, endY - centerY);
        if (!finite(radius) || !finite(endRadius) || radius <= 0.0001f || endRadius <= 0.0001f)
            return (float) Math.hypot(endX - startX, endY - startY);
        float startAngle = (float) Math.atan2(startY - centerY, startX - centerX);
        float endAngle = (float) Math.atan2(endY - centerY, endX - centerX);
        float delta = endAngle - startAngle;
        final float twoPi = (float) (Math.PI * 2d);
        if (Math.abs(endX - startX) <= 0.0001f && Math.abs(endY - startY) <= 0.0001f) {
            delta = clockwise ? -twoPi : twoPi;
        } else if (clockwise) {
            while (delta >= 0f) delta -= twoPi;
        } else {
            while (delta <= 0f) delta += twoPi;
        }
        float length = Math.abs(delta) * (radius + endRadius) * 0.5f;
        return finite(length) ? length : (float) Math.hypot(endX - startX, endY - startY);
    }

    private static final class PreviewState {
        int count;
        boolean truncated;

        void add(Slicer.Layer layer, int role, float startX, float startY, float endX, float endY) {
            if (layer == null || !finite(startX) || !finite(startY) || !finite(endX) || !finite(endY)) return;
            if (count >= MAX_PREVIEW_SEGMENTS) {
                truncated = true;
                return;
            }
            Slicer.Segment segment = new Slicer.Segment(
                    new Slicer.Point(startX, startY), new Slicer.Point(endX, endY));
            layer.segments.add(segment);
            if (role == TOOLPATH_PERIMETER) layer.perimeterSegments.add(segment);
            else if (role == TOOLPATH_SUPPORT) layer.supportSegments.add(segment);
            count++;
        }
    }

    private static float axisValue(String command, char axis, float fallback) {
        Matcher matcher = AXIS.matcher(command);
        while (matcher.find()) if (Character.toUpperCase(matcher.group(1).charAt(0)) == axis) return finiteFloat(matcher.group(2), fallback);
        return fallback;
    }

    private static float finiteFloat(String value, float fallback) {
        try {
            float parsed = Float.parseFloat(value);
            return finite(parsed) ? parsed : fallback;
        } catch (Exception ignored) { return fallback; }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (Exception ignored) { return fallback; }
    }

    private static void validate(MeshModel mesh, Slicer.Config config) {
        if (mesh == null || mesh.vertices.length == 0 || mesh.triangles.length == 0) throw new IllegalArgumentException("No model selected");
        if (config == null) throw new IllegalArgumentException("No print recipe selected");
        if (!finite(config.layerHeight) || config.layerHeight <= 0f || config.layerHeight > 2f) throw new IllegalArgumentException("Layer height is invalid");
        if (!finite(config.firstLayerHeight) || config.firstLayerHeight <= 0f || config.firstLayerHeight > 2f) throw new IllegalArgumentException("First-layer height is invalid");
        if (!finite(config.nozzle) || config.nozzle <= 0f || config.nozzle > 2f) throw new IllegalArgumentException("Nozzle diameter is invalid");
        if (!finite(config.filamentDiameter) || config.filamentDiameter < 1f || config.filamentDiameter > 4f) throw new IllegalArgumentException("Filament diameter is invalid");
        if (!finite(config.infill) || config.infill < 0f || config.infill > 1f) throw new IllegalArgumentException("Infill is invalid");
        if (!finite(config.nozzleTemperature) || config.nozzleTemperature < 0f || config.nozzleTemperature > 400f
                || !finite(config.firstLayerNozzleTemperature) || config.firstLayerNozzleTemperature < 0f || config.firstLayerNozzleTemperature > 400f
                || !finite(config.bedTemperature) || config.bedTemperature < 0f || config.bedTemperature > 150f
                || !finite(config.firstLayerBedTemperature) || config.firstLayerBedTemperature < 0f || config.firstLayerBedTemperature > 150f)
            throw new IllegalArgumentException("Temperature values are invalid");
        if (!finite(config.extrusionMultiplier) || config.extrusionMultiplier <= 0f || config.extrusionMultiplier > 2f
                || !finite(config.maxVolumetricSpeed) || config.maxVolumetricSpeed <= 0f || config.maxVolumetricSpeed > 200f
                || !finite(config.travelSpeed) || config.travelSpeed <= 0f || config.travelSpeed > 2_000f
                || !finite(config.outerWallSpeed) || config.outerWallSpeed <= 0f || config.outerWallSpeed > 1_000f
                || !finite(config.innerWallSpeed) || config.innerWallSpeed <= 0f || config.innerWallSpeed > 1_000f
                || !finite(config.infillSpeed) || config.infillSpeed <= 0f || config.infillSpeed > 1_000f
                || !finite(config.initialLayerSpeed) || config.initialLayerSpeed <= 0f || config.initialLayerSpeed > 1_000f
                || !finite(config.fanMinPercent) || config.fanMinPercent < 0f || config.fanMinPercent > 100f
                || !finite(config.fanMaxPercent) || config.fanMaxPercent < 0f || config.fanMaxPercent > 100f
                || config.fanMinPercent > config.fanMaxPercent)
            throw new IllegalArgumentException("Motion, extrusion or cooling values are invalid");
        if (!finite(config.supportThresholdDegrees) || config.supportThresholdDegrees < 0f || config.supportThresholdDegrees > 90f) throw new IllegalArgumentException("Support threshold is invalid");
        if (config.perimeters < 1 || config.perimeters > 20 || config.topLayers < 0 || config.topLayers > 100 || config.bottomLayers < 0 || config.bottomLayers > 100) throw new IllegalArgumentException("Wall or solid-layer count is invalid");
        if (!finite(config.bedX) || !finite(config.bedY) || !finite(config.bedZ) || config.bedX <= 0f || config.bedY <= 0f || config.bedZ <= 0f) throw new IllegalArgumentException("Build volume is invalid");
        if (config.printer == null || config.printer.trim().length() == 0
                || config.filament == null || config.filament.trim().length() == 0)
            throw new IllegalArgumentException("Printer and filament names are required");
        if (mesh.maxX - mesh.minX > config.bedX || mesh.maxY - mesh.minY > config.bedY || mesh.maxZ - mesh.minZ > config.bedZ)
            throw new IllegalArgumentException(String.format(Locale.US,
                    "Model is %.1f × %.1f × %.1f mm; it does not fit the configured build volume",
                    mesh.maxX - mesh.minX, mesh.maxY - mesh.minY, mesh.maxZ - mesh.minZ));
    }

    private static String safeBaseName(String name) {
        String safe = name == null ? "model" : name.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.length() == 0 ? "model" : safe;
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }

    private static void deleteTree(File directory) {
        if (directory == null || PrinterTransport.isSymbolicLink(directory)) {
            if (directory != null && PrinterTransport.isSymbolicLink(directory)) directory.delete();
            return;
        }
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) {
            if (PrinterTransport.isSymbolicLink(child)) child.delete();
            else if (child.isDirectory()) deleteTree(child);
            else child.delete();
        }
        directory.delete();
    }

    /** Read native output without allowing one malformed line to allocate the whole file. */
    private static final class BoundedLineReader implements java.io.Closeable {
        private final java.io.Reader reader;
        private final char[] buffer = new char[32 * 1024];
        private int offset;
        private int length;

        BoundedLineReader(java.io.Reader reader) {
            this.reader = reader;
        }

        String readLine() throws IOException {
            StringBuilder line = new StringBuilder(256);
            boolean sawCharacter = false;
            while (true) {
                int value = nextChar();
                if (value < 0) return sawCharacter ? line.toString() : null;
                sawCharacter = true;
                if (value == '\n') return line.toString();
                if (value == '\r') continue;
                if (line.length() >= MAX_GCODE_LINE_CHARS)
                    throw new IOException("Native G-code line exceeds the 1 MB limit");
                line.append((char) value);
            }
        }

        private int nextChar() throws IOException {
            if (offset >= length) {
                length = reader.read(buffer);
                offset = 0;
                if (length < 0) return -1;
                if (length == 0) return nextChar();
            }
            return buffer[offset++];
        }

        @Override public void close() throws IOException { reader.close(); }
    }
}
