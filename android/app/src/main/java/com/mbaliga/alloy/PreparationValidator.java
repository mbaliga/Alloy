package com.mbaliga.alloy;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Fast setup validation shown before a slice job starts. This is intentionally
 * separate from ArtifactValidator: it checks the current model and recipe
 * before an engine is invoked, while ArtifactValidator checks the generated
 * result before it can be exported or staged for transport.
 */
public final class PreparationValidator {
    private static final float COLLISION_CLEARANCE_MM = 0.05f;
    private static final int MAX_COLLISION_WARNINGS = 12;

    private PreparationValidator() { }

    public static Report validate(MeshModel mesh, Slicer.Config config, ProfileCatalog.Profile profile) {
        ArrayList<String> errors = new ArrayList<>();
        ArrayList<String> warnings = new ArrayList<>();
        if (mesh == null) {
            errors.add("Import a model before slicing");
            return new Report(errors, warnings);
        }
        if (config == null) {
            errors.add("Select a print recipe before slicing");
            return new Report(errors, warnings);
        }
        if (mesh.vertices.length == 0 || mesh.triangles.length == 0) errors.add("The model contains no printable geometry");
        if (!finite(mesh.minX) || !finite(mesh.maxX) || !finite(mesh.minY) || !finite(mesh.maxY)
                || !finite(mesh.minZ) || !finite(mesh.maxZ)) errors.add("The model contains non-finite dimensions");
        if (!finite(config.bedX) || !finite(config.bedY) || !finite(config.bedZ)
                || config.bedX <= 0f || config.bedY <= 0f || config.bedZ <= 0f) {
            errors.add("The selected build volume is invalid");
        } else if (finite(mesh.minX) && finite(mesh.maxX) && finite(mesh.minY) && finite(mesh.maxY)
                && finite(mesh.minZ) && finite(mesh.maxZ)) {
            float width = mesh.maxX - mesh.minX;
            float depth = mesh.maxY - mesh.minY;
            float height = mesh.maxZ - mesh.minZ;
            if (width <= 0f || depth <= 0f || height <= 0f) errors.add("The model must have width, depth and height");
            if (width > config.bedX || depth > config.bedY || height > config.bedZ) {
                errors.add(String.format(Locale.US,
                        "Model is %.1f × %.1f × %.1f mm; build volume is %.1f × %.1f × %.1f mm",
                        width, depth, height, config.bedX, config.bedY, config.bedZ));
            }
        }
        if (!finite(config.layerHeight) || config.layerHeight < 0.01f || config.layerHeight > 2f
                || !finite(config.firstLayerHeight) || config.firstLayerHeight < 0.01f || config.firstLayerHeight > 2f)
            errors.add("Layer heights must be between 0.01 and 2.00 mm");
        if (!finite(config.infill) || config.infill < 0f || config.infill > 1f)
            errors.add("Infill must be between 0 and 100 percent");
        if (config.printer == null || config.printer.trim().length() == 0
                || config.filament == null || config.filament.trim().length() == 0)
            errors.add("Printer and material names are required");
        try {
            Slicer.validate(mesh, config);
        } catch (IllegalArgumentException error) {
            if (error.getMessage() != null && !errors.contains(error.getMessage())) errors.add(error.getMessage());
        }

        if (mesh.vertices.length > 0 && mesh.triangles.length > 0) {
            MeshModel.GeometryReport geometry = mesh.geometryReport();
            if (!geometry.isWatertight()) warnings.add("Mesh health: " + geometry.summary());
            addPartCollisionWarnings(mesh, warnings);
        }
        if (profile == null) warnings.add("No packaged profile was loaded; conservative recipe defaults are active");
        else if (!profile.verified) warnings.add("The selected profile is not approved for physical printing");
        if (config.supports) {
            String supportWarning = SupportEngineStatus.preparationWarning(config);
            if (supportWarning.length() > 0) warnings.add(supportWarning);
        }
        if (config.layerHeight > config.nozzle) warnings.add("Layer height is larger than the nozzle diameter");
        return new Report(errors, warnings);
    }

    /**
     * Broad-phase review for assemblies. Parts are frequently imported in
     * their assembled CAD positions, so this remains a warning rather than a
     * hard block; Auto-arrange or manual placement can resolve it before a
     * user acknowledges the setup review.
     */
    private static void addPartCollisionWarnings(MeshModel mesh, ArrayList<String> warnings) {
        if (mesh.parts == null || mesh.parts.length < 2) return;
        int added = 0;
        for (int left = 0; left < mesh.parts.length; left++) {
            MeshModel.PartBounds leftBounds = mesh.partBounds(left);
            for (int right = left + 1; right < mesh.parts.length; right++) {
                if (!leftBounds.intersects(mesh.partBounds(right), COLLISION_CLEARANCE_MM)) continue;
                warnings.add("Potential part collision: " + mesh.parts[left].name + " ↔ "
                        + mesh.parts[right].name + " · inspect placement or auto-arrange");
                if (++added >= MAX_COLLISION_WARNINGS) {
                    if (left + 1 < mesh.parts.length || right + 1 < mesh.parts.length)
                        warnings.add("Additional potential part collisions are not listed");
                    return;
                }
            }
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

        public boolean isReady() { return errors.isEmpty(); }

        public String message() {
            StringBuilder output = new StringBuilder();
            if (!errors.isEmpty()) {
                output.append("BLOCKED\n");
                for (String error : errors) output.append("• ").append(error).append('\n');
            }
            if (!warnings.isEmpty()) {
                if (output.length() > 0) output.append('\n');
                output.append("REVIEW\n");
                for (String warning : warnings) output.append("• ").append(warning).append('\n');
            }
            return output.length() == 0 ? "Setup passed preflight" : output.toString().trim();
        }
    }
}
