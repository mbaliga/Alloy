package com.mbaliga.alloy;

import java.util.Locale;

/**
 * The small, explicit boundary between a slicer's aggregate output and the
 * consumable information shown to a person. A total filament length does not
 * prove a separately weighed support, purge, or prime amount, so those remain
 * unavailable unless an engine reports them independently.
 */
public final class PrintPlanEstimate {
    /**
     * The one filament figure emitted by the slicer. Its exact meaning is
     * carried by {@link #filamentScope}; it must never be presented as a
     * model-only figure when supports are enabled.
     */
    public final float reportedFilamentMm;
    public final float approximateReportedFilamentGrams;
    /** Describes the scope of the engine's single reported filament figure. */
    public final String filamentScope;
    public final String supportMaterial;
    public final String primePurgeCleaning;
    public final String time;

    private PrintPlanEstimate(float reportedFilamentMm, float approximateReportedFilamentGrams, String filamentScope,
                              String supportMaterial, String primePurgeCleaning, String time) {
        this.reportedFilamentMm = reportedFilamentMm;
        this.approximateReportedFilamentGrams = approximateReportedFilamentGrams;
        this.filamentScope = filamentScope;
        this.supportMaterial = supportMaterial;
        this.primePurgeCleaning = primePurgeCleaning;
        this.time = time;
    }

    public static PrintPlanEstimate from(Slicer.Result slice, Slicer.Config config) {
        if (slice == null || config == null) throw new IllegalArgumentException("slice and config are required");
        String support;
        if (slice.supportFilamentMm > 0f) {
            support = String.format(Locale.US, "%.1f mm  ·  about %.1f g in support-tagged moves (same selected material density).",
                    slice.supportFilamentMm, grams(slice.supportFilamentMm, config.filamentDiameter,
                            config.filament, config.nativeSettings.get("filament_density")));
        } else if (slice.supportFilamentMm == 0f) {
            support = config.supports
                    ? "0 g  ·  no support-tagged extrusion was emitted."
                    : "0 g  ·  supports are off for this recipe.";
        } else if (!config.supports) {
            support = "0 g  ·  supports are off for this recipe.";
        } else {
            support = "Not reported separately; included, if present, in the engine's total filament figure.";
        }
        String scope = config.supports || slice.supportFilamentMm > 0f
                ? "ENGINE FILAMENT TOTAL"
                : "MODEL TOOLPATH FILAMENT";
        String time = formatTime(slice);
        String startup;
        if (slice.startupFilamentMm >= 0f) {
            startup = String.format(Locale.US,
                    "%.1f mm pre-layer positive-E in exported G-code  ·  about %.1f g. "
                            + "Printer-firmware flush and cleaning outside this G-code are not included.",
                    slice.startupFilamentMm, grams(slice.startupFilamentMm, config.filamentDiameter,
                            config.filament, config.nativeSettings.get("filament_density")));
        } else {
            startup = "Not included in this estimate. Startup priming, purge and cleaning waste are not currently modeled.";
        }
        return new PrintPlanEstimate(slice.filamentMm,
                grams(slice.filamentMm, config.filamentDiameter, config.filament,
                        config.nativeSettings.get("filament_density")),
                scope,
                support,
                startup,
                time);
    }

    static String formatTime(Slicer.Result slice) {
        if (slice == null || slice.printTimeSeconds < 0f) return "No time estimate reported";
        String source;
        if (Slicer.Result.PRINT_TIME_SOURCE_ESTIMATED.equals(slice.printTimeSource))
            source = "slicer estimate; actual time may vary";
        else if (Slicer.Result.PRINT_TIME_SOURCE_MODEL.equals(slice.printTimeSource))
            source = "model time from file; actual time may vary";
        else source = "slicer-reported time; actual time may vary";
        return duration(slice.printTimeSeconds) + "  ·  " + source;
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
