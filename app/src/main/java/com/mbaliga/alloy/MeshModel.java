package com.mbaliga.alloy;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;

/** Small, dependency-free STL and 3MF mesh reader for the v1 import path. */
public final class MeshModel {
    public final float[] vertices;
    public final int[] triangles;
    public final String displayName;
    public final float minX, maxX, minY, maxY, minZ, maxZ;

    private MeshModel(float[] vertices, int[] triangles, String displayName) {
        this.vertices = vertices;
        this.triangles = triangles;
        this.displayName = displayName;
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
        byte[] data = readAll(source);
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".3mf")) return read3mf(name, data);
        return readStl(name, data);
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
                return create(name, vs, ts);
            }
        }
        String text = new String(data, "UTF-8");
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Integer> ts = new ArrayList<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String s = line.trim();
            if (!s.toLowerCase(Locale.US).startsWith("vertex ")) continue;
            String[] p = s.split("\\s+");
            if (p.length < 4) continue;
            vs.add(Float.parseFloat(p[1])); vs.add(Float.parseFloat(p[2])); vs.add(Float.parseFloat(p[3]));
            ts.add(vs.size() / 3 - 1);
        }
        if (ts.size() < 3 || ts.size() % 3 != 0) throw new IOException("STL contains no complete triangles");
        return create(name, vs, ts);
    }

    private static MeshModel read3mf(String name, byte[] data) throws IOException {
        ArrayList<Float> vs = new ArrayList<>();
        ArrayList<Integer> ts = new ArrayList<>();
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().startsWith("3D/") || !entry.getName().endsWith(".model")) continue;
                XmlPullParser parser = Xml.newPullParser();
                parser.setInput(zip, "UTF-8");
                int event;
                while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                    if (event != XmlPullParser.START_TAG) continue;
                    String tag = parser.getName();
                    if ("vertex".equals(tag)) {
                        vs.add(Float.parseFloat(parser.getAttributeValue(null, "x")));
                        vs.add(Float.parseFloat(parser.getAttributeValue(null, "y")));
                        vs.add(Float.parseFloat(parser.getAttributeValue(null, "z")));
                    } else if ("triangle".equals(tag)) {
                        ts.add(Integer.parseInt(parser.getAttributeValue(null, "v1")));
                        ts.add(Integer.parseInt(parser.getAttributeValue(null, "v2")));
                        ts.add(Integer.parseInt(parser.getAttributeValue(null, "v3")));
                    }
                }
                break;
            }
        } catch (Exception e) {
            throw new IOException("Could not read 3MF model: " + e.getMessage(), e);
        }
        if (ts.size() < 3) throw new IOException("3MF contains no mesh triangles");
        return create(name, vs, ts);
    }

    private static MeshModel create(String name, ArrayList<Float> vs, ArrayList<Integer> ts) {
        float[] vertices = new float[vs.size()];
        int[] triangles = new int[ts.size()];
        for (int i = 0; i < vs.size(); i++) vertices[i] = vs.get(i);
        for (int i = 0; i < ts.size(); i++) triangles[i] = ts.get(i);
        return new MeshModel(vertices, triangles, name);
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int n;
        while ((n = input.read(buffer)) != -1) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
