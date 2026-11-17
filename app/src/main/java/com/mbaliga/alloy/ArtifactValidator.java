package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Locale;

/** Structural preflight for an export; semantic printer safety remains a native/profile gate. */
public final class ArtifactValidator {
    private ArtifactValidator() { }

    public static Report validate(MeshModel mesh, Slicer.Result result, Slicer.Config config) {
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        if (mesh == null) errors.add("No model is selected");
        if (result == null) errors.add("No slice result is available");
        if (config == null) errors.add("No recipe is available");
        if (mesh != null) {
            MeshModel.GeometryReport geometry = mesh.geometryReport();
            if (!geometry.isWatertight()) warnings.add("Mesh health: " + geometry.summary());
        }
        if (result != null) {
            GcodeSafetyValidator.Report safety = config == null
                    ? GcodeSafetyValidator.inspect(result.gcode)
                    : GcodeSafetyValidator.inspect(result.gcode, config);
            errors.addAll(safety.errors);
            if (NativeSlicerEngine.isNativeEngineId(result.engineId)) {
                A1MiniTemplatePolicy.Report template = A1MiniTemplatePolicy.inspect(result.gcode);
                errors.addAll(template.errors);
            }
            if (result.layers == null || result.layers.isEmpty()) errors.add("No printable layers were generated");
            else if (config != null) {
                for (Slicer.Layer layer : result.layers) {
                    if (layer == null) {
                        errors.add("Slice contains a null layer");
                        continue;
                    }
                    if (layer.segments == null || layer.segments.isEmpty()) warnings.add("Layer " + (layer.index + 1) + " has no toolpath");
                    if (layer.segments == null) continue;
                    for (Slicer.Segment segment : layer.segments) {
                        if (segment == null) {
                            errors.add("Layer " + (layer.index + 1) + " contains a null segment");
                            continue;
                        }
                        checkPoint(segment.a, layer.index, "start", config, errors);
                        checkPoint(segment.b, layer.index, "end", config, errors);
                    }
                }
            }
            if (result.warnings > 0) warnings.add(result.warnings + " slicer warning(s) were reported");
            if (!result.engineVerified) warnings.add("Engine output is not verified for physical printing");
            if (config != null) {
                String supportWarning = SupportEngineStatus.exportWarning(config, result);
                if (supportWarning.length() > 0) warnings.add(supportWarning);
            }
        }
        return new Report(errors, warnings);
    }

    private static void checkPoint(Slicer.Point point, int layer, String end, Slicer.Config config, ArrayList<String> errors) {
        if (point == null || !finite(point.x) || !finite(point.y)) {
            errors.add("Layer " + (layer + 1) + " has a non-finite " + end + " point");
            return;
        }
        float tolerance = 0.01f;
        if (point.x < -tolerance || point.x > config.bedX + tolerance || point.y < -tolerance || point.y > config.bedY + tolerance) {
            errors.add(String.format(Locale.US, "Layer %d has a point outside the %.0f × %.0f mm bed", layer + 1, config.bedX, config.bedY));
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Report {
        public final ArrayList<String> errors;
        public final ArrayList<String> warnings;

        Report(ArrayList<String> errors, ArrayList<String> warnings) {
            this.errors = errors;
            this.warnings = warnings;
        }

        public boolean isValid() {
            return errors.isEmpty();
        }

        public String summary() {
            if (!errors.isEmpty()) return errors.get(0);
            if (!warnings.isEmpty()) return warnings.get(0);
            return "Artifact passed structural preflight";
        }
    }
}
