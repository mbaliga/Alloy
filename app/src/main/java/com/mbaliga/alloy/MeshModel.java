package com.mbaliga.alloy;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Small, dependency-free STL, OBJ and 3MF mesh reader; STEP is tessellated natively before this boundary. */
public final class MeshModel {
    /** Bounds for the uniform plate-level scale applied to unitless STL/OBJ sources. */
    public static final float MIN_MODEL_SCALE = 0.1f;
    public static final float MAX_MODEL_SCALE = 100f;
    private static final long MAX_INPUT_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_3MF_ENTRY_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_VERTICES = 6_000_000;
    private static final int MAX_TRIANGLES = 2_000_000;
    private static final int MAX_3MF_MODEL_ENTRIES = 64;
    private static final int MAX_3MF_ENTRIES = 4096;
    private static final int MAX_3MF_RELATIONSHIPS_BYTES = 4 * 1024 * 1024;
    private static final int MAX_3MF_MODEL_CONFIG_BYTES = 16 * 1024 * 1024;
    private static final int MAX_3MF_VOLUME_RANGES = 4096;
    private static final int MAX_STL_LINE_CHARS = 1 * 1024 * 1024;
    private static final int MAX_OBJ_LINE_CHARS = 1 * 1024 * 1024;
    private static final int MAX_OBJ_FACE_VERTICES = 100_000;
    private static final int MAX_PROJECT_MODELS = 32;
    private static final float DEFAULT_REPAIR_EPSILON_MM = 0.0001f;

    public final float[] vertices;
    public final int[] triangles;
    /** Optional renderer-only normals supplied by a trusted visual asset. */
    public final float[] displayNormals;
    public final String displayName;
    public final Part[] parts;
    public final float minX, maxX, minY, maxY, minZ, maxZ;
    private volatile GeometryReport cachedGeometryReport;

    private MeshModel(float[] vertices, int[] triangles, String displayName, Part[] parts) {
        this(vertices, triangles, displayName, parts, null);
    }

    private MeshModel(float[] vertices, int[] triangles, String displayName, Part[] parts,
                      float[] displayNormals) {
        this.vertices = vertices;
        this.triangles = triangles;
        this.displayNormals = displayNormals;
        this.displayName = displayName;
        this.parts = parts;
        float x0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        float z0 = Float.POSITIVE_INFINITY, z1 = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < vertices.length; i += 3) {
            x0 = Math.min(x0, vertices[i]); x1 = Math.max(x1, vertices[i]);
            y0 = Math.min(y0, vertices[i + 1]); y1 = Math.max(y1, vertices[i + 1]);
            z0 = Math.min(z0, vertices[i + 2]); z1 = Math.max(z1, vertices[i + 2]);
        }
        minX = x0; maxX = x1; minY = y0; maxY = y1; minZ = z0; maxZ = z1;
    }

    public static MeshModel read(String name, InputStream source) throws IOException {
        if (name == null || name.trim().length() == 0) throw new IOException("Model name is missing");
        if (source == null) throw new IOException("Model source is missing");
        byte[] data = readAll(source);
        String lower = name.trim().toLowerCase(Locale.US);
        if (lower.endsWith(".3mf")) return read3mf(name, data);
        if (lower.endsWith(".stl")) return readStl(name, data);
        if (lower.endsWith(".obj")) return readObj(name, data);

        // Document providers may omit DISPLAY_NAME or return a generic name
        // such as "download". A bounded signature fallback keeps a lawful
        // community model openable from the phone without renaming it first.
        // ZIP data is sent to the 3MF reader; everything else is attempted as
        // STL and fails closed if it contains no complete mesh.
        if (isZip(data)) return read3mf(name + ".3mf", data);
        if (isLikelyObj(data)) return readObj(name + ".obj", data);
        return readStl(name + ".stl", data);
    }

    private static boolean isZip(byte[] data) {
        return data != null && data.length >= 4
                && data[0] == 'P' && data[1] == 'K'
                && ((data[2] == 3 && data[3] == 4)
                || (data[2] == 5 && data[3] == 6)
                || (data[2] == 7 && data[3] == 8));
    }

    private static boolean isLikelyObj(byte[] data) {
        if (data == null || data.length == 0) return false;
        int length = Math.min(data.length, 64 * 1024);
        String sample = new String(data, 0, length, StandardCharsets.UTF_8);
        boolean vertex = false;
        boolean face = false;
        for (String line : sample.split("\\r?\\n")) {
            String value = line.trim();
            if (value.startsWith("v ") || value.startsWith("v\t")) vertex = true;
            if (value.startsWith("f ") || value.startsWith("f\t")) face = true;
            if (vertex && face) return true;
        }
        return false;
    }

    /** Combine separately imported meshes into one lightly packed plate model while retaining part ranges. */
    public static MeshModel combine(String name, ArrayList<MeshModel> sources) throws IOException {
        return combine(name, sources, null);
    }

    /** Combine meshes with optional per-source labels for inspectable arrays and assemblies. */
    public static MeshModel combine(String name, ArrayList<MeshModel> sources,
                                    String[] sourceLabels) throws IOException {
        if (sources == null || sources.isEmpty()) throw new IOException("No models were selected");
        if (sources.size() > MAX_PROJECT_MODELS) throw new IOException("A project may contain at most " + MAX_PROJECT_MODELS + " models");
        if (sourceLabels != null && sourceLabels.length != sources.size())
            throw new IOException("Combined source labels do not match the model count");
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        ArrayList<Part> parts = new ArrayList<>();
        float cursorX = 0f, cursorY = 0f, rowDepth = 0f;
        for (int sourceIndex = 0; sourceIndex < sources.size(); sourceIndex++) {
            MeshModel source = sources.get(sourceIndex);
            if (source == null || source.vertices.length == 0 || source.triangles.length == 0)
                throw new IOException("One selected model is empty");
            if (vertices.size() / 3 > MAX_VERTICES - source.vertices.length / 3)
                throw new IOException("Combined project exceeds the supported vertex count");
            if (triangles.size() > MAX_TRIANGLES * 3 - source.triangles.length)
                throw new IOException("Combined project exceeds the supported triangle count");
            int vertexBase = vertices.size() / 3;
            int triangleBase = triangles.size() / 3;
            float width = source.maxX - source.minX;
            float depth = source.maxY - source.minY;
            if (cursorX > 0f && cursorX + width > 180f) {
                cursorX = 0f;
                cursorY += rowDepth + 5f;
                rowDepth = 0f;
            }
            float offsetX = cursorX - source.minX;
            float offsetY = cursorY - source.minY;
            for (int index = 0; index < source.vertices.length; index += 3) {
                vertices.add(source.vertices[index] + offsetX);
                vertices.add(source.vertices[index + 1] + offsetY);
                vertices.add(source.vertices[index + 2]);
            }
            for (int index : source.triangles) triangles.add(vertexBase + index);
            String sourceLabel = sourceLabels == null ? source.displayName : sourceLabels[sourceIndex];
            if (sourceLabel == null || sourceLabel.trim().length() == 0)
                throw new IOException("Combined source label is empty");
            for (Part part : source.parts) {
                String partName = source.parts.length == 1 || sourceLabel.equals(part.name)
                        ? sourceLabel : sourceLabel + " · " + part.name;
                parts.add(new Part(partName, triangleBase + part.triangleStart, part.triangleCount));
            }
            cursorX += width + 5f;
            rowDepth = Math.max(rowDepth, depth);
        }
        return create(name, vertices, triangles, parts);
    }

    private static MeshModel readStl(String name, byte[] data) throws IOException {
        if (data.length >= 84) {
            long count = Integer.toUnsignedLong(ByteBuffer.wrap(data, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (count > 0 && count <= 2_000_000 && 84L + count * 50L <= data.length) {
                ArrayList<Float> vs = new ArrayList<>();
                ArrayList<Integer> ts = new ArrayList<>();
                ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
                b.position(84);
                for (int i = 0; i < count; i++) {
                    b.position(b.position() + 12);
                    int base = vs.size() / 3;
                    for (int j = 0; j < 3; j++) {
                        vs.add(b.getFloat()); vs.add(b.getFloat()); vs.add(b.getFloat());
                        ts.add(base + j);
                    }
                    b.position(b.position() + 2);
                }
                return createStl(name, vs, ts);
            }
        }
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Integer> ts = new ArrayList<>();
        int lineStart = 0;
        int lineNumber = 0;
        for (int index = 0; index <= data.length; index++) {
            if (index < data.length && data[index] != '\n') continue;
            int lineEnd = index;
            if (lineEnd > lineStart && data[lineEnd - 1] == '\r') lineEnd--;
            if (lineEnd - lineStart > MAX_STL_LINE_CHARS)
                throw new IOException("STL line " + (lineNumber + 1) + " exceeds the 1 MB limit");
            String s = new String(data, lineStart, lineEnd - lineStart, StandardCharsets.UTF_8).trim();
            lineNumber++;
            if (s.toLowerCase(Locale.US).startsWith("vertex ")) {
                String[] p = s.split("\\s+");
                if (p.length >= 4) {
                    try {
                        float x = Float.parseFloat(p[1]);
                        float y = Float.parseFloat(p[2]);
                        float z = Float.parseFloat(p[3]);
                        if (!finite(x) || !finite(y) || !finite(z)) throw new NumberFormatException("non-finite");
                        vs.add(x); vs.add(y); vs.add(z);
                    } catch (NumberFormatException error) {
                        throw new IOException("STL vertex is invalid at line " + lineNumber, error);
                    }
                    if (vs.size() > MAX_VERTICES * 3) throw new IOException("STL exceeds the supported mesh size");
                    ts.add(vs.size() / 3 - 1);
                }
            }
            lineStart = index + 1;
        }
        if (ts.size() < 3 || ts.size() % 3 != 0) throw new IOException("STL contains no complete triangles");
        return createStl(name, vs, ts);
    }

    private static MeshModel readObj(String name, byte[] data) throws IOException {
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        ArrayList<Part> parts = new ArrayList<>();
        String partName = name;
        String objectName = name;
        int partTriangleStart = 0;
        boolean namedPart = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.ByteArrayInputStream(data), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.length() > MAX_OBJ_LINE_CHARS)
                    throw new IOException("OBJ line " + lineNumber + " exceeds the 1 MB limit");
                String value = line.trim();
                if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
                if (value.length() == 0 || value.startsWith("#")) continue;
                int separator = firstWhitespace(value);
                String directive = separator < 0 ? value : value.substring(0, separator);
                String arguments = separator < 0 ? "" : value.substring(separator).trim();
                if ("v".equals(directive)) {
                    String[] fields = arguments.split("\\s+");
                    if (fields.length < 3) throw new IOException("OBJ vertex is incomplete at line " + lineNumber);
                    float x = objFloat(fields[0], "vertex", lineNumber);
                    float y = objFloat(fields[1], "vertex", lineNumber);
                    float z = objFloat(fields[2], "vertex", lineNumber);
                    if (vertices.size() / 3 >= MAX_VERTICES)
                        throw new IOException("OBJ exceeds the supported vertex count");
                    vertices.add(x); vertices.add(y); vertices.add(z);
                } else if ("o".equals(directive) || "g".equals(directive)) {
                    if (triangles.size() / 3 > partTriangleStart)
                        parts.add(new Part(partName, partTriangleStart, triangles.size() / 3 - partTriangleStart));
                    objectName = objPartName(arguments, name, parts.size() + 1);
                    partName = objectName;
                    partTriangleStart = triangles.size() / 3;
                    namedPart = true;
                } else if ("usemtl".equals(directive)) {
                    // Material groups are useful presentation parts even
                    // when the provider supplies only the OBJ. Preserve the
                    // boundary without trying to dereference an external
                    // MTL/texture path (which would be unsafe and nonportable
                    // through Android document providers).
                    if (triangles.size() / 3 > partTriangleStart)
                        parts.add(new Part(partName, partTriangleStart, triangles.size() / 3 - partTriangleStart));
                    String material = objPartName(arguments, name, parts.size() + 1);
                    partName = objPartName(objectName + " · " + material, name, parts.size() + 1);
                    partTriangleStart = triangles.size() / 3;
                    namedPart = true;
                } else if ("f".equals(directive)) {
                    String[] fields = arguments.split("\\s+");
                    if (fields.length < 3) throw new IOException("OBJ face is incomplete at line " + lineNumber);
                    if (fields.length - 1 > MAX_OBJ_FACE_VERTICES)
                        throw new IOException("OBJ face has too many vertices");
                    int[] face = new int[fields.length];
                    for (int index = 0; index < fields.length; index++)
                        face[index] = objVertexIndex(fields[index], vertices.size() / 3, lineNumber);
                    for (int index = 1; index + 1 < fields.length; index++) {
                        if (triangles.size() / 3 >= MAX_TRIANGLES)
                            throw new IOException("OBJ exceeds the supported triangle count");
                        triangles.add(face[0]);
                        triangles.add(face[index]);
                        triangles.add(face[index + 1]);
                    }
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Could not read OBJ model: " + error.getMessage(), error);
        }
        if (triangles.size() < 3) throw new IOException("OBJ contains no complete faces");
        if (triangles.size() / 3 > partTriangleStart)
            parts.add(new Part(partName, partTriangleStart, triangles.size() / 3 - partTriangleStart));
        if (parts.isEmpty() || !namedPart) {
            parts.clear();
            parts.add(new Part(name, 0, triangles.size() / 3));
        }
        return create(name, vertices, triangles, parts);
    }

    private static int firstWhitespace(String value) {
        for (int index = 0; index < value.length(); index++)
            if (Character.isWhitespace(value.charAt(index))) return index;
        return -1;
    }

    private static float objFloat(String value, String label, int lineNumber) throws IOException {
        try {
            float parsed = Float.parseFloat(value);
            if (!finite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("OBJ " + label + " is invalid at line " + lineNumber, error);
        }
    }

    private static int objVertexIndex(String value, int vertexCount, int lineNumber) throws IOException {
        int slash = value.indexOf('/');
        String indexValue = slash < 0 ? value : value.substring(0, slash);
        if (indexValue.length() == 0) throw new IOException("OBJ face has no vertex index at line " + lineNumber);
        try {
            int index = Integer.parseInt(indexValue);
            if (index == 0) throw new NumberFormatException("zero");
            int resolved = index > 0 ? index - 1 : vertexCount + index;
            if (resolved < 0 || resolved >= vertexCount)
                throw new IOException("OBJ face references a missing vertex at line " + lineNumber);
            return resolved;
        } catch (NumberFormatException error) {
            throw new IOException("OBJ face has an invalid vertex index at line " + lineNumber, error);
        }
    }

    private static String objPartName(String value, String fallback, int ordinal) {
        String label = value == null ? "" : value.trim();
        if (label.length() == 0) label = fallback + " · part " + ordinal;
        return label.length() > 120 ? label.substring(0, 120) : label;
    }

    /**
     * STL has no object table, but many exporters write several disconnected
     * watertight solids into one file. Keep the normal path cheap for large
     * files while identifying the small/medium assembly files users commonly
     * inspect on a phone.
     */
    private static MeshModel createStl(String name, ArrayList<Float> vertices, ArrayList<Integer> triangles) throws IOException {
        ArrayList<Part> parts = inferStlParts(name, vertices, triangles);
        return create(name, vertices, triangles, parts);
    }

    private static ArrayList<Part> inferStlParts(String name, ArrayList<Float> vertices, ArrayList<Integer> triangles) {
        ArrayList<Part> one = new ArrayList<>();
        one.add(new Part(name, 0, triangles.size() / 3));
        int triangleCount = triangles.size() / 3;
        if (triangleCount <= 1 || triangleCount > 250_000) return one;

        DisjointSet sets = new DisjointSet(triangleCount);
        HashMap<VertexKey, Integer> owners = new HashMap<>();
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            for (int corner = 0; corner < 3; corner++) {
                int vertex = triangles.get(triangle * 3 + corner);
                int offset = vertex * 3;
                VertexKey key = new VertexKey(vertices.get(offset), vertices.get(offset + 1), vertices.get(offset + 2));
                Integer owner = owners.putIfAbsent(key, triangle);
                if (owner != null) sets.union(owner, triangle);
            }
        }

        LinkedHashMap<Integer, ArrayList<Integer>> groups = new LinkedHashMap<>();
        for (int triangle = 0; triangle < triangleCount; triangle++)
            groups.computeIfAbsent(sets.find(triangle), ignored -> new ArrayList<>()).add(triangle);
        if (groups.size() <= 1) return one;

        ArrayList<Integer> reordered = new ArrayList<>(triangles.size());
        ArrayList<Part> result = new ArrayList<>();
        String lowerName = name == null ? "" : name.toLowerCase(Locale.US);
        int groupIndex = 0;
        for (ArrayList<Integer> group : groups.values()) {
            int triangleStart = reordered.size() / 3;
            for (int triangle : group) {
                reordered.add(triangles.get(triangle * 3));
                reordered.add(triangles.get(triangle * 3 + 1));
                reordered.add(triangles.get(triangle * 3 + 2));
            }
            String label;
            if (groups.size() == 2 && lowerName.contains("box") && lowerName.contains("lid"))
                label = groupIndex == 0 ? "Box" : "Lid";
            else
                label = "Solid " + (groupIndex + 1);
            result.add(new Part(label, triangleStart, group.size()));
            groupIndex++;
        }
        triangles.clear();
        triangles.addAll(reordered);
        return result;
    }

    private static final class DisjointSet {
        private final int[] parent;
        private final byte[] rank;

        DisjointSet(int size) {
            parent = new int[size];
            rank = new byte[size];
            for (int index = 0; index < size; index++) parent[index] = index;
        }

        int find(int value) {
            int root = value;
            while (parent[root] != root) root = parent[root];
            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }
            return root;
        }

        void union(int first, int second) {
            int a = find(first), b = find(second);
            if (a == b) return;
            if (rank[a] < rank[b]) parent[a] = b;
            else {
                parent[b] = a;
                if (rank[a] == rank[b]) rank[a]++;
            }
        }
    }

    private static final class VertexKey {
        final long x, y, z;

        VertexKey(float x, float y, float z) {
            this.x = Math.round(x * 100_000d);
            this.y = Math.round(y * 100_000d);
            this.z = Math.round(z * 100_000d);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof VertexKey)) return false;
            VertexKey key = (VertexKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override public int hashCode() {
            long value = x * 31L + y * 17L + z;
            return (int) (value ^ (value >>> 32));
        }
    }

    private static MeshModel read3mf(String name, byte[] data) throws IOException {
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Integer> ts = new ArrayList<>();
        Map<Integer, RawObject> objects = new LinkedHashMap<>();
        ArrayList<BuildItem> buildItems = new ArrayList<>();
        ArrayList<Part> parts = new ArrayList<>();
        Map<Integer, ArrayList<VolumeRange>> volumeRanges = new HashMap<>();
        int modelEntries = 0;
        long[] totalModelBytes = new long[]{0L};
        Set<String> entryNames = new HashSet<>();
        byte[] rootRelationships = null;
        byte[] modelRelationships = null;
        Map<String, byte[]> relatedModels = new LinkedHashMap<>();
        byte[] modelConfig = null;
        java.io.File packageFile = null;
        try {
            packageFile = java.io.File.createTempFile("alloy-3mf-", ".zip");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(packageFile)) {
                output.write(data);
            }
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(packageFile)) {
                java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
                int entryCount = 0;
                while (entries.hasMoreElements()) {
                    java.util.zip.ZipEntry entry = entries.nextElement();
                    if (++entryCount > MAX_3MF_ENTRIES)
                        throw new IOException("3MF contains too many ZIP entries");
                    String entryName = entry.getName();
                    if (!safeZipEntryName(entryName) || !supported3mfEntryName(entryName) || !entryNames.add(entryName))
                        throw new IOException("3MF contains an unsafe or duplicate ZIP entry");
                    // 3MF packages may carry auxiliary model documents with their
                    // own local object-ID namespaces. The package root is the
                    // declared assembly; importing every auxiliary document can
                    // duplicate geometry or falsely report duplicate IDs.
                    try (InputStream entryInput = new CountingInputStream(
                            zip.getInputStream(entry), MAX_3MF_ENTRY_BYTES, totalModelBytes)) {
                        if ("_rels/.rels".equals(entryName)) {
                            rootRelationships = readEntryBytes(entryInput, MAX_3MF_RELATIONSHIPS_BYTES);
                            continue;
                        }
                        if ("3D/_rels/3dmodel.model.rels".equals(entryName)) {
                            modelRelationships = readEntryBytes(entryInput, MAX_3MF_RELATIONSHIPS_BYTES);
                            continue;
                        }
                        if (!"3D/3dmodel.model".equals(entryName)) {
                            if ("Metadata/Slic3r_PE_model.config".equals(entryName))
                                modelConfig = readEntryBytes(entryInput, MAX_3MF_MODEL_CONFIG_BYTES);
                            else if (entryName.startsWith("3D/Objects/") && entryName.endsWith(".model"))
                                relatedModels.put(entryName, readEntryBytes(entryInput, (int) MAX_3MF_ENTRY_BYTES));
                            else
                                drain(entryInput);
                            continue;
                        }
                        if (++modelEntries > MAX_3MF_MODEL_ENTRIES) throw new IOException("3MF contains too many model entries");
                        parseModelEntry(readEntryBytes(entryInput, (int) MAX_3MF_ENTRY_BYTES), true,
                                objects, buildItems);
                    }
                }
            }
        } catch (Exception e) {
            String message = e.getMessage();
            if (message != null && message.toLowerCase(Locale.US).contains("zip entry path"))
                throw new IOException("Could not read 3MF model: 3MF contains an unsafe ZIP entry", e);
            throw new IOException("Could not read 3MF model: " + e.getMessage(), e);
        } finally {
            if (packageFile != null && packageFile.exists() && !packageFile.delete())
                packageFile.deleteOnExit();
        }
        if (modelConfig != null) volumeRanges = readVolumeRanges(modelConfig);
        if (rootRelationships != null) {
            String relationships = new String(rootRelationships, StandardCharsets.UTF_8);
            if (!relationships.contains("3D/3dmodel.model"))
                throw new IOException("3MF root relationships do not target 3D/3dmodel.model");
        }
        if (modelRelationships != null) {
            for (String target : relatedModelTargets(modelRelationships)) {
                byte[] related = relatedModels.get(target);
                if (related == null) throw new IOException("3MF model relationship target is missing: " + target);
                if (++modelEntries > MAX_3MF_MODEL_ENTRIES) throw new IOException("3MF contains too many model entries");
                parseModelEntry(related, false, objects, buildItems);
            }
        }
        if (objects.isEmpty()) throw new IOException("3MF contains no mesh objects");
        if (buildItems.isEmpty()) {
            for (RawObject object : objects.values()) appendObject(object.id, identity(), objects, vs, ts, parts,
                    volumeRanges, object.name, new HashSet<Integer>());
        } else {
            for (BuildItem item : buildItems) {
                RawObject object = objects.get(item.objectId);
                String instanceName = object == null ? "object-" + item.objectId : object.name;
                appendObject(item.objectId, item.transform, objects, vs, ts, parts,
                        volumeRanges, instanceName, new HashSet<Integer>());
            }
        }
        if (ts.size() < 3) throw new IOException("3MF contains no mesh triangles");
        return create(name, vs, ts, parts);
    }

    /** Parse one 3MF model document into the shared object graph. */
    private static void parseModelEntry(byte[] data, boolean collectBuildItems,
                                        Map<Integer, RawObject> objects,
                                        ArrayList<BuildItem> buildItems) throws IOException {
        if (data == null || data.length == 0) throw new IOException("3MF model entry is empty");
        XmlPullParser parser = Xml.newPullParser();
        RawObject current = null;
        float unitScale = 1f;
        try {
            parser.setInput(new java.io.ByteArrayInputStream(data), "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = localName(parser);
                    if ("model".equals(tag)) {
                        unitScale = unitScale(parser.getAttributeValue(null, "unit"));
                    } else if ("object".equals(tag)) {
                        int id = integerAttribute(parser, "id", "object");
                        if (objects.containsKey(id)) throw new IOException("3MF contains duplicate object id " + id);
                        current = new RawObject(id);
                        String objectName = parser.getAttributeValue(null, "name");
                        if (objectName != null && objectName.trim().length() > 0) current.name = objectName.trim();
                        objects.put(id, current);
                    } else if ("vertex".equals(tag) && current != null) {
                        current.vertices.add(Float.parseFloat(parser.getAttributeValue(null, "x")) * unitScale);
                        current.vertices.add(Float.parseFloat(parser.getAttributeValue(null, "y")) * unitScale);
                        current.vertices.add(Float.parseFloat(parser.getAttributeValue(null, "z")) * unitScale);
                        if (current.vertices.size() > MAX_VERTICES * 3) throw new IOException("3MF exceeds the supported mesh size");
                    } else if ("triangle".equals(tag) && current != null) {
                        current.triangles.add(Integer.parseInt(parser.getAttributeValue(null, "v1")));
                        current.triangles.add(Integer.parseInt(parser.getAttributeValue(null, "v2")));
                        current.triangles.add(Integer.parseInt(parser.getAttributeValue(null, "v3")));
                        if (current.triangles.size() / 3 > MAX_TRIANGLES) throw new IOException("3MF exceeds the supported triangle count");
                    } else if ("component".equals(tag) && current != null) {
                        int id = integerAttribute(parser, "objectid", "component");
                        current.components.add(new ComponentRef(id,
                                transform(parser.getAttributeValue(null, "transform"), unitScale)));
                    } else if (collectBuildItems && "item".equals(tag)) {
                        int id = integerAttribute(parser, "objectid", "build item");
                        buildItems.add(new BuildItem(id,
                                transform(parser.getAttributeValue(null, "transform"), unitScale)));
                    }
                } else if (event == XmlPullParser.END_TAG && "object".equals(localName(parser))) {
                    current = null;
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not read 3MF model entry: " + error.getMessage(), error);
        }
    }

    /** Return only bounded, relationship-declared auxiliary model paths. */
    private static ArrayList<String> relatedModelTargets(byte[] data) throws IOException {
        String relationships = new String(data, StandardCharsets.UTF_8);
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Target\\s*=\\s*\\\"([^\\\"]+)\\\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(relationships);
        ArrayList<String> result = new ArrayList<>();
        while (matcher.find()) {
            String target = matcher.group(1).trim().replace('\\', '/');
            while (target.startsWith("/")) target = target.substring(1);
            if (!target.startsWith("3D/Objects/") || !target.endsWith(".model") || !safeZipEntryName(target))
                throw new IOException("3MF model relationship has an unsafe target");
            if (!result.contains(target)) result.add(target);
            if (result.size() > MAX_3MF_MODEL_ENTRIES)
                throw new IOException("3MF has too many related model entries");
        }
        if (result.isEmpty()) throw new IOException("3MF model relationships declare no related object model");
        return result;
    }

    /**
     * SliceBeam/Prusa-style 3MF exports keep one mesh object but record the
     * original volume boundaries in Metadata/Slic3r_PE_model.config. Reading
     * those ranges is what lets a STEP assembly remain an inspectable set of
     * named parts after the native tessellation round trip.
     */
    private static Map<Integer, ArrayList<VolumeRange>> readVolumeRanges(byte[] data) throws IOException {
        Map<Integer, ArrayList<VolumeRange>> result = new HashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        int currentObject = -1;
        VolumeRange currentVolume = null;
        try {
            parser.setInput(new java.io.ByteArrayInputStream(data), "UTF-8");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String tag = localName(parser);
                    if ("object".equals(tag)) {
                        if (currentObject != -1) throw new IOException("3MF model config contains nested objects");
                        currentObject = integerAttribute(parser, "id", "model config object");
                        if (currentObject < 0 || result.containsKey(currentObject))
                            throw new IOException("3MF model config contains a duplicate or invalid object id");
                        result.put(currentObject, new ArrayList<VolumeRange>());
                    } else if ("volume".equals(tag)) {
                        if (currentObject < 0) throw new IOException("3MF model config volume has no object");
                        int first = integerAttribute(parser, "firstid", "model config volume");
                        int last = integerAttribute(parser, "lastid", "model config volume");
                        if (first < 0 || last < first)
                            throw new IOException("3MF model config contains an invalid volume range");
                        ArrayList<VolumeRange> ranges = result.get(currentObject);
                        if (ranges == null || ranges.size() >= MAX_3MF_VOLUME_RANGES)
                            throw new IOException("3MF model config contains too many volume ranges");
                        currentVolume = new VolumeRange(first, last);
                        ranges.add(currentVolume);
                    } else if ("metadata".equals(tag) && currentVolume != null
                            && "volume".equals(parser.getAttributeValue(null, "type"))
                            && "name".equals(parser.getAttributeValue(null, "key"))) {
                        String value = parser.getAttributeValue(null, "value");
                        if (value != null && value.trim().length() > 0) currentVolume.name = value.trim();
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("volume".equals(localName(parser))) currentVolume = null;
                    else if ("object".equals(localName(parser))) currentObject = -1;
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not read 3MF model config: " + error.getMessage(), error);
        }
        return result;
    }

    private static int integerAttribute(XmlPullParser parser, String name, String label) throws IOException {
        String value = parser.getAttributeValue(null, name);
        if (value == null) throw new IOException("3MF " + label + " is missing " + name);
        try { return Integer.parseInt(value); } catch (NumberFormatException error) { throw new IOException("3MF " + label + " has an invalid " + name, error); }
    }

    /** XmlPullParser implementations differ on whether a qualified element name is returned. */
    private static String localName(XmlPullParser parser) {
        String name = parser.getName();
        if (name == null) return "";
        int separator = name.indexOf(':');
        return separator >= 0 ? name.substring(separator + 1) : name;
    }

    private static float[] transform(String value, float unitScale) throws IOException {
        float[] out = identity();
        if (value == null || value.trim().length() == 0) return out;
        String[] parts = value.trim().split("\\s+");
        if (parts.length != 12) throw new IOException("3MF transform must contain 12 values");
        try {
            // 3MF stores the affine matrix as four columns of three values:
            // m00 m10 m20 m01 m11 m21 m02 m12 m22 m03 m13 m23.
            // Alloy keeps the same matrix in row-major 3x4 form.
            float[] source = new float[12];
            for (int i = 0; i < 12; i++) source[i] = Float.parseFloat(parts[i]);
            out[0] = source[0]; out[1] = source[3]; out[2] = source[6]; out[3] = source[9] * unitScale;
            out[4] = source[1]; out[5] = source[4]; out[6] = source[7]; out[7] = source[10] * unitScale;
            out[8] = source[2]; out[9] = source[5]; out[10] = source[8]; out[11] = source[11] * unitScale;
        } catch (NumberFormatException error) {
            throw new IOException("3MF transform contains a non-numeric value", error);
        }
        for (float valuePart : out) if (!finite(valuePart)) throw new IOException("3MF transform contains a non-finite value");
        return out;
    }

    private static float[] identity() {
        return new float[]{1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f};
    }

    private static float[] multiply(float[] a, float[] b) {
        return new float[]{
                a[0] * b[0] + a[1] * b[4] + a[2] * b[8],
                a[0] * b[1] + a[1] * b[5] + a[2] * b[9],
                a[0] * b[2] + a[1] * b[6] + a[2] * b[10],
                a[0] * b[3] + a[1] * b[7] + a[2] * b[11] + a[3],
                a[4] * b[0] + a[5] * b[4] + a[6] * b[8],
                a[4] * b[1] + a[5] * b[5] + a[6] * b[9],
                a[4] * b[2] + a[5] * b[6] + a[6] * b[10],
                a[4] * b[3] + a[5] * b[7] + a[6] * b[11] + a[7],
                a[8] * b[0] + a[9] * b[4] + a[10] * b[8],
                a[8] * b[1] + a[9] * b[5] + a[10] * b[9],
                a[8] * b[2] + a[9] * b[6] + a[10] * b[10],
                a[8] * b[3] + a[9] * b[7] + a[10] * b[11] + a[11]
        };
    }

    private static void appendObject(int id, float[] parent, Map<Integer, RawObject> objects,
                                     ArrayList<Float> vertices, ArrayList<Integer> triangles, ArrayList<Part> parts,
                                     Map<Integer, ArrayList<VolumeRange>> volumeRanges,
                                     String instanceName, Set<Integer> stack) throws IOException {
        RawObject object = objects.get(id);
        if (object == null) throw new IOException("3MF references missing object " + id);
        if (!stack.add(id)) throw new IOException("3MF component graph contains a cycle at object " + id);
        if (vertices.size() / 3 > MAX_VERTICES - object.vertices.size() / 3)
            throw new IOException("3MF exceeds the supported combined vertex count");
        if (triangles.size() > MAX_TRIANGLES * 3 - object.triangles.size())
            throw new IOException("3MF exceeds the supported combined triangle count");
        int base = vertices.size() / 3;
        int triangleStart = triangles.size() / 3;
        for (int i = 0; i < object.vertices.size(); i += 3) {
            float x = object.vertices.get(i), y = object.vertices.get(i + 1), z = object.vertices.get(i + 2);
            vertices.add(parent[0] * x + parent[1] * y + parent[2] * z + parent[3]);
            vertices.add(parent[4] * x + parent[5] * y + parent[6] * z + parent[7]);
            vertices.add(parent[8] * x + parent[9] * y + parent[10] * z + parent[11]);
        }
        for (int i = 0; i < object.triangles.size(); i++) {
            int index = object.triangles.get(i);
            if (index < 0 || index * 3 + 2 >= object.vertices.size())
                throw new IOException("3MF object " + id + " contains an invalid triangle index");
            triangles.add(base + index);
        }
        if (!object.triangles.isEmpty()) {
            String label = object.name.equals(instanceName) ? object.name : instanceName + " · " + object.name;
            ArrayList<VolumeRange> ranges = volumeRanges.get(id);
            if (ranges == null || ranges.isEmpty()) {
                parts.add(new Part(label, triangleStart, object.triangles.size() / 3));
            } else {
                int triangleCount = object.triangles.size() / 3;
                int previousLast = -1;
                for (int index = 0; index < ranges.size(); index++) {
                    VolumeRange range = ranges.get(index);
                    if (range.firstTriangleId < 0 || range.lastTriangleId >= triangleCount
                            || range.firstTriangleId <= previousLast)
                        throw new IOException("3MF model config volume range does not match object " + id);
                    String partName = range.name == null || range.name.length() == 0
                            ? label + " · part-" + (index + 1)
                            : label + " · " + range.name;
                    parts.add(new Part(partName, triangleStart + range.firstTriangleId,
                            range.lastTriangleId - range.firstTriangleId + 1));
                    previousLast = range.lastTriangleId;
                }
            }
        }
        for (ComponentRef component : object.components)
            appendObject(component.objectId, multiply(parent, component.transform), objects, vertices, triangles, parts,
                    volumeRanges, instanceName + " / object-" + component.objectId, stack);
        stack.remove(id);
    }

    private static final class RawObject {
        final int id;
        String name;
        final ArrayList<Float> vertices = new ArrayList<>();
        final ArrayList<Integer> triangles = new ArrayList<>();
        final ArrayList<ComponentRef> components = new ArrayList<>();

        RawObject(int id) { this.id = id; this.name = "object-" + id; }
    }

    private static final class ComponentRef {
        final int objectId;
        final float[] transform;
        ComponentRef(int objectId, float[] transform) { this.objectId = objectId; this.transform = transform; }
    }

    private static final class BuildItem {
        final int objectId;
        final float[] transform;
        BuildItem(int objectId, float[] transform) { this.objectId = objectId; this.transform = transform; }
    }

    private static final class VolumeRange {
        final int firstTriangleId;
        final int lastTriangleId;
        String name;

        VolumeRange(int firstTriangleId, int lastTriangleId) {
            this.firstTriangleId = firstTriangleId;
            this.lastTriangleId = lastTriangleId;
        }
    }

    private static float unitScale(String unit) throws IOException {
        if (unit == null || unit.length() == 0 || "millimeter".equalsIgnoreCase(unit)) return 1f;
        if ("micron".equalsIgnoreCase(unit)) return 0.001f;
        if ("centimeter".equalsIgnoreCase(unit)) return 10f;
        if ("meter".equalsIgnoreCase(unit)) return 1000f;
        if ("inch".equalsIgnoreCase(unit)) return 25.4f;
        if ("foot".equalsIgnoreCase(unit)) return 304.8f;
        throw new IOException("Unsupported 3MF unit: " + unit);
    }

    private static boolean safeZipEntryName(String name) {
        if (name == null || name.length() == 0 || name.length() > 512
                || name.startsWith("/") || name.indexOf('\\') >= 0)
            return false;
        if (hasControlCharacter(name)) return false;
        String[] components = name.split("/", -1);
        for (String component : components) if (component.length() == 0 || ".".equals(component) || "..".equals(component)) return false;
        return true;
    }

    /**
     * Keep package entries inside standard 3MF namespaces. This also protects
     * against ZIP readers that normalize a traversal name such as
     * ../outside.stl before exposing it to the caller.
     */
    private static boolean supported3mfEntryName(String name) {
        return "[Content_Types].xml".equals(name)
                || "_rels/.rels".equals(name)
                || "3D/3dmodel.model".equals(name)
                || name.startsWith("3D/_rels/")
                || name.startsWith("3D/Objects/")
                || name.startsWith("_rels/")
                || name.startsWith("Metadata/")
                || name.startsWith("Textures/");
    }

    private static boolean hasControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 0x20 || character == 0x7f) return true;
        }
        return false;
    }

    private static void drain(InputStream input) throws IOException {
        byte[] buffer = new byte[32 * 1024];
        while (input.read(buffer) != -1) { }
    }

    private static byte[] readEntryBytes(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 32 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() > limit - read) throw new IOException("3MF metadata exceeds its size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        private final long limit;
        private final long[] total;
        private long count;

        CountingInputStream(InputStream input, long limit, long[] total) {
            super(input);
            this.limit = limit;
            this.total = total;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value != -1) check(1);
            return value;
        }

        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) check(read);
            return read;
        }

        @Override public long skip(long length) throws IOException {
            long skipped = super.skip(length);
            if (skipped > 0) check(skipped);
            return skipped;
        }

        private void check(long read) throws IOException {
            count += read;
            total[0] += read;
            if (count > limit) throw new IOException("3MF model entry exceeds the 128 MB limit");
            if (total[0] > MAX_INPUT_BYTES) throw new IOException("3MF decompressed model data exceeds the 256 MB limit");
        }
    }

    private static MeshModel create(String name, ArrayList<Float> vs, ArrayList<Integer> ts) throws IOException {
        ArrayList<Part> parts = new ArrayList<>();
        parts.add(new Part(name, 0, ts.size() / 3));
        return create(name, vs, ts, parts);
    }

    /**
     * Package-private construction boundary for Alloy's bounded parametric
     * workbench. Generated geometry still goes through the same validation as
     * imported files; callers cannot bypass index, finite-coordinate or part
     * range checks by using this helper.
     */
    static MeshModel generated(String name, ArrayList<Float> vs, ArrayList<Integer> ts,
                               ArrayList<Part> partRanges) throws IOException {
        return create(name, vs, ts, partRanges);
    }

    /** Package-private visual import boundary for a precomputed normal stream. */
    static MeshModel generated(String name, ArrayList<Float> vs, ArrayList<Integer> ts,
                               ArrayList<Part> partRanges, float[] displayNormals) throws IOException {
        return create(name, vs, ts, partRanges, displayNormals);
    }

    private static MeshModel create(String name, ArrayList<Float> vs, ArrayList<Integer> ts,
                                    ArrayList<Part> partRanges) throws IOException {
        return create(name, vs, ts, partRanges, null);
    }

    private static MeshModel create(String name, ArrayList<Float> vs, ArrayList<Integer> ts,
                                    ArrayList<Part> partRanges, float[] displayNormals) throws IOException {
        if (vs.isEmpty() || vs.size() % 3 != 0 || ts.isEmpty() || ts.size() % 3 != 0)
            throw new IOException("Mesh contains incomplete geometry");
        if (vs.size() / 3 > MAX_VERTICES) throw new IOException("Mesh exceeds the supported vertex count");
        float[] vertices = new float[vs.size()];
        int[] triangles = new int[ts.size()];
        for (int i = 0; i < vs.size(); i++) vertices[i] = vs.get(i);
        for (int i = 0; i < ts.size(); i++) {
            triangles[i] = ts.get(i);
            if (triangles[i] < 0 || triangles[i] * 3 + 2 >= vertices.length)
                throw new IOException("Mesh contains an invalid triangle index");
        }
        for (float vertex : vertices) {
            if (Float.isNaN(vertex) || Float.isInfinite(vertex)) throw new IOException("Mesh contains a non-finite coordinate");
        }
        if (displayNormals != null && displayNormals.length != vertices.length)
            throw new IOException("Mesh display normals do not match the vertex count");
        if (displayNormals != null) {
            for (float normal : displayNormals) {
                if (Float.isNaN(normal) || Float.isInfinite(normal))
                    throw new IOException("Mesh contains a non-finite display normal");
            }
        }
        if (partRanges == null || partRanges.isEmpty()) {
            partRanges = new ArrayList<>();
            partRanges.add(new Part(name, 0, triangles.length / 3));
        }
        Part[] parts = partRanges.toArray(new Part[0]);
        for (Part part : parts) {
            if (part.triangleStart < 0 || part.triangleCount <= 0
                    || part.triangleStart > triangles.length / 3 - part.triangleCount)
                throw new IOException("Mesh contains an invalid part range");
        }
        return new MeshModel(vertices, triangles, name, parts, displayNormals);
    }

    /** Return a scaled and oriented copy with its lowest point on the bed. */
    public MeshModel transformed(String name, float scale, float rotationDegrees) throws IOException {
        return transformed(name, scale, rotationDegrees, 0f, 0f);
    }

    /** Apply bounded X/Y tilt followed by Z rotation around the model centre. */
    public MeshModel transformed(String name, float scale, float rotationDegrees,
                                 float tiltXDegrees, float tiltYDegrees) throws IOException {
        if (!finite(scale) || scale < MIN_MODEL_SCALE || scale > MAX_MODEL_SCALE)
            throw new IOException("Model scale is invalid");
        if (!finite(rotationDegrees) || !finite(tiltXDegrees) || !finite(tiltYDegrees)
                || Math.abs(tiltXDegrees) > 360f || Math.abs(tiltYDegrees) > 360f)
            throw new IOException("Model orientation is invalid");
        float centerX = (minX + maxX) / 2f, centerY = (minY + maxY) / 2f, centerZ = (minZ + maxZ) / 2f;
        double xRadians = Math.toRadians(tiltXDegrees), yRadians = Math.toRadians(tiltYDegrees), zRadians = Math.toRadians(rotationDegrees);
        float cosX = (float) Math.cos(xRadians), sinX = (float) Math.sin(xRadians);
        float cosY = (float) Math.cos(yRadians), sinY = (float) Math.sin(yRadians);
        float cosZ = (float) Math.cos(zRadians), sinZ = (float) Math.sin(zRadians);
        ArrayList<Float> transformed = new ArrayList<>(vertices.length);
        float lowest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < vertices.length; i += 3) {
            float x = (vertices[i] - centerX) * scale;
            float y = (vertices[i + 1] - centerY) * scale;
            float z = (vertices[i + 2] - centerZ) * scale;
            float yX = y * cosX - z * sinX;
            float zX = y * sinX + z * cosX;
            float xY = x * cosY + zX * sinY;
            float zY = -x * sinY + zX * cosY;
            float xZ = xY * cosZ - yX * sinZ;
            float yZ = xY * sinZ + yX * cosZ;
            transformed.add(centerX + xZ);
            transformed.add(centerY + yZ);
            transformed.add(zY);
            lowest = Math.min(lowest, zY);
        }
        for (int i = 2; i < transformed.size(); i += 3) transformed.set(i, transformed.get(i) - lowest);
        ArrayList<Integer> indices = new ArrayList<>(triangles.length);
        for (int index : triangles) indices.add(index);
        ArrayList<Part> partRanges = new ArrayList<>();
        for (Part part : parts) partRanges.add(part);
        return create(name, transformed, indices, partRanges);
    }

    /** Apply an independent X/Y rotation and scale to one logical part. */
    public MeshModel transformedPart(String name, int partIndex, float scale, float rotationDegrees,
                                     float offsetX, float offsetY) throws IOException {
        return transformedPart(name, partIndex, scale, rotationDegrees, 0f, 0f, offsetX, offsetY);
    }

    /** Apply independent X/Y tilt, Z rotation, scale and XY placement to one logical part. */
    public MeshModel transformedPart(String name, int partIndex, float scale, float rotationDegrees,
                                     float tiltXDegrees, float tiltYDegrees,
                                     float offsetX, float offsetY) throws IOException {
        if (partIndex < 0 || partIndex >= parts.length) throw new IOException("Part index is invalid");
        if (!finite(scale) || scale <= 0f || scale > 10f) throw new IOException("Part scale is invalid");
        if (!finite(rotationDegrees) || !finite(tiltXDegrees) || !finite(tiltYDegrees)
                || Math.abs(tiltXDegrees) > 360f || Math.abs(tiltYDegrees) > 360f
                || !finite(offsetX) || !finite(offsetY))
            throw new IOException("Part transform is invalid");
        Part part = parts[partIndex];
        ArrayList<Float> transformed = new ArrayList<>(vertices.length + 32);
        for (float vertex : vertices) transformed.add(vertex);
        ArrayList<Integer> indices = new ArrayList<>(triangles.length);
        for (int index : triangles) indices.add(index);

        // Duplicate vertices shared with another part before editing indices;
        // a part transform must never move a neighboring logical part.
        HashSet<Integer> selectedVertices = new HashSet<>();
        for (int triangle = part.triangleStart; triangle < part.triangleStart + part.triangleCount; triangle++) {
            int offset = triangle * 3;
            for (int corner = 0; corner < 3; corner++) selectedVertices.add(triangles[offset + corner]);
        }
        HashSet<Integer> sharedVertices = new HashSet<>();
        for (int other = 0; other < parts.length; other++) {
            if (other == partIndex) continue;
            Part otherPart = parts[other];
            for (int triangle = otherPart.triangleStart; triangle < otherPart.triangleStart + otherPart.triangleCount; triangle++) {
                int offset = triangle * 3;
                for (int corner = 0; corner < 3; corner++) {
                    int vertex = triangles[offset + corner];
                    if (selectedVertices.contains(vertex)) sharedVertices.add(vertex);
                }
            }
        }
        HashMap<Integer, Integer> remapped = new HashMap<>();
        for (int vertex : selectedVertices) {
            if (sharedVertices.contains(vertex)) {
                int oldOffset = vertex * 3;
                int next = transformed.size() / 3;
                transformed.add(vertices[oldOffset]);
                transformed.add(vertices[oldOffset + 1]);
                transformed.add(vertices[oldOffset + 2]);
                remapped.put(vertex, next);
            } else {
                remapped.put(vertex, vertex);
            }
        }
        for (int triangle = part.triangleStart; triangle < part.triangleStart + part.triangleCount; triangle++) {
            int offset = triangle * 3;
            for (int corner = 0; corner < 3; corner++) {
                int old = triangles[offset + corner];
                indices.set(offset + corner, remapped.get(old));
            }
        }

        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (int vertex : selectedVertices) {
            int offset = vertex * 3;
            minX = Math.min(minX, vertices[offset]); maxX = Math.max(maxX, vertices[offset]);
            minY = Math.min(minY, vertices[offset + 1]); maxY = Math.max(maxY, vertices[offset + 1]);
            minZ = Math.min(minZ, vertices[offset + 2]); maxZ = Math.max(maxZ, vertices[offset + 2]);
        }
        double xRadians = Math.toRadians(tiltXDegrees), yRadians = Math.toRadians(tiltYDegrees), zRadians = Math.toRadians(rotationDegrees);
        float cosX = (float) Math.cos(xRadians), sinX = (float) Math.sin(xRadians);
        float cosY = (float) Math.cos(yRadians), sinY = (float) Math.sin(yRadians);
        float cos = (float) Math.cos(zRadians), sin = (float) Math.sin(zRadians);
        float centerX = (minX + maxX) / 2f, centerY = (minY + maxY) / 2f, centerZ = (minZ + maxZ) / 2f;
        for (int vertex : selectedVertices) {
            int targetVertex = remapped.get(vertex);
            int sourceOffset = vertex * 3;
            int targetOffset = targetVertex * 3;
            float x = (vertices[sourceOffset] - centerX) * scale;
            float y = (vertices[sourceOffset + 1] - centerY) * scale;
            float z = (vertices[sourceOffset + 2] - centerZ) * scale;
            float yX = y * cosX - z * sinX;
            float zX = y * sinX + z * cosX;
            float xY = x * cosY + zX * sinY;
            float zY = -x * sinY + zX * cosY;
            transformed.set(targetOffset, centerX + xY * cos - yX * sin + offsetX);
            transformed.set(targetOffset + 1, centerY + xY * sin + yX * cos + offsetY);
            transformed.set(targetOffset + 2, centerZ + zY);
        }
        ArrayList<Part> ranges = new ArrayList<>();
        for (Part value : parts) ranges.add(value);
        return create(name, transformed, indices, ranges);
    }

    /** Return a copy translated vertically so its lowest vertex rests on Z=0. */
    public MeshModel leveledOnBed(String name) throws IOException {
        if (!finite(minZ)) throw new IOException("Model has no finite height");
        ArrayList<Float> leveled = new ArrayList<>(vertices.length);
        for (int index = 0; index < vertices.length; index += 3) {
            leveled.add(vertices[index]);
            leveled.add(vertices[index + 1]);
            leveled.add(vertices[index + 2] - minZ);
        }
        ArrayList<Integer> indices = new ArrayList<>(triangles.length);
        for (int index : triangles) indices.add(index);
        ArrayList<Part> ranges = new ArrayList<>();
        for (Part value : parts) ranges.add(value);
        return create(name, leveled, indices, ranges);
    }

    /**
     * Rotate the largest non-degenerate triangle onto the build plate.
     *
     * This is deliberately a bounded preparation aid, not a general automatic
     * orientation optimizer: it chooses one existing face, applies one proper
     * 3-D rotation to the whole mesh, and leaves supports/material policy to
     * the normal preparation review. The selected outward normal is mapped to
     * -Z so a watertight solid rests on that face, then the lowest vertex is
     * translated to Z=0.
     */
    public MeshModel layFlat(String name) throws IOException {
        if (triangles.length < 3) throw new IOException("Model has no face to lay flat");
        int selected = -1;
        double largestAreaSquared = 0d;
        for (int triangle = 0; triangle < triangles.length / 3; triangle++) {
            int a = triangles[triangle * 3] * 3;
            int b = triangles[triangle * 3 + 1] * 3;
            int c = triangles[triangle * 3 + 2] * 3;
            double ux = vertices[b] - vertices[a], uy = vertices[b + 1] - vertices[a + 1], uz = vertices[b + 2] - vertices[a + 2];
            double vx = vertices[c] - vertices[a], vy = vertices[c + 1] - vertices[a + 1], vz = vertices[c + 2] - vertices[a + 2];
            double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            double areaSquared = nx * nx + ny * ny + nz * nz;
            if (areaSquared > largestAreaSquared) {
                largestAreaSquared = areaSquared;
                selected = triangle;
            }
        }
        if (selected < 0 || largestAreaSquared < 1.0e-12d)
            throw new IOException("Model contains no usable face to lay flat");
        int base = selected * 3;
        int ia = triangles[base] * 3, ib = triangles[base + 1] * 3, ic = triangles[base + 2] * 3;
        double ux = vertices[ib] - vertices[ia], uy = vertices[ib + 1] - vertices[ia + 1], uz = vertices[ib + 2] - vertices[ia + 2];
        double vx = vertices[ic] - vertices[ia], vy = vertices[ic + 1] - vertices[ia + 1], vz = vertices[ic + 2] - vertices[ia + 2];
        double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
        nx /= length; ny /= length; nz /= length;
        // Rodrigues rotation from the selected normal to the downward bed normal.
        double tx = 0d, ty = 0d, tz = -1d;
        double kx = ny * tz - nz * ty, ky = nz * tx - nx * tz, kz = nx * ty - ny * tx;
        double sine = Math.sqrt(kx * kx + ky * ky + kz * kz);
        double cosine = nx * tx + ny * ty + nz * tz;
        double angle = Math.atan2(sine, cosine);
        if (sine > 1.0e-10d) { kx /= sine; ky /= sine; kz /= sine; }
        else if (cosine < 0d) { kx = 1d; ky = 0d; kz = 0d; }
        else { kx = 0d; ky = 0d; kz = 0d; }
        double sinAngle = Math.sin(angle), cosAngle = Math.cos(angle);
        float centerX = (minX + maxX) / 2f, centerY = (minY + maxY) / 2f, centerZ = (minZ + maxZ) / 2f;
        ArrayList<Float> rotated = new ArrayList<>(vertices.length);
        float lowest = Float.POSITIVE_INFINITY;
        for (int index = 0; index < vertices.length; index += 3) {
            double px = vertices[index] - centerX, py = vertices[index + 1] - centerY, pz = vertices[index + 2] - centerZ;
            double crossX = ky * pz - kz * py, crossY = kz * px - kx * pz, crossZ = kx * py - ky * px;
            double dot = kx * px + ky * py + kz * pz;
            double rx = px * cosAngle + crossX * sinAngle + kx * dot * (1d - cosAngle);
            double ry = py * cosAngle + crossY * sinAngle + ky * dot * (1d - cosAngle);
            double rz = pz * cosAngle + crossZ * sinAngle + kz * dot * (1d - cosAngle);
            rotated.add((float) (centerX + rx));
            rotated.add((float) (centerY + ry));
            rotated.add((float) rz);
            lowest = Math.min(lowest, (float) rz);
        }
        for (int index = 2; index < rotated.size(); index += 3)
            rotated.set(index, rotated.get(index) - lowest);
        ArrayList<Integer> indices = new ArrayList<>(triangles.length);
        for (int index : triangles) indices.add(index);
        ArrayList<Part> ranges = new ArrayList<>();
        for (Part part : parts) ranges.add(part);
        return create(name, rotated, indices, ranges);
    }

    /**
     * Choose the best of a bounded set of axis-aligned print orientations.
     * This evaluates the six principal-axis poses plus XY quarter-turns,
     * preferring lower print height and then a larger bed footprint.
     */
    public MeshModel autoOrient(String name) throws IOException {
        if (vertices.length == 0 || triangles.length < 3)
            throw new IOException("Model has no printable geometry");
        float[][] candidates = new float[][]{
                {0f, 0f, 0f}, {0f, 90f, 0f}, {0f, -90f, 0f},
                {90f, 0f, 0f}, {-90f, 0f, 0f}, {0f, 0f, 90f},
                {0f, 0f, 180f}, {0f, 0f, 270f}
        };
        MeshModel best = null;
        for (float[] pose : candidates) {
            MeshModel candidate = transformed(name, 1f, pose[2], pose[0], pose[1]);
            if (best == null || betterAutoOrientation(candidate, best)) best = candidate;
        }
        return best;
    }

    private static boolean betterAutoOrientation(MeshModel candidate, MeshModel current) {
        final float epsilon = 0.0001f;
        float candidateHeight = candidate.maxZ - candidate.minZ;
        float currentHeight = current.maxZ - current.minZ;
        if (candidateHeight < currentHeight - epsilon) return true;
        if (candidateHeight > currentHeight + epsilon) return false;
        float candidateFootprint = (candidate.maxX - candidate.minX)
                * (candidate.maxY - candidate.minY);
        float currentFootprint = (current.maxX - current.minX)
                * (current.maxY - current.minY);
        return candidateFootprint > currentFootprint + epsilon;
    }

    public static final class PartTransform {
        public final float scale;
        public final float rotationDegrees;
        public final float tiltXDegrees;
        public final float tiltYDegrees;
        public final float offsetX;
        public final float offsetY;

        public PartTransform(float scale, float rotationDegrees, float offsetX, float offsetY) {
            this(scale, rotationDegrees, 0f, 0f, offsetX, offsetY);
        }

        public PartTransform(float scale, float rotationDegrees, float tiltXDegrees, float tiltYDegrees,
                             float offsetX, float offsetY) {
            this.scale = scale;
            this.rotationDegrees = rotationDegrees;
            this.tiltXDegrees = tiltXDegrees;
            this.tiltYDegrees = tiltYDegrees;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
        }

        public static PartTransform identity() { return new PartTransform(1f, 0f, 0f, 0f); }
    }

    /** Return the axis-aligned dimensions of one logical assembly part. */
    public PartBounds partBounds(int partIndex) {
        if (parts == null || partIndex < 0 || partIndex >= parts.length)
            throw new IllegalArgumentException("Part index is invalid");
        Part part = parts[partIndex];
        float x0 = Float.POSITIVE_INFINITY, x1 = Float.NEGATIVE_INFINITY;
        float y0 = Float.POSITIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        float z0 = Float.POSITIVE_INFINITY, z1 = Float.NEGATIVE_INFINITY;
        for (int triangle = part.triangleStart; triangle < part.triangleStart + part.triangleCount; triangle++) {
            int offset = triangle * 3;
            for (int corner = 0; corner < 3; corner++) {
                int vertex = triangles[offset + corner] * 3;
                x0 = Math.min(x0, vertices[vertex]); x1 = Math.max(x1, vertices[vertex]);
                y0 = Math.min(y0, vertices[vertex + 1]); y1 = Math.max(y1, vertices[vertex + 1]);
                z0 = Math.min(z0, vertices[vertex + 2]); z1 = Math.max(z1, vertices[vertex + 2]);
            }
        }
        return new PartBounds(x0, x1, y0, y1, z0, z1, part.triangleCount);
    }

    /**
     * Compute bounded mesh-health information lazily for the inspector. Large
     * community meshes still receive a finite-facet check, while edge topology
     * is intentionally skipped when the map would be too expensive on a phone.
     */
    public GeometryReport geometryReport() {
        GeometryReport existing = cachedGeometryReport;
        if (existing != null) return existing;
        int triangleCount = triangles.length / 3;
        int degenerate = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int offset = triangle * 3;
            int a = triangles[offset] * 3, b = triangles[offset + 1] * 3, c = triangles[offset + 2] * 3;
            if (triangles[offset] == triangles[offset + 1] || triangles[offset] == triangles[offset + 2]
                    || triangles[offset + 1] == triangles[offset + 2] || zeroArea(a, b, c)) degenerate++;
        }
        if (triangleCount > 100_000) {
            GeometryReport report = new GeometryReport(triangleCount, degenerate, -1, -1, false);
            cachedGeometryReport = report;
            return report;
        }
        HashMap<VertexKey, Integer> vertexIds = new HashMap<>();
        HashMap<Long, Integer> edgeCounts = new HashMap<>();
        int nextVertexId = 0;
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int offset = triangle * 3;
            int[] ids = new int[3];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = triangles[offset + corner] * 3;
                VertexKey key = new VertexKey(vertices[vertex], vertices[vertex + 1], vertices[vertex + 2]);
                Integer id = vertexIds.get(key);
                if (id == null) {
                    id = nextVertexId++;
                    vertexIds.put(key, id);
                }
                ids[corner] = id;
            }
            addEdge(edgeCounts, ids[0], ids[1]);
            addEdge(edgeCounts, ids[1], ids[2]);
            addEdge(edgeCounts, ids[2], ids[0]);
        }
        int boundary = 0, nonManifold = 0;
        for (Integer count : edgeCounts.values()) {
            if (count == 1) boundary++;
            else if (count > 2) nonManifold++;
        }
        GeometryReport report = new GeometryReport(triangleCount, degenerate, boundary, nonManifold, true);
        cachedGeometryReport = report;
        return report;
    }

    /**
     * Return a cleaned copy suitable for another inspection or slice attempt.
     *
     * This is deliberately conservative: it removes only facets that are
     * provably degenerate or duplicated, welds vertices within a very small
     * tolerance, closes only bounded planar convex boundary loops, and fixes
     * winding for closed connected components. Ambiguous/non-planar/open
     * boundary topology is left untouched and remains visible to the caller.
     */
    public RepairResult repair(String name) throws IOException {
        return repair(name, DEFAULT_REPAIR_EPSILON_MM);
    }

    /** Apply bounded near-duplicate welding with a caller-selected tolerance. */
    public RepairResult repair(String name, float epsilonMm) throws IOException {
        if (!finite(epsilonMm) || epsilonMm <= 0f || epsilonMm > 0.1f)
            throw new IOException("Mesh repair tolerance is invalid");
        int sourceTriangleCount = triangles.length / 3;
        if (sourceTriangleCount == 0) throw new IOException("Mesh contains no facets to repair");

        ArrayList<Float> repairedVertices = new ArrayList<>();
        ArrayList<Integer> repairedTriangles = new ArrayList<>();
        ArrayList<Part> repairedParts = new ArrayList<>();
        int removedDegenerate = 0;
        int removedDuplicate = 0;
        int weldedVertices = 0;
        int filledPlanarHoles = 0;

        for (Part part : parts) {
            int triangleStart = repairedTriangles.size() / 3;
            int verticesBefore = repairedVertices.size() / 3;
            HashMap<RepairVertexKey, ArrayList<Integer>> buckets = new HashMap<>();
            HashMap<Integer, Integer> remapped = new HashMap<>();
            HashSet<RepairTriangleKey> seenTriangles = new HashSet<>();

            for (int triangle = part.triangleStart;
                 triangle < part.triangleStart + part.triangleCount; triangle++) {
                int offset = triangle * 3;
                int originalA = triangles[offset];
                int originalB = triangles[offset + 1];
                int originalC = triangles[offset + 2];
                if (originalA == originalB || originalA == originalC || originalB == originalC
                        || zeroAreaByVertexIndex(vertices, originalA, originalB, originalC)) {
                    removedDegenerate++;
                    continue;
                }
                int a = repairVertexFromSource(originalA, epsilonMm, buckets, remapped, repairedVertices, vertices);
                int b = repairVertexFromSource(originalB, epsilonMm, buckets, remapped, repairedVertices, vertices);
                int c = repairVertexFromSource(originalC, epsilonMm, buckets, remapped, repairedVertices, vertices);
                if (a == b || a == c || b == c || zeroArea(repairedVertices, a, b, c)) {
                    removedDegenerate++;
                    continue;
                }
                if (!seenTriangles.add(new RepairTriangleKey(a, b, c))) {
                    removedDuplicate++;
                    continue;
                }
                repairedTriangles.add(a);
                repairedTriangles.add(b);
                repairedTriangles.add(c);
            }

            int partTriangleCount = repairedTriangles.size() / 3 - triangleStart;
            if (partTriangleCount > 0) {
                repairedParts.add(new Part(part.name, triangleStart, partTriangleCount));
                weldedVertices += Math.max(0, remapped.size() - (repairedVertices.size() / 3 - verticesBefore));
            }
        }
        if (repairedTriangles.isEmpty()) throw new IOException("Mesh repair removed every facet");
        if (repairedParts.size() != parts.length)
            throw new IOException("Mesh repair removed every facet from one logical part; inspect the source manually");

        ArrayList<Part> closedParts = new ArrayList<>();
        int flippedTriangles = 0;
        // Appended cap triangles stay contiguous only for a single logical
        // part. Multi-part assemblies retain the conservative weld/winding
        // repair until a part-aware rebuild can preserve their spans.
        if (repairedParts.size() == 1) {
            Part part = repairedParts.get(0);
            FillStats fill = fillPlanarConvexBoundaryLoops(repairedVertices, repairedTriangles,
                    part.triangleStart, part.triangleCount, epsilonMm);
            filledPlanarHoles += fill.holes;
            closedParts.add(new Part(part.name, part.triangleStart, part.triangleCount + fill.triangles));
        } else {
            closedParts.addAll(repairedParts);
        }
        if (closedParts.size() == repairedParts.size()) repairedParts = closedParts;
        for (Part part : repairedParts)
            flippedTriangles += normalizeClosedWinding(repairedVertices, repairedTriangles,
                    part.triangleStart, part.triangleCount);

        MeshModel repaired = create(name, repairedVertices, repairedTriangles, repairedParts);
        RepairReport report = new RepairReport(sourceTriangleCount, repairedTriangles.size() / 3,
                removedDegenerate, removedDuplicate, weldedVertices, flippedTriangles,
                filledPlanarHoles, epsilonMm);
        return new RepairResult(repaired, report);
    }

    /**
     * Close only simple convex planar loops. This intentionally does not try
     * to infer arbitrary CAD topology: a loop must have two boundary
     * neighbors at every vertex, be planar within the repair tolerance, and
     * be convex in its dominant plane projection.
     */
    private static FillStats fillPlanarConvexBoundaryLoops(ArrayList<Float> vertices,
                                                            ArrayList<Integer> triangles,
                                                            int triangleStart,
                                                            int triangleCount,
                                                            float epsilonMm) {
        if (triangleCount < 4) return new FillStats();
        HashMap<Long, ArrayList<RepairEdge>> edges = new HashMap<>();
        HashMap<Integer, ArrayList<Integer>> neighbors = new HashMap<>();
        for (int local = 0; local < triangleCount; local++) {
            int offset = (triangleStart + local) * 3;
            addRepairEdge(edges, local, triangles.get(offset), triangles.get(offset + 1));
            addRepairEdge(edges, local, triangles.get(offset + 1), triangles.get(offset + 2));
            addRepairEdge(edges, local, triangles.get(offset + 2), triangles.get(offset));
        }
        for (ArrayList<RepairEdge> uses : edges.values()) {
            if (uses.size() != 1) continue;
            RepairEdge edge = uses.get(0);
            addNeighbor(neighbors, edge.from, edge.to);
            addNeighbor(neighbors, edge.to, edge.from);
        }
        HashSet<Long> visited = new HashSet<>();
        int holes = 0, addedTriangles = 0;
        for (ArrayList<RepairEdge> uses : edges.values()) {
            if (uses.size() != 1) continue;
            RepairEdge seed = uses.get(0);
            long seedKey = edgeKey(seed.from, seed.to);
            if (visited.contains(seedKey)) continue;
            ArrayList<Integer> loop = boundaryLoop(seed.from, seed.to, neighbors, visited);
            if (loop == null || loop.size() < 3 || loop.size() > 128
                    || !isConvexPlanarLoop(loop, vertices, epsilonMm)) continue;
            int center = vertices.size() / 3;
            float cx = 0f, cy = 0f, cz = 0f;
            for (int vertex : loop) {
                int offset = vertex * 3;
                cx += vertices.get(offset); cy += vertices.get(offset + 1); cz += vertices.get(offset + 2);
            }
            float inverse = 1f / loop.size();
            vertices.add(cx * inverse); vertices.add(cy * inverse); vertices.add(cz * inverse);
            int before = triangles.size();
            for (int index = 0; index < loop.size(); index++) {
                int a = loop.get(index), b = loop.get((index + 1) % loop.size());
                if (!zeroArea(vertices, a, b, center)) {
                    triangles.add(a); triangles.add(b); triangles.add(center);
                }
            }
            int appended = (triangles.size() - before) / 3;
            if (appended == loop.size()) {
                holes++;
                addedTriangles += appended;
            } else {
                while (triangles.size() > before) triangles.remove(triangles.size() - 1);
                vertices.subList(vertices.size() - 3, vertices.size()).clear();
            }
        }
        return new FillStats(holes, addedTriangles);
    }

    private static void addNeighbor(HashMap<Integer, ArrayList<Integer>> neighbors, int from, int to) {
        ArrayList<Integer> values = neighbors.get(from);
        if (values == null) { values = new ArrayList<>(); neighbors.put(from, values); }
        if (!values.contains(to)) values.add(to);
    }

    private static ArrayList<Integer> boundaryLoop(int start, int next,
                                                    HashMap<Integer, ArrayList<Integer>> neighbors,
                                                    HashSet<Long> visited) {
        ArrayList<Integer> loop = new ArrayList<>();
        int previous = start, current = next;
        loop.add(start);
        for (int step = 0; step < 130; step++) {
            long key = edgeKey(previous, current);
            if (!visited.add(key)) return current == start ? loop : null;
            if (current == start) return loop;
            if (loop.contains(current)) return null;
            loop.add(current);
            ArrayList<Integer> options = neighbors.get(current);
            if (options == null || options.size() != 2) return null;
            int candidate = options.get(0) == previous ? options.get(1) : options.get(0);
            previous = current;
            current = candidate;
        }
        return null;
    }

    private static boolean isConvexPlanarLoop(ArrayList<Integer> loop, ArrayList<Float> vertices,
                                              float epsilonMm) {
        int first = loop.get(0) * 3;
        float nx = 0f, ny = 0f, nz = 0f;
        for (int index = 1; index + 1 < loop.size(); index++) {
            int b = loop.get(index) * 3, c = loop.get(index + 1) * 3;
            float ux = vertices.get(b) - vertices.get(first), uy = vertices.get(b + 1) - vertices.get(first), uz = vertices.get(b + 2) - vertices.get(first);
            float vx = vertices.get(c) - vertices.get(first), vy = vertices.get(c + 1) - vertices.get(first), vz = vertices.get(c + 2) - vertices.get(first);
            nx = uy * vz - uz * vy; ny = uz * vx - ux * vz; nz = ux * vy - uy * vx;
            if (nx * nx + ny * ny + nz * nz > 0.00000001f) break;
        }
        float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!finite(normalLength) || normalLength <= 0f) return false;
        nx /= normalLength; ny /= normalLength; nz /= normalLength;
        float px = vertices.get(first), py = vertices.get(first + 1), pz = vertices.get(first + 2);
        float tolerance = Math.max(0.01f, epsilonMm * 4f);
        for (int vertex : loop) {
            int offset = vertex * 3;
            float distance = (vertices.get(offset) - px) * nx
                    + (vertices.get(offset + 1) - py) * ny
                    + (vertices.get(offset + 2) - pz) * nz;
            if (Math.abs(distance) > tolerance) return false;
        }
        int axis = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz) ? 0
                : (Math.abs(ny) >= Math.abs(nz) ? 1 : 2);
        int sign = 0;
        for (int index = 0; index < loop.size(); index++) {
            int a = loop.get(index) * 3, b = loop.get((index + 1) % loop.size()) * 3,
                    c = loop.get((index + 2) % loop.size()) * 3;
            float ax, ay, bx, by;
            if (axis == 0) { ax = vertices.get(a + 1); ay = vertices.get(a + 2); bx = vertices.get(b + 1); by = vertices.get(b + 2); }
            else if (axis == 1) { ax = vertices.get(a); ay = vertices.get(a + 2); bx = vertices.get(b); by = vertices.get(b + 2); }
            else { ax = vertices.get(a); ay = vertices.get(a + 1); bx = vertices.get(b); by = vertices.get(b + 1); }
            float cx, cy;
            if (axis == 0) { cx = vertices.get(c + 1); cy = vertices.get(c + 2); }
            else if (axis == 1) { cx = vertices.get(c); cy = vertices.get(c + 2); }
            else { cx = vertices.get(c); cy = vertices.get(c + 1); }
            float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
            if (Math.abs(cross) <= 0.000001f) return false;
            int current = cross > 0f ? 1 : -1;
            if (sign == 0) sign = current;
            else if (sign != current) return false;
        }
        return true;
    }

    private static int repairVertexFromSource(int originalIndex, float epsilonMm,
                                              HashMap<RepairVertexKey, ArrayList<Integer>> buckets,
                                              HashMap<Integer, Integer> remapped,
                                              ArrayList<Float> output, float[] source) {
        Integer cached = remapped.get(originalIndex);
        if (cached != null) return cached;
        int offset = originalIndex * 3;
        float x = source[offset], y = source[offset + 1], z = source[offset + 2];
        RepairVertexKey key = new RepairVertexKey(x, y, z, epsilonMm);
        ArrayList<Integer> candidates = buckets.get(key);
        float epsilonSquared = epsilonMm * epsilonMm;
        if (candidates != null) {
            for (int candidate : candidates) {
                int candidateOffset = candidate * 3;
                float dx = output.get(candidateOffset) - x;
                float dy = output.get(candidateOffset + 1) - y;
                float dz = output.get(candidateOffset + 2) - z;
                if (dx * dx + dy * dy + dz * dz <= epsilonSquared) {
                    remapped.put(originalIndex, candidate);
                    return candidate;
                }
            }
        }
        int next = output.size() / 3;
        output.add(x); output.add(y); output.add(z);
        if (candidates == null) {
            candidates = new ArrayList<>();
            buckets.put(key, candidates);
        }
        candidates.add(next);
        remapped.put(originalIndex, next);
        return next;
    }

    private static boolean zeroAreaByVertexIndex(float[] coordinates, int first, int second, int third) {
        return zeroArea(coordinates, first * 3, second * 3, third * 3);
    }

    private static boolean zeroArea(float[] coordinates, int a, int b, int c) {
        float ux = coordinates[b] - coordinates[a], uy = coordinates[b + 1] - coordinates[a + 1], uz = coordinates[b + 2] - coordinates[a + 2];
        float vx = coordinates[c] - coordinates[a], vy = coordinates[c + 1] - coordinates[a + 1], vz = coordinates[c + 2] - coordinates[a + 2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return !finite(nx) || !finite(ny) || !finite(nz) || nx * nx + ny * ny + nz * nz <= 0.00000001f;
    }

    private static boolean zeroArea(ArrayList<Float> coordinates, int first, int second, int third) {
        int a = first * 3, b = second * 3, c = third * 3;
        float ux = coordinates.get(b) - coordinates.get(a);
        float uy = coordinates.get(b + 1) - coordinates.get(a + 1);
        float uz = coordinates.get(b + 2) - coordinates.get(a + 2);
        float vx = coordinates.get(c) - coordinates.get(a);
        float vy = coordinates.get(c + 1) - coordinates.get(a + 1);
        float vz = coordinates.get(c + 2) - coordinates.get(a + 2);
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return !finite(nx) || !finite(ny) || !finite(nz) || nx * nx + ny * ny + nz * nz <= 0.00000001f;
    }

    private static int normalizeClosedWinding(ArrayList<Float> vertices, ArrayList<Integer> triangles,
                                              int triangleStart, int triangleCount) {
        if (triangleCount < 2) return 0;
        HashMap<Long, ArrayList<RepairEdge>> edges = new HashMap<>();
        for (int local = 0; local < triangleCount; local++) {
            int offset = (triangleStart + local) * 3;
            addRepairEdge(edges, local, triangles.get(offset), triangles.get(offset + 1));
            addRepairEdge(edges, local, triangles.get(offset + 1), triangles.get(offset + 2));
            addRepairEdge(edges, local, triangles.get(offset + 2), triangles.get(offset));
        }
        boolean[] visited = new boolean[triangleCount];
        boolean[] flip = new boolean[triangleCount];
        int flipped = 0;
        for (int root = 0; root < triangleCount; root++) {
            if (visited[root]) continue;
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            ArrayList<Integer> component = new ArrayList<>();
            visited[root] = true;
            pending.add(root);
            while (!pending.isEmpty()) {
                int local = pending.removeFirst();
                component.add(local);
                int offset = (triangleStart + local) * 3;
                int[] original = new int[]{triangles.get(offset), triangles.get(offset + 1), triangles.get(offset + 2)};
                for (int edgeIndex = 0; edgeIndex < 3; edgeIndex++) {
                    int from = original[edgeIndex];
                    int to = original[(edgeIndex + 1) % 3];
                    if (flip[local]) {
                        int swap = from; from = to; to = swap;
                    }
                    ArrayList<RepairEdge> uses = edges.get(edgeKey(from, to));
                    if (uses == null || uses.size() != 2) continue;
                    for (RepairEdge use : uses) {
                        if (use.triangle == local) continue;
                        boolean shouldFlip = use.from == from && use.to == to;
                        if (!visited[use.triangle]) {
                            visited[use.triangle] = true;
                            flip[use.triangle] = shouldFlip;
                            pending.add(use.triangle);
                        }
                    }
                }
            }

            int componentConsistencyFlips = 0;
            for (int local : component) {
                if (!flip[local]) continue;
                int offset = (triangleStart + local) * 3;
                int swap = triangles.get(offset + 1);
                triangles.set(offset + 1, triangles.get(offset + 2));
                triangles.set(offset + 2, swap);
                componentConsistencyFlips++;
            }
            flipped += componentConsistencyFlips;

            boolean closed = true;
            boolean[] inComponent = new boolean[triangleCount];
            for (int local : component) inComponent[local] = true;
            for (int local : component) {
                int offset = (triangleStart + local) * 3;
                for (int edgeIndex = 0; edgeIndex < 3; edgeIndex++) {
                    int from = triangles.get(offset + edgeIndex);
                    int to = triangles.get(offset + ((edgeIndex + 1) % 3));
                    ArrayList<RepairEdge> uses = edges.get(edgeKey(from, to));
                    if (uses == null || uses.size() != 2) {
                        closed = false;
                        break;
                    }
                    int other = uses.get(0).triangle == local ? uses.get(1).triangle : uses.get(0).triangle;
                    if (!inComponent[other]) {
                        closed = false;
                        break;
                    }
                }
                if (!closed) break;
            }
            if (!closed) continue;
            double signedVolume = 0d;
            for (int local : component) {
                int offset = (triangleStart + local) * 3;
                int a = triangles.get(offset) * 3;
                int b = triangles.get(offset + 1) * 3;
                int c = triangles.get(offset + 2) * 3;
                double ax = vertices.get(a), ay = vertices.get(a + 1), az = vertices.get(a + 2);
                double bx = vertices.get(b), by = vertices.get(b + 1), bz = vertices.get(b + 2);
                double cx = vertices.get(c), cy = vertices.get(c + 1), cz = vertices.get(c + 2);
                signedVolume += ax * (by * cz - bz * cy)
                        - ay * (bx * cz - bz * cx)
                        + az * (bx * cy - by * cx);
            }
            if (signedVolume >= -0.000000001d) continue;
            for (int local : component) {
                int offset = (triangleStart + local) * 3;
                int swap = triangles.get(offset + 1);
                triangles.set(offset + 1, triangles.get(offset + 2));
                triangles.set(offset + 2, swap);
                flipped++;
            }
        }
        return flipped;
    }

    private static void addRepairEdge(HashMap<Long, ArrayList<RepairEdge>> edges,
                                      int triangle, int from, int to) {
        if (from == to) return;
        long key = edgeKey(from, to);
        ArrayList<RepairEdge> uses = edges.get(key);
        if (uses == null) {
            uses = new ArrayList<>();
            edges.put(key, uses);
        }
        uses.add(new RepairEdge(triangle, from, to));
    }

    private static long edgeKey(int first, int second) {
        int low = Math.min(first, second), high = Math.max(first, second);
        return ((long) low << 32) | (high & 0xffffffffL);
    }

    private static final class RepairEdge {
        final int triangle;
        final int from;
        final int to;

        RepairEdge(int triangle, int from, int to) {
            this.triangle = triangle;
            this.from = from;
            this.to = to;
        }
    }

    private static final class FillStats {
        final int holes;
        final int triangles;

        FillStats() { this(0, 0); }

        FillStats(int holes, int triangles) {
            this.holes = holes;
            this.triangles = triangles;
        }
    }

    private static final class RepairVertexKey {
        final long x, y, z;

        RepairVertexKey(float x, float y, float z, float epsilon) {
            this.x = Math.round(x / epsilon);
            this.y = Math.round(y / epsilon);
            this.z = Math.round(z / epsilon);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof RepairVertexKey)) return false;
            RepairVertexKey key = (RepairVertexKey) other;
            return x == key.x && y == key.y && z == key.z;
        }

        @Override public int hashCode() {
            long value = x * 31L + y * 17L + z;
            return (int) (value ^ (value >>> 32));
        }
    }

    private static final class RepairTriangleKey {
        final int a, b, c;

        RepairTriangleKey(int first, int second, int third) {
            int low = Math.min(first, Math.min(second, third));
            int high = Math.max(first, Math.max(second, third));
            int middle = first + second + third - low - high;
            a = low; b = middle; c = high;
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof RepairTriangleKey)) return false;
            RepairTriangleKey key = (RepairTriangleKey) other;
            return a == key.a && b == key.b && c == key.c;
        }

        @Override public int hashCode() { return (a * 31 + b) * 31 + c; }
    }

    public static final class RepairResult {
        public final MeshModel mesh;
        public final RepairReport report;

        RepairResult(MeshModel mesh, RepairReport report) {
            this.mesh = mesh;
            this.report = report;
        }
    }

    public static final class RepairReport {
        public final int sourceTriangleCount;
        public final int outputTriangleCount;
        public final int removedDegenerateTriangles;
        public final int removedDuplicateTriangles;
        public final int weldedVertices;
        public final int flippedTriangles;
        public final int filledPlanarHoles;
        public final float epsilonMm;

        RepairReport(int sourceTriangleCount, int outputTriangleCount,
                     int removedDegenerateTriangles, int removedDuplicateTriangles,
                     int weldedVertices, int flippedTriangles, int filledPlanarHoles,
                     float epsilonMm) {
            this.sourceTriangleCount = sourceTriangleCount;
            this.outputTriangleCount = outputTriangleCount;
            this.removedDegenerateTriangles = removedDegenerateTriangles;
            this.removedDuplicateTriangles = removedDuplicateTriangles;
            this.weldedVertices = weldedVertices;
            this.flippedTriangles = flippedTriangles;
            this.filledPlanarHoles = filledPlanarHoles;
            this.epsilonMm = epsilonMm;
        }

        public boolean changed() {
            return removedDegenerateTriangles > 0 || removedDuplicateTriangles > 0
                    || weldedVertices > 0 || flippedTriangles > 0 || filledPlanarHoles > 0;
        }

        public String summary() {
            if (!changed()) return "No repair needed; mesh is already clean";
            ArrayList<String> changes = new ArrayList<>();
            if (removedDegenerateTriangles > 0) changes.add("removed " + removedDegenerateTriangles + " degenerate facet(s)");
            if (removedDuplicateTriangles > 0) changes.add("removed " + removedDuplicateTriangles + " duplicate facet(s)");
            if (weldedVertices > 0) changes.add("welded " + weldedVertices + " near-duplicate vertex/vertices");
            if (flippedTriangles > 0) changes.add("normalized " + flippedTriangles + " closed-component facet winding");
            if (filledPlanarHoles > 0) changes.add("closed " + filledPlanarHoles + " planar hole(s)");
            return "Geometry repaired · " + join(changes, " · ");
        }

        private static String join(ArrayList<String> values, String separator) {
            StringBuilder output = new StringBuilder();
            for (String value : values) {
                if (output.length() > 0) output.append(separator);
                output.append(value);
            }
            return output.toString();
        }
    }

    private boolean zeroArea(int a, int b, int c) {
        float ux = vertices[b] - vertices[a], uy = vertices[b + 1] - vertices[a + 1], uz = vertices[b + 2] - vertices[a + 2];
        float vx = vertices[c] - vertices[a], vy = vertices[c + 1] - vertices[a + 1], vz = vertices[c + 2] - vertices[a + 2];
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        return !finite(nx) || !finite(ny) || !finite(nz) || nx * nx + ny * ny + nz * nz <= 0.00000001f;
    }

    private static void addEdge(HashMap<Long, Integer> edgeCounts, int first, int second) {
        if (first == second) return;
        int low = Math.min(first, second), high = Math.max(first, second);
        long key = ((long) low << 32) | (high & 0xffffffffL);
        Integer count = edgeCounts.get(key);
        edgeCounts.put(key, count == null ? 1 : count + 1);
    }

    public static final class GeometryReport {
        public final int triangleCount;
        public final int degenerateTriangles;
        public final int boundaryEdges;
        public final int nonManifoldEdges;
        public final boolean topologyChecked;

        GeometryReport(int triangleCount, int degenerateTriangles, int boundaryEdges, int nonManifoldEdges, boolean topologyChecked) {
            this.triangleCount = triangleCount;
            this.degenerateTriangles = degenerateTriangles;
            this.boundaryEdges = boundaryEdges;
            this.nonManifoldEdges = nonManifoldEdges;
            this.topologyChecked = topologyChecked;
        }

        public boolean isWatertight() {
            return topologyChecked && degenerateTriangles == 0 && boundaryEdges == 0 && nonManifoldEdges == 0;
        }

        public String summary() {
            if (!topologyChecked) return degenerateTriangles == 0 ? "health checked · topology deferred" : degenerateTriangles + " degenerate facet(s)";
            if (isWatertight()) return "watertight mesh";
            if (degenerateTriangles > 0) return degenerateTriangles + " degenerate facet(s)";
            if (nonManifoldEdges > 0) return nonManifoldEdges + " non-manifold edge(s)";
            return boundaryEdges + " open edge(s)";
        }
    }

    public static final class PartBounds {
        public final float minX, maxX, minY, maxY, minZ, maxZ;
        public final int triangleCount;

        PartBounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ, int triangleCount) {
            this.minX = minX; this.maxX = maxX;
            this.minY = minY; this.maxY = maxY;
            this.minZ = minZ; this.maxZ = maxZ;
            this.triangleCount = triangleCount;
        }

        public float width() { return maxX - minX; }
        public float depth() { return maxY - minY; }
        public float height() { return maxZ - minZ; }

        /**
         * Conservative 3D broad-phase collision test for preparation review.
         * This is intentionally an AABB test: a positive result is a warning
         * that needs inspection, never proof of a true solid intersection.
         */
        public boolean intersects(PartBounds other, float clearanceMm) {
            if (other == null || Float.isNaN(clearanceMm) || Float.isInfinite(clearanceMm)
                    || clearanceMm < 0f) return false;
            return overlap(minX, maxX, other.minX, other.maxX, clearanceMm)
                    && overlap(minY, maxY, other.minY, other.maxY, clearanceMm)
                    && overlap(minZ, maxZ, other.minZ, other.maxZ, clearanceMm);
        }

        private static boolean overlap(float leftMin, float leftMax,
                                       float rightMin, float rightMax, float clearanceMm) {
            return Math.min(leftMax, rightMax) - Math.max(leftMin, rightMin) > clearanceMm;
        }
    }

    public static final class Part {
        public final String name;
        public final int triangleStart;
        public final int triangleCount;

        Part(String name, int triangleStart, int triangleCount) {
            String trimmed = name == null || name.trim().length() == 0 ? "Part" : name.trim();
            this.name = trimmed.length() > 120 ? trimmed.substring(0, 117) + "..." : trimmed;
            this.triangleStart = triangleStart;
            this.triangleCount = triangleCount;
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int n;
        long total = 0;
        while ((n = input.read(buffer)) != -1) {
            total += n;
            if (total > MAX_INPUT_BYTES) throw new IOException("Model file exceeds the 256 MB import limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
