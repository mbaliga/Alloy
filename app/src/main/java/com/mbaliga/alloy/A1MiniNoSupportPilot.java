package com.mbaliga.alloy;

/**
 * Narrow, build-time-gated bridge for collecting physical acceptance evidence.
 *
 * It is deliberately not a general "beta print" switch. Normal and production
 * builds compile this class but its eligibility is always false unless the
 * explicit CI-debug physical-pilot Gradle property was supplied. Even then,
 * only untouched, Alloy-bundled fixtures under the pinned A1 Mini/PLA/no-
 * support recipe may reach the pilot transport boundary.
 */
final class A1MiniNoSupportPilot {
    static final String PROFILE_ID = "bambu.a1-mini.0.4.pla-basic";
    private static final String ASSET_PREFIX = "models/";
    private static final String[] FIXTURES = {
            "models/box-20mm.stl",
            "models/box-and-lid.stl",
            "models/mounting-block.stl",
            "models/thin-wall-frame-fixture.stl",
            "models/travel_obstacle.stl"
    };

    private A1MiniNoSupportPilot() { }

    static boolean enabled() {
        return BuildConfig.PHYSICAL_PILOT_ENABLED;
    }

    static boolean isFixtureAsset(String assetPath) {
        if (assetPath == null || !assetPath.startsWith(ASSET_PREFIX)) return false;
        for (String fixture : FIXTURES) if (fixture.equals(assetPath)) return true;
        return false;
    }

    static Verdict evaluate(ProfileCatalog.Profile profile, Slicer.Config config,
                            Slicer.Result result, String trustedFixtureAsset) {
        if (!enabled()) return new Verdict(false, "This is not a physical-pilot build");
        if (profile == null || !PROFILE_ID.equals(profile.id))
            return new Verdict(false, "Select the pinned A1 Mini 0.4 mm PLA Basic pilot profile");
        if (!isFixtureAsset(trustedFixtureAsset))
            return new Verdict(false, "Open an untouched bundled pilot fixture from Library");
        if (config == null || config.supports)
            return new Verdict(false, "Pilot jobs require supports to remain off");
        if (!matchesProfileRecipe(profile, config))
            return new Verdict(false, "Pilot jobs require the unchanged A1 Mini 0.4 mm PLA recipe");
        if (result == null || !NativeSlicerEngine.isNativeEngineId(result.engineId))
            return new Verdict(false, "Slice the selected bundled fixture with the native engine first");
        if (result.gcode == null || result.gcode.contains(";TYPE:Support"))
            return new Verdict(false, "Pilot package contains unsupported support toolpaths");
        return new Verdict(true, "Controlled A1 Mini no-support pilot scope");
    }

    private static boolean matchesProfileRecipe(ProfileCatalog.Profile profile, Slicer.Config config) {
        return profile.name.equals(config.printer)
                && profile.material.equalsIgnoreCase(config.filament)
                && close(config.nozzle, profile.nozzle) && close(config.bedX, profile.bedX)
                && close(config.bedY, profile.bedY) && close(config.bedZ, profile.buildZ)
                && close(config.layerHeight, profile.layerHeight)
                && close(config.firstLayerHeight, profile.firstLayerHeight)
                && close(config.infill, profile.infill)
                && config.perimeters == profile.perimeters && config.topLayers == profile.topLayers
                && config.bottomLayers == profile.bottomLayers
                && close(config.supportThresholdDegrees, profile.supportThresholdDegrees)
                && close(config.filamentDiameter, profile.filamentDiameter)
                && close(config.nozzleTemperature, profile.nozzleTemperature)
                && close(config.firstLayerNozzleTemperature, profile.firstLayerNozzleTemperature)
                && close(config.bedTemperature, profile.bedTemperature)
                && close(config.firstLayerBedTemperature, profile.firstLayerBedTemperature)
                && close(config.extrusionMultiplier, profile.flowRatio)
                && close(config.maxVolumetricSpeed, profile.maxVolumetricSpeed)
                && config.nativeSettings.equals(profile.nativeSettings);
    }

    private static boolean close(float actual, double expected) {
        return Math.abs(actual - expected) <= 0.0001f;
    }

    static final class Verdict {
        final boolean allowed;
        final String detail;

        Verdict(boolean allowed, String detail) {
            this.allowed = allowed;
            this.detail = detail;
        }
    }
}
