package com.mbaliga.alloy;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Initial stock is a transparent ledger, not a claim that a material is installed. */
@RunWith(AndroidJUnit4.class)
public final class InitialMaterialInventoryTest {
    @Test public void a1MiniMaterialFamiliesHaveSeparateZeroStockLedgerRows() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InventoryStore inventory = new InventoryStore(context.getSharedPreferences(
                "initial-material-inventory-" + System.nanoTime(), Context.MODE_PRIVATE));
        boolean pla = false, petg = false, tpu = false, pva = false, plaSupport = false, petgSupport = false;
        java.util.Set<String> restrictedFamilies = new java.util.HashSet<>(java.util.Arrays.asList(
                "abs", "asa", "pc", "pa", "pet", "pla-cf", "petg-cf", "cf-gf-filled"));
        for (InventoryStore.Item item : inventory.items()) {
            if ("pla-basic".equals(item.id)) pla |= unknownStock(item);
            if ("petg-basic".equals(item.id)) petg |= unknownStock(item);
            if ("tpu".equals(item.id)) tpu |= unknownStock(item);
            if ("pva".equals(item.id)) pva |= unknownStock(item);
            if ("support-pla".equals(item.id)) plaSupport |= unknownStock(item);
            if ("support-petg".equals(item.id)) petgSupport |= unknownStock(item);
            if (restrictedFamilies.remove(item.id)) {
                Assert.assertEquals(item.id + " must not invent a stock value", "Not recorded", item.quantityLabel());
                Assert.assertFalse(item.id + " unknown stock is not a reorder alert", item.needsReorder());
                Assert.assertTrue(item.id + " must be labeled track-only and not recommended for A1 Mini",
                        item.minimum == 0 && item.care.contains("Track only")
                                && item.care.contains("not recommended on A1 Mini"));
            }
        }
        Assert.assertTrue("A1 mini starter material families should be visible without implying stock",
                pla && petg && tpu && pva && plaSupport && petgSupport);
        Assert.assertTrue("every not-recommended A1 mini material class must remain visible in inventory",
                restrictedFamilies.isEmpty());
    }

    private static boolean unknownStock(InventoryStore.Item item) {
        return item.quantity == 0 && !item.quantityConfirmed && "Not recorded".equals(item.quantityLabel());
    }
}
