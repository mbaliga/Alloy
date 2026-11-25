package com.mbaliga.alloy;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.IOException;

/**
 * Bounded, deterministic on-device presentation renderer for the visualizer.
 * It is deliberately a concept renderer, not an AI model and not a geometry
 * or material authority. The original viewport thumbnail remains the source
 * of truth; this adds a finish tint, scene framing and an environment cue.
 */
public final class StudioPreviewRenderer {
    private static final int MAX_PIXELS = 4_000_000;
    private static final int MAX_DIMENSION = 2_048;

    public enum Finish {
        NATURAL_PLA("Natural PLA", 0.76f, 0.83f, 0.46f, 0.18f),
        MATTE_BLACK("Matte black", 0.16f, 0.19f, 0.23f, 0.72f),
        ARCTIC_WHITE("Arctic white", 0.88f, 0.91f, 0.96f, 0.72f),
        SAFETY_ORANGE("Safety orange", 0.98f, 0.32f, 0.06f, 0.72f),
        METALLIC("Metallic graphite", 0.45f, 0.51f, 0.58f, 0.70f);

        public final String label;
        final float red;
        final float green;
        final float blue;
        final float strength;

        Finish(String label, float red, float green, float blue, float strength) {
            this.label = label;
            this.red = red;
            this.green = green;
            this.blue = blue;
            this.strength = strength;
        }
    }

    public enum Environment {
        WORKSHOP("Workshop", 0xFF15171B, 0xFF4A382B, 0xFFB47A46),
        PRODUCT("Product photo", 0xFFF9FAFC, 0xFFE7EAF0, 0xFFBCC4D0),
        OUTDOOR("Outdoor", 0xFFBFE4F4, 0xFF78A98A, 0xFF496C52),
        INSTALLED("Installed scene", 0xFFE7DED0, 0xFFB9A58B, 0xFF75695E);

        public final String label;
        final int top;
        final int bottom;
        final int ground;

        Environment(String label, int top, int bottom, int ground) {
            this.label = label;
            this.top = top;
            this.bottom = bottom;
            this.ground = ground;
        }
    }

    private StudioPreviewRenderer() { }

    public static Bitmap render(byte[] sourcePng, Finish finish, Environment environment) throws IOException {
        if (sourcePng == null || sourcePng.length == 0 || sourcePng.length > 2 * 1024 * 1024)
            throw new IOException("visualization reference is invalid");
        if (finish == null || environment == null) throw new IOException("visualization style is missing");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(sourcePng, 0, sourcePng.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_DIMENSION
                || bounds.outHeight > MAX_DIMENSION || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS)
            throw new IOException("visualization reference dimensions are invalid");
        Bitmap source = BitmapFactory.decodeByteArray(sourcePng, 0, sourcePng.length);
        if (source == null) throw new IOException("visualization reference could not be decoded");
        Bitmap result = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(result);
            drawEnvironment(canvas, result.getWidth(), result.getHeight(), environment);
            float margin = Math.max(10f, Math.min(result.getWidth(), result.getHeight()) * 0.06f);
            RectF image = new RectF(margin, margin, result.getWidth() - margin, result.getHeight() - margin);
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(Color.argb(environment == Environment.PRODUCT ? 22 : 48, 0, 0, 0));
            canvas.drawOval(new RectF(image.left + image.width() * 0.16f, image.bottom - image.height() * 0.16f,
                    image.right - image.width() * 0.16f, image.bottom + image.height() * 0.02f), shadow);
            Paint sourcePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            sourcePaint.setColorFilter(new ColorMatrixColorFilter(finishMatrix(finish)));
            canvas.drawBitmap(source, null, image, sourcePaint);
            Paint glaze = new Paint(Paint.ANTI_ALIAS_FLAG);
            glaze.setColor(Color.argb(environment == Environment.PRODUCT ? 8 : 20, 255, 255, 255));
            canvas.drawRoundRect(image, margin * 0.6f, margin * 0.6f, glaze);
            drawCaption(canvas, result.getWidth(), result.getHeight(), finish, environment);
            return result;
        } finally {
            source.recycle();
        }
    }

    private static void drawEnvironment(Canvas canvas, int width, int height, Environment environment) {
        Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
        background.setShader(new LinearGradient(0f, 0f, 0f, height,
                environment.top, environment.bottom, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, width, height, background);
        background.setShader(null);
        background.setColor(environment.ground);
        canvas.drawRect(0f, height * 0.78f, width, height, background);
        if (environment == Environment.WORKSHOP) {
            Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
            grid.setColor(Color.argb(30, 255, 255, 255));
            grid.setStrokeWidth(Math.max(1f, width / 512f));
            float step = Math.max(18f, width / 14f);
            for (float x = 0f; x <= width; x += step) canvas.drawLine(x, height * 0.78f, x, height, grid);
            for (float y = height * 0.80f; y <= height; y += step * 0.55f) canvas.drawLine(0f, y, width, y, grid);
        } else if (environment == Environment.OUTDOOR) {
            Paint sun = new Paint(Paint.ANTI_ALIAS_FLAG);
            sun.setColor(Color.argb(38, 255, 255, 220));
            canvas.drawCircle(width * 0.82f, height * 0.18f, Math.max(16f, width * 0.08f), sun);
        }
    }

    private static void drawCaption(Canvas canvas, int width, int height, Finish finish, Environment environment) {
        Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
        caption.setColor(Color.argb(210, 255, 255, 255));
        caption.setTextSize(Math.max(10f, Math.min(width, height) * 0.026f));
        caption.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD));
        canvas.drawText("ALLOY  ·  STUDIO PREVIEW", width * 0.055f, height * 0.075f, caption);
        caption.setTypeface(android.graphics.Typeface.DEFAULT);
        caption.setColor(Color.argb(190, 255, 255, 255));
        canvas.drawText(finish.label + "  ·  " + environment.label, width * 0.055f, height * 0.94f, caption);
    }

    private static float[] finishMatrix(Finish finish) {
        float amount = finish.strength;
        float keep = 1f - amount;
        float lr = 0.299f, lg = 0.587f, lb = 0.114f;
        return new float[]{
                keep + amount * finish.red * lr, amount * finish.red * lg, amount * finish.red * lb, 0f, 0f,
                amount * finish.green * lr, keep + amount * finish.green * lg, amount * finish.green * lb, 0f, 0f,
                amount * finish.blue * lr, amount * finish.blue * lg, keep + amount * finish.blue * lb, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
        };
    }
}
