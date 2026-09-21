package com.mbaliga.alloy;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;

/** Regression coverage for importing user-owned Bambu preset JSON safely. */
@RunWith(AndroidJUnit4.class)
public final class ProfileCatalogImportTest {
    @Test
    public void importsMachineProcessAndFilamentPresetsWithSafeProjection() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"machine\",\"name\":\"Workshop Printer\",\"printer_model\":\"Workshop Printer\","
                        + "\"nozzle_diameter\":[\"0.6\"],\"printable_area\":[\"0x0\",\"220x0\",\"220x220\",\"0x220\"],"
                        + "\"printable_height\":\"250\",\"machine_max_feedrate_z\":[\"42\",\"42\"],"
                        + "\"start_gcode\":\"M109 S999\"}"),
                json("{\"type\":\"process\",\"name\":\"Draft 0.28\",\"layer_height\":\"0.28\","
                        + "\"initial_layer_print_height\":\"0.24\",\"sparse_infill_density\":\"30%\","
                        + "\"wall_loops\":\"3\",\"top_shell_layers\":\"6\",\"bottom_shell_layers\":\"4\","
                        + "\"enable_support\":\"1\",\"support_threshold_angle\":\"35\",\"travel_speed\":[\"500\"],"
                        + "\"outer_wall_speed\":[\"100\"],\"inner_wall_speed\":[\"150\"],"
                        + "\"sparse_infill_speed\":[\"180\"],\"initial_layer_speed\":[\"35\"],"
                        + "\"gap_fill_speed\":[\"200\"],\"initial_layer_infill_speed\":[\"105\"],"
                        + "\"initial_layer_travel_acceleration\":[\"6000\"],\"inner_wall_acceleration\":[\"0\"],"
                        + "\"outer_wall_acceleration\":[\"5000\"]}"),
                json("{\"type\":\"filament\",\"name\":\"Generic PETG\",\"filament_type\":\"PETG\","
                        + "\"filament_diameter\":[\"1.75\"],\"nozzle_temperature\":[\"245\"],"
                        + "\"nozzle_temperature_initial_layer\":[\"250\"],\"hot_plate_temp\":[\"80\"],"
                        + "\"hot_plate_temp_initial_layer\":[\"85\"],\"filament_flow_ratio\":[\"1.02\"],"
                        + "\"filament_max_volumetric_speed\":[\"12\"],\"fan_min_speed\":[\"20\"],"
                        + "\"fan_max_speed\":[\"60\"]}")
        };

        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertFalse("user imports must stay unverified", imported.verified);
        Assert.assertTrue(imported.name.contains("Workshop Printer"));
        Assert.assertTrue(imported.printerId.startsWith("bambu.imported."));
        Assert.assertEquals(220f, imported.bedX, 0.001f);
        Assert.assertEquals(220f, imported.bedY, 0.001f);
        Assert.assertEquals(250f, imported.buildZ, 0.001f);
        Assert.assertEquals(0.6f, imported.nozzle, 0.001f);
        Assert.assertEquals(0.28f, imported.layerHeight, 0.001f);
        Assert.assertEquals(0.30f, imported.infill, 0.001f);
        Assert.assertEquals(3, imported.perimeters);
        Assert.assertTrue(imported.supports);
        Assert.assertEquals("PETG", imported.material);
        Assert.assertEquals(245f, imported.nozzleTemperature, 0.001f);
        Assert.assertEquals("200", imported.nativeSettings.get("gap_fill_speed"));
        Assert.assertEquals("105", imported.nativeSettings.get("initial_layer_infill_speed"));
        Assert.assertEquals("6000", imported.nativeSettings.get("initial_layer_travel_acceleration"));
        Assert.assertEquals("0", imported.nativeSettings.get("inner_wall_acceleration"));
        Assert.assertEquals("5000", imported.nativeSettings.get("outer_wall_acceleration"));
        Assert.assertEquals("42,42", imported.nativeSettings.get("machine_max_feedrate_z"));
        Assert.assertFalse("arbitrary G-code must never cross the allowlist",
                imported.nativeSettings.containsKey("start_gcode"));
        Assert.assertEquals(40, imported.provenanceRevision.length());

        ProfileCatalog.Profile roundTrip = ProfileCatalog.load(new ByteArrayInputStream(
                ProfileCatalog.serialize(imported)));
        Assert.assertEquals(imported.id, roundTrip.id);
        Assert.assertEquals(imported.nativeSettings, roundTrip.nativeSettings);
        Assert.assertFalse(roundTrip.verified);
    }

    @Test
    public void translatesCurrentBambuSupportVocabularyIntoNativeRecipeKeys() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"machine\",\"name\":\"Bambu Lab A1 mini\","
                        + "\"printer_model\":\"Bambu Lab A1 mini\",\"printable_area\":[\"0x0\",\"180x0\",\"180x180\",\"0x180\"],"
                        + "printable_height\":\"180\"}"),
                json("{\"type\":\"process\",\"name\":\"0.20mm Standard @BBL A1M\","
                        + "\"enable_support\":\"1\",\"support_type\":\"tree(auto)\","
                        + "\"support_style\":\"default\",\"support_base_pattern\":\"default\","
                        + "\"support_base_pattern_spacing\":\"2.5\",\"support_speed\":[\"80\"],"
                        + "\"support_interface_speed\":[\"80\"],\"support_interface_top_layers\":\"2\","
                        + "\"support_interface_bottom_layers\":\"2\",\"support_top_z_distance\":\"0.2\","
                        + "\"support_bottom_z_distance\":\"0.2\",\"support_line_width\":\"0.4\","
                        + "\"support_object_xy_distance\":\"0.35\",\"support_interface_spacing\":\"0.45\","
                        + "\"support_bottom_interface_spacing\":\"0.5\",\"tree_support_branch_angle\":\"45\","
                        + "\"tree_support_branch_diameter\":\"2\",\"tree_support_branch_distance\":\"5\","
                        + "\"tree_support_wall_count\":\"-1\"}")
        };

        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertEquals("organic", imported.nativeSettings.get("support_material_style"));
        Assert.assertEquals("default", imported.nativeSettings.get("support_material_pattern"));
        Assert.assertEquals("2.5", imported.nativeSettings.get("support_material_spacing"));
        Assert.assertEquals("80", imported.nativeSettings.get("support_material_speed"));
        Assert.assertEquals("80", imported.nativeSettings.get("support_material_interface_speed"));
        Assert.assertEquals("2", imported.nativeSettings.get("support_material_interface_layers"));
        Assert.assertEquals("2", imported.nativeSettings.get("support_material_bottom_interface_layers"));
        Assert.assertEquals("0.2", imported.nativeSettings.get("support_material_contact_distance"));
        Assert.assertEquals("0.2", imported.nativeSettings.get("support_material_bottom_contact_distance"));
        Assert.assertEquals("0.4", imported.nativeSettings.get("support_material_extrusion_width"));
        Assert.assertEquals("0.45", imported.nativeSettings.get("support_interface_spacing"));
        Assert.assertEquals("0.5", imported.nativeSettings.get("support_bottom_interface_spacing"));
        Assert.assertEquals("5", imported.nativeSettings.get("support_tree_branch_distance"));
        Assert.assertEquals("-1", imported.nativeSettings.get("support_tree_branch_diameter_double_wall"));
        Assert.assertFalse("current provider spellings must not leak as ignored settings",
                imported.nativeSettings.containsKey("support_style"));

        Slicer.Config config = new Slicer.Config();
        imported.applyTo(config);
        File nativeConfig = new File(context.getCacheDir(), "imported-current-bambu-support.ini");
        try {
            NativeSlicerEngine.writeConfig(config, nativeConfig);
            String serialized = new String(Files.readAllBytes(nativeConfig.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("support_style = organic"));
            Assert.assertTrue(serialized.contains("support_base_pattern = default"));
            Assert.assertTrue(serialized.contains("support_speed = 80"));
            Assert.assertTrue(serialized.contains("support_interface_spacing = 0.45"));
            Assert.assertTrue(serialized.contains("support_bottom_interface_spacing = 0.5"));
            Assert.assertTrue(serialized.contains("tree_support_branch_distance_organic = 5"));
        } finally {
            if (nativeConfig.exists()) Assert.assertTrue(nativeConfig.delete());
        }
    }

    @Test
    public void importsResolvedBambuProjectSettingsWithoutAllowingTemplates() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"name\":\"project_settings\",\"printer_model\":\"Bambu Lab A1 mini\","
                        + "\"default_print_profile\":\"0.20mm Standard @BBL A1M\","
                        + "\"default_filament_profile\":\"Bambu PLA Basic @BBL A1M\","
                        + "\"printable_area\":[\"0x0\",\"180x0\",\"180x180\",\"0x180\"],"
                        + "\"printable_height\":\"180\",\"nozzle_diameter\":[\"0.4\"],"
                        + "\"layer_height\":\"0.2\",\"top_shell_layers\":\"4\","
                        + "\"bottom_shell_layers\":\"3\",\"enable_support\":\"1\","
                        + "\"support_type\":\"tree(auto)\",\"support_style\":\"default\","
                        + "\"support_interface_top_layers\":\"2\",\"support_interface_bottom_layers\":\"2\","
                        + "\"support_top_z_distance\":\"0.2\",\"support_bottom_z_distance\":\"0.2\","
                        + "\"support_line_width\":\"0.4\",\"support_speed\":[\"80\"],"
                        + "\"nozzle_temperature\":[\"200\"],\"nozzle_temperature_initial_layer\":[\"200\"],"
                        + "\"hot_plate_temp\":[\"60\"],\"hot_plate_temp_initial_layer\":[\"60\"],"
                        + "\"filament_type\":\"PLA\",\"filament_diameter\":[\"1.75\"],"
                        + "\"filament_flow_ratio\":[\"1\"],\"filament_max_volumetric_speed\":[\"2\"],"
                        + "\"fan_min_speed\":[\"60\"],\"fan_max_speed\":[\"80\"],"
                        + "\"start_gcode\":\"M109 S999\"}")
        };

        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertTrue(imported.name.contains("0.20mm Standard"));
        Assert.assertTrue(imported.printerId.endsWith("bambu-lab-a1-mini"));
        Assert.assertEquals(4, imported.topLayers);
        Assert.assertTrue(imported.supports);
        Assert.assertEquals(200f, imported.nozzleTemperature, 0.001f);
        Assert.assertEquals(2f, imported.maxVolumetricSpeed, 0.001f);
        Assert.assertEquals("organic", imported.nativeSettings.get("support_material_style"));
        Assert.assertEquals("80", imported.nativeSettings.get("support_material_speed"));
        Assert.assertFalse("project templates must never be projected",
                imported.nativeSettings.containsKey("start_gcode"));
    }

    @Test
    public void preservesSelectedBambuBuildPlateTemperature() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, new byte[][]{
                json("{\"name\":\"project_settings\",\"printer_model\":\"Bambu Lab A1 mini\","
                        + "\"curr_bed_type\":\"Cool Plate\",\"cool_plate_temp\":[\"35\"],"
                        + "\"cool_plate_temp_initial_layer\":[\"35\"],\"hot_plate_temp\":[\"60\"],"
                        + "\"hot_plate_temp_initial_layer\":[\"60\"],\"filament_type\":\"PLA\"}")
        });
        Assert.assertEquals("Cool Plate", imported.nativeSettings.get("curr_bed_type"));
        Assert.assertEquals("35", imported.nativeSettings.get("cool_plate_temp"));

        Slicer.Config config = new Slicer.Config();
        imported.applyTo(config);
        File nativeConfig = new File(context.getCacheDir(), "selected-build-plate-temperature.ini");
        try {
            NativeSlicerEngine.writeConfig(config, nativeConfig);
            String serialized = new String(Files.readAllBytes(nativeConfig.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("bed_temperature = 35"));
            Assert.assertTrue(serialized.contains("first_layer_bed_temperature = 35"));
            Assert.assertTrue(serialized.contains("cool_plate_temp = 35"));
        } finally {
            if (nativeConfig.exists()) Assert.assertTrue(nativeConfig.delete());
        }
    }

    @Test
    public void g3ReferenceFixtureResolvesTheBambuProjectOverrides() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(targetContext.getAssets());
        ProfileCatalog.Profile imported;
        try (java.io.InputStream input = testContext.getAssets().open(
                "profiles/g3-bambu-a1mini-project-settings.json")) {
            imported = ProfileCatalog.importBambu(baseline,
                    new byte[][]{ProfileCatalog.readProfileDocument(input)});
        }
        Assert.assertEquals(4, imported.topLayers);
        Assert.assertEquals(0.6f, Float.parseFloat(
                imported.nativeSettings.get("top_solid_min_thickness")), 0.001f);
        Assert.assertEquals(200f, imported.nozzleTemperature, 0.001f);
        Assert.assertEquals(2f, imported.maxVolumetricSpeed, 0.001f);
        Assert.assertEquals("arachne", imported.nativeSettings.get("perimeter_generator"));
        Assert.assertEquals("1000,1000",
                imported.nativeSettings.get("machine_max_acceleration_x"));
        Assert.assertEquals("1500,1250",
                imported.nativeSettings.get("machine_max_acceleration_travel"));
        Assert.assertEquals("80", imported.nativeSettings.get("support_material_speed"));
        Assert.assertEquals("organic", imported.nativeSettings.get("support_material_style"));
        Assert.assertEquals("5", imported.nativeSettings.get("support_tree_branch_diameter_angle"));
        Assert.assertEquals("10", imported.nativeSettings.get("min_print_speed"));
        Assert.assertEquals(700f, imported.travelSpeed, 0.001f);
        Assert.assertEquals(60f, imported.outerWallSpeed, 0.001f);
        Assert.assertEquals(60f, imported.innerWallSpeed, 0.001f);
        Assert.assertEquals(100f, imported.infillSpeed, 0.001f);
        Assert.assertEquals(30f, imported.initialLayerSpeed, 0.001f);
        Assert.assertEquals("100", imported.nativeSettings.get("solid_infill_speed"));
        Assert.assertEquals("100", imported.nativeSettings.get("top_solid_infill_speed"));
        Assert.assertEquals("0.4", imported.nativeSettings.get("infill_extrusion_width"));
        Assert.assertEquals("120,120", imported.nativeSettings.get("machine_max_feedrate_e"));
        Assert.assertEquals("500,200", imported.nativeSettings.get("machine_max_feedrate_x"));
        Assert.assertEquals("10,10", imported.nativeSettings.get("machine_max_jerk_x"));
        Assert.assertEquals("9", imported.nativeSettings.get("outer_wall_jerk"));
        Assert.assertEquals("9", imported.nativeSettings.get("inner_wall_jerk"));
        Assert.assertEquals("9", imported.nativeSettings.get("infill_jerk"));
        Assert.assertEquals("9", imported.nativeSettings.get("top_surface_jerk"));
        Assert.assertEquals("9", imported.nativeSettings.get("initial_layer_jerk"));
        Assert.assertEquals("9", imported.nativeSettings.get("travel_jerk"));
        Assert.assertEquals("400%", imported.nativeSettings.get("infill_anchor"));
        Assert.assertEquals("20", imported.nativeSettings.get("infill_anchor_max"));
        Assert.assertEquals("auto_brim", imported.nativeSettings.get("brim_type"));
        Assert.assertEquals("5", imported.nativeSettings.get("brim_width"));
        Assert.assertEquals("0", imported.nativeSettings.get("brim_object_gap"));
        Assert.assertEquals("0.8", imported.nativeSettings.get("retract_length"));
        Assert.assertEquals("0.4", imported.nativeSettings.get("retract_lift"));
        Assert.assertEquals("2", imported.nativeSettings.get("wipe_distance"));
        Assert.assertEquals("1", imported.nativeSettings.get("support_remove_small_overhang"));
        Assert.assertEquals("5", imported.nativeSettings.get("support_tree_branch_distance"));
        Assert.assertEquals("0.2", imported.nativeSettings.get("support_object_first_layer_gap"));
        Assert.assertEquals("1", imported.nativeSettings.get("support_interface_not_for_body"));
        Assert.assertEquals("cubic", imported.nativeSettings.get("fill_pattern"));
        Assert.assertEquals("rectilinear", imported.nativeSettings.get("internal_solid_infill_pattern"));
        Assert.assertEquals("1500,1250", imported.nativeSettings.get("machine_max_acceleration_retracting"));
    }

    @Test
    public void g3ReferenceFixtureWritesEffectiveMotionRecipeToNativeConfig() throws Exception {
        Context targetContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context testContext = InstrumentationRegistry.getInstrumentation().getContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(targetContext.getAssets());
        ProfileCatalog.Profile imported;
        try (java.io.InputStream input = testContext.getAssets().open(
                "profiles/g3-bambu-a1mini-project-settings.json")) {
            imported = ProfileCatalog.importBambu(baseline,
                    new byte[][]{ProfileCatalog.readProfileDocument(input)});
        }
        Slicer.Config config = new Slicer.Config();
        imported.applyTo(config);
        File nativeConfig = new File(targetContext.getCacheDir(), "g3-effective-motion-recipe.ini");
        try {
            NativeSlicerEngine.writeConfig(config, nativeConfig);
            String serialized = new String(Files.readAllBytes(nativeConfig.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("travel_speed = 700.00000"));
            Assert.assertTrue(serialized.contains("external_perimeter_speed = 60.00000"));
            Assert.assertTrue(serialized.contains("perimeter_speed = 60.00000"));
            Assert.assertTrue(serialized.contains("infill_speed = 100.00000"));
            Assert.assertTrue(serialized.contains("first_layer_speed = 30.00000"));
            Assert.assertTrue(serialized.contains("external_perimeter_acceleration = 500"));
            Assert.assertTrue(serialized.contains("first_layer_acceleration = 300"));
            Assert.assertTrue(serialized.contains("top_solid_infill_acceleration = 500"));
            Assert.assertTrue(serialized.contains("machine_max_speed_e = 120,120"));
            Assert.assertTrue(serialized.contains("machine_max_jerk_x = 10,10"));
            Assert.assertTrue(serialized.contains("outer_wall_jerk = 9"));
            Assert.assertTrue(serialized.contains("inner_wall_jerk = 9"));
            Assert.assertTrue(serialized.contains("infill_jerk = 9"));
            Assert.assertTrue(serialized.contains("top_surface_jerk = 9"));
            Assert.assertTrue(serialized.contains("initial_layer_jerk = 9"));
            Assert.assertTrue(serialized.contains("travel_jerk = 9"));
            Assert.assertTrue(serialized.contains("infill_anchor = 400%"));
            Assert.assertTrue(serialized.contains("infill_anchor_max = 20"));
            Assert.assertTrue(serialized.contains("retract_length = 0.8"));
            Assert.assertTrue(serialized.contains("retract_lift = 0.4"));
            Assert.assertTrue(serialized.contains("wipe_distance = 2"));
            Assert.assertTrue(serialized.contains("support_remove_small_overhang = 1"));
            Assert.assertTrue(serialized.contains("tree_support_branch_distance_organic = 5"));
            Assert.assertTrue(serialized.contains("support_object_first_layer_gap = 0.2"));
            Assert.assertTrue(serialized.contains("support_interface_not_for_body = 1"));
            Assert.assertTrue(serialized.contains("internal_solid_infill_pattern = rectilinear"));
        } finally {
            if (nativeConfig.exists()) Assert.assertTrue(nativeConfig.delete());
        }
    }

    @Test
    public void resolvedProjectOverridesReachNativeConfigWithoutStockAccelerationLeak() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"name\":\"project_settings\",\"printer_model\":\"Bambu Lab A1 mini\","
                        + "\"default_print_profile\":\"0.20mm Standard @BBL A1M\","
                        + "\"default_filament_profile\":\"Bambu PLA Basic @BBL A1M\","
                        + "\"machine_max_acceleration_x\":[\"1000\",\"1000\"],"
                        + "\"machine_max_acceleration_y\":[\"1000\",\"1000\"],"
                        + "\"machine_max_acceleration_travel\":[\"1500\",\"1250\"],"
                        + "\"machine_max_acceleration_extruding\":[\"1500\",\"1250\"],"
                        + "\"default_acceleration\":[\"6000\"],\"top_shell_layers\":\"4\","
                        + "\"top_shell_thickness\":\"0.6\",\"bottom_shell_thickness\":\"0\","
                        + "\"detect_thin_wall\":\"1\",\"wall_generator\":\"arachne\","
                        + "\"infill_direction\":\"37\",\"layer_height\":\"0.2\","
                        + "\"initial_layer_print_height\":\"0.2\",\"wall_loops\":\"2\","
                        + "\"sparse_infill_density\":\"15%\",\"enable_support\":\"0\","
                        + "\"nozzle_diameter\":[\"0.4\"],\"printable_area\":[\"0x0\",\"180x0\",\"180x180\",\"0x180\"],"
                        + "\"printable_height\":\"180\",\"filament_type\":\"PLA\","
                        + "\"filament_diameter\":[\"1.75\"],\"nozzle_temperature\":[\"200\"],"
                        + "\"nozzle_temperature_initial_layer\":[\"200\"],\"hot_plate_temp\":[\"60\"],"
                        + "\"hot_plate_temp_initial_layer\":[\"60\"],\"filament_flow_ratio\":[\"1\"],"
                        + "\"filament_max_volumetric_speed\":[\"2\"],\"fan_min_speed\":[\"60\"],"
                        + "\"fan_max_speed\":[\"80\"]}")
        };

        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertEquals("0.6", imported.nativeSettings.get("top_solid_min_thickness"));
        Assert.assertEquals("0", imported.nativeSettings.get("bottom_solid_min_thickness"));
        Assert.assertEquals("1", imported.nativeSettings.get("thin_walls"));
        Assert.assertEquals("arachne", imported.nativeSettings.get("perimeter_generator"));
        Assert.assertEquals("37", imported.nativeSettings.get("fill_angle"));
        Slicer.Config config = new Slicer.Config();
        imported.applyTo(config);
        File nativeConfig = new File(context.getCacheDir(), "resolved-project-overrides.ini");
        try {
            NativeSlicerEngine.writeConfig(config, nativeConfig);
            String serialized = new String(Files.readAllBytes(nativeConfig.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("top_solid_min_thickness = 0.6"));
            Assert.assertTrue(serialized.contains("bottom_solid_min_thickness = 0"));
            Assert.assertTrue(serialized.contains("thin_walls = 1"));
            Assert.assertTrue(serialized.contains("wall_generator = arachne"));
            Assert.assertTrue(serialized.contains("infill_direction = 37"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_x = 1000,1000"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_y = 1000,1000"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_travel = 1500,1250"));
            Assert.assertTrue(serialized.contains("machine_max_acceleration_extruding = 1500,1250"));
            Assert.assertTrue(serialized.contains("default_acceleration = 6000"));
        } finally {
            if (nativeConfig.exists()) Assert.assertTrue(nativeConfig.delete());
        }
    }

    @Test
    public void projectsCurrentBambuQualityVocabularyIntoNativeRecipe() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"name\":\"project_settings\",\"printer_model\":\"Bambu Lab A1 mini\","
                        + "\"sparse_infill_pattern\":\"cubic\",\"top_surface_pattern\":\"zig-zag\","
                        + "\"bottom_surface_pattern\":\"zig-zag\",\"internal_solid_infill_pattern\":\"zig-zag\","
                        + "\"line_width\":\"0.4\",\"outer_wall_line_width\":\"0.42\","
                        + "\"inner_wall_line_width\":\"0.45\",\"internal_solid_infill_line_width\":\"0.42\","
                        + "\"top_surface_line_width\":\"0.42\",\"initial_layer_line_width\":\"0.5\","
                        + "\"filament_flow_ratio\":\"0.97\",\"print_flow_ratio\":\"1.02\","
                        + "\"initial_layer_flow_ratio\":\"1.03\",\"top_solid_infill_flow_ratio\":\"0.98\","
                        + "\"bottom_solid_infill_flow_ratio\":\"1.01\","
                        + "\"bridge_flow\":\"1.05\",\"bridge_no_support\":\"1\","
                        + "\"gap_infill_speed\":[\"30\"],\"minimum_sparse_infill_area\":\"15\","
                        + "\"enable_overhang_speed\":\"1\",\"overhang_1_4_speed\":[\"0\"],"
                        + "\"overhang_2_4_speed\":[\"50\"],\"overhang_3_4_speed\":[\"30\"],"
                        + "\"overhang_4_4_speed\":[\"10\"],\"fan_cooling_layer_time\":[\"80\"],"
                        + "\"retract_when_changing_layer\":[\"0\"],\"skirt_loops\":\"0\"}")
        };

        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertEquals("cubic", imported.nativeSettings.get("fill_pattern"));
        Assert.assertEquals("rectilinear", imported.nativeSettings.get("top_fill_pattern"));
        Assert.assertEquals("rectilinear", imported.nativeSettings.get("bottom_fill_pattern"));
        Assert.assertEquals("rectilinear", imported.nativeSettings.get("internal_solid_infill_pattern"));
        Assert.assertEquals("0.4", imported.nativeSettings.get("extrusion_width"));
        Assert.assertEquals("0.42", imported.nativeSettings.get("external_perimeter_extrusion_width"));
        Assert.assertEquals("0.45", imported.nativeSettings.get("perimeter_extrusion_width"));
        Assert.assertEquals("0.42", imported.nativeSettings.get("solid_infill_extrusion_width"));
        Assert.assertEquals("0.42", imported.nativeSettings.get("top_infill_extrusion_width"));
        Assert.assertEquals("0.5", imported.nativeSettings.get("first_layer_extrusion_width"));
        Assert.assertEquals(0.97f, imported.flowRatio, 0.001f);
        Assert.assertEquals("1.02", imported.nativeSettings.get("print_flow_ratio"));
        Assert.assertEquals("1.03", imported.nativeSettings.get("first_layer_flow_ratio"));
        Assert.assertEquals("0.98", imported.nativeSettings.get("top_solid_infill_flow_ratio"));
        Assert.assertEquals("1.01", imported.nativeSettings.get("bottom_solid_infill_flow_ratio"));
        Assert.assertEquals("1.05", imported.nativeSettings.get("bridge_flow_ratio"));
        Assert.assertEquals("1", imported.nativeSettings.get("dont_support_bridges"));
        Assert.assertEquals("30", imported.nativeSettings.get("gap_fill_speed"));
        Assert.assertEquals("15", imported.nativeSettings.get("solid_infill_below_area"));
        Assert.assertEquals("1", imported.nativeSettings.get("enable_dynamic_overhang_speeds"));
        Assert.assertEquals("50", imported.nativeSettings.get("overhang_speed_1"));
        Assert.assertEquals("80", imported.nativeSettings.get("fan_below_layer_time"));
        Assert.assertEquals("0", imported.nativeSettings.get("retract_layer_change"));
        Assert.assertEquals("0", imported.nativeSettings.get("skirts"));

        Slicer.Config config = new Slicer.Config();
        imported.applyTo(config);
        File nativeConfig = new File(context.getCacheDir(), "current-bambu-quality-vocabulary.ini");
        try {
            NativeSlicerEngine.writeConfig(config, nativeConfig);
            String serialized = new String(Files.readAllBytes(nativeConfig.toPath()), StandardCharsets.US_ASCII);
            Assert.assertTrue(serialized.contains("sparse_infill_pattern = cubic"));
            Assert.assertTrue(serialized.contains("top_surface_pattern = rectilinear"));
            Assert.assertTrue(serialized.contains("bottom_surface_pattern = rectilinear"));
            Assert.assertTrue(serialized.contains("internal_solid_infill_pattern = rectilinear"));
            Assert.assertTrue(serialized.contains("line_width = 0.4"));
            Assert.assertTrue(serialized.contains("outer_wall_line_width = 0.42"));
            Assert.assertTrue(serialized.contains("filament_flow_ratio = 0.97"));
            Assert.assertTrue(serialized.contains("print_flow_ratio = 1.02"));
            Assert.assertTrue(serialized.contains("first_layer_flow_ratio = 1.03"));
            Assert.assertTrue(serialized.contains("top_solid_infill_flow_ratio = 0.98"));
            Assert.assertTrue(serialized.contains("bottom_solid_infill_flow_ratio = 1.01"));
            Assert.assertTrue(serialized.contains("set_other_flow_ratios = 1"));
            Assert.assertTrue(serialized.contains("bridge_flow = 1.05"));
            Assert.assertTrue(serialized.contains("bridge_no_support = 1"));
            Assert.assertTrue(serialized.contains("gap_fill_speed = 30"));
            Assert.assertTrue(serialized.contains("overhang_2_4_speed = 50"));
            Assert.assertTrue(serialized.contains("fan_below_layer_time = 80"));
            Assert.assertTrue(serialized.contains("fan_cooling_layer_time = 80"));
        } finally {
            if (nativeConfig.exists()) Assert.assertTrue(nativeConfig.delete());
        }
    }

    @Test
    public void rejectsEmptyOrOversizedImportSet() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        try {
            ProfileCatalog.importBambu(baseline, new byte[0][]);
            Assert.fail("empty import must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("Select one"));
        }
    }

    @Test
    public void resolvesSelectedInheritanceAndDoesNotBorrowA1MachineLimits() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"process\",\"name\":\"Fine process\",\"inherits\":\"Base process\","
                        + "\"layer_height\":\"0.12\"}"),
                json("{\"type\":\"process\",\"name\":\"Base process\",\"wall_loops\":\"4\","
                        + "\"top_shell_layers\":\"7\"}"),
                json("{\"type\":\"machine\",\"name\":\"Workshop CoreXY\",\"printer_model\":\"Workshop CoreXY\","
                        + "\"nozzle_diameter\":[\"0.4\"],\"printable_area\":[\"0x0\",\"300x0\",\"300x300\",\"0x300\"],"
                        + "\"printable_height\":\"300\"}")
        };
        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertEquals(0.12f, imported.layerHeight, 0.001f);
        Assert.assertEquals(4, imported.perimeters);
        Assert.assertEquals(7, imported.topLayers);
        Assert.assertEquals(300f, imported.bedX, 0.001f);
        Assert.assertFalse("A1 feed-rate limits must not leak into another machine", imported.nativeSettings.containsKey("machine_max_feedrate_x"));
    }

    @Test
    public void resolvesAllParentsWhenBambuUsesAnInheritanceArray() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"process\",\"name\":\"Parent walls\",\"wall_loops\":\"5\"}"),
                json("{\"type\":\"process\",\"name\":\"Parent shells\",\"top_shell_layers\":\"8\"}"),
                json("{\"type\":\"process\",\"name\":\"Array child\",\"inherits\":[\"Parent walls\",\"Parent shells\"],\"layer_height\":\"0.16\"}"),
                json("{\"type\":\"machine\",\"name\":\"Array machine\",\"printer_model\":\"Array machine\",\"printable_area\":[\"0x0\",\"200x0\",\"200x200\",\"0x200\"],\"printable_height\":\"200\"}")
        };
        ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
        Assert.assertEquals(0.16f, imported.layerHeight, 0.001f);
        Assert.assertEquals(5, imported.perimeters);
        Assert.assertEquals(8, imported.topLayers);
    }

    @Test
    public void rejectsAmbiguousUnrelatedPresetsInsteadOfChoosingProviderOrder() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"process\",\"name\":\"Draft candidate\",\"layer_height\":\"0.28\"}"),
                json("{\"type\":\"process\",\"name\":\"Fine candidate\",\"layer_height\":\"0.12\"}"),
                json("{\"type\":\"machine\",\"name\":\"One machine\",\"printer_model\":\"One machine\",\"printable_area\":[\"0x0\",\"200x0\",\"200x200\",\"0x200\"],\"printable_height\":\"200\"}")
        };
        try {
            ProfileCatalog.importBambu(baseline, documents);
            Assert.fail("ambiguous process presets must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("exactly one active process"));
        }
    }

    @Test
    public void rejectsDuplicatePresetIdsInsteadOfOverwritingOneDocument() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"process\",\"name\":\"Same process\",\"layer_height\":\"0.28\"}"),
                json("{\"type\":\"process\",\"name\":\"Same process\",\"layer_height\":\"0.12\"}"),
                json("{\"type\":\"machine\",\"name\":\"One machine\",\"printer_model\":\"One machine\"}")
        };
        try {
            ProfileCatalog.importBambu(baseline, documents);
            Assert.fail("duplicate preset IDs must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("Duplicate Bambu process preset"));
        }
    }

    @Test
    public void rejectsPresetInheritanceCyclesInsteadOfSilentlyUsingPartialValues() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"process\",\"name\":\"Cycle root\",\"inherits\":\"Cycle A\",\"layer_height\":\"0.20\"}"),
                json("{\"type\":\"process\",\"name\":\"Cycle A\",\"inherits\":\"Cycle B\",\"wall_loops\":\"4\"}"),
                json("{\"type\":\"process\",\"name\":\"Cycle B\",\"inherits\":\"Cycle A\",\"top_shell_layers\":\"5\"}"),
                json("{\"type\":\"machine\",\"name\":\"Cycle machine\",\"printer_model\":\"Cycle machine\"}")
        };
        try {
            ProfileCatalog.importBambu(baseline, documents);
            Assert.fail("inheritance cycles must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("inheritance cycle"));
        }
    }

    @Test
    public void rejectsUnsupportedPresetTypesBeforeProjectingNativeSettings() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        byte[][] documents = {
                json("{\"type\":\"metadata\",\"name\":\"Untrusted metadata\",\"machine_max_feedrate_x\":\"999999\"}"),
                json("{\"type\":\"machine\",\"name\":\"One machine\",\"printer_model\":\"One machine\"}")
        };
        try {
            ProfileCatalog.importBambu(baseline, documents);
            Assert.fail("unsupported preset types must be rejected");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("Unsupported Bambu preset type"));
        }
    }

    private static byte[] json(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
