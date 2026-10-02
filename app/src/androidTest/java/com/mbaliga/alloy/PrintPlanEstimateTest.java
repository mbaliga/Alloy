package com.mbaliga.alloy;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;

/** Keeps estimated material copy honest when profiles or engines evolve. */
public final class PrintPlanEstimateTest {
    @Test public void profileDensityOverridesTheBroadMaterialFallback() {
        Slicer.Config config = new Slicer.Config();
        config.filament = "PLA Basic";
        config.filamentDiameter = 1.75f;
        config.nativeSettings.put("filament_density", "1.26");
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 1_000f, 0,
                "native", false, 120f, 0f);
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        Assert.assertEquals(3.03f, estimate.approximateModelFilamentGrams, 0.02f);
        Assert.assertEquals("MODEL TOOLPATH FILAMENT", estimate.filamentScope);
        Assert.assertEquals("0 g  ·  supports are off for this recipe.", estimate.supportMaterial);
        Assert.assertEquals("2m 00s", estimate.time);
    }

    @Test public void unsupportedSeparateConsumablesRemainExplicitlyUnavailable() {
        Slicer.Config config = new Slicer.Config();
        config.filament = "PETG";
        config.supports = true;
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 0f, 0,
                "native", false, -1f, -1f);
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        Assert.assertEquals(0f, estimate.approximateModelFilamentGrams, 0f);
        Assert.assertTrue(estimate.supportMaterial.contains("Not reported separately"));
        Assert.assertEquals("ENGINE FILAMENT TOTAL", estimate.filamentScope);
        Assert.assertTrue(estimate.primePurgeCleaning.startsWith("Not available"));
        Assert.assertEquals("Not reported by engine", estimate.time);
    }
}
