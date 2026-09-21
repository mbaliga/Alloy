package com.mbaliga.alloy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the Bambu-shaped, inspectable .gcode.3mf package used by Alloy Export. */
public final class GcodePackageWriter {
    private static final int MAX_THUMBNAIL_BYTES = 1 * 1024 * 1024;

    private GcodePackageWriter() { }

    public static void write(MeshModel mesh, Slicer.Result result, Slicer.Config config, OutputStream destination) throws IOException {
        write(mesh, result, config, destination, null);
    }

    /**
     * Write an inspectable package and, when available, the same bounded
     * preview image under the two Bambu-recognized thumbnail names.
     * Thumbnail presence is optional because headless/native callers may not
     * have a UI surface from which to capture one.
     */
    public static void write(MeshModel mesh, Slicer.Result result, Slicer.Config config,
                             OutputStream destination, byte[] thumbnailPng) throws IOException {
        if (mesh == null || result == null || config == null) throw new IOException("Cannot package an incomplete slice");
        if (thumbnailPng != null && (!isPng(thumbnailPng) || thumbnailPng.length > MAX_THUMBNAIL_BYTES))
            throw new IOException("Package thumbnail is invalid");
        // The caller owns destination and is responsible for closing and
        // syncing it. Closing ZipOutputStream here would close that stream
        // before ArtifactStore can durably commit the staged artifact.
        ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8);
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/><Default Extension=\"json\" ContentType=\"application/json\"/><Default Extension=\"config\" ContentType=\"text/plain\"/><Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"gcode\" ContentType=\"text/x.gcode\"/></Types>");
            entry(zip, "_rels/.rels", rootRelationships(thumbnailPng != null));
            entry(zip, "3D/_rels/3dmodel.model.rels", modelRelationships());
            zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
            writePackageModelXml(mesh, zip);
            zip.closeEntry();
            // Bambu's package keeps the printable mesh in a related object
            // model. Keeping the wrapper and object separate makes the
            // artifact consumable by tooling that follows the relationship
            // instead of accepting Alloy's earlier inline-only form.
            zip.putNextEntry(new ZipEntry("3D/Objects/object_1.model"));
            writeObjectModelXml(mesh, zip);
            zip.closeEntry();
            entry(zip, "Metadata/plate_1.gcode", result.gcode);
            String source = result.engineId == null ? "alloy-unknown" : result.engineId;
            String warning = result.engineVerified ? "" : "Validate before physical printing";
            String supportWarning = SupportEngineStatus.exportWarning(config, result);
            if (supportWarning.length() > 0) warning = warning.length() == 0
                    ? supportWarning : warning + "; " + supportWarning;
            String supportEngine = config.supports ? source : "disabled";
            String templatePolicy = NativeSlicerEngine.isNativeEngineId(result.engineId)
                    ? A1MiniTemplatePolicy.ID : "";
            String meshHealth = mesh.geometryReport().summary();
            float width = Math.max(0f, mesh.maxX - mesh.minX);
            float depth = Math.max(0f, mesh.maxY - mesh.minY);
            String plateMetadata = "{\"bbox_all\":[" + coordinate(mesh.minX) + "," + coordinate(mesh.minY)
                    + "," + coordinate(mesh.maxX) + "," + coordinate(mesh.maxY) + "],\"bbox_objects\":[{\"area\":"
                    + coordinate(width * depth) + ",\"bbox\":[" + coordinate(mesh.minX) + "," + coordinate(mesh.minY)
                    + "," + coordinate(mesh.maxX) + "," + coordinate(mesh.maxY) + "],\"id\":1,\"layer_height\":"
                    + number(config.layerHeight) + ",\"name\":\"" + json(mesh.displayName) + "\"}],\"bed_type\":\"cool_plate\","
                    + "\"filament_colors\":[\"#FFFFFF\"],\"filament_ids\":[0],\"first_extruder\":0,\"nozzle_diameter\":"
                    + number(config.nozzle) + ",\"version\":2,\"plate\":1,\"printer\":\"" + json(config.printer)
                    + "\",\"material\":\"" + json(config.filament) + "\",\"source\":\"" + json(source)
                    + "\",\"engine_verified\":" + result.engineVerified + ",\"template_policy\":\"" + json(templatePolicy) + "\""
                    + ",\"support_engine\":\"" + json(supportEngine) + "\",\"support_parity_verified\":"
                    + SupportEngineStatus.physicalPrintReady(config, result)
                    + ",\"layer_height_mm\":" + number(config.layerHeight) + ",\"first_layer_height_mm\":" + number(config.firstLayerHeight)
                    + ",\"infill_percent\":" + number(config.infill * 100f) + ",\"perimeters\":" + config.perimeters
                    + ",\"top_layers\":" + config.topLayers + ",\"bottom_layers\":" + config.bottomLayers
                    + ",\"supports\":" + config.supports + ",\"support_threshold_degrees\":" + number(config.supportThresholdDegrees)
                    + ",\"nozzle_temperature_c\":" + number(config.nozzleTemperature)
                    + ",\"first_layer_nozzle_temperature_c\":" + number(config.firstLayerNozzleTemperature)
                    + ",\"bed_temperature_c\":" + number(config.bedTemperature)
                    + ",\"first_layer_bed_temperature_c\":" + number(config.firstLayerBedTemperature)
                    + ",\"extrusion_multiplier\":" + number(config.extrusionMultiplier)
                    + ",\"max_volumetric_speed_mm3_s\":" + number(config.maxVolumetricSpeed)
                    + ",\"print_time_seconds\":" + number(result.printTimeSeconds) + ",\"travel_mm\":" + number(result.travelMm)
                    + ",\"mesh_health\":\"" + json(meshHealth) + "\",\"warning\":\"" + json(warning) + "\"}";
            entry(zip, "Metadata/plate_1.json", plateMetadata);
            entry(zip, "Metadata/slice_info.config", sliceInfoConfig(mesh, result, config, thumbnailPng != null));
            entry(zip, "Metadata/plate_1.gcode.md5", md5(result.gcode));
            entry(zip, "Metadata/print_profile.config", printProfileConfig(config));
            entry(zip, "Metadata/project_settings.config", projectSettingsConfig(config));
            entry(zip, "Metadata/model_settings.config", modelSettingsConfig(mesh, thumbnailPng != null));
            entry(zip, "Metadata/_rels/model_settings.config.rels", relationships());
            entry(zip, "Metadata/cut_information.xml", cutInformation(mesh));
            entry(zip, "Metadata/filament_sequence.json", "{\"plate_1\":{\"sequence\":[1],\"nozzle_sequence\":[0],\"optimal_assignment\":[0]}}");
            if (thumbnailPng != null) {
                entry(zip, "Metadata/plate_1.png", thumbnailPng);
                entry(zip, "Metadata/plate_1_small.png", thumbnailPng);
                // One bounded preview is reused for the three Bambu view
                // roles. This is deterministic and does not claim that a
                // top/pick camera was separately rendered.
                entry(zip, "Metadata/plate_no_light_1.png", thumbnailPng);
                entry(zip, "Metadata/top_1.png", thumbnailPng);
                entry(zip, "Metadata/pick_1.png", thumbnailPng);
                entry(zip, "Metadata/bbl_thumbnail.png", thumbnailPng);
            }
        zip.finish();
        zip.flush();
    }

    private static String rootRelationships(boolean hasThumbnail) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\" Target=\"/3D/3dmodel.model\"/>");
        if (hasThumbnail) {
            out.append("<Relationship Target=\"/Metadata/plate_1.png\" Id=\"rel-2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/thumbnail\"/>");
            out.append("<Relationship Target=\"/Metadata/plate_1.png\" Id=\"rel-4\" Type=\"http://schemas.bambulab.com/package/2021/cover-thumbnail-middle\"/>");
            out.append("<Relationship Target=\"/Metadata/plate_1_small.png\" Id=\"rel-5\" Type=\"http://schemas.bambulab.com/package/2021/cover-thumbnail-small\"/>");
        }
        return out.append("</Relationships>").toString();
    }

    private static String modelRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Target=\"/3D/Objects/object_1.model\" Id=\"rel-1\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/></Relationships>";
    }

    private static String relationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>";
    }

    /** Bambu's legacy print profile entry is a comment-only config snapshot. */
    private static String printProfileConfig(Slicer.Config config) {
        return "; generated by Alloy\n"
                + "; printer = " + config.printer + "\n"
                + "; filament_type = " + config.filament + "\n"
                + "; layer_height = " + number(config.layerHeight) + "\n"
                + "; first_layer_height = " + number(config.firstLayerHeight) + "\n"
                + "; fill_density = " + number(config.infill * 100f) + "%\n"
                + "; perimeters = " + config.perimeters + "\n"
                + "; top_solid_layers = " + config.topLayers + "\n"
                + "; bottom_solid_layers = " + config.bottomLayers + "\n"
                + "; support_material = " + (config.supports ? "1" : "0") + "\n"
                + "; support_material_threshold = " + number(config.supportThresholdDegrees) + "\n"
                + "; nozzle_diameter = " + number(config.nozzle) + "\n"
                + "; filament_diameter = " + number(config.filamentDiameter) + "\n"
                + "; extrusion_multiplier = " + number(config.extrusionMultiplier) + "\n"
                + "; max_volumetric_speed = " + number(config.maxVolumetricSpeed) + "\n"
                + "; temperature = " + number(config.nozzleTemperature) + "\n"
                + "; first_layer_temperature = " + number(config.firstLayerNozzleTemperature) + "\n"
                + "; bed_temperature = " + number(config.bedTemperature) + "\n"
                + "; first_layer_bed_temperature = " + number(config.firstLayerBedTemperature) + "\n"
                + "; travel_speed = " + number(config.travelSpeed) + "\n"
                + "; external_perimeter_speed = " + number(config.outerWallSpeed) + "\n"
                + "; perimeter_speed = " + number(config.innerWallSpeed) + "\n"
                + "; infill_speed = " + number(config.infillSpeed) + "\n"
                + "; first_layer_speed = " + number(config.initialLayerSpeed) + "\n"
                + "; min_fan_speed = " + number(config.fanMinPercent) + "\n"
                + "; max_fan_speed = " + number(config.fanMaxPercent) + "\n";
    }

    /** Keep project settings at the JSON top level so Bambu can ignore unknown fields safely. */
    private static String projectSettingsConfig(Slicer.Config config) {
        return "{\"type\":\"project\",\"name\":\"Alloy\",\"from\":\"Project\",\"version\":\"1.0.0\","
                + "\"printer_model\":\"" + json(config.printer) + "\",\"filament_type\":\"" + json(config.filament) + "\","
                + "\"layer_height\":" + number(config.layerHeight) + ",\"first_layer_height\":" + number(config.firstLayerHeight)
                + ",\"fill_density\":\"" + number(config.infill * 100f) + "%\",\"perimeters\":" + config.perimeters
                + ",\"top_solid_layers\":" + config.topLayers + ",\"bottom_solid_layers\":" + config.bottomLayers
                + ",\"support_material\":" + (config.supports ? "true" : "false")
                + ",\"support_material_threshold\":" + number(config.supportThresholdDegrees)
                + ",\"nozzle_diameter\":" + number(config.nozzle) + ",\"filament_diameter\":" + number(config.filamentDiameter)
                + ",\"extrusion_multiplier\":" + number(config.extrusionMultiplier)
                + ",\"max_volumetric_speed\":" + number(config.maxVolumetricSpeed)
                + ",\"temperature\":" + number(config.nozzleTemperature)
                + ",\"first_layer_temperature\":" + number(config.firstLayerNozzleTemperature)
                + ",\"bed_temperature\":" + number(config.bedTemperature)
                + ",\"first_layer_bed_temperature\":" + number(config.firstLayerBedTemperature)
                + "}";
    }

    /** Canonical Bambu model-settings XML for the part/object table in this archive. */
    private static String modelSettingsConfig(MeshModel mesh, boolean hasThumbnail) {
        MeshModel.Part[] parts = mesh.parts == null || mesh.parts.length == 0
                ? new MeshModel.Part[]{new MeshModel.Part("Model", 0, mesh.triangles.length / 3)} : mesh.parts;
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><config>\n");
        out.append("  <object id=\"2\">\n");
        out.append("    <metadata key=\"name\" value=\"").append(xmlEscape(mesh.displayName)).append("\"/>\n");
        out.append("    <metadata key=\"extruder\" value=\"1\"/>\n");
        out.append("    <metadata face_count=\"").append(mesh.triangles.length / 3).append("\"/>\n");
        for (int index = 0; index < parts.length; index++) {
            MeshModel.Part part = parts[index];
            out.append("    <part id=\"").append(index + 1).append("\" subtype=\"normal_part\">\n");
            out.append("      <metadata key=\"name\" value=\"").append(xmlEscape(part.name)).append("\"/>\n");
            out.append("      <metadata key=\"source_object_id\" value=\"0\"/>\n");
            out.append("      <metadata key=\"source_volume_id\" value=\"").append(index).append("\"/>\n");
            out.append("      <mesh_stat face_count=\"").append(part.triangleCount)
                    .append("\" edges_fixed=\"0\" degenerate_facets=\"0\" facets_removed=\"0\" facets_reversed=\"0\" backwards_edges=\"0\"/>\n");
            out.append("    </part>\n");
        }
        out.append("  </object>\n");
        out.append("  <plate>\n");
        out.append("    <metadata key=\"plater_id\" value=\"1\"/>\n");
        out.append("    <metadata key=\"locked\" value=\"false\"/>\n");
        out.append("    <metadata key=\"filament_maps\" value=\"1\"/>\n");
        out.append("    <metadata key=\"gcode_file\" value=\"Metadata/plate_1.gcode\"/>\n");
        if (hasThumbnail) {
            out.append("    <metadata key=\"thumbnail_file\" value=\"Metadata/plate_1.png\"/>\n");
            out.append("    <metadata key=\"thumbnail_no_light_file\" value=\"Metadata/plate_no_light_1.png\"/>\n");
            out.append("    <metadata key=\"top_file\" value=\"Metadata/top_1.png\"/>\n");
            out.append("    <metadata key=\"pick_file\" value=\"Metadata/pick_1.png\"/>\n");
        }
        out.append("    <model_instance><metadata key=\"object_id\" value=\"2\"/><metadata key=\"instance_id\" value=\"0\"/><metadata key=\"identify_id\" value=\"1\"/></model_instance>\n");
        return out.append("  </plate>\n  <assemble>\n  </assemble>\n</config>\n").toString();
    }

    private static String cutInformation(MeshModel mesh) {
        MeshModel.Part[] parts = mesh.parts == null || mesh.parts.length == 0
                ? new MeshModel.Part[]{new MeshModel.Part("Model", 0, mesh.triangles.length / 3)} : mesh.parts;
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><objects>\n");
        for (int index = 0; index < parts.length; index++)
            out.append(" <object id=\"").append(index + 1)
                    .append("\"><cut_id id=\"0\" check_sum=\"1\" connectors_cnt=\"0\"/></object>\n");
        return out.append("</objects>\n").toString();
    }

    /** Canonical Bambu slice-info XML with one independently printable plate. */
    private static String sliceInfoConfig(MeshModel mesh, Slicer.Result result, Slicer.Config config, boolean hasThumbnail) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n");
        out.append("  <header>\n");
        out.append("    <header_item key=\"X-BBL-Client-Type\" value=\"slicer\"/>\n");
        out.append("    <header_item key=\"X-BBL-Client-Version\" value=\"Alloy 1.0\"/>\n");
        out.append("  </header>\n  <plate>\n");
        xmlMetadata(out, "index", "1");
        xmlMetadata(out, "extruder_type", "");
        xmlMetadata(out, "nozzle_volume_type", "");
        xmlMetadata(out, "printer_model_id", config.printer);
        xmlMetadata(out, "nozzle_diameters", number(config.nozzle));
        xmlMetadata(out, "timelapse_type", "0");
        xmlMetadata(out, "prediction", number(result.printTimeSeconds));
        xmlMetadata(out, "weight", "");
        xmlMetadata(out, "pause_count", "0");
        xmlMetadata(out, "first_layer_time", "0");
        xmlMetadata(out, "outside", "false");
        xmlMetadata(out, "support_used", Boolean.toString(config.supports));
        xmlMetadata(out, "support_engine", config.supports
                ? (result.engineId == null ? "alloy-unknown" : result.engineId) : "disabled");
        xmlMetadata(out, "support_parity_verified",
                Boolean.toString(SupportEngineStatus.physicalPrintReady(config, result)));
        xmlMetadata(out, "label_object_enabled", "false");
        xmlMetadata(out, "gcode_file", "Metadata/plate_1.gcode");
        if (hasThumbnail) {
            xmlMetadata(out, "thumbnail_file", "Metadata/plate_1.png");
            xmlMetadata(out, "thumbnail_no_light_file", "Metadata/plate_no_light_1.png");
            xmlMetadata(out, "top_file", "Metadata/top_1.png");
            xmlMetadata(out, "pick_file", "Metadata/pick_1.png");
        }
        return out.append("  </plate>\n</config>\n").toString();
    }

    private static void xmlMetadata(StringBuilder out, String key, String value) {
        out.append("    <metadata key=\"").append(xmlEscape(key)).append("\" value=\"")
                .append(xmlEscape(value)).append("\"/>\n");
    }

    private static String md5(String value) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte item : digest) out.append(String.format(Locale.US, "%02X", item & 0xff));
            return out.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("MD5 is unavailable", error);
        }
    }

    /** Write the Bambu-compatible wrapper that points to the related mesh object. */
    private static void writePackageModelXml(MeshModel mesh, OutputStream destination) throws IOException {
        Writer xml = new OutputStreamWriter(destination, StandardCharsets.UTF_8);
        xml.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\" xmlns:p=\"http://schemas.microsoft.com/3dmanufacturing/production/2015/06\" requiredextensions=\"p\"><metadata name=\"Application\">Alloy-1.0</metadata><metadata name=\"BambuStudio:3mfVersion\">1</metadata><resources><object id=\"2\" type=\"model\"><components><component p:path=\"/3D/Objects/object_1.model\" objectid=\"1\" transform=\"1 0 0 0 1 0 0 0 1 0 0 0\"/></components></object></resources><build><item objectid=\"2\" printable=\"1\" transform=\"1 0 0 0 1 0 0 0 1 0 0 0\"/></build></model>");
        xml.flush();
    }

    /** Stream the mesh object so a large imported mesh does not create another full-size heap copy. */
    private static void writeObjectModelXml(MeshModel mesh, OutputStream destination) throws IOException {
        MeshModel.Part[] parts = mesh.parts == null || mesh.parts.length == 0
                ? new MeshModel.Part[]{new MeshModel.Part("Model", 0, mesh.triangles.length / 3)} : mesh.parts;
        Writer xml = new OutputStreamWriter(destination, StandardCharsets.UTF_8);
        xml.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources><object id=\"1\" type=\"model\" name=\"");
        xml.write(xmlEscape(mesh.displayName)); xml.write("\"><mesh><vertices>");
        Map<Integer, Integer> localVertices = new HashMap<>();
        // 3MF requires vertices before triangles. Discover and stream the
        // unique vertices in one pass, then stream triangle references in a
        // second pass without retaining XML text for the whole mesh.
        for (MeshModel.Part part : parts) {
            for (int triangle = part.triangleStart; triangle < part.triangleStart + part.triangleCount; triangle++) {
                int triangleOffset = triangle * 3;
                localIndex(mesh, mesh.triangles[triangleOffset], localVertices, xml);
                localIndex(mesh, mesh.triangles[triangleOffset + 1], localVertices, xml);
                localIndex(mesh, mesh.triangles[triangleOffset + 2], localVertices, xml);
            }
        }
        xml.write("</vertices><triangles>");
        for (MeshModel.Part part : parts) {
            for (int triangle = part.triangleStart; triangle < part.triangleStart + part.triangleCount; triangle++) {
                int triangleOffset = triangle * 3;
                Integer first = localVertices.get(mesh.triangles[triangleOffset]);
                Integer second = localVertices.get(mesh.triangles[triangleOffset + 1]);
                Integer third = localVertices.get(mesh.triangles[triangleOffset + 2]);
                if (first == null || second == null || third == null)
                    throw new IOException("Mesh vertex map changed while exporting");
                xml.write(String.format(Locale.US, "<triangle v1=\"%d\" v2=\"%d\" v3=\"%d\"/>", first, second, third));
            }
        }
        xml.write("</triangles></mesh></object></resources><build/></model>");
        xml.flush();
    }

    private static int localIndex(MeshModel mesh, int globalIndex, Map<Integer, Integer> localVertices, Writer xml) throws IOException {
        Integer existing = localVertices.get(globalIndex);
        if (existing != null) return existing;
        int local = localVertices.size();
        int offset = globalIndex * 3;
        xml.write(String.format(Locale.US, "<vertex x=\"%.5f\" y=\"%.5f\" z=\"%.5f\"/>",
                mesh.vertices[offset], mesh.vertices[offset + 1], mesh.vertices[offset + 2]));
        localVertices.put(globalIndex, local);
        return local;
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void entry(ZipOutputStream zip, String name, byte[] value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value);
        zip.closeEntry();
    }

    private static boolean isPng(byte[] value) {
        return value != null && value.length >= 8
                && (value[0] & 0xff) == 0x89 && value[1] == 0x50 && value[2] == 0x4e && value[3] == 0x47
                && value[4] == 0x0d && value[5] == 0x0a && value[6] == 0x1a && value[7] == 0x0a;
    }

    private static String json(String value) {
        return (value == null ? "" : value).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String xmlEscape(String value) {
        return (value == null ? "" : value).replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("'", "&apos;");
    }

    private static String number(float value) {
        return Float.isFinite(value) && value >= 0f ? String.format(Locale.US, "%.2f", value) : "null";
    }

    private static String coordinate(float value) {
        return Float.isFinite(value) ? String.format(Locale.US, "%.5f", value) : "null";
    }
}
