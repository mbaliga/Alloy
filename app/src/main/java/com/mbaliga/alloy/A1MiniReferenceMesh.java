package com.mbaliga.alloy;

import android.content.res.AssetManager;
import android.util.Base64;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * Loads the optional, user-supplied A1 mini presentation mesh.
 *
 * The asset is sourced from the owner-provided visual-review material and is
 * packaged in the product release. Builds for a separate distribution can
 * explicitly omit it with alloyIncludeSuppliedReferenceVisuals=false; in that
 * case the renderer safely falls back to Alloy-owned presentation geometry.
 */
final class A1MiniReferenceMesh {
    private static final String ASSET = "visuals/a1-mini-reference.mesh";
    private static final int MAX_VERTICES = 100_000;
    private static final int MAX_TRIANGLES = 200_000;

    private A1MiniReferenceMesh() { }

    static MeshModel tryLoad(AssetManager assets) {
        if (assets == null) return null;
        try (InputStream input = assets.open(ASSET)) {
            return read(input);
        } catch (Exception ignored) {
            // Missing optional presentation content must never prevent the
            // slicer UI. The renderer remains safe for explicitly stripped
            // distribution variants.
            return null;
        }
    }

    private static MeshModel read(InputStream input) throws IOException {
        HashMap<String, String> fields = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.length() == 0 || line.startsWith("#")) continue;
                int split = line.indexOf('=');
                if (split <= 0 || split == line.length() - 1) throw new IOException("Malformed A1 reference field");
                fields.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        if (!"1".equals(fields.get("version"))) throw new IOException("Unsupported A1 reference version");
        float scale = parseFinite(fields.get("scale"), "scale");
        float ox = parseFinite(fields.get("originX"), "originX");
        float oy = parseFinite(fields.get("originY"), "originY");
        float oz = parseFinite(fields.get("originZ"), "originZ");
        byte[] positionBytes = decode(fields.get("positions"), "positions");
        byte[] normalBytes = decode(fields.get("normals"), "normals");
        byte[] indexBytes = decode(fields.get("indices"), "indices");
        if (positionBytes.length % 6 != 0 || normalBytes.length % 3 != 0)
            throw new IOException("A1 reference vertex payload is incomplete");
        int vertexCount = positionBytes.length / 6;
        if (vertexCount == 0 || vertexCount > MAX_VERTICES || normalBytes.length != vertexCount * 3)
            throw new IOException("A1 reference vertex count is invalid");
        boolean u32 = "true".equalsIgnoreCase(fields.get("indices32"));
        int indexWidth = u32 ? 4 : 2;
        if (indexBytes.length % indexWidth != 0) throw new IOException("A1 reference index payload is incomplete");
        int indexCount = indexBytes.length / indexWidth;
        if (indexCount == 0 || indexCount % 3 != 0 || indexCount / 3 > MAX_TRIANGLES)
            throw new IOException("A1 reference triangle count is invalid");

        ArrayList<Float> vertices = new ArrayList<>(vertexCount * 3);
        float[] displayNormals = new float[vertexCount * 3];
        ByteBuffer q = ByteBuffer.wrap(positionBytes).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer nq = ByteBuffer.wrap(normalBytes).order(ByteOrder.LITTLE_ENDIAN);
        // The supplied Three.js scene uses x/z as the plate plane and y as
        // height. Alloy uses x/y as the plate plane and z as height. The
        // +20mm depth offset is the same measured presentation nudge used by
        // the supplied preview, not a slicer or collision transform.
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            float machineX = (q.getShort() & 0xffff) * scale + ox;
            float machineHeight = (q.getShort() & 0xffff) * scale + oy;
            float machineDepth = (q.getShort() & 0xffff) * scale + oz;
            vertices.add(machineX + 90f);
            vertices.add(machineDepth + 110f);
            vertices.add(machineHeight);
            // extract_mesh.py bakes normals in the source scene's (x, y,
            // z) = (width, height, depth) basis. Swap the latter two axes to
            // match Alloy's (x, y, z) = (width, depth, height) basis.
            float nx = nq.get() / 127f;
            float sourceHeightNormal = nq.get() / 127f;
            float sourceDepthNormal = nq.get() / 127f;
            displayNormals[vertex * 3] = nx;
            displayNormals[vertex * 3 + 1] = sourceDepthNormal;
            displayNormals[vertex * 3 + 2] = sourceHeightNormal;
        }

        ArrayList<Integer> triangles = new ArrayList<>(indexCount);
        ByteBuffer indices = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN);
        for (int index = 0; index < indexCount; index++) {
            long value = u32 ? Integer.toUnsignedLong(indices.getInt()) : (indices.getShort() & 0xffffL);
            if (value >= vertexCount) throw new IOException("A1 reference index is out of range");
            triangles.add((int) value);
        }
        ArrayList<MeshModel.Part> parts = new ArrayList<>();
        parts.add(new MeshModel.Part("A1 mini reference machine", 0, indexCount / 3));
        return MeshModel.generated("A1 mini reference machine", vertices, triangles, parts, displayNormals);
    }

    private static byte[] decode(String value, String field) throws IOException {
        if (value == null || value.length() == 0) throw new IOException("A1 reference " + field + " is missing");
        try {
            return Base64.decode(value, Base64.NO_WRAP);
        } catch (IllegalArgumentException error) {
            throw new IOException("A1 reference " + field + " is not base64", error);
        }
    }

    private static float parseFinite(String value, String field) throws IOException {
        try {
            float parsed = Float.parseFloat(value);
            if (Float.isNaN(parsed) || Float.isInfinite(parsed)) throw new NumberFormatException("non-finite");
            return parsed;
        } catch (Exception error) {
            throw new IOException(String.format(Locale.US, "A1 reference %s is invalid", field), error);
        }
    }
}
