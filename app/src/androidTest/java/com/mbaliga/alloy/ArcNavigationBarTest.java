package com.mbaliga.alloy;

import android.content.Context;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Geometry contract for the shared Hyle-style two-level phone navigation.
 * It deliberately tests hit targets rather than pixels: the canvas treatment
 * may evolve, but the arc must never clip an action off a narrow phone.
 */
@RunWith(AndroidJUnit4.class)
public final class ArcNavigationBarTest {
    @Test public void arcKeepsAllEightControlsInsideItsCompactBounds() {
        AtomicReference<ArcNavigationBar> reference = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            ArcNavigationBar bar = new ArcNavigationBar(context);
            int width = 1080;
            int height = Math.round(116 * context.getResources().getDisplayMetrics().density);
            bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            bar.layout(0, 0, width, height);
            reference.set(bar);
        });
        ArcNavigationBar bar = reference.get();
        Assert.assertNotNull(bar);
        Assert.assertEquals(8, bar.getChildCount());
        for (int i = 0; i < bar.getChildCount(); i++) {
            View child = bar.getChildAt(i);
            Assert.assertNotNull("Every visual control needs an accessibility name", child.getContentDescription());
            Assert.assertTrue("Arc child left edge is clipped", child.getLeft() >= 0);
            Assert.assertTrue("Arc child right edge is clipped", child.getRight() <= bar.getWidth());
            Assert.assertTrue("Arc child top edge is clipped", child.getTop() >= 0);
            Assert.assertTrue("Arc child bottom edge is clipped", child.getBottom() <= bar.getHeight());
        }
        View search = bar.getChildAt(1);
        View alerts = bar.getChildAt(2);
        Assert.assertTrue("Search must stay in the left upper arc boundary", search.getLeft() < bar.getWidth() / 4);
        Assert.assertTrue("Alerts must stay in the right upper arc boundary", alerts.getRight() > bar.getWidth() * 3 / 4);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> bar.setContextLabel("Library"));
        Assert.assertEquals("Open actions for Library", bar.getChildAt(0).getContentDescription());
    }
}
