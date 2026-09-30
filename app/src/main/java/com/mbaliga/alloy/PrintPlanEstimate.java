package com.mbaliga.alloy;

import java.util.Locale;

/**
 * The small, explicit boundary between a slicer's aggregate output and the
 * consumable information shown to a person. A total filament length does not
 * prove a separately weighed support, purge, or prime amount, so those remain
 * unavailable unless an engine reports them independently.
 */
public final class PrintPlanEstimate {
    public final float modelFilamentMm;
    public final float approximateModelFilamentGrams;
    public final String supportMaterial;
    public final String primePurgeCleaning;
    public final String time;

    private PrintPlanEstimate(float modelFilamentMm, float approximateModelFilamentGrams,
                              String supportMaterial, String primePurgeCleaning, String time) {
        this.modelFilamentMm = modelFilamentMm;
        this.approximateModelFilamentGrams = approximateModelFilamentGrams;
        this.supportMaterial = supportMaterial;
        this.primePurgeCleaning = primePurgeCleaning;
        this.time = time;
    }

    public static PrintPlanEstimate from(Slicer.Result slice, Slicer.Config config) {
        if (slice == null || config == null) throw new IllegalArgumentException("slice and config are required");
        String support = config.supports
                ? "Not reported separately; included, if present, in the engine's total filament figure."
                : "0 g  ·  supports are off for this recipe.";
        String time = slice.printTimeSeconds < 0f ? "Not reported by engine" : duration(slice.printTimeSeconds);
        return new PrintPlanEstimate(slice.filamentMm,
                grams(slice.filamentMm, config.filamentDiameter, config.filament,
                        config.nativeSettings.get("filament_density")),
                support,
                "Not available. This is not a verified multi-material or printer-telemetry estimate.",
                time);
    }

    static float grams(float lengthMm, float diameterMm, String material, String profileDensity) {
        if (!finite(lengthMm) || lengthMm <= 0f || !finite(diameterMm) || diameterMm <= 0f) return 0f;
        float density = density(material, profileDensity);
        float volumeCm3 = (float) (lengthMm * Math.PI * diameterMm * diameterMm / 4_000d);
        return volumeCm3 * density;
    }

    static float density(String material, String profileDensity) {
        if (profileDensity != null) {
            try {
                float parsed = Float.parseFloat(profileDensity.trim());
                if (finite(parsed) && parsed >= 0.5f && parsed <= 3f) return parsed;
            } catch (NumberFormatException ignored) {
                // A malformed optional profile value never becomes a zero-gram claim.
            }
        }
        String normalized = material == null ? "" : material.toLowerCase(Locale.US);
        if (normalized.contains("petg")) return 1.27f;
        if (normalized.contains("abs")) return 1.04f;
        if (normalized.contains("asa")) return 1.07f;
        if (normalized.contains("tpu")) return 1.21f;
        return 1.24f; // PLA/default planning fallback, g/cm³.
    }

    static String duration(float seconds) {
        int total = Math.max(0, Math.round(seconds));
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int remainder = total % 60;
        return hours > 0 ? String.format(Locale.US, "%dh %02dm", hours, minutes)
                : String.format(Locale.US, "%dm %02ds", minutes, remainder);
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
