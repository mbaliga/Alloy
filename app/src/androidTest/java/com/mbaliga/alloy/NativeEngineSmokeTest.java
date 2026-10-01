package com.mbaliga.alloy;

import android.content.Context;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import ru.ytkab0bp.slicebeam.slic3r.Native;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** End-to-end on-device proof for the optional arm64 native slice path. */
@RunWith(AndroidJUnit4.class)
public final class NativeEngineSmokeTest {
    @Test
    public void slicesBundledCubeAndStagesValidatedPackage() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);

        assertSlicesAndStages(context, config, "box-20mm.stl", "native-smoke-box");
    }

    @Test
    public void slicesBundledAssemblyAndWorkshopPart() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);

        assertSlicesAndStages(context, config, "box-and-lid.stl", "native-smoke-box-and-lid");
        assertSlicesAndStages(context, config, "mounting-block.stl", "native-smoke-mounting-block");

        Slicer.Config supportConfig = config.copy();
        supportConfig.supports = true;
        Slicer.Result supportResult = assertSlicesAndStages(context, supportConfig,
                "overhang-support-fixture.stl", "native-smoke-overhang");
        Assert.assertTrue("support-enabled native output must contain support toolpaths",
                supportResult.gcode.contains(";TYPE:Support"));
        assertSlicesAndStages(context, config, "thin-wall-frame-fixture.stl", "native-smoke-thin-wall");
        assertSlicesAndStages(context, config, "travel_obstacle.stl", "native-smoke-travel-obstacle");
    }

    @Test
    public void typedRecipeOwnsOverlappingNativeSettings() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        File defaultConfigFile = new File(context.getCacheDir(), "native-config-default-brim.ini");
        NativeSlicerEngine.writeConfig(config, defaultConfigFile);
        String defaultSerialized = new String(Files.readAllBytes(defaultConfigFile.toPath()), StandardCharsets.US_ASCII);
        Assert.assertTrue(defaultSerialized.contains("brim_type = no_brim"));
        Assert.assertTrue(defaultSerialized.contains("brim_width = 0"));
        Assert.assertTrue(defaultSerialized.contains("brim_object_gap = 0"));
        Assert.assertTrue("Bambu Auto Lift must survive native config projection",
                defaultSerialized.contains("z_hop_types = Auto Lift\n"));
        Assert.assertTrue("Bambu Auto Lift must reach the filament override",
                defaultSerialized.contains("filament_z_hop_types = Auto Lift\n"));
        Assert.assertTrue(defaultSerialized.contains("filament_retraction_length = 0.8\n"));
        Assert.assertTrue(defaultSerialized.contains("filament_z_hop = 0.4\n"));
        // The desktop flavor is a bounded parity override, not an arbitrary
        // native command channel. Verify both the accepted legacy value and
        // the fail-closed fallback before the contract-mutation assertions.
        config.nativeSettings.put("gcode_flavor", "marlin");
        File legacyFlavorFile = new File(context.getCacheDir(), "native-config-legacy-flavor.ini");
        NativeSlicerEngine.writeConfig(config, legacyFlavorFile);
        String legacyFlavorSerialized = new String(Files.readAllBytes(legacyFlavorFile.toPath()), StandardCharsets.US_ASCII);
        Assert.assertTrue(legacyFlavorSerialized.contains("gcode_flavor = marlin\n"));
        config.nativeSettings.put("gcode_flavor", "unsafe-command");
        File fallbackFlavorFile = new File(context.getCacheDir(), "native-config-fallback-flavor.ini");
        NativeSlicerEngine.writeConfig(config, fallbackFlavorFile);
        String fallbackFlavorSerialized = new String(Files.readAllBytes(fallbackFlavorFile.toPath()), StandardCharsets.US_ASCII);
        Assert.assertTrue(fallbackFlavorSerialized.contains("gcode_flavor = marlin2\n"));
        // This simulates a future profile or tampered request carrying keys
        // that Alloy itself owns. The adapter must keep typed values and its
        // controlled machine-start G-code authoritative.
        config.nativeSettings.put("temperature", "999");
        config.nativeSettings.put("start_gcode", "M109 S999");
        config.nativeSettings.put("support_tree_branch_distance", "5");
        config.nativeSettings.put("support_tree_branch_diameter_angle", "5");
        config.nativeSettings.put("top_fill_pattern", "concentric");
        config.nativeSettings.put("bottom_fill_pattern", "rectilinear");
        config.nativeSettings.put("seam_position", "nearest");
        config.nativeSettings.put("ironing", "1");
        config.nativeSettings.put("ironing_flowrate", "15%");
        config.nativeSettings.put("bridge_speed", "42");
        config.nativeSettings.put("default_acceleration", "1234");
        config.nativeSettings.put("brim_type", "outer_only");
        config.nativeSettings.put("brim_width", "5");
        config.nativeSettings.put("initial_layer_infill_speed", "105");
        config.nativeSettings.put("initial_layer_travel_acceleration", "6000");
        config.nativeSettings.put("inner_wall_acceleration", "0");
        config.nativeSettings.put("outer_wall_acceleration", "5000");
        File configFile = new File(context.getCacheDir(), "native-config-contract.ini");
        String serialized = null;
        try {
            NativeSlicerEngine.writeConfig(config, configFile);
            serialized = new String(Files.readAllBytes(configFile.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("temperature = 220.00000"));
            Assert.assertTrue(serialized.contains("brim_type = outer_only"));
            Assert.assertTrue(serialized.contains("brim_width = 5"));
            Assert.assertTrue(serialized.contains("brim_object_gap = 0.1"));
            Assert.assertFalse(serialized.contains("brim_separation ="));
            Assert.assertTrue(serialized.contains("initial_layer_print_height = 0.20000"));
            Assert.assertTrue(serialized.contains("wall_loops = 2"));
            Assert.assertTrue(serialized.contains("top_shell_layers = 5"));
            Assert.assertTrue(serialized.contains("bottom_shell_layers = 3"));
            Assert.assertTrue(serialized.contains("sparse_infill_density = 15.00000%"));
            Assert.assertTrue(serialized.contains("sparse_infill_pattern = grid"));
            Assert.assertTrue(serialized.contains("infill_direction = 45"));
            Assert.assertTrue(serialized.contains("wall_generator = classic"));
            Assert.assertTrue(serialized.contains("outer_wall_speed = 200.00000"));
            Assert.assertTrue(serialized.contains("inner_wall_speed = 300.00000"));
            Assert.assertTrue(serialized.contains("sparse_infill_speed = 270.00000"));
            Assert.assertTrue(serialized.contains("gap_infill_speed = 250"));
            Assert.assertTrue(serialized.contains("skirt_loops = 0"));
            Assert.assertTrue(serialized.contains("internal_solid_infill_speed = 250"));
            Assert.assertTrue(serialized.contains("top_surface_speed = 200"));
            Assert.assertTrue(serialized.contains("initial_layer_speed = 50.00000"));
            Assert.assertTrue(serialized.contains("initial_layer_infill_speed = 105"));
            Assert.assertTrue(serialized.contains("initial_layer_travel_acceleration = 6000"));
            Assert.assertTrue(serialized.contains("top_surface_pattern = concentric"));
            Assert.assertTrue(serialized.contains("bottom_surface_pattern = rectilinear"));
            Assert.assertTrue(serialized.contains("seam_position = nearest"));
            Assert.assertTrue(serialized.contains("ironing = 1"));
            Assert.assertTrue(serialized.contains("ironing_flowrate = 15%"));
            Assert.assertTrue(serialized.contains("bridge_speed = 42"));
            Assert.assertTrue(serialized.contains("default_acceleration = 1234"));
            Assert.assertTrue(serialized.contains("machine_start_gcode = ; Alloy native start"));
            Assert.assertTrue(serialized.contains("M204 S1234\\n"));
            Assert.assertTrue(serialized.contains("spiral_mode = 0"));
            Assert.assertTrue(serialized.contains("arc_fitting = emit_center"));
            Assert.assertTrue(serialized.contains("enable_arc_fitting = 1"));
            Assert.assertTrue(serialized.contains("ironing_flowrate = 15%"));
            Assert.assertTrue(serialized.contains("spiral_vase = 0"));
            Assert.assertTrue(serialized.contains("gcode_comments = 1"));
            Assert.assertTrue(serialized.contains("support_material_speed = 150"));
            Assert.assertTrue(serialized.contains("solid_infill_speed = 250"));
            Assert.assertTrue(serialized.contains("solid_infill_below_area = 15"));
            Assert.assertTrue(serialized.contains("top_solid_infill_speed = 200"));
            Assert.assertTrue(serialized.contains("first_layer_extrusion_width = 0.5"));
            Assert.assertTrue(serialized.contains("perimeter_extrusion_width = 0.45"));
            Assert.assertTrue(serialized.contains("external_perimeter_extrusion_width = 0.42"));
            Assert.assertTrue(serialized.contains("infill_extrusion_width = 0.45"));
            Assert.assertTrue(serialized.contains("solid_infill_extrusion_width = 0.42"));
            Assert.assertTrue(serialized.contains("top_infill_extrusion_width = 0.42"));
            Assert.assertTrue(serialized.contains("line_width = 0.42"));
            Assert.assertTrue(serialized.contains("outer_wall_line_width = 0.42"));
            Assert.assertTrue(serialized.contains("inner_wall_line_width = 0.45"));
            Assert.assertTrue(serialized.contains("sparse_infill_line_width = 0.45"));
            Assert.assertTrue(serialized.contains("internal_solid_infill_line_width = 0.42"));
            Assert.assertTrue(serialized.contains("top_surface_line_width = 0.42"));
            Assert.assertTrue(serialized.contains("initial_layer_line_width = 0.5"));
            Assert.assertTrue(serialized.contains("support_line_width = 0.42"));
            Assert.assertTrue(serialized.contains("top_solid_min_thickness = 1.0"));
            Assert.assertTrue(serialized.contains("bottom_solid_min_thickness = 0"));
            Assert.assertTrue(serialized.contains("bridge_flow_ratio = 1"));
            Assert.assertTrue(serialized.contains("dont_support_bridges = 0"));
            Assert.assertTrue(serialized.contains("enable_dynamic_overhang_speeds = 1"));
            Assert.assertTrue(serialized.contains("first_layer_acceleration = 500"));
            Assert.assertTrue(serialized.contains("infill_acceleration = 6000"));
            Assert.assertTrue(serialized.contains("fill_angle = 45"));
            Assert.assertTrue(serialized.contains("external_perimeter_acceleration = 5000"));
            Assert.assertTrue(serialized.contains("fan_below_layer_time = 80"));
            Assert.assertTrue(serialized.contains("overhang_speed_0 = 0"));
            Assert.assertTrue(serialized.contains("overhang_speed_1 = 50"));
            Assert.assertTrue(serialized.contains("overhang_speed_2 = 30"));
            Assert.assertTrue(serialized.contains("overhang_speed_3 = 10"));
            Assert.assertTrue(serialized.contains("support_material_bottom_contact_distance = 0.2"));
            Assert.assertTrue(serialized.contains("support_material_contact_distance = 0.2"));
            Assert.assertTrue(serialized.contains("support_interface_top_layers = 2"));
            Assert.assertTrue(serialized.contains("support_interface_bottom_layers = 2"));
            Assert.assertTrue(serialized.contains("support_top_z_distance = 0.2"));
            Assert.assertTrue(serialized.contains("support_bottom_z_distance = 0.2"));
            Assert.assertTrue(serialized.contains("retract_before_travel = 1"));
            Assert.assertTrue(serialized.contains("retraction_minimum_travel = 1"));
            Assert.assertTrue(serialized.contains("retract_lift = 0.4"));
            Assert.assertTrue(serialized.contains("support_material_pattern = rectilinear"));
            Assert.assertTrue(serialized.contains("support_base_pattern = default"));
            Assert.assertTrue(serialized.contains("support_material_spacing = 2.5"));
            Assert.assertTrue(serialized.contains("support_tree_angle = 45"));
            Assert.assertTrue(serialized.contains("support_tree_branch_diameter = 2"));
            Assert.assertTrue(serialized.contains("support_tree_branch_diameter_double_wall = 0"));
            Assert.assertTrue(serialized.contains("support_tree_branch_distance = 5"));
            Assert.assertTrue(serialized.contains("support_tree_branch_diameter_angle = 5"));
        Assert.assertTrue(serialized.contains("tree_support_branch_distance_organic = 5"));
            Assert.assertTrue(serialized.contains("filament_max_volumetric_speed = 21"));
            Assert.assertTrue(serialized.contains("max_volumetric_speed = 0"));
            Assert.assertTrue(serialized.contains("gcode_flavor = marlin2"));
            Assert.assertTrue(serialized.contains("nozzle_temperature = 220"));
            Assert.assertTrue(serialized.contains("nozzle_temperature_initial_layer = 220"));
            Assert.assertTrue(serialized.contains("cool_plate_temp = 60"));
            Assert.assertTrue(serialized.contains("cool_plate_temp_initial_layer = 60"));
            Assert.assertTrue(serialized.contains("fan_cooling_layer_time = 80"));
            Assert.assertTrue(serialized.contains("slow_down_layer_time = 6"));
            Assert.assertTrue(serialized.contains("slow_down_min_speed = 20"));
            Assert.assertTrue(serialized.contains("support_material_xy_spacing = 0.35"));
            Assert.assertTrue(serialized.contains("top_one_perimeter_type = top"));
            Assert.assertTrue(serialized.contains("top_solid_infill_acceleration = 2000"));
            Assert.assertTrue(serialized.contains("initial_layer_acceleration = 500"));
            Assert.assertTrue(serialized.contains("outer_wall_acceleration = 5000"));
            Assert.assertTrue(serialized.contains("inner_wall_acceleration = 0"));
            Assert.assertTrue(serialized.contains("top_surface_acceleration = 2000"));
            Assert.assertTrue(serialized.contains("sparse_infill_acceleration = 100%"));
            Assert.assertTrue(serialized.contains("internal_solid_infill_acceleration = 2000"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_x = 1234,1234"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_y = 1234,1234"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_travel = 1234,1234"));
            Assert.assertFalse(serialized.contains("machine_max_acceleration_x = 20000,20000"));
            Assert.assertFalse(serialized.contains("machine_max_acceleration_travel = 9000,9000"));
            Assert.assertFalse(serialized.contains("temperature = 999"));
            Assert.assertFalse(serialized.contains("start_gcode = M109 S999"));
        } catch (AssertionError error) {
            // Native CI logs otherwise surface only Assert.fail() without the
            // fragment that drifted. Keep the exact serialized contract in
            // the failure so a profile/native-schema change is diagnosable
            // without treating a failed runtime gate as a passing slice.
            AssertionError diagnostic = new AssertionError(
                    "Native recipe projection contract drifted. Serialized config:\n"
                            + (serialized == null ? "<config was not written>" : serialized));
            diagnostic.initCause(error);
            throw diagnostic;
        } finally {
            if (configFile.exists()) Assert.assertTrue(configFile.delete());
        }
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Result result = new NativeSlicerEngine(context.getCacheDir(), false).slice(mesh, config, null);
        Assert.assertTrue(result.gcode.contains("M109 S220"));
        Assert.assertFalse(result.gcode.contains("M109 S999"));
    }

    @Test(timeout = 30_000)
    public void nativeArcOutputRemainsInspectableAndSafe() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        config.nativeSettings.put("arc_fitting", "emit_center");
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/box-20mm.stl")) {
            mesh = MeshModel.read("box-20mm.stl", input);
        }
        Slicer.Result result = new NativeSlicerEngine(context.getCacheDir(), false).slice(mesh, config, null);
        // The cube is intentionally straight-edged, so arc fitting may
        // legitimately leave the artifact with no G2/G3 commands. Curved
        // support paths are covered by the support-enabled G3 fixture; this
        // test verifies that the selected mode survives into the native
        // artifact and that the phone preview remains safe and inspectable.
        Assert.assertTrue("arc fitting mode should be preserved in native output",
                result.gcode.contains("; enable_arc_fitting = 1"));
        GcodeSafetyValidator.Report safety = GcodeSafetyValidator.inspect(result.gcode);
        Assert.assertTrue(safety.summary(), safety.isValid());
        int previewSegments = 0;
        for (Slicer.Layer layer : result.layers) previewSegments += layer.segments.size();
        Assert.assertTrue("G2/G3 moves must remain visible in the phone preview", previewSegments > 200);
    }

    @Test
    public void exactOcctBooleanRoundTripsThroughModelCache() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ModelStore.Materialized first = null;
        ModelStore.Materialized second = null;
        ModelStore.Materialized result = null;
        try {
            MeshModel base = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "boolean-base", 40f, 40f, 30f);
            MeshModel tool = ModelWorkbench.create(ModelWorkbench.Primitive.BOX, "boolean-tool", 20f, 20f, 20f);
            first = ModelStore.materializeGenerated(context.getFilesDir(), ModelWorkbench.toBinaryStl(base));
            second = ModelStore.materializeGenerated(context.getFilesDir(), ModelWorkbench.toBinaryStl(tool));
            result = NativeGeometry.apply(context.getFilesDir(), first.file, second.file, NativeGeometry.CUT);
            MeshModel restored;
            try (InputStream input = new java.io.FileInputStream(result.file)) {
                restored = MeshModel.read("boolean-result.stl", input);
            }
            Assert.assertTrue("OCCT boolean result should contain triangles", restored.triangles.length / 3 > 12);
            Assert.assertEquals(40f, restored.maxX - restored.minX, 0.01f);
            Assert.assertEquals(40f, restored.maxY - restored.minY, 0.01f);
            Assert.assertEquals(30f, restored.maxZ - restored.minZ, 0.01f);
            Assert.assertTrue("OCCT boolean result should remain watertight", restored.geometryReport().isWatertight());
        } finally {
            if (result != null && result.file.exists()) Assert.assertTrue(result.file.delete());
            if (first != null && first.file.exists()) Assert.assertTrue(first.file.delete());
            if (second != null && second.file.exists()) Assert.assertTrue(second.file.delete());
        }
    }

    @Test(timeout = 45_000)
    public void ownerStepAssemblyTessellatesIntoInspectablePartsWhenPackaged() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean privateAssetsPresent;
        try (InputStream ignored = context.getAssets().open("models/catalog-private.json")) {
            privateAssetsPresent = true;
        } catch (Exception absent) {
            privateAssetsPresent = false;
        }
        Assume.assumeTrue("Owner STEP assets are only present in the private visual-review build",
                privateAssetsPresent);

        ProfileCatalog.Profile profile = ProfileCatalog.loadDefault(context.getAssets());
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        ModelStore.Materialized source = null;
        ModelStore.Materialized converted = null;
        try {
            source = ModelStore.materializeAsset(context.getFilesDir(), context.getAssets(),
                    "models/redmagic-keyboard-case-assembly-v04-angled.step");
            Assert.assertEquals(".step", source.extension);
            long nativeModel = Native.model_read_from_file(source.file.getCanonicalPath(),
                    "redmagic-keyboard-case-assembly-v04-angled.step", 0);
            try {
                Assert.assertEquals("STEP assembly should load as one native object", 1,
                        Native.model_get_objects_count(nativeModel));
                Assert.assertTrue("native STEP loader returned "
                                + Native.model_get_volumes_count(nativeModel, 0) + " volume(s)",
                        Native.model_get_volumes_count(nativeModel, 0) > 1);
            } finally {
                Native.model_release(nativeModel);
            }
            converted = NativeStepImporter.convertTo3mf(context.getFilesDir(), source, config,
                    "Redmagic Keyboard Case v0.4 angled assembly");
            Assert.assertEquals(".3mf", converted.extension);
            try (InputStream input = new java.io.FileInputStream(converted.file)) {
                MeshModel assembly = MeshModel.read("Redmagic Keyboard Case v0.4 angled assembly.3mf", input);
                Assert.assertTrue("owner STEP assembly should tessellate into triangles",
                        assembly.triangles.length / 3 > 10_000);
                Assert.assertTrue("owner STEP assembly should expose multiple named solids",
                        assembly.parts.length > 1);
                Assert.assertTrue("owner STEP assembly should retain its printable footprint: x="
                                + assembly.minX + ".." + assembly.maxX + ", y="
                                + assembly.minY + ".." + assembly.maxY,
                        assembly.maxX - assembly.minX <= 180.01f
                                && assembly.maxY - assembly.minY <= 180.01f);
            }
        } finally {
            if (converted != null && converted.file.exists()) Assert.assertTrue(converted.file.delete());
            if (source != null && source.file.exists()) Assert.assertTrue(source.file.delete());
        }
    }

    /**
     * Optional evidence export for the desktop-parity gate. The normal test
     * suite never writes these files; invoke this method with
     * -e export-g3 true and pull the app-external directory from the device.
     */
    @Test
    public void exportsNativeG3EvidenceWhenRequested() throws Exception {
        Assume.assumeTrue("Run this test with -PalloyNativeEngine=true", BuildConfig.NATIVE_ENGINE_ENABLED);
        Bundle arguments = InstrumentationRegistry.getArguments();
        Assume.assumeTrue("Pass -e export-g3 true to export parity evidence",
                "true".equalsIgnoreCase(arguments.getString("export-g3")));

        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        ProfileCatalog.Profile profile = loadG3ReferenceProfile(context, testContext);
        Slicer.Config config = new Slicer.Config();
        profile.applyTo(config);
        String requestedFlavor = arguments.getString("g3-gcode-flavor");
        if (requestedFlavor != null && !requestedFlavor.trim().isEmpty()) {
            String normalizedFlavor = requestedFlavor.trim().toLowerCase(java.util.Locale.US);
            if (!"marlin".equals(normalizedFlavor) && !"marlin2".equals(normalizedFlavor))
                throw new IllegalArgumentException("g3-gcode-flavor must be marlin or marlin2");
            config.nativeSettings.put("gcode_flavor", normalizedFlavor);
        }
        String brimType = requestedBrimType(arguments);
        if (brimType != null) config.nativeSettings.put("brim_type", brimType);
        String wipe = requestedWipe(arguments);
        if (wipe != null) config.nativeSettings.put("wipe", wipe);
        String zHopTypes = requestedZHopTypes(arguments);
        if (zHopTypes != null) config.nativeSettings.put("z_hop_types", zHopTypes);
        String travelSlope = requestedTravelSlope(arguments);
        if (travelSlope != null) config.nativeSettings.put("travel_slope", travelSlope);
        boolean arcFitting = requestedArcFitting(arguments);
        if (arcFitting) config.nativeSettings.put("arc_fitting", "emit_center");
        File evidenceDir = new File(context.getExternalFilesDir(null), "alloy-g3-evidence");
        if (!evidenceDir.exists()) Assert.assertTrue(evidenceDir.mkdirs());
        copyAsset(context, "profiles/a1-mini-0.4-pla-basic.json",
                new File(evidenceDir, "a1-mini-0.4-pla-basic.json"));
        copyAsset(testContext, "profiles/g3-bambu-a1mini-project-settings.json",
                new File(evidenceDir, "g3-bambu-a1mini-project-settings.json"));

        String evidenceSuffix = brimType == null ? "" : "_brim_" + brimType;
        if (wipe != null) evidenceSuffix += "_wipe_" + wipe;
        if (zHopTypes != null) evidenceSuffix += "_zhop_" + zHopTypes.toLowerCase(java.util.Locale.US).replace(' ', '_');
        if (travelSlope != null) evidenceSuffix += "_slope_" + travelSlope.replace('.', '_');
        exportNativeG3(context, config, "box-20mm.stl", arcFitting ? "cube_20mm_arcs" + evidenceSuffix + ".gcode" : "cube_20mm" + evidenceSuffix + ".gcode", evidenceDir);
        exportNativeG3(context, config, "travel_obstacle.stl",
                "travel_obstacle" + evidenceSuffix + ".gcode", evidenceDir);
        if ("auto_brim".equals(brimType)) {
            File obstacleEvidence = new File(evidenceDir,
                    "travel_obstacle" + evidenceSuffix + ".gcode");
            String obstacleGcode = new String(Files.readAllBytes(obstacleEvidence.toPath()),
                    StandardCharsets.UTF_8);
            Assert.assertFalse("auto-brim must not force a fixed outer brim on the stable obstacle fixture",
                    obstacleGcode.contains(";TYPE:Brim"));
            Assert.assertTrue("auto-brim must preserve Bambu's fallback skirt",
                    obstacleGcode.contains(";TYPE:Skirt"));
        }
        Slicer.Config supportConfig = config.copy();
        supportConfig.supports = true;
        String supportStyle = requestedSupportStyle(arguments);
        String supportPattern = requestedSupportPattern(arguments);
        supportConfig.nativeSettings.put("support_material_style", supportStyle);
        supportConfig.nativeSettings.put("support_material_pattern", supportPattern);
        String treeBranchDistance = requestedTreeBranchDistance(arguments);
        if (treeBranchDistance != null) {
            supportConfig.nativeSettings.put("support_tree_branch_distance", treeBranchDistance);
        }
        String supportSpacing = requestedSupportSpacing(arguments);
        if (supportSpacing != null) {
            supportConfig.nativeSettings.put("support_material_spacing", supportSpacing);
        }
        String treeBranchDiameter = requestedTreeBranchDiameter(arguments);
        if (treeBranchDiameter != null) {
            supportConfig.nativeSettings.put("support_tree_branch_diameter", treeBranchDiameter);
        }
        String supportLineWidth = requestedSupportLineWidth(arguments);
        if (supportLineWidth != null) {
            supportConfig.nativeSettings.put("support_material_extrusion_width", supportLineWidth);
        }
        String supportSpeed = requestedSupportSpeed(arguments);
        if (supportSpeed != null) {
            supportConfig.nativeSettings.put("support_material_speed", supportSpeed);
        }
        String treeDiameterAngle = requestedTreeDiameterAngle(arguments);
        if (treeDiameterAngle != null) {
            supportConfig.nativeSettings.put("support_tree_branch_diameter_angle", treeDiameterAngle);
        }
        String supportOutput = supportEvidenceOutput(
                supportStyle, supportPattern, treeBranchDistance, supportSpacing,
                treeBranchDiameter, supportLineWidth, supportSpeed, treeDiameterAngle);
        if (brimType != null) supportOutput = supportOutput.replace(".gcode", evidenceSuffix + ".gcode");
        if (arcFitting) supportOutput = supportOutput.replace(".gcode", "_arcs.gcode");
        exportNativeG3(context, supportConfig, "overhang-support-fixture.stl", supportOutput, evidenceDir);
        Slicer.Config thinWallConfig = config.copy();
        String smallPerimeterSpeed = requestedSmallPerimeterSpeed(arguments);
        if (smallPerimeterSpeed != null) {
            thinWallConfig.nativeSettings.put("small_perimeter_speed", smallPerimeterSpeed + "%");
        }
        String slowdownBelowLayerTime = requestedSlowdownBelowLayerTime(arguments);
        if (slowdownBelowLayerTime != null) {
            thinWallConfig.nativeSettings.put("slowdown_below_layer_time", slowdownBelowLayerTime);
        }
        String thinWallOutput = arcFitting ? "thin_wall_frame_arcs" + evidenceSuffix + ".gcode" : "thin_wall_frame" + evidenceSuffix + ".gcode";
        if (smallPerimeterSpeed != null) {
            thinWallOutput = thinWallOutput.replace(".gcode",
                    "_small_perimeter_" + smallPerimeterSpeed.replace('.', '_') + ".gcode");
        }
        if (slowdownBelowLayerTime != null) {
            thinWallOutput = thinWallOutput.replace(".gcode",
                    "_slowdown_" + slowdownBelowLayerTime.replace('.', '_') + ".gcode");
        }
        exportNativeG3(context, thinWallConfig, "thin-wall-frame-fixture.stl", thinWallOutput, evidenceDir);
    }

    /**
     * The G3 comparison must use the same resolved project recipe as the
     * Bambu reference export. The ordinary smoke tests intentionally retain
     * the shipped stock profile; only this opt-in evidence path consumes the
     * bounded project-settings fixture.
     */
    private static ProfileCatalog.Profile loadG3ReferenceProfile(
            Context context, Context testContext) throws Exception {
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        try (InputStream input = testContext.getAssets().open(
                "profiles/g3-bambu-a1mini-project-settings.json")) {
            return ProfileCatalog.importBambu(baseline,
                    new byte[][]{ProfileCatalog.readProfileDocument(input)});
        }
    }

    private static boolean requestedArcFitting(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-arc-fitting");
        if (value == null || value.trim().isEmpty()) return false;
        if (!"true".equalsIgnoreCase(value.trim()) && !"false".equalsIgnoreCase(value.trim()))
            throw new IllegalArgumentException("g3-arc-fitting must be true or false");
        return Boolean.parseBoolean(value.trim());
    }

    /** Keep brim experiments explicit and bounded to native enum values. */
    private static String requestedBrimType(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-brim-type");
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().toLowerCase(java.util.Locale.US);
        if (!"no_brim".equals(normalized) && !"outer_only".equals(normalized)
                && !"inner_only".equals(normalized) && !"outer_and_inner".equals(normalized)
                && !"auto_brim".equals(normalized))
            throw new IllegalArgumentException(
                    "g3-brim-type must be no_brim, outer_only, inner_only, outer_and_inner or auto_brim");
        return normalized;
    }

    /** Keep wipe experiments explicit; the desktop fixture resolves this to 0. */
    private static String requestedWipe(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-wipe");
        if (value == null || value.trim().isEmpty()) return null;
        if (!"0".equals(value.trim()) && !"1".equals(value.trim()))
            throw new IllegalArgumentException("g3-wipe must be 0 or 1");
        return value.trim();
    }

    /** Keep Z-hop strategy experiments explicit and bounded to native enum values. */
    private static String requestedZHopTypes(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-z-hop-types");
        if (value == null || value.trim().isEmpty()) return null;
        String normalized = value.trim().replace('_', ' ').replace('-', ' ');
        if (!"Auto Lift".equalsIgnoreCase(normalized)
                && !"Slope Lift".equalsIgnoreCase(normalized)
                && !"Normal Lift".equalsIgnoreCase(normalized)
                && !"Spiral Lift".equalsIgnoreCase(normalized))
            throw new IllegalArgumentException(
                    "g3-z-hop-types must be Auto Lift, Slope Lift, Normal Lift or Spiral Lift");
        if ("auto lift".equalsIgnoreCase(normalized)) return "Auto Lift";
        if ("slope lift".equalsIgnoreCase(normalized)) return "Slope Lift";
        if ("normal lift".equalsIgnoreCase(normalized)) return "Normal Lift";
        return "Spiral Lift";
    }

    /** Keep the Bambu travel-slope experiment finite and within native bounds. */
    private static String requestedTravelSlope(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-travel-slope");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed <= 0f || parsed >= 90f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("g3-travel-slope must be finite and between 0 and 90 degrees", error);
        }
    }

    /** Keep support-style experiments explicit and bounded to native choices. */
    private static String requestedSupportStyle(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-support-style");
        if (value == null || value.trim().isEmpty()) return "organic";
        String normalized = value.trim().toLowerCase(java.util.Locale.US);
        if (!"organic".equals(normalized) && !"tree".equals(normalized)
                && !"snug".equals(normalized) && !"grid".equals(normalized))
            throw new IllegalArgumentException("g3-support-style must be organic, tree, snug or grid");
        return normalized;
    }

    /** Keep support-pattern experiments explicit and bounded to native choices. */
    private static String requestedSupportPattern(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-support-pattern");
        if (value == null || value.trim().isEmpty()) return "rectilinear";
        String normalized = value.trim().toLowerCase(java.util.Locale.US);
        if (!"rectilinear".equals(normalized) && !"rectilinear-grid".equals(normalized)
                && !"honeycomb".equals(normalized))
            throw new IllegalArgumentException(
                    "g3-support-pattern must be rectilinear, rectilinear-grid or honeycomb");
        return normalized;
    }

    private static String requestedTreeBranchDistance(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-tree-branch-distance");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed <= 0f || parsed > 100f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-tree-branch-distance must be finite and between 0 and 100 mm", error);
        }
    }

    /** Keep support-density experiments bounded and separate from the shipped profile. */
    private static String requestedSupportSpacing(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-support-spacing");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0.5f || parsed > 10f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-support-spacing must be finite and between 0.5 and 10 mm", error);
        }
    }

    /** Keep organic-support branch-thickness experiments bounded and isolated. */
    private static String requestedTreeBranchDiameter(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-tree-branch-diameter");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0.5f || parsed > 10f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-tree-branch-diameter must be finite and between 0.5 and 10 mm", error);
        }
    }

    /** Keep support-line-width experiments bounded and isolated from profile defaults. */
    private static String requestedSupportLineWidth(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-support-line-width");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0.2f || parsed > 1f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-support-line-width must be finite and between 0.2 and 1 mm", error);
        }
    }

    /** Keep support-speed experiments bounded and isolated from profile defaults. */
    private static String requestedSupportSpeed(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-support-speed");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 1f || parsed > 1_000f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-support-speed must be finite and between 1 and 1000 mm/s", error);
        }
    }

    /** Keep the thin-wall speed-base experiment bounded and out of the shipped profile. */
    private static String requestedSmallPerimeterSpeed(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-small-perimeter-speed");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 10f || parsed > 100f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-small-perimeter-speed must be finite and between 10 and 100 percent", error);
        }
    }

    /** Keep the cooling-time experiment bounded and out of the shipped profile. */
    private static String requestedSlowdownBelowLayerTime(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-slowdown-below-layer-time");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0f || parsed > 120f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-slowdown-below-layer-time must be finite and between 0 and 120 seconds", error);
        }
    }

    /** Keep organic branch-growth-angle experiments bounded and isolated. */
    private static String requestedTreeDiameterAngle(Bundle arguments) {
        String value = arguments == null ? null : arguments.getString("g3-tree-diameter-angle");
        if (value == null || value.trim().isEmpty()) return null;
        try {
            float parsed = Float.parseFloat(value.trim());
            if (Float.isNaN(parsed) || Float.isInfinite(parsed) || parsed < 0f || parsed >= 90f)
                throw new NumberFormatException("out of range");
            return value.trim();
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException(
                    "g3-tree-diameter-angle must be finite and between 0 and 90 degrees", error);
        }
    }

    private static String supportEvidenceOutput(String style, String pattern,
                                                String treeBranchDistance, String supportSpacing,
                                                String treeBranchDiameter, String supportLineWidth,
                                                String supportSpeed, String treeDiameterAngle) {
        if ("organic".equals(style) && "rectilinear".equals(pattern)
                && treeBranchDistance == null && supportSpacing == null
                && treeBranchDiameter == null && supportLineWidth == null
                && supportSpeed == null && treeDiameterAngle == null)
            return "overhang_support.gcode";
        String suffix = style + "_" + pattern.replace('-', '_');
        if (treeBranchDistance != null) suffix += "_distance_" + treeBranchDistance.replace('.', '_');
        if (supportSpacing != null) suffix += "_spacing_" + supportSpacing.replace('.', '_');
        if (treeBranchDiameter != null) suffix += "_diameter_" + treeBranchDiameter.replace('.', '_');
        if (supportLineWidth != null) suffix += "_line_width_" + supportLineWidth.replace('.', '_');
        if (supportSpeed != null) suffix += "_speed_" + supportSpeed.replace('.', '_');
        if (treeDiameterAngle != null) suffix += "_diameter_angle_" + treeDiameterAngle.replace('.', '_');
        return "overhang_support_" + suffix + ".gcode";
    }

    private static void exportNativeG3(Context context, Slicer.Config config, String assetName,
                                       String outputName, File evidenceDir) throws Exception {
        String configName = outputName.endsWith(".gcode")
                ? outputName.substring(0, outputName.length() - ".gcode".length()) + ".config.ini"
                : outputName + ".config.ini";
        File configFile = new File(evidenceDir, configName);
        NativeSlicerEngine.writeConfig(config, configFile);
        Assert.assertTrue("native config evidence should be written", configFile.isFile());
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/" + assetName)) {
            mesh = MeshModel.read(assetName, input);
        }
        Slicer.Result result = new NativeSlicerEngine(context.getCacheDir(), false)
                .slice(mesh, config.copy(), null);
        GcodeSafetyValidator.Report safety = GcodeSafetyValidator.inspect(result.gcode);
        Assert.assertTrue(safety.summary(), safety.isValid());
        Files.write(new File(evidenceDir, outputName).toPath(),
                result.gcode.getBytes(StandardCharsets.UTF_8));
    }

    private static void copyAsset(Context context, String assetName, File target) throws Exception {
        try (InputStream input = context.getAssets().open(assetName);
             OutputStream output = Files.newOutputStream(target.toPath())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) output.write(buffer, 0, read);
            }
        }
        Assert.assertTrue("asset evidence should be written", target.isFile());
    }

    private static Slicer.Result assertSlicesAndStages(Context context, Slicer.Config config,
                                                       String assetName, String artifactName) throws Exception {
        MeshModel mesh;
        try (InputStream input = context.getAssets().open("models/" + assetName)) {
            mesh = MeshModel.read(assetName, input);
        }

        Slicer.Result result = new NativeSlicerEngine(context.getCacheDir(), false)
                .slice(mesh, config.copy(), null);
        Assert.assertEquals(NativeSlicerEngine.ORCA_NATIVE_ENGINE_ID, result.engineId);
        Assert.assertFalse(result.gcode.isEmpty());
        Assert.assertFalse(result.layers.isEmpty());
        GcodeSafetyValidator.Report safety = GcodeSafetyValidator.inspect(result.gcode);
        Assert.assertTrue(safety.summary(), safety.isValid());
        Assert.assertTrue(safety.hasAbsolutePositioning);
        Assert.assertTrue(safety.hasExtrusionMode);
        Assert.assertTrue(safety.hasStopTemperature);
        Assert.assertTrue(safety.hasPrintableMotion);
        Assert.assertTrue("native output should carry the A1 Mini machine envelope",
                result.gcode.contains("M201 X6000 Y6000 Z1500 E5000"));
        Assert.assertTrue("native output should carry the A1 Mini feed-rate envelope",
                result.gcode.contains("M203 X500 Y500 Z30 E30"));
        Assert.assertTrue("native output should carry the A1 Mini jerk envelope",
                result.gcode.contains("M205 X9.00 Y9.00 Z5.00 E3.00"));
        Assert.assertTrue("native output should retain new-Marlin acceleration semantics",
                result.gcode.contains("M204 P20000 R5000 T6000"));
        Assert.assertTrue("native output should retain the reviewed machine-start default acceleration",
                result.gcode.contains("M204 S6000\n"));

        Assert.assertFalse(result.engineVerified);
        PrinterTransport.Artifact artifact = null;
        try {
            artifact = ArtifactStore.stage(context.getCacheDir(), mesh, result, config, artifactName);
            Assert.assertTrue(artifact.hasSourceFile());
            Assert.assertTrue(artifact.sizeBytes > 0L);
            GcodePackageValidator.validate(new File(artifact.sourceFile.getAbsolutePath()));
        } finally {
            if (artifact != null && artifact.sourceFile.exists())
                Assert.assertTrue(artifact.sourceFile.delete());
        }
        return result;
    }
}
