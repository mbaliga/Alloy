package com.mbaliga.alloy;

/**
 * Keeps support-generation provenance separate from the general slicer
 * provenance. A native library can load and produce valid G-code while its
 * support engine still differs materially from Bambu Studio's current
 * TreeSupport3D implementation.
 */
final class SupportEngineStatus {
    static final String BAMBU_TREESUPPORT3D_ENGINE_ID = "bambu-treesupport3d";

    private SupportEngineStatus() { }

    static boolean requested(Slicer.Config config) {
        return config != null && config.supports;
    }

    static boolean physicalPrintReady(Slicer.Config config, Slicer.Result result) {
        if (!requested(config)) return true;
        return result != null
                && result.engineVerified
                && BAMBU_TREESUPPORT3D_ENGINE_ID.equals(result.engineId);
    }

    static String detail(Slicer.Config config, Slicer.Result result) {
        if (!requested(config)) return "Supports are disabled for this recipe";
        if (result == null) return "Slice with the production support engine first";
        if (BAMBU_TREESUPPORT3D_ENGINE_ID.equals(result.engineId)) {
            return result.engineVerified
                    ? "Bambu TreeSupport3D result is verified"
                    : "Bambu TreeSupport3D result is not yet verified for physical printing";
        }
        String style = config.nativeSettings.get("support_material_style");
        if ("organic".equalsIgnoreCase(style) || "tree".equalsIgnoreCase(style)) {
            return "Current result uses the legacy SliceBeam organic/tree path; Bambu tree(auto) parity is not available";
        }
        return "Current support result is not produced by Bambu TreeSupport3D";
    }

    static String preparationWarning(Slicer.Config config) {
        if (!requested(config)) return "";
        String style = config.nativeSettings.get("support_material_style");
        if ("organic".equalsIgnoreCase(style) || "tree".equalsIgnoreCase(style)) {
            return "Tree supports are currently legacy SliceBeam organic output; Bambu tree(auto) parity is not available for production printing";
        }
        return "Support output is not yet verified against Bambu Studio for production printing";
    }

    static String exportWarning(Slicer.Config config, Slicer.Result result) {
        return physicalPrintReady(config, result) ? "" : detail(config, result);
    }
}
