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
        for (InventoryStore.Item item : inventory.items()) {
            pla |= "pla-basic".equals(item.id) && item.quantity >= 0;
            petg |= "petg-basic".equals(item.id) && item.quantity == 0;
            tpu |= "tpu".equals(item.id) && item.quantity == 0;
            pva |= "pva".equals(item.id) && item.quantity == 0;
            plaSupport |= "support-pla".equals(item.id) && item.quantity == 0;
            petgSupport |= "support-petg".equals(item.id) && item.quantity == 0;
        }
        Assert.assertTrue("A1 mini starter material families should be visible without implying stock",
                pla && petg && tpu && pva && plaSupport && petgSupport);
    }
}
