package com.mbaliga.alloy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes the minimum inspectable .gcode.3mf package used by Alloy Export. */
public final class GcodePackageWriter {
    private GcodePackageWriter() { }

    public static void write(MeshModel mesh, Slicer.Result result, OutputStream destination) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(destination, StandardCharsets.UTF_8)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"model\" ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/><Override PartName=\"/Metadata/plate_1.gcode\" ContentType=\"text/x.gcode\"/></Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rel0\" Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\" Target=\"/3D/3dmodel.model\"/></Relationships>");
            entry(zip, "3D/_rels/3dmodel.model.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"></Relationships>");
            entry(zip, "3D/3dmodel.model", modelXml(mesh));
            entry(zip, "Metadata/plate_1.gcode", result.gcode);
            entry(zip, "Metadata/plate_1.json", "{\"plate\":1,\"printer\":\"Bambu Lab A1 Mini\",\"source\":\"Alloy v1\",\"warning\":\"Validate before physical printing\"}");
            entry(zip, "Metadata/slice_info.config", "[print]\nsource=Alloy v1 offline slicer\n");
        }
    }

    private static String modelXml(MeshModel mesh) {
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources><object id=\"1\" type=\"model\"><mesh><vertices>");
        for (int i = 0; i < mesh.vertices.length; i += 3) {
            xml.append(String.format(Locale.US, "<vertex x=\"%.5f\" y=\"%.5f\" z=\"%.5f\"/>", mesh.vertices[i], mesh.vertices[i + 1], mesh.vertices[i + 2]));
        }
        xml.append("</vertices><triangles>");
        for (int i = 0; i < mesh.triangles.length; i += 3) {
            xml.append(String.format(Locale.US, "<triangle v1=\"%d\" v2=\"%d\" v3=\"%d\"/>", mesh.triangles[i], mesh.triangles[i + 1], mesh.triangles[i + 2]));
        }
        return xml.append("</triangles></mesh></object></resources><build><item objectid=\"1\"/></build></model>").toString();
    }

    private static void entry(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
