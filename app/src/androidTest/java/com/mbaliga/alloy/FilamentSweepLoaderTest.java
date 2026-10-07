package com.mbaliga.alloy;

import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Regression contract for the durable slicing progress surface.  This checks
 * that service values are safely bounded, announced to assistive technology,
 * and that the supplied hothead/rail composition can draw on a real device.
 */
@RunWith(AndroidJUnit4.class)
public final class FilamentSweepLoaderTest {
    @Test public void longCenterCopyWrapsWithinPhoneWidth() {
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        textPaint.setTextSize(22f);
        float availableWidth = 296f;
        List<String> lines = FilamentSweepLoader.wrapCopy(
                "Putting the finishing touches on the toolpath", textPaint, availableWidth);
        Assert.assertTrue("long progress copy should wrap rather than clip", lines.size() > 1);
        for (String line : lines) {
            Assert.assertTrue("each centered line must fit the phone width",
                    textPaint.measureText(line) <= availableWidth);
        }
    }

    @Test public void nozzleTracksBothRailEndsWithScreenPadding() {
        float railLeft = 84f;
        float railRight = 636f;
        float hotheadSize = 122f;
        float viewportWidth = 720f;
        float leftNozzle = FilamentSweepLoader.sweepCenter(railLeft, railRight, 0);
        float rightNozzle = FilamentSweepLoader.sweepCenter(railLeft, railRight, 100);
        Assert.assertEquals("empty progress begins at the left rail endpoint", railLeft, leftNozzle, 0.01f);
        Assert.assertEquals("full progress ends at the right rail endpoint", railRight, rightNozzle, 0.01f);
        float leftHead = FilamentSweepLoader.headLeftForCenter(leftNozzle, hotheadSize);
        float rightHead = FilamentSweepLoader.headLeftForCenter(rightNozzle, hotheadSize);
        Assert.assertEquals(railLeft - hotheadSize * 0.5f, leftHead, 0.01f);
        Assert.assertEquals(railRight - hotheadSize * 0.5f, rightHead, 0.01f);
        Assert.assertTrue("leftmost hothead must retain screen padding", leftHead >= 0f);
        Assert.assertTrue("rightmost hothead must retain screen padding",
                rightHead + hotheadSize <= viewportWidth);
    }

    @Test public void progressIsClampedAnnouncedAndDrawn() {
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        int[] visiblePixels = new int[1];
        String[] announcements = new String[2];
        instrumentation.runOnMainSync(() -> {
            Context context = instrumentation.getTargetContext();
            FilamentSweepLoader loader = new FilamentSweepLoader(context);
            loader.setProgress(-12, "Preparing geometry");
            announcements[0] = loader.announcement();
            loader.setProgress(135, "Writing package");
            announcements[1] = loader.announcement();

            int width = 720;
            int height = 1280;
            loader.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            loader.layout(0, 0, width, height);
            Bitmap surface = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
                loader.draw(new Canvas(surface));
                int background = Color.rgb(5, 8, 8);
                for (int y = 0; y < height; y += 4) {
                    for (int x = 0; x < width; x += 4) {
                        if ((surface.getPixel(x, y) & 0x00ffffff) != (background & 0x00ffffff)) {
                            visiblePixels[0]++;
                        }
                    }
                }
            } finally {
                surface.recycle();
            }
        });
        Assert.assertTrue("negative service progress must display as 0", announcements[0].contains("0 percent"));
        Assert.assertTrue("over-complete service progress must display as 100", announcements[1].contains("100 percent"));
        Assert.assertTrue("the current service phase must be announced", announcements[1].contains("Writing package"));
        Assert.assertTrue("loader needs visible rail, copy and supplied hothead art", visiblePixels[0] > 100);
    }
}
