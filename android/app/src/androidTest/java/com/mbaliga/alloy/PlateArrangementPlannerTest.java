package com.mbaliga.alloy;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/** Regression coverage for deterministic, clearance-aware phone-side packing. */
public final class PlateArrangementPlannerTest {
    @Test public void packsByAreaAndMayUseQuarterTurnWithoutOverlap() {
        ArrayList<PlateArrangementPlanner.Part> parts = new ArrayList<>(Arrays.asList(
                new PlateArrangementPlanner.Part(0, 66f, 34f),
                new PlateArrangementPlanner.Part(1, 34f, 66f),
                new PlateArrangementPlanner.Part(2, 24f, 24f)));

        ArrayList<PlateArrangementPlanner.Placement> placements =
                PlateArrangementPlanner.plan(parts, 100f, 100f, 2f);
        Assert.assertEquals(3, placements.size());
        for (PlateArrangementPlanner.Placement placement : placements) {
            Assert.assertTrue(placement.x >= 0f);
            Assert.assertTrue(placement.y >= 0f);
            Assert.assertTrue(placement.maxX() <= 100.0001f);
            Assert.assertTrue(placement.maxY() <= 100.0001f);
            Assert.assertTrue(placement.rotationDegrees == 0f || placement.rotationDegrees == 90f);
        }
        for (int i = 0; i < placements.size(); i++) {
            PlateArrangementPlanner.Placement left = placements.get(i);
            for (int j = i + 1; j < placements.size(); j++) {
                PlateArrangementPlanner.Placement right = placements.get(j);
                boolean separatedX = left.maxX() + 1.9999f <= right.x
                        || right.maxX() + 1.9999f <= left.x;
                boolean separatedY = left.maxY() + 1.9999f <= right.y
                        || right.maxY() + 1.9999f <= left.y;
                Assert.assertTrue("packed parts must not overlap", separatedX || separatedY);
            }
        }
    }

    @Test public void returnsSourceOrderEvenWhenPackingSortsByArea() {
        ArrayList<PlateArrangementPlanner.Part> parts = new ArrayList<>(Arrays.asList(
                new PlateArrangementPlanner.Part(7, 12f, 12f),
                new PlateArrangementPlanner.Part(2, 60f, 20f),
                new PlateArrangementPlanner.Part(5, 20f, 60f)));
        ArrayList<PlateArrangementPlanner.Placement> placements =
                PlateArrangementPlanner.plan(parts, 100f, 100f, 2f);
        Assert.assertEquals(2, placements.get(0).sourceIndex);
        Assert.assertEquals(5, placements.get(1).sourceIndex);
        Assert.assertEquals(7, placements.get(2).sourceIndex);
    }

    @Test public void rejectsPartThatCannotFitInEitherBedOrientation() {
        try {
            PlateArrangementPlanner.plan(Arrays.asList(
                    new PlateArrangementPlanner.Part(0, 101f, 10f)), 100f, 100f, 2f);
            Assert.fail("an oversized part must be rejected");
        } catch (IllegalArgumentException expected) {
            Assert.assertTrue(expected.getMessage().contains("larger"));
        }
    }
}
