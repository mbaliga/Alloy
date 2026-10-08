package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Geometry and touch contract for the shared Hyle-style phone navigation. */
@RunWith(AndroidJUnit4.class)
public final class ArcNavigationBarTest {
    @Test public void upperAndLowerArcsAreParallelOffsets() {
        for (float t : new float[]{0f, .2f, .5f, .8f, 1f}) {
            Assert.assertEquals("Upper rail must be a constant offset of the lower rail",
                    60f, ArcNavigationBar.lowerArcY(t) - ArcNavigationBar.upperArcY(t), .001f);
        }
        for (float t : new float[]{.2f, .5f, .8f}) {
            float delta = .001f;
            float lowerSlope = (ArcNavigationBar.lowerArcY(t + delta)
                    - ArcNavigationBar.lowerArcY(t - delta)) / (2f * delta);
            float upperSlope = (ArcNavigationBar.upperArcY(t + delta)
                    - ArcNavigationBar.upperArcY(t - delta)) / (2f * delta);
            Assert.assertEquals("Parallel rails must have matching tangents", lowerSlope, upperSlope, .001f);
        }
    }

    @Test public void renderedRailsAndControlsStayInsidePortraitAndWideBounds() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            for (int widthDp : new int[]{320, 360, 420, 600}) {
                int width = Math.round(widthDp * density);
                int height = Math.round(116f * density);
                ArcNavigationBar bar = layout(context, width, height);
                Assert.assertEquals(8, bar.getChildCount());
                for (int i = 0; i < bar.getChildCount(); i++) {
                    View child = bar.getChildAt(i);
                    Assert.assertNotNull("Every visual control needs an accessibility name", child.getContentDescription());
                    Assert.assertTrue("Arc child left edge is clipped", child.getLeft() >= 0);
                    Assert.assertTrue("Arc child right edge is clipped", child.getRight() <= width);
                    Assert.assertTrue("Arc child top edge is clipped", child.getTop() >= 0);
                    Assert.assertTrue("Arc child bottom edge is clipped", child.getBottom() <= height);
                }

                float scale = Math.min((width - 2f * Math.round(22f * density)) / 400f, height / 150f);
                float stageLeft = (width - 400f * scale) / 2f;
                float stageTop = (height - 150f * scale) / 2f;
                assertCenter("Search must sit on the left upper-arc endpoint", bar.getChildAt(1),
                        stageLeft + 22f * scale, stageTop + ArcNavigationBar.upperArcY(0f) * scale);
                assertCenter("Alerts must sit on the right upper-arc endpoint", bar.getChildAt(2),
                        stageLeft + 378f * scale, stageTop + ArcNavigationBar.upperArcY(1f) * scale);

                Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                bar.draw(new Canvas(bitmap));
                float sampleT = .82f;
                int sampleX = Math.round(stageLeft + (22f + 356f * sampleT) * scale);
                int upperY = Math.round(stageTop + ArcNavigationBar.upperArcY(sampleT) * scale);
                int lowerY = Math.round(stageTop + ArcNavigationBar.lowerArcY(sampleT) * scale);
                Assert.assertTrue("Upper arc paint must remain in the rendered view", upperY >= 0 && upperY < height);
                Assert.assertTrue("Lower arc paint must remain in the rendered view", lowerY >= 0 && lowerY < height);
                int upperPixel = bitmap.getPixel(sampleX, upperY);
                int lowerPixel = bitmap.getPixel(sampleX, lowerY);
                Assert.assertTrue("Expected the actual upper rail at its path center",
                        Color.red(upperPixel) > 220 && Color.green(upperPixel) > 220 && Color.blue(upperPixel) > 220);
                Assert.assertTrue("Expected the actual lower rail at its path center",
                        Color.red(lowerPixel) < 80 && Color.green(lowerPixel) < 80 && Color.blue(lowerPixel) < 80);
                bitmap.recycle();
            }
        });
    }

    @Test public void pressAndSlidePreviewsThenSelectsDestination() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
            float density = context.getResources().getDisplayMetrics().density;
            ArcNavigationBar bar = layout(context, Math.round(360f * density), Math.round(116f * density));
            bar.setContextLabel("Saved Model");
            View library = bar.getChildAt(4);
            View more = bar.getChildAt(7);
            long now = SystemClock.uptimeMillis();
            dispatch(bar, MotionEvent.ACTION_DOWN, library.getLeft() + library.getWidth() / 2f,
                    library.getTop() + library.getHeight() / 2f, now);
            Assert.assertEquals("Library", bar.displayedContextLabel());
            dispatch(bar, MotionEvent.ACTION_MOVE, more.getLeft() + more.getWidth() / 2f,
                    more.getTop() + more.getHeight() / 2f, now + 40);
            Assert.assertEquals("More", bar.displayedContextLabel());
            dispatch(bar, MotionEvent.ACTION_UP, more.getLeft() + more.getWidth() / 2f,
                    more.getTop() + more.getHeight() / 2f, now + 80);
            Assert.assertEquals("Saved Model", bar.displayedContextLabel());
            Assert.assertTrue("Release must select the destination under the finger", more.isSelected());
            Assert.assertTrue(more.getContentDescription().contains("selected"));
        });
    }

    private static ArcNavigationBar layout(Context context, int width, int height) {
        ArcNavigationBar bar = new ArcNavigationBar(context);
        bar.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        bar.layout(0, 0, width, height);
        return bar;
    }

    private static void assertCenter(String message, View child, float x, float y) {
        Assert.assertEquals(message + " (x)", x, child.getLeft() + child.getWidth() / 2f, 1.5f);
        Assert.assertEquals(message + " (y)", y, child.getTop() + child.getHeight() / 2f, 1.5f);
    }

    private static void dispatch(ArcNavigationBar bar, int action, float x, float y, long time) {
        MotionEvent event = MotionEvent.obtain(time, time, action, x, y, 0);
        bar.dispatchTouchEvent(event);
        event.recycle();
    }
}
