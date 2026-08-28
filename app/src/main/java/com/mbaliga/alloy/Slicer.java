package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Locale;

/**
 * Conservative, offline mesh slicer used by Alloy v1. It produces ordinary
 * G-code and is intentionally independent of the rejected Orca-Mobile fork.
 * The UI labels the result as a v1 slice so it cannot be mistaken for a
 * proven Bambu Studio/Orca parity result.
 */
public final class Slicer {
    public static final class Config {
        public float layerHeight = 0.20f;
        public float nozzle = 0.40f;
        public float infill = 0.15f;
        public float bedX = 180f, bedY = 180f, bedZ = 180f;
        public String printer = "Bambu Lab A1 Mini";
        public String filament = "PLA";
    }

    public interface ProgressListener { void onProgress(int percent, String phase); }

    public static final class Point {
        public final float x, y;
        public Point(float x, float y) { this.x = x; this.y = y; }
    }

    public static final class Segment {
        public final Point a, b;
        public Segment(Point a, Point b) { this.a = a; this.b = b; }
    }

    public static final class Layer {
        public final int index;
        public final float z;
        public final ArrayList<Segment> segments = new ArrayList<>();
        public Layer(int index, float z) { this.index = index; this.z = z; }
    }

    public static final class Result {
        public final String gcode;
        public final ArrayList<Layer> layers;
        public final float filamentMm;
        public final int warnings;
        public Result(String gcode, ArrayList<Layer> layers, float filamentMm, int warnings) {
            this.gcode = gcode; this.layers = layers; this.filamentMm = filamentMm; this.warnings = warnings;
        }
    }

    private static final float EPS = 0.0001f;

    public Result slice(MeshModel mesh, Config config, ProgressListener listener) {
        if (mesh.vertices.length == 0 || mesh.triangles.length == 0) throw new IllegalArgumentException("Empty mesh");
        float height = mesh.maxZ - mesh.minZ;
        if (height <= EPS) throw new IllegalArgumentException("Model has no height");
        int layerCount = Math.max(1, (int) Math.ceil(height / config.layerHeight));
        float width = mesh.maxX - mesh.minX, depth = mesh.maxY - mesh.minY;
        if (width > config.bedX || depth > config.bedY || height > config.bedZ)
            throw new IllegalArgumentException(String.format(Locale.US,
                    "Model is %.1f × %.1f × %.1f mm; A1 Mini volume is 180 × 180 × 180 mm",
                    width, depth, height));
        float shiftX = config.bedX / 2f - (mesh.minX + mesh.maxX) / 2f;
        float shiftY = config.bedY / 2f - (mesh.minY + mesh.maxY) / 2f;
        ArrayList<Layer> layers = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        out.append("; Alloy v1 offline slice\n");
        out.append("; printer = ").append(config.printer).append("\n");
        out.append("; filament = ").append(config.filament).append("\n");
        out.append("; layer_height = ").append(String.format(Locale.US, "%.2f", config.layerHeight)).append("\n");
        out.append("; WARNING: validate this artifact before physical printing\n");
        out.append("G90\nM83\nG92 E0\nM107\n");
        float filament = 0f;
        float totalTravel = 0f;
        for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
            float plane = mesh.minZ + Math.min(height - EPS, (layerIndex + 0.5f) * config.layerHeight);
            Layer layer = new Layer(layerIndex, (layerIndex + 1) * config.layerHeight);
            for (int t = 0; t < mesh.triangles.length; t += 3) {
                int ia = mesh.triangles[t] * 3, ib = mesh.triangles[t + 1] * 3, ic = mesh.triangles[t + 2] * 3;
                ArrayList<Point> points = new ArrayList<>();
                addUnique(points, intersection(mesh.vertices, ia, ib, plane, shiftX, shiftY));
                addUnique(points, intersection(mesh.vertices, ib, ic, plane, shiftX, shiftY));
                addUnique(points, intersection(mesh.vertices, ic, ia, plane, shiftX, shiftY));
                if (points.size() >= 2) {
                    Point p1 = points.get(0), p2 = points.get(1);
                    if (distance(p1, p2) > 0.01f) layer.segments.add(new Segment(p1, p2));
                }
            }
            out.append(";LAYER:").append(layerIndex).append("\n");
            out.append(String.format(Locale.US, "G1 Z%.3f F720\n", layer.z));
            for (Segment s : layer.segments) {
                float len = distance(s.a, s.b);
                out.append(String.format(Locale.US, "G0 X%.3f Y%.3f F9000\n", s.a.x, s.a.y));
                float e = extrusion(len, config);
                out.append(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F2400\n", s.b.x, s.b.y, e));
                filament += e; totalTravel += len;
            }
            if (config.infill > 0.001f && !layer.segments.isEmpty()) {
                float spacing = Math.max(1.0f, config.nozzle / config.infill);
                float y0 = mesh.minY + shiftY + config.nozzle;
                float y1 = mesh.maxY + shiftY - config.nozzle;
                for (float y = y0; y <= y1; y += spacing) {
                    ArrayList<Float> xs = new ArrayList<>();
                    for (Segment s : layer.segments) {
                        if ((s.a.y <= y && s.b.y > y) || (s.b.y <= y && s.a.y > y)) {
                            float x = s.a.x + (y - s.a.y) * (s.b.x - s.a.x) / (s.b.y - s.a.y);
                            xs.add(x);
                        }
                    }
                    Collections.sort(xs);
                    for (int i = 0; i + 1 < xs.size(); i += 2) {
                        float xa = xs.get(i), xb = xs.get(i + 1);
                        if (xb - xa < 0.3f) continue;
                        Point a = new Point(xa, y), b = new Point(xb, y);
                        float len = xb - xa, e = extrusion(len, config);
                        out.append(String.format(Locale.US, "G0 X%.3f Y%.3f F9000\n", a.x, a.y));
                        out.append(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F3000\n", b.x, b.y, e));
                        filament += e; totalTravel += len;
                    }
                }
            }
            layers.add(layer);
            if (listener != null) listener.onProgress((layerIndex + 1) * 100 / layerCount, "Slicing layer " + (layerIndex + 1) + " / " + layerCount);
        }
        float timeSeconds = totalTravel / 40f + layerCount * 2.5f;
        out.insert(0, String.format(Locale.US,
                "; estimated printing time (normal mode) = %dm %02ds\n; filament used [mm] = %.2f\n",
                (int) (timeSeconds / 60f), (int) timeSeconds % 60, filament));
        out.append("G1 E-1.0 F1800\nG0 X0 Y0 F9000\nM104 S0\nM140 S0\nM84\n");
        int warnings = layerCount == 1 || layers.get(0).segments.isEmpty() ? 1 : 0;
        return new Result(out.toString(), layers, filament, warnings);
    }

    private static float extrusion(float length, Config c) {
        float beadArea = c.layerHeight * c.nozzle;
        float filamentArea = (float) (Math.PI * Math.pow(1.75 / 2.0, 2));
        return length * beadArea / filamentArea;
    }

    private static Point intersection(float[] v, int ia, int ib, float z, float sx, float sy) {
        float za = v[ia + 2], zb = v[ib + 2];
        if (Math.abs(za - z) < EPS) return new Point(v[ia] + sx, v[ia + 1] + sy);
        if (Math.abs(zb - z) < EPS) return new Point(v[ib] + sx, v[ib + 1] + sy);
        if ((za < z && zb > z) || (za > z && zb < z)) {
            float t = (z - za) / (zb - za);
            return new Point(v[ia] + t * (v[ib] - v[ia]) + sx, v[ia + 1] + t * (v[ib + 1] - v[ia + 1]) + sy);
        }
        return null;
    }

    private static void addUnique(ArrayList<Point> points, Point candidate) {
        if (candidate == null) return;
        for (Point p : points) if (distance(p, candidate) < 0.01f) return;
        points.add(candidate);
    }

    private static float distance(Point a, Point b) {
        return (float) Math.hypot(a.x - b.x, a.y - b.y);
    }
}
