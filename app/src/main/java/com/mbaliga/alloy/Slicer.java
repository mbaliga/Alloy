package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        public float firstLayerHeight = 0.20f;
        public float nozzle = 0.40f;
        public float filamentDiameter = 1.75f;
        public float infill = 0.15f;
        public float bedX = 180f, bedY = 180f, bedZ = 180f;
        public String printer = "Bambu Lab A1 Mini";
        public String filament = "PLA";
        // These values are part of the selected material/process preset. They
        // are kept in the typed recipe so the native adapter cannot silently
        // fall back to zero-temperature or generic-motion defaults.
        public float nozzleTemperature = 220f;
        public float firstLayerNozzleTemperature = 220f;
        public float bedTemperature = 60f;
        public float firstLayerBedTemperature = 60f;
        public float extrusionMultiplier = 0.98f;
        public float maxVolumetricSpeed = 21f;
        public float travelSpeed = 700f;
        public float outerWallSpeed = 200f;
        public float innerWallSpeed = 300f;
        public float infillSpeed = 270f;
        public float initialLayerSpeed = 50f;
        public float fanMinPercent = 60f;
        public float fanMaxPercent = 80f;
        public boolean supports;
        public float supportThresholdDegrees = 30f;
        public int perimeters = 2;
        public int topLayers = 5;
        public int bottomLayers = 3;
        /** Additional safe scalar settings projected from the selected profile. */
        public final LinkedHashMap<String, String> nativeSettings = new LinkedHashMap<>();

        public Config copy() {
            Config copy = new Config();
            copy.layerHeight = layerHeight;
            copy.firstLayerHeight = firstLayerHeight;
            copy.nozzle = nozzle;
            copy.filamentDiameter = filamentDiameter;
            copy.infill = infill;
            copy.bedX = bedX;
            copy.bedY = bedY;
            copy.bedZ = bedZ;
            copy.printer = printer;
            copy.filament = filament;
            copy.nozzleTemperature = nozzleTemperature;
            copy.firstLayerNozzleTemperature = firstLayerNozzleTemperature;
            copy.bedTemperature = bedTemperature;
            copy.firstLayerBedTemperature = firstLayerBedTemperature;
            copy.extrusionMultiplier = extrusionMultiplier;
            copy.maxVolumetricSpeed = maxVolumetricSpeed;
            copy.travelSpeed = travelSpeed;
            copy.outerWallSpeed = outerWallSpeed;
            copy.innerWallSpeed = innerWallSpeed;
            copy.infillSpeed = infillSpeed;
            copy.initialLayerSpeed = initialLayerSpeed;
            copy.fanMinPercent = fanMinPercent;
            copy.fanMaxPercent = fanMaxPercent;
            copy.supports = supports;
            copy.supportThresholdDegrees = supportThresholdDegrees;
            copy.perimeters = perimeters;
            copy.topLayers = topLayers;
            copy.bottomLayers = bottomLayers;
            copy.nativeSettings.putAll(nativeSettings);
            return copy;
        }
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
        public final ArrayList<Segment> perimeterSegments = new ArrayList<>();
        public final ArrayList<Segment> supportSegments = new ArrayList<>();
        public Layer(int index, float z) { this.index = index; this.z = z; }
    }

    public static final class Result {
        public final String gcode;
        public final ArrayList<Layer> layers;
        public final float filamentMm;
        public final int warnings;
        public Result(String gcode, ArrayList<Layer> layers, float filamentMm, int warnings) {
            this(gcode, layers, filamentMm, warnings, "alloy-legacy-offline", false, -1f, -1f);
        }
        public final String engineId;
        public final boolean engineVerified;
        public final float printTimeSeconds;
        public final float travelMm;
        public Result(String gcode, ArrayList<Layer> layers, float filamentMm, int warnings,
                      String engineId, boolean engineVerified) {
            this(gcode, layers, filamentMm, warnings, engineId, engineVerified, -1f, -1f);
        }
        public Result(String gcode, ArrayList<Layer> layers, float filamentMm, int warnings,
                      String engineId, boolean engineVerified, float printTimeSeconds, float travelMm) {
            this.gcode = gcode; this.layers = layers; this.filamentMm = filamentMm; this.warnings = warnings;
            this.engineId = engineId; this.engineVerified = engineVerified;
            this.printTimeSeconds = printTimeSeconds; this.travelMm = travelMm;
        }
    }

    private static final float EPS = 0.0001f;
    private static final int MAX_SUPPORT_PROJECTIONS = 100_000;
    private static final int MAX_SUPPORT_SEGMENTS_PER_LAYER = 20_000;

    public Result slice(MeshModel mesh, Config config, ProgressListener listener) {
        validate(mesh, config);
        if (mesh.vertices.length == 0 || mesh.triangles.length == 0) throw new IllegalArgumentException("Empty mesh");
        float height = mesh.maxZ - mesh.minZ;
        if (height <= EPS) throw new IllegalArgumentException("Model has no height");
        int layerCount = height <= config.firstLayerHeight
                ? 1
                : 1 + (int) Math.ceil((height - config.firstLayerHeight) / config.layerHeight);
        long workEstimate = (long) (mesh.triangles.length / 3) * layerCount;
        if (layerCount > 5_000 || workEstimate > 50_000_000L)
            throw new IllegalArgumentException("This offline fallback is limited to simple models; enable the native engine for larger geometry");
        ArrayList<SupportProjection> supportProjections = config.supports
                ? collectSupportProjections(mesh, config) : new ArrayList<>();
        if (config.supports && (long) supportProjections.size() * layerCount > 10_000_000L)
            throw new IllegalArgumentException("This offline fallback cannot generate supports for this model; enable the native engine");
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
        out.append("; first_layer_height = ").append(String.format(Locale.US, "%.2f", config.firstLayerHeight)).append("\n");
        out.append("; WARNING: validate this artifact before physical printing\n");
        if (config.supports) {
            out.append("; support_mode = conservative_grid\n");
            out.append("; support_projections = ").append(supportProjections.size()).append("\n");
            out.append("; WARNING: fallback supports are conservative and not production-equivalent\n");
        }
        if (config.perimeters > 1) out.append("; WARNING: offline fallback emits one perimeter; requested perimeters = ").append(config.perimeters).append("\n");
        out.append(String.format(Locale.US, "M140 S%.0f\nM104 S%.0f\nM109 S%.0f\n", config.firstLayerBedTemperature,
                config.nozzleTemperature, config.firstLayerNozzleTemperature));
        out.append("G28\nG90\nM83\nG92 E0\nM107\n");
        float filament = 0f;
        float totalTravel = 0f;
        float totalPrintSeconds = 0f;
        float currentX = 0f, currentY = 0f;
        for (int layerIndex = 0; layerIndex < layerCount; layerIndex++) {
            if (layerIndex == 1) {
                out.append(String.format(Locale.US, "M104 S%.0f\nM140 S%.0f\nM106 S%.0f\n",
                        config.nozzleTemperature, config.bedTemperature, config.fanMaxPercent * 2.55f));
            }
            float plane = layerIndex == 0
                    ? mesh.minZ + Math.min(height - EPS, config.firstLayerHeight * 0.5f)
                    : mesh.minZ + Math.min(height - EPS, config.firstLayerHeight + (layerIndex - 0.5f) * config.layerHeight);
            Layer layer = new Layer(layerIndex, layerIndex == 0
                    ? config.firstLayerHeight
                    : config.firstLayerHeight + layerIndex * config.layerHeight);
            if (config.supports) addSupportSegments(layer, supportProjections, mesh.minZ, shiftX, shiftY, config);
            for (int t = 0; t < mesh.triangles.length; t += 3) {
                if ((t & 0x3fff) == 0 && Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException("Slice cancelled");
                int ia = mesh.triangles[t] * 3, ib = mesh.triangles[t + 1] * 3, ic = mesh.triangles[t + 2] * 3;
                ArrayList<Point> points = new ArrayList<>();
                addUnique(points, intersection(mesh.vertices, ia, ib, plane, shiftX, shiftY));
                addUnique(points, intersection(mesh.vertices, ib, ic, plane, shiftX, shiftY));
                addUnique(points, intersection(mesh.vertices, ic, ia, plane, shiftX, shiftY));
                if (points.size() >= 2) {
                    Point p1 = points.get(0), p2 = points.get(1);
                    if (distance(p1, p2) > 0.01f) {
                        Segment perimeter = new Segment(p1, p2);
                        layer.perimeterSegments.add(perimeter);
                        layer.segments.add(perimeter);
                    }
                }
            }
            out.append(";LAYER:").append(layerIndex).append("\n");
            out.append(String.format(Locale.US, "G1 Z%.3f F720\n", layer.z));
            if (!layer.supportSegments.isEmpty()) {
                out.append(";TYPE:Support material\n");
                for (Segment s : layer.supportSegments) {
                    float len = distance(s.a, s.b);
                    float travel = distance(new Point(currentX, currentY), s.a);
                    out.append(String.format(Locale.US, "G0 X%.3f Y%.3f F%.0f\n", s.a.x, s.a.y, config.travelSpeed * 60f));
                    float e = extrusion(len, config) * 0.75f;
                    float supportSpeed = printSpeed(config.infillSpeed, config);
                    out.append(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F%.0f\n", s.b.x, s.b.y, e, supportSpeed * 60f));
                    filament += e; totalTravel += travel;
                    totalPrintSeconds += travel / config.travelSpeed + len / supportSpeed;
                    currentX = s.b.x; currentY = s.b.y;
                }
            }
            if (!layer.perimeterSegments.isEmpty()) out.append(";TYPE:Outer wall\n");
            for (Segment s : layer.perimeterSegments) {
                float len = distance(s.a, s.b);
                float travel = distance(new Point(currentX, currentY), s.a);
                out.append(String.format(Locale.US, "G0 X%.3f Y%.3f F%.0f\n", s.a.x, s.a.y, config.travelSpeed * 60f));
                float e = extrusion(len, config);
                float wallSpeed = printSpeed(layerIndex == 0 ? config.initialLayerSpeed : config.outerWallSpeed, config);
                out.append(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F%.0f\n", s.b.x, s.b.y, e, wallSpeed * 60f));
                filament += e; totalTravel += travel;
                totalPrintSeconds += travel / config.travelSpeed + len / wallSpeed;
                currentX = s.b.x; currentY = s.b.y;
            }
            boolean solidLayer = layerIndex < config.bottomLayers
                    || layerIndex >= Math.max(0, layerCount - config.topLayers);
            float effectiveInfill = solidLayer ? 1f : config.infill;
            if (effectiveInfill > 0.001f && !layer.perimeterSegments.isEmpty()) {
                float spacing = Math.max(1.0f, config.nozzle / effectiveInfill);
                float y0 = mesh.minY + shiftY + config.nozzle;
                float y1 = mesh.maxY + shiftY - config.nozzle;
                out.append(solidLayer ? ";TYPE:Solid infill\n" : ";TYPE:Sparse infill\n");
                for (float y = y0; y <= y1; y += spacing) {
                    if (Thread.currentThread().isInterrupted())
                        throw new java.util.concurrent.CancellationException("Slice cancelled");
                    ArrayList<Float> xs = new ArrayList<>();
                    for (Segment s : layer.perimeterSegments) {
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
                        out.append(String.format(Locale.US, "G0 X%.3f Y%.3f F%.0f\n", a.x, a.y, config.travelSpeed * 60f));
                        float infillSpeed = printSpeed(config.infillSpeed, config);
                        out.append(String.format(Locale.US, "G1 X%.3f Y%.3f E%.5f F%.0f\n", b.x, b.y, e, infillSpeed * 60f));
                        // Keep the preview model honest: infill is part of the inspected toolpath.
                        layer.segments.add(new Segment(a, b));
                        float travel = distance(new Point(currentX, currentY), a);
                        filament += e; totalTravel += travel;
                        totalPrintSeconds += travel / config.travelSpeed + len / infillSpeed;
                        currentX = b.x; currentY = b.y;
                    }
                }
            }
            layers.add(layer);
            if (listener != null) listener.onProgress((layerIndex + 1) * 100 / layerCount, "Slicing layer " + (layerIndex + 1) + " / " + layerCount);
        }
        float timeSeconds = totalPrintSeconds + layerCount * 2.5f;
        out.insert(0, String.format(Locale.US,
                "; estimated printing time (normal mode) = %dm %02ds\n; filament used [mm] = %.2f\n",
                (int) (timeSeconds / 60f), (int) timeSeconds % 60, filament));
        out.append("G1 E-1.0 F1800\nG0 X0 Y0 F9000\nM104 S0\nM140 S0\nM84\n");
        int warnings = layerCount == 1 || layers.get(0).segments.isEmpty() ? 1 : 0;
        if (config.supports) warnings++;
        return new Result(out.toString(), layers, filament, warnings,
                "alloy-legacy-offline", false, timeSeconds, totalTravel);
    }

    static void validate(MeshModel mesh, Config config) {
        if (mesh == null) throw new IllegalArgumentException("No model selected");
        if (config == null) throw new IllegalArgumentException("No print recipe selected");
        if (!finite(config.layerHeight) || config.layerHeight < 0.01f || config.layerHeight > 2f
                || !finite(config.firstLayerHeight) || config.firstLayerHeight < 0.01f || config.firstLayerHeight > 2f)
            throw new IllegalArgumentException("Layer height must be between 0.01 and 2.00 mm");
        if (!finite(config.nozzle) || config.nozzle <= 0f || config.nozzle > 2f)
            throw new IllegalArgumentException("Nozzle diameter is invalid");
        if (!finite(config.filamentDiameter) || config.filamentDiameter < 1f || config.filamentDiameter > 4f)
            throw new IllegalArgumentException("Filament diameter is invalid");
        if (!finite(config.infill) || config.infill < 0f || config.infill > 1f)
            throw new IllegalArgumentException("Infill must be between 0 and 100 percent");
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
        if (!finite(config.supportThresholdDegrees) || config.supportThresholdDegrees < 0f || config.supportThresholdDegrees > 90f)
            throw new IllegalArgumentException("Support threshold must be between 0 and 90 degrees");
        if (config.perimeters < 1 || config.perimeters > 20 || config.topLayers < 0 || config.topLayers > 100
                || config.bottomLayers < 0 || config.bottomLayers > 100)
            throw new IllegalArgumentException("Wall or solid-layer count is invalid");
        if (!finite(config.bedX) || !finite(config.bedY) || !finite(config.bedZ)
                || config.bedX <= 0f || config.bedY <= 0f || config.bedZ <= 0f)
            throw new IllegalArgumentException("Build volume is invalid");
        if (config.printer == null || config.printer.trim().length() == 0
                || config.filament == null || config.filament.trim().length() == 0)
            throw new IllegalArgumentException("Printer and filament names are required");
        for (int index : mesh.triangles) {
            if (index < 0 || index * 3 + 2 >= mesh.vertices.length)
                throw new IllegalArgumentException("Mesh contains an invalid triangle index");
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static ArrayList<SupportProjection> collectSupportProjections(MeshModel mesh, Config config) {
        ArrayList<SupportProjection> result = new ArrayList<>();
        for (int t = 0; t < mesh.triangles.length; t += 3) {
            int ia = mesh.triangles[t] * 3, ib = mesh.triangles[t + 1] * 3, ic = mesh.triangles[t + 2] * 3;
            float abx = mesh.vertices[ib] - mesh.vertices[ia];
            float aby = mesh.vertices[ib + 1] - mesh.vertices[ia + 1];
            float abz = mesh.vertices[ib + 2] - mesh.vertices[ia + 2];
            float acx = mesh.vertices[ic] - mesh.vertices[ia];
            float acy = mesh.vertices[ic + 1] - mesh.vertices[ia + 1];
            float acz = mesh.vertices[ic + 2] - mesh.vertices[ia + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (normalLength <= EPS || nz >= -EPS) continue;
            float downward = -nz / normalLength;
            float angle = (float) Math.toDegrees(Math.acos(Math.max(0f, Math.min(1f, downward))));
            if (angle > config.supportThresholdDegrees) continue;
            float minZ = Math.min(mesh.vertices[ia + 2], Math.min(mesh.vertices[ib + 2], mesh.vertices[ic + 2]));
            if (minZ <= mesh.minZ + config.firstLayerHeight + EPS) continue;
            result.add(new SupportProjection(
                    mesh.vertices[ia], mesh.vertices[ia + 1],
                    mesh.vertices[ib], mesh.vertices[ib + 1],
                    mesh.vertices[ic], mesh.vertices[ic + 1], minZ));
            if (result.size() > MAX_SUPPORT_PROJECTIONS)
                throw new IllegalArgumentException("This offline fallback has too many support faces; enable the native engine");
        }
        return result;
    }

    private static void addSupportSegments(Layer layer, ArrayList<SupportProjection> projections,
                                           float modelMinZ, float shiftX, float shiftY, Config config) {
        if (projections.isEmpty()) return;
        float spacing = Math.max(2.5f, config.nozzle * 5f);
        float supportZ = modelMinZ + layer.z;
        for (SupportProjection projection : projections) {
            if (supportZ >= projection.minZ - config.layerHeight * 0.75f) continue;
            float minY = Math.max(0f, projection.minY() + shiftY);
            float maxY = Math.min(config.bedY, projection.maxY() + shiftY);
            float firstY = (float) (Math.floor(minY / spacing) * spacing + spacing * 0.5f);
            for (float y = firstY; y <= maxY + EPS; y += spacing) {
                float[] xs = supportIntersections(projection, y - shiftY);
                if (xs == null) continue;
                float left = Math.max(0f, Math.min(config.bedX, xs[0] + shiftX));
                float right = Math.max(0f, Math.min(config.bedX, xs[1] + shiftX));
                if (right - left < Math.max(0.3f, config.nozzle * 0.5f)) continue;
                Segment support = new Segment(new Point(left, y), new Point(right, y));
                layer.supportSegments.add(support);
                layer.segments.add(support);
                if (layer.supportSegments.size() > MAX_SUPPORT_SEGMENTS_PER_LAYER)
                    throw new IllegalArgumentException("This offline fallback generated too many support lines; enable the native engine");
            }
        }
    }

    private static float[] supportIntersections(SupportProjection triangle, float y) {
        float[] intersections = new float[3];
        int count = 0;
        count = addSupportEdge(intersections, count, triangle.ax, triangle.ay, triangle.bx, triangle.by, y);
        count = addSupportEdge(intersections, count, triangle.bx, triangle.by, triangle.cx, triangle.cy, y);
        count = addSupportEdge(intersections, count, triangle.cx, triangle.cy, triangle.ax, triangle.ay, y);
        if (count < 2) return null;
        for (int a = 0; a < count - 1; a++) for (int b = a + 1; b < count; b++) {
            if (intersections[b] < intersections[a]) {
                float swap = intersections[a]; intersections[a] = intersections[b]; intersections[b] = swap;
            }
        }
        return new float[]{intersections[0], intersections[count - 1]};
    }

    private static int addSupportEdge(float[] intersections, int count,
                                      float ax, float ay, float bx, float by, float y) {
        if ((ay <= y && by > y) || (by <= y && ay > y)) {
            intersections[count++] = ax + (y - ay) * (bx - ax) / (by - ay);
        }
        return count;
    }

    private static final class SupportProjection {
        final float ax, ay, bx, by, cx, cy, minZ;

        SupportProjection(float ax, float ay, float bx, float by, float cx, float cy, float minZ) {
            this.ax = ax; this.ay = ay; this.bx = bx; this.by = by;
            this.cx = cx; this.cy = cy; this.minZ = minZ;
        }

        float minY() { return Math.min(ay, Math.min(by, cy)); }
        float maxY() { return Math.max(ay, Math.max(by, cy)); }
    }

    private static float extrusion(float length, Config c) {
        float beadArea = c.layerHeight * c.nozzle;
        float filamentArea = (float) (Math.PI * Math.pow(c.filamentDiameter / 2.0, 2));
        return length * beadArea / filamentArea * c.extrusionMultiplier;
    }

    private static float printSpeed(float requested, Config c) {
        float volumetricLimit = c.maxVolumetricSpeed / Math.max(0.0001f, c.layerHeight * c.nozzle);
        return Math.min(requested, volumetricLimit);
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
