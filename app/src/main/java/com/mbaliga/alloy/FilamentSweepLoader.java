package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

/**
 * A bounded slicing progress surface built around the supplied Alloy hothead
 * artwork. The head only advances when the durable slice job reports progress;
 * it is not a fake completion animation.
 */
public final class FilamentSweepLoader extends View {
    private static final int BACKGROUND = Color.rgb(5, 8, 8);
    private static final int COPY = Color.rgb(221, 224, 220);
    private static final int MUTED = Color.rgb(146, 153, 148);
    private static final int RAIL = Color.rgb(68, 72, 70);
    private static final String[] WAITING_COPY = {
            "Tinkering with the toolpath",
            "Checking every layer",
            "Noodling the travel moves",
            "Making room for a clean first layer"
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap hothead;
    private final Rect source = new Rect();
    private final RectF rail = new RectF();
    private final RectF filament = new RectF();
    private final RectF hotheadBounds = new RectF();
    private int progress;
    private String phase = "Preparing slice";

    public FilamentSweepLoader(Context context) {
        super(context);
        setBackgroundColor(BACKGROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        hothead = BitmapFactory.decodeResource(getResources(), R.drawable.alloy_hothead_extruding);
        if (hothead != null) source.set(0, 0, hothead.getWidth(), hothead.getHeight());
        setProgress(0, phase);
    }

    /** Values are always clamped because background-service progress is advisory UI data. */
    public void setProgress(int percent, String nextPhase) {
        progress = Math.max(0, Math.min(100, percent));
        if (nextPhase != null && nextPhase.trim().length() > 0) phase = nextPhase.trim();
        setContentDescription("Slicing model: " + progress + " percent. " + phase);
        invalidate();
    }

    String announcement() { return getContentDescription().toString(); }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float width = getWidth();
        float height = getHeight();
        if (width <= 0f || height <= 0f) return;

        float hotheadSize = Math.min(132f * density, width * 0.34f);
        // Keep the entire hothead inside the viewport while placing the rail
        // endpoints directly below its nozzle at 0% and 100% progress.
        float side = Math.max(32f * density, hotheadSize * 0.5f + 12f * density);
        side = Math.min(side, width * 0.5f);

        // The rail stays near the bottom with room for gesture areas; its
        // lower edge is the physical alignment line for the hothead tip.
        float railHeight = 13f * density;
        float railTop = height - 86f * density;
        rail.set(side, railTop, width - side, railTop + railHeight);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(RAIL);
        canvas.drawRoundRect(rail, railHeight / 2f, railHeight / 2f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, density));
        paint.setColor(Color.rgb(132, 138, 134));
        canvas.drawRoundRect(rail, railHeight / 2f, railHeight / 2f, paint);

        float nozzleX = sweepCenter(rail.left, rail.right, progress);
        float headLeft = headLeftForCenter(nozzleX, hotheadSize);
        if (progress > 0) {
            filament.set(rail.left, rail.top, nozzleX, rail.bottom);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(filament.left, filament.top, filament.right, filament.top,
                    new int[]{Color.rgb(154, 81, 20), Color.rgb(255, 150, 42), Color.rgb(255, 104, 20)},
                    null, Shader.TileMode.CLAMP));
            canvas.drawRoundRect(filament, railHeight / 2f, railHeight / 2f, paint);
            paint.setShader(null);
        }

        if (hothead != null) {
            // The supplied PNG already includes the nozzle/extruded-filament
            // silhouette. Its bounds end at the rail's lower edge—never in
            // the middle of it—so the contact reads as extrusion, not a block.
            hotheadBounds.set(headLeft, rail.bottom - hotheadSize, headLeft + hotheadSize, rail.bottom);
            canvas.drawBitmap(hothead, source, hotheadBounds, paint);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        paint.setTextSize(22f * density);
        paint.setColor(COPY);
        canvas.drawText(waitingCopy(), width / 2f, height * 0.47f, paint);
        paint.setTextSize(13f * density);
        paint.setColor(MUTED);
        canvas.drawText(phase + "  ·  " + progress + "%", width / 2f, height * 0.47f + 31f * density, paint);
        if (progress == 0) {
            long cycleMs = 6_000L;
            postInvalidateDelayed(cycleMs - (SystemClock.uptimeMillis() % cycleMs));
        }
    }

    static float sweepCenter(float railLeft, float railRight, int percent) {
        int clamped = Math.max(0, Math.min(100, percent));
        return railLeft + (railRight - railLeft) * (clamped / 100f);
    }

    static float headLeftForCenter(float centerX, float headSize) {
        return centerX - headSize * 0.5f;
    }

    private String waitingCopy() {
        if (progress >= 90) return "Putting the finishing touches on the toolpath";
        if (progress > 0) return WAITING_COPY[Math.min(WAITING_COPY.length - 1, progress / 25)];
        return WAITING_COPY[(int) ((SystemClock.uptimeMillis() / 6_000L) % WAITING_COPY.length)];
    }
}
