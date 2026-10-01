package com.mbaliga.alloy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Small, deterministic, phone-friendly parametric modeling kernel.
 *
 * This is deliberately an additive workbench rather than a claim of full CAD:
 * it creates printable primitives and assemblies, then hands them to the
 * normal Alloy mesh, repair, project and slicer gates. All dimensions and
 * tessellation counts are bounded before allocation.
 */
public final class ModelWorkbench {
    public enum Primitive {
        BOX("Box"), CHAMFERED_BOX("Chamfered box"), HOLLOW_BOX("Open-top enclosure"),
        CYLINDER("Cylinder"), SPHERE("Sphere"), WEDGE("Wedge"), TUBE("Tube");

        public final String label;
        Primitive(String label) { this.label = label; }

        public static Primitive parse(String value) {
            if (value == null) throw new IllegalArgumentException("Primitive is missing");
            String normalized = value.trim().toLowerCase(Locale.US);
            for (Primitive primitive : values())
                if (primitive.name().toLowerCase(Locale.US).equals(normalized)
                        || primitive.label.toLowerCase(Locale.US).equals(normalized)) return primitive;
            throw new IllegalArgumentException("Unknown primitive: " + value);
        }
    }

    private static final float MIN_MM = 0.5f;
    /**
     * The parametric kernel must be able to represent every currently
     * disclosed planning profile.  The active printer envelope is enforced by
     * the caller (and again by the slicer); the compatibility overload below
     * deliberately retains the A1 Mini default.
     */
    private static final float MAX_SUPPORTED_MM = 256f;
    private static final float DEFAULT_BED_MM = 180f;
    private static final int RING_SEGMENTS = 32;
    private static final int SPHERE_RINGS = 16;
    private static final int MAX_TRIANGLES = 10_000;

    private ModelWorkbench() { }

    public static MeshModel create(Primitive primitive, String name,
                                   float width, float depth, float height) throws IOException {
        if (primitive == null) throw new IOException("Primitive is missing");
        checkDimension(width, "width");
        checkDimension(depth, "depth");
        checkDimension(height, "height");
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        switch (primitive) {
            case BOX: addBox(vertices, triangles, width, depth, height); break;
            case CHAMFERED_BOX:
                addChamferedBoxAt(vertices, triangles, width, depth, height,
                        defaultBevel(width, depth), 0f, 0f, 0f);
                break;
            case HOLLOW_BOX:
                addHollowBox(vertices, triangles, width, depth, height,
                        defaultWall(width, depth), defaultBottom(height));
                break;
            case CYLINDER: addCylinder(vertices, triangles, width / 2f, depth / 2f, height, RING_SEGMENTS); break;
            case SPHERE: addSphere(vertices, triangles, width / 2f, depth / 2f, height / 2f, RING_SEGMENTS, SPHERE_RINGS); break;
            case WEDGE: addWedge(vertices, triangles, width, depth, height); break;
            case TUBE:
                float outerX = width / 2f, outerY = depth / 2f;
                float inner = Math.min(outerX, outerY) * 0.55f;
                addTube(vertices, triangles, outerX, outerY, inner, height, RING_SEGMENTS);
                break;
            default: throw new IOException("Primitive is unsupported");
        }
        if (triangles.size() / 3 > MAX_TRIANGLES) throw new IOException("Generated primitive is too detailed");
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        parts.add(new MeshModel.Part(safeName(name, primitive.label), 0, triangles.size() / 3));
        return MeshModel.generated(safeName(name, primitive.label), vertices, triangles, parts);
    }

    /**
     * Create a printable chamfered enclosure with an explicit corner bevel.
     * The bevel is bounded so the ring cannot self-overlap or collapse.
     */
    public static MeshModel createChamferedBox(String name, float width, float depth,
                                               float height, float bevel) throws IOException {
        checkDimension(width, "width");
        checkDimension(depth, "depth");
        checkDimension(height, "height");
        checkBevel(bevel, width, depth);
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        addChamferedBoxAt(vertices, triangles, width, depth, height, bevel, 0f, 0f, 0f);
        if (triangles.size() / 3 > MAX_TRIANGLES) throw new IOException("Generated primitive is too detailed");
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        parts.add(new MeshModel.Part(safeName(name, Primitive.CHAMFERED_BOX.label), 0, triangles.size() / 3));
        return MeshModel.generated(safeName(name, Primitive.CHAMFERED_BOX.label), vertices, triangles, parts);
    }

    /**
     * Create a watertight open-top enclosure with explicit wall and floor
     * thickness. The inner cavity, top rim, outer walls and floor are all
     * represented in one indexed solid so it remains printable and editable.
     */
    public static MeshModel createHollowBox(String name, float width, float depth,
                                             float height, float wall, float bottom) throws IOException {
        checkDimension(width, "width");
        checkDimension(depth, "depth");
        checkDimension(height, "height");
        checkWallAndBottom(wall, bottom, width, depth, height);
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        addHollowBox(vertices, triangles, width, depth, height, wall, bottom);
        if (triangles.size() / 3 > MAX_TRIANGLES) throw new IOException("Generated primitive is too detailed");
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        parts.add(new MeshModel.Part(safeName(name, Primitive.HOLLOW_BOX.label), 0, triangles.size() / 3));
        return MeshModel.generated(safeName(name, Primitive.HOLLOW_BOX.label), vertices, triangles, parts);
    }

    /**
     * Make a bounded shelf-packed duplicate array from the current model.
     * The source geometry is not mutated; each duplicate remains a named part
     * in the resulting assembly and is checked against the selected build
     * envelope. The compatibility overload retains the A1 Mini envelope.
     */
    public static MeshModel createArray(String name, MeshModel source, int copies) throws IOException {
        return createArray(name, source, copies, DEFAULT_BED_MM, DEFAULT_BED_MM, DEFAULT_BED_MM);
    }

    /**
     * Make a bounded shelf-packed duplicate array for an explicit printer
     * envelope. Keeping this dimension-aware rather than widening the A1 Mini
     * default prevents profile selection from being silently ignored.
     */
    public static MeshModel createArray(String name, MeshModel source, int copies,
                                        float bedX, float bedY, float bedZ) throws IOException {
        if (source == null || source.vertices.length == 0 || source.triangles.length == 0)
            throw new IOException("Array source is empty");
        if (copies < 2 || copies > 32) throw new IOException("Array count must be between 2 and 32");
        checkEnvelope(bedX, bedY, bedZ);
        if (source.maxX - source.minX > bedX + 0.001f || source.maxY - source.minY > bedY + 0.001f
                || source.maxZ - source.minZ > bedZ + 0.001f)
            throw new IOException("Array source must fit the selected build volume");
        ArrayList<MeshModel> sources = new ArrayList<>();
        String[] labels = new String[copies];
        for (int index = 0; index < copies; index++) {
            sources.add(source);
            labels[index] = safeName(name, "Array") + " copy " + (index + 1);
        }
        MeshModel result = MeshModel.combine(safeName(name, "Array"), sources, labels, bedX);
        if (result.minX < -0.001f || result.minY < -0.001f || result.minZ < -0.001f
                || result.maxX > bedX + 0.001f || result.maxY > bedY + 0.001f || result.maxZ > bedZ + 0.001f)
            throw new IOException("Array copies do not fit the selected build volume");
        return result;
    }

    /**
     * Mirror a printable mesh across the centre of its X or Y footprint.
     * Reflection reverses triangle winding so the resulting solid keeps its
     * outward-facing normals and remains suitable for the normal repair and
     * native-slicing boundaries. The operation preserves named part ranges.
     */
    public static MeshModel mirror(String name, MeshModel source, boolean xAxis) throws IOException {
        if (source == null || source.vertices.length == 0 || source.triangles.length == 0)
            throw new IOException("Mirror source is empty");
        float centre = xAxis ? (source.minX + source.maxX) / 2f : (source.minY + source.maxY) / 2f;
        ArrayList<Float> vertices = new ArrayList<>(source.vertices.length);
        for (int index = 0; index < source.vertices.length; index += 3) {
            float x = source.vertices[index];
            float y = source.vertices[index + 1];
            if (xAxis) x = 2f * centre - x;
            else y = 2f * centre - y;
            vertices.add(x); vertices.add(y); vertices.add(source.vertices[index + 2]);
        }
        ArrayList<Integer> triangles = new ArrayList<>(source.triangles.length);
        for (int index = 0; index < source.triangles.length; index += 3) {
            triangles.add(source.triangles[index]);
            triangles.add(source.triangles[index + 2]);
            triangles.add(source.triangles[index + 1]);
        }
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        for (MeshModel.Part part : source.parts)
            parts.add(new MeshModel.Part(part.name, part.triangleStart, part.triangleCount));
        return MeshModel.generated(safeName(name, "Mirrored model"), vertices, triangles, parts);
    }

    /**
     * Create the owned, immediately inspectable showroom model used on a
     * fresh install. It is a printable two-part chamfered box/lid assembly,
     * not a placeholder vendor asset, and it still flows through the normal
     * import, repair, preparation and slicer gates.
     */
    public static MeshModel createShowcaseBoxAssembly() throws IOException {
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        int bodyStart = triangles.size() / 3;
        addChamferedBoxAt(vertices, triangles, 54f, 42f, 26f, 4f, 0f, 29f, 23f);
        parts.add(new MeshModel.Part("Showcase body", bodyStart,
                triangles.size() / 3 - bodyStart));
        int rimStart = triangles.size() / 3;
        addChamferedBoxAt(vertices, triangles, 49f, 37f, 1.4f, 2.4f, 26.3f, 29f, 23f);
        parts.add(new MeshModel.Part("Inner rim", rimStart,
                triangles.size() / 3 - rimStart));
        int lidStart = triangles.size() / 3;
        addChamferedBoxAt(vertices, triangles, 58f, 46f, 6f, 3.5f, 29f, 29f, 23f);
        parts.add(new MeshModel.Part("Showcase lid", lidStart,
                triangles.size() / 3 - lidStart));
        int hingeStart = triangles.size() / 3;
        addCylinderXAt(vertices, triangles, 10f, 12f, 44.6f, 27.7f, 2.1f, 2.1f, 18);
        addCylinderXAt(vertices, triangles, 36f, 12f, 44.6f, 27.7f, 2.1f, 2.1f, 18);
        parts.add(new MeshModel.Part("Hinge barrels", hingeStart,
                triangles.size() / 3 - hingeStart));
        int claspStart = triangles.size() / 3;
        // Keep the rounded clasp inside the 180 mm plate after its radius is
        // applied; the front edge of the body is at Y=2 mm.
        addCylinderAt(vertices, triangles, 2.8f, 2.8f, 3.2f, 29f, 3.0f, 26.0f, 24);
        parts.add(new MeshModel.Part("Front clasp", claspStart,
                triangles.size() / 3 - claspStart));
        int badgeStart = triangles.size() / 3;
        addChamferedBoxAt(vertices, triangles, 22f, 0.9f, 7f, 0.35f, 9.5f, 29f, 1.65f);
        parts.add(new MeshModel.Part("Front badge", badgeStart,
                triangles.size() / 3 - badgeStart));
        int inlayStart = triangles.size() / 3;
        addChamferedBoxAt(vertices, triangles, 34f, 2.2f, 0.75f, 0.45f, 35.2f, 29f, 23f);
        parts.add(new MeshModel.Part("Lid inlay", inlayStart,
                triangles.size() / 3 - inlayStart));
        return MeshModel.generated("Alloy showcase box", vertices, triangles, parts);
    }

    /**
     * Create a watertight prism from a bounded convex XY sketch. The Android
     * workbench intentionally starts with convex profiles so triangulation is
     * deterministic and cannot silently fill a concavity incorrectly.
     */
    public static MeshModel createExtrudedPolygon(String name, float[][] points, float height) throws IOException {
        if (points == null || points.length < 3 || points.length > 16)
            throw new IOException("Sketch needs between 3 and 16 points");
        checkDimension(height, "height");
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        double area = 0d;
        for (int i = 0; i < points.length; i++) {
            float[] point = points[i];
            if (point == null || point.length < 2 || Float.isNaN(point[0]) || Float.isInfinite(point[0])
                    || Float.isNaN(point[1]) || Float.isInfinite(point[1]))
                throw new IOException("Sketch contains an invalid point");
            if (Math.abs(point[0]) > MAX_SUPPORTED_MM / 2f || Math.abs(point[1]) > MAX_SUPPORTED_MM / 2f)
                throw new IOException("Sketch coordinates must stay within the build volume");
            float[] next = points[(i + 1) % points.length];
            if (next == null || next.length < 2 || Math.hypot(point[0] - next[0], point[1] - next[1]) < 0.001d)
                throw new IOException("Sketch contains duplicate or adjacent points");
            area += (double) point[0] * next[1] - (double) next[0] * point[1];
            minX = Math.min(minX, point[0]); maxX = Math.max(maxX, point[0]);
            minY = Math.min(minY, point[1]); maxY = Math.max(maxY, point[1]);
        }
        if (maxX - minX < MIN_MM || maxY - minY < MIN_MM || Math.abs(area) < 0.001d)
            throw new IOException("Sketch area is too small");
        float winding = area > 0d ? 1f : -1f;
        for (int i = 0; i < points.length; i++) {
            float[] a = points[i], b = points[(i + 1) % points.length], c = points[(i + 2) % points.length];
            float cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
            if (cross * winding <= 0.0001f)
                throw new IOException("Sketch must be convex and consistently wound");
        }
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        int[] bottom = new int[points.length], top = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            bottom[i] = vertex(vertices, points[i][0], points[i][1], 0f);
            top[i] = vertex(vertices, points[i][0], points[i][1], height);
        }
        for (int i = 1; i < points.length - 1; i++) {
            if (winding > 0f) {
                triangle(triangles, bottom[0], bottom[i + 1], bottom[i]);
                triangle(triangles, top[0], top[i], top[i + 1]);
            } else {
                triangle(triangles, bottom[0], bottom[i], bottom[i + 1]);
                triangle(triangles, top[0], top[i + 1], top[i]);
            }
        }
        for (int i = 0; i < points.length; i++) {
            int next = (i + 1) % points.length;
            if (winding > 0f) quad(triangles, bottom[i], bottom[next], top[next], top[i]);
            else quad(triangles, bottom[next], bottom[i], top[i], top[next]);
        }
        if (triangles.size() / 3 > MAX_TRIANGLES) throw new IOException("Generated sketch is too detailed");
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        parts.add(new MeshModel.Part(safeName(name, "Sketch extrusion"), 0, triangles.size() / 3));
        return MeshModel.generated(safeName(name, "Sketch extrusion"), vertices, triangles, parts);
    }

    /** Export only the validated indexed mesh into a standard binary STL. */
    public static byte[] toBinaryStl(MeshModel model) throws IOException {
        return toBinaryStl(model, MAX_TRIANGLES);
    }

    /**
     * Export a larger imported mesh for the native boolean bridge. The public
     * default remains deliberately small for generated parametric models.
     */
    public static byte[] toBinaryStl(MeshModel model, int maxTriangles) throws IOException {
        if (model == null || model.vertices.length == 0 || model.triangles.length == 0)
            throw new IOException("Generated model is empty");
        int triangleCount = model.triangles.length / 3;
        if (maxTriangles <= 0 || maxTriangles > 200_000 || triangleCount <= 0 || triangleCount > maxTriangles)
            throw new IOException("Generated model exceeds the STL limit");
        long length = 84L + triangleCount * 50L;
        if (length > 16L * 1024L * 1024L) throw new IOException("Generated STL is too large");
        ByteBuffer output = ByteBuffer.allocate((int) length).order(ByteOrder.LITTLE_ENDIAN);
        byte[] header = new byte[80];
        byte[] title = "Alloy parametric model".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, header, 0, Math.min(header.length, title.length));
        output.put(header).putInt(triangleCount);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int a = model.triangles[triangle * 3] * 3;
            int b = model.triangles[triangle * 3 + 1] * 3;
            int c = model.triangles[triangle * 3 + 2] * 3;
            float abx = model.vertices[b] - model.vertices[a];
            float aby = model.vertices[b + 1] - model.vertices[a + 1];
            float abz = model.vertices[b + 2] - model.vertices[a + 2];
            float acx = model.vertices[c] - model.vertices[a];
            float acy = model.vertices[c + 1] - model.vertices[a + 1];
            float acz = model.vertices[c + 2] - model.vertices[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float lengthNormal = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (lengthNormal > 0f) { nx /= lengthNormal; ny /= lengthNormal; nz /= lengthNormal; }
            output.putFloat(nx).putFloat(ny).putFloat(nz);
            output.putFloat(model.vertices[a]).putFloat(model.vertices[a + 1]).putFloat(model.vertices[a + 2]);
            output.putFloat(model.vertices[b]).putFloat(model.vertices[b + 1]).putFloat(model.vertices[b + 2]);
            output.putFloat(model.vertices[c]).putFloat(model.vertices[c + 1]).putFloat(model.vertices[c + 2]);
            output.putShort((short) 0);
        }
        return output.array();
    }

    private static void checkDimension(float value, String label) throws IOException {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < MIN_MM || value > MAX_SUPPORTED_MM)
            throw new IOException("Primitive " + label + " must be between " + MIN_MM + " and " + MAX_SUPPORTED_MM + " mm");
    }

    private static void checkEnvelope(float bedX, float bedY, float bedZ) throws IOException {
        checkDimension(bedX, "build width");
        checkDimension(bedY, "build depth");
        checkDimension(bedZ, "build height");
    }

    private static float defaultBevel(float width, float depth) {
        return Math.max(0.5f, Math.min(width, depth) * 0.12f);
    }

    private static float defaultWall(float width, float depth) {
        return Math.min(2f, Math.min(width, depth) * 0.12f);
    }

    private static float defaultBottom(float height) {
        return Math.min(2f, height * 0.12f);
    }

    private static void checkBevel(float bevel, float width, float depth) throws IOException {
        float maximum = Math.min(width, depth) * 0.24f;
        if (Float.isNaN(bevel) || Float.isInfinite(bevel) || bevel < 0.5f || bevel > maximum)
            throw new IOException("Chamfer must be between 0.5 and " + String.format(Locale.US, "%.2f", maximum) + " mm");
    }

    private static void checkWallAndBottom(float wall, float bottom,
                                           float width, float depth, float height) throws IOException {
        float maximumWall = Math.min(width, depth) / 2f - MIN_MM;
        float maximumBottom = height - MIN_MM;
        if (Float.isNaN(wall) || Float.isInfinite(wall) || wall < MIN_MM || wall > maximumWall)
            throw new IOException("Wall thickness must be between " + MIN_MM + " and "
                    + String.format(Locale.US, "%.2f", maximumWall) + " mm");
        if (Float.isNaN(bottom) || Float.isInfinite(bottom) || bottom < MIN_MM || bottom > maximumBottom)
            throw new IOException("Floor thickness must be between " + MIN_MM + " and "
                    + String.format(Locale.US, "%.2f", maximumBottom) + " mm");
    }

    private static String safeName(String name, String fallback) {
        String value = name == null ? "" : name.trim();
        if (value.length() == 0) value = fallback;
        value = value.replaceAll("[^A-Za-z0-9 ._+\u00b7-]", "_");
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    private static int vertex(ArrayList<Float> vertices, float x, float y, float z) {
        int index = vertices.size() / 3;
        vertices.add(x); vertices.add(y); vertices.add(z);
        return index;
    }

    private static void triangle(ArrayList<Integer> triangles, int a, int b, int c) {
        triangles.add(a); triangles.add(b); triangles.add(c);
    }

    private static void quad(ArrayList<Integer> triangles, int a, int b, int c, int d) {
        triangle(triangles, a, b, c); triangle(triangles, a, c, d);
    }

    private static void addBox(ArrayList<Float> v, ArrayList<Integer> t, float w, float d, float h) {
        int v0 = vertex(v, -w / 2f, -d / 2f, 0f);
        int v1 = vertex(v, w / 2f, -d / 2f, 0f);
        int v2 = vertex(v, w / 2f, d / 2f, 0f);
        int v3 = vertex(v, -w / 2f, d / 2f, 0f);
        int v4 = vertex(v, -w / 2f, -d / 2f, h);
        int v5 = vertex(v, w / 2f, -d / 2f, h);
        int v6 = vertex(v, w / 2f, d / 2f, h);
        int v7 = vertex(v, -w / 2f, d / 2f, h);
        quad(t, v0, v3, v2, v1); quad(t, v4, v5, v6, v7);
        quad(t, v0, v1, v5, v4); quad(t, v1, v2, v6, v5);
        quad(t, v2, v3, v7, v6); quad(t, v3, v0, v4, v7);
    }

    /** Add a single indexed open-top enclosure centered on XY and grounded at Z=0. */
    private static void addHollowBox(ArrayList<Float> v, ArrayList<Integer> t,
                                     float width, float depth, float height,
                                     float wall, float bottom) {
        float outerX = width / 2f, outerY = depth / 2f;
        float innerX = outerX - wall, innerY = outerY - wall;
        int[] outerBottom = new int[4], outerTop = new int[4];
        int[] innerFloor = new int[4], innerTop = new int[4];
        float[][] outer = {{-outerX, -outerY}, {outerX, -outerY},
                {outerX, outerY}, {-outerX, outerY}};
        float[][] inner = {{-innerX, -innerY}, {innerX, -innerY},
                {innerX, innerY}, {-innerX, innerY}};
        for (int i = 0; i < 4; i++) {
            outerBottom[i] = vertex(v, outer[i][0], outer[i][1], 0f);
            outerTop[i] = vertex(v, outer[i][0], outer[i][1], height);
            innerFloor[i] = vertex(v, inner[i][0], inner[i][1], bottom);
            innerTop[i] = vertex(v, inner[i][0], inner[i][1], height);
        }
        // Exterior bottom, outer walls, interior cavity walls, floor and rim.
        quad(t, outerBottom[0], outerBottom[3], outerBottom[2], outerBottom[1]);
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            quad(t, outerBottom[i], outerBottom[next], outerTop[next], outerTop[i]);
            quad(t, innerFloor[next], innerFloor[i], innerTop[i], innerTop[next]);
            quad(t, outerTop[i], outerTop[next], innerTop[next], innerTop[i]);
        }
        quad(t, innerFloor[0], innerFloor[1], innerFloor[2], innerFloor[3]);
    }

    private static void addChamferedBox(ArrayList<Float> v, ArrayList<Integer> t,
                                        float width, float depth, float height, float bevel, float z) {
        addChamferedBoxAt(v, t, width, depth, height, bevel, z, width / 2f, depth / 2f);
    }

    private static void addChamferedBoxAt(ArrayList<Float> v, ArrayList<Integer> t,
                                          float width, float depth, float height,
                                          float bevel, float z) {
        addChamferedBoxAt(v, t, width, depth, height, bevel, z, width / 2f, depth / 2f);
    }

    private static void addChamferedBoxAt(ArrayList<Float> v, ArrayList<Integer> t,
                                          float width, float depth, float height,
                                          float bevel, float z, float offsetX, float offsetY) {
        float b = Math.max(0.5f, Math.min(bevel, Math.min(width, depth) * 0.24f));
        float x0 = -width / 2f, x1 = width / 2f, y0 = -depth / 2f, y1 = depth / 2f;
        float[][] ring = {
                {x0 + b, y0}, {x1 - b, y0}, {x1, y0 + b}, {x1, y1 - b},
                {x1 - b, y1}, {x0 + b, y1}, {x0, y1 - b}, {x0, y0 + b}
        };
        int[] bottom = new int[ring.length], top = new int[ring.length];
        for (int i = 0; i < ring.length; i++) {
            bottom[i] = vertex(v, ring[i][0] + offsetX, ring[i][1] + offsetY, z);
            top[i] = vertex(v, ring[i][0] + offsetX, ring[i][1] + offsetY, z + height);
        }
        for (int i = 1; i < ring.length - 1; i++) {
            triangle(t, bottom[0], bottom[i + 1], bottom[i]);
            triangle(t, top[0], top[i], top[i + 1]);
        }
        for (int i = 0; i < ring.length; i++) {
            int next = (i + 1) % ring.length;
            quad(t, bottom[i], bottom[next], top[next], top[i]);
        }
    }

    /** Add a bounded vertical cylinder translated to a world-space location. */
    private static void addCylinderAt(ArrayList<Float> v, ArrayList<Integer> t,
                                      float rx, float ry, float height,
                                      float centerX, float centerY, float z,
                                      int segments) {
        int[] bottom = new int[segments], top = new int[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2d * i / segments;
            float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
            bottom[i] = vertex(v, centerX + x * rx, centerY + y * ry, z);
            top[i] = vertex(v, centerX + x * rx, centerY + y * ry, z + height);
        }
        int bottomCenter = vertex(v, centerX, centerY, z);
        int topCenter = vertex(v, centerX, centerY, z + height);
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            quad(t, bottom[i], bottom[next], top[next], top[i]);
            triangle(t, bottomCenter, bottom[next], bottom[i]);
            triangle(t, topCenter, top[i], top[next]);
        }
    }

    /** Add a bounded cylinder whose axis is X, useful for hinge barrels. */
    private static void addCylinderXAt(ArrayList<Float> v, ArrayList<Integer> t,
                                       float x, float length, float centerY, float centerZ,
                                       float radiusY, float radiusZ, int segments) {
        int[] left = new int[segments], right = new int[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2d * i / segments;
            float y = (float) Math.cos(angle), z = (float) Math.sin(angle);
            left[i] = vertex(v, x, centerY + y * radiusY, centerZ + z * radiusZ);
            right[i] = vertex(v, x + length, centerY + y * radiusY, centerZ + z * radiusZ);
        }
        int leftCenter = vertex(v, x, centerY, centerZ);
        int rightCenter = vertex(v, x + length, centerY, centerZ);
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            quad(t, left[i], left[next], right[next], right[i]);
            triangle(t, leftCenter, left[next], left[i]);
            triangle(t, rightCenter, right[i], right[next]);
        }
    }

    private static void addWedge(ArrayList<Float> v, ArrayList<Integer> t, float w, float d, float h) {
        int v0 = vertex(v, -w / 2f, -d / 2f, 0f);
        int v1 = vertex(v, w / 2f, -d / 2f, 0f);
        int v2 = vertex(v, w / 2f, d / 2f, 0f);
        int v3 = vertex(v, -w / 2f, d / 2f, 0f);
        int v4 = vertex(v, -w / 2f, -d / 2f, h * 0.20f);
        int v5 = vertex(v, w / 2f, -d / 2f, h * 0.20f);
        int v6 = vertex(v, w / 2f, d / 2f, h);
        int v7 = vertex(v, -w / 2f, d / 2f, h);
        quad(t, v0, v3, v2, v1); quad(t, v4, v5, v6, v7);
        quad(t, v0, v1, v5, v4); quad(t, v1, v2, v6, v5);
        quad(t, v2, v3, v7, v6); quad(t, v3, v0, v4, v7);
    }

    private static void addCylinder(ArrayList<Float> v, ArrayList<Integer> t,
                                    float rx, float ry, float h, int segments) {
        int[] bottom = new int[segments], top = new int[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2d * i / segments;
            float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
            bottom[i] = vertex(v, x * rx, y * ry, 0f);
            top[i] = vertex(v, x * rx, y * ry, h);
        }
        int bottomCenter = vertex(v, 0f, 0f, 0f), topCenter = vertex(v, 0f, 0f, h);
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            quad(t, bottom[i], bottom[next], top[next], top[i]);
            triangle(t, bottomCenter, bottom[next], bottom[i]);
            triangle(t, topCenter, top[i], top[next]);
        }
    }

    private static void addTube(ArrayList<Float> v, ArrayList<Integer> t,
                                float outerX, float outerY, float innerRadius, float h, int segments) {
        int[] outerBottom = new int[segments], outerTop = new int[segments];
        int[] innerBottom = new int[segments], innerTop = new int[segments];
        for (int i = 0; i < segments; i++) {
            double angle = Math.PI * 2d * i / segments;
            float x = (float) Math.cos(angle), y = (float) Math.sin(angle);
            outerBottom[i] = vertex(v, x * outerX, y * outerY, 0f);
            outerTop[i] = vertex(v, x * outerX, y * outerY, h);
            innerBottom[i] = vertex(v, x * innerRadius, y * innerRadius, 0f);
            innerTop[i] = vertex(v, x * innerRadius, y * innerRadius, h);
        }
        for (int i = 0; i < segments; i++) {
            int next = (i + 1) % segments;
            quad(t, outerBottom[i], outerBottom[next], outerTop[next], outerTop[i]);
            quad(t, innerTop[i], innerTop[next], innerBottom[next], innerBottom[i]);
            quad(t, outerTop[i], outerTop[next], innerTop[next], innerTop[i]);
            quad(t, innerBottom[i], innerBottom[next], outerBottom[next], outerBottom[i]);
        }
    }

    private static void addSphere(ArrayList<Float> v, ArrayList<Integer> t,
                                  float rx, float ry, float rz, int segments, int rings) {
        int[][] grid = new int[rings + 1][segments];
        for (int ring = 0; ring <= rings; ring++) {
            double latitude = -Math.PI / 2d + Math.PI * ring / rings;
            float z = (float) Math.sin(latitude) * rz;
            float radius = (float) Math.cos(latitude);
            for (int segment = 0; segment < segments; segment++) {
                double longitude = Math.PI * 2d * segment / segments;
                grid[ring][segment] = vertex(v, (float) Math.cos(longitude) * radius * rx,
                        (float) Math.sin(longitude) * radius * ry, z + rz);
            }
        }
        for (int ring = 0; ring < rings; ring++) {
            for (int segment = 0; segment < segments; segment++) {
                int next = (segment + 1) % segments;
                quad(t, grid[ring][segment], grid[ring][next], grid[ring + 1][next], grid[ring + 1][segment]);
            }
        }
    }
}
