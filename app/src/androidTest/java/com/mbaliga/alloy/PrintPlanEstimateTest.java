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
                "native", false, 120f, Slicer.Result.PRINT_TIME_SOURCE_ESTIMATED, 0f);
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        Assert.assertEquals(3.03f, estimate.approximateReportedFilamentGrams, 0.02f);
        Assert.assertEquals("MODEL TOOLPATH FILAMENT", estimate.filamentScope);
        Assert.assertEquals("0 g  ·  supports are off for this recipe.", estimate.supportMaterial);
        Assert.assertEquals("2m 00s  ·  slicer estimate; actual time may vary", estimate.time);
    }

    @Test public void modelTimeSourceIsVisibleWithoutClaimingPrinterVerification() {
        Slicer.Config config = new Slicer.Config();
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 100f, 0,
                "native", false, 3720f, Slicer.Result.PRINT_TIME_SOURCE_MODEL, 12f);
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        Assert.assertEquals("1h 02m  ·  model time from file; actual time may vary", estimate.time);
    }

    @Test public void unsupportedSeparateConsumablesRemainExplicitlyUnavailable() {
        Slicer.Config config = new Slicer.Config();
        config.filament = "PETG";
        config.supports = true;
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 0f, 0,
                "native", false, -1f, -1f);
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        Assert.assertEquals(0f, estimate.approximateReportedFilamentGrams, 0f);
        Assert.assertTrue(estimate.supportMaterial.contains("Not reported separately"));
        Assert.assertEquals("ENGINE FILAMENT TOTAL", estimate.filamentScope);
        Assert.assertTrue(estimate.primePurgeCleaning.startsWith("Not included in this estimate"));
        Assert.assertTrue(estimate.primePurgeCleaning.contains("startup priming, purge and cleaning waste"));
        Assert.assertEquals("No time estimate reported", estimate.time);
    }

    @Test public void reportedSupportAndPreLayerExtrusionAreSeparatedFromEngineTotal() {
        Slicer.Config config = new Slicer.Config();
        config.filament = "PETG";
        config.supports = true;
        config.nativeSettings.put("filament_density", "1.27");
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 100f, 0,
                "native", false, 600f, Slicer.Result.PRINT_TIME_SOURCE_ESTIMATED,
                25f, 20f, 3f);

        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);

        Assert.assertEquals(100f, estimate.reportedFilamentMm, 0f);
        Assert.assertTrue(estimate.supportMaterial.startsWith("20.0 mm"));
        Assert.assertTrue(estimate.supportMaterial.contains("about"));
        Assert.assertTrue(estimate.primePurgeCleaning.startsWith("3.0 mm pre-layer positive-E"));
        Assert.assertTrue(estimate.primePurgeCleaning.contains("outside this G-code are not included"));
    }

    @Test public void emittedSupportMovesOverrideARecipeThatClaimsSupportsAreOff() {
        Slicer.Config config = new Slicer.Config();
        config.supports = false;
        Slicer.Result slice = new Slicer.Result("", new ArrayList<>(), 100f, 0,
                "native", false, 600f, Slicer.Result.PRINT_TIME_SOURCE_ESTIMATED,
                25f, 20f, 3f);

        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);

        Assert.assertTrue(estimate.supportMaterial.startsWith("20.0 mm"));
        Assert.assertEquals("ENGINE FILAMENT TOTAL", estimate.filamentScope);
    }
}
