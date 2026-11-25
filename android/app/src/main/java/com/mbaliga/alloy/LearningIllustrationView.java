package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * Small offline diagrams for articles that do not need a photographic/raster
 * asset. They are deliberately explanatory rather than pseudo-telemetry or a
 * counterfeit printer interface, and preserve an accessible text alternative.
 */
final class LearningIllustrationView extends View {
    private static final int INK = Color.rgb(35, 44, 48);
    private static final int BLUE = Color.rgb(113, 158, 190);
    private static final int TEAL = Color.rgb(102, 205, 189);
    private static final int MINT = Color.rgb(176, 224, 202);
    private static final int ORANGE = Color.rgb(242, 161, 45);
    private static final int RED = Color.rgb(190, 83, 66);
    private static final int PANEL = Color.rgb(242, 244, 240);

    private final String articleId;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;

    LearningIllustrationView(Context context, String articleId, String description) {
        super(context);
        this.articleId = articleId == null ? "" : articleId;
        this.density = getResources().getDisplayMetrics().density;
        setContentDescription(description);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float inset = dp(5);
        RectF stage = new RectF(inset, inset, w - inset, h - inset);
        paint.setShader(null);
        paint.setColor(PANEL);
        canvas.drawRoundRect(stage, dp(18), dp(18), paint);

        float cx = stage.centerX();
        float cy = stage.centerY();
        paint.setShader(new RadialGradient(cx, cy, Math.min(w, h) * .48f,
                new int[]{Color.argb(95, 102, 205, 189), Color.argb(0, 102, 205, 189)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, Math.min(w, h) * .48f, paint);
        paint.setShader(null);
        paint.setColor(Color.argb(40, 20, 34, 38));
        canvas.drawOval(new RectF(cx - w * .27f, cy + h * .20f, cx + w * .27f, cy + h * .34f), paint);

        if (isMaterial()) drawMaterial(canvas, stage);
        else if (isSupport()) drawSupport(canvas, stage);
        else if (isLayer()) drawLayers(canvas, stage);
        else if (isOrientation()) drawOrientation(canvas, stage);
        else if (isSafety()) drawSafety(canvas, stage);
        else if (isRecovery()) drawRecovery(canvas, stage);
        else drawPlate(canvas, stage);
    }

    private boolean isMaterial() {
        return articleId.contains("material") || articleId.contains("spool")
                || articleId.contains("feed") || articleId.contains("runout")
                || articleId.contains("moisture");
    }

    private boolean isSupport() {
        return articleId.contains("support") || articleId.contains("bridge")
                || articleId.contains("overhang");
    }

    private boolean isLayer() {
        return articleId.contains("layer") || articleId.contains("wall")
                || articleId.contains("infill") || articleId.contains("seam")
                || articleId.contains("first-");
    }

    private boolean isOrientation() {
        return articleId.contains("orientation") || articleId.contains("bounds")
                || articleId.contains("fit") || articleId.contains("place");
    }

    private boolean isSafety() {
        return articleId.contains("stop") || articleId.contains("cable")
                || articleId.contains("hotend") || articleId.contains("temperature")
                || articleId.contains("noisy") || articleId.contains("shift")
                || articleId.contains("spaghetti");
    }

    private boolean isRecovery() {
        return articleId.contains("connection") || articleId.contains("busy")
                || articleId.contains("interrupted") || articleId.contains("rejected");
    }

    private void drawMaterial(Canvas canvas, RectF stage) {
        float cx = stage.centerX() - dp(34);
        float cy = stage.centerY();
        float r = Math.min(stage.width(), stage.height()) * .26f;
        paint.setColor(INK);
        canvas.drawCircle(cx, cy, r, paint);
        paint.setColor(TEAL);
        canvas.drawCircle(cx, cy, r * .76f, paint);
        paint.setColor(PANEL);
        canvas.drawCircle(cx, cy, r * .36f, paint);
        paint.setColor(INK);
        canvas.drawCircle(cx, cy, r * .16f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(ORANGE);
        Path filament = new Path();
        filament.moveTo(cx + r * .67f, cy - r * .20f);
        filament.cubicTo(stage.centerX() + dp(26), cy - r, stage.right - dp(50), cy + r,
                stage.right - dp(32), cy - dp(10));
        canvas.drawPath(filament, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(ORANGE);
        canvas.drawCircle(stage.right - dp(30), cy - dp(10), dp(7), paint);
    }

    private void drawSupport(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float base = stage.bottom - dp(24);
        paint.setColor(INK);
        canvas.drawRoundRect(new RectF(cx - dp(58), base - dp(9), cx + dp(58), base), dp(5), dp(5), paint);
        paint.setColor(BLUE);
        canvas.drawRoundRect(new RectF(cx - dp(29), base - dp(57), cx + dp(31), base - dp(9)), dp(8), dp(8), paint);
        paint.setColor(MINT);
        Path tree = new Path();
        tree.moveTo(cx, base - dp(10));
        tree.cubicTo(cx - dp(6), base - dp(31), cx - dp(37), base - dp(37), cx - dp(47), base - dp(63));
        tree.moveTo(cx, base - dp(10));
        tree.cubicTo(cx + dp(5), base - dp(28), cx + dp(27), base - dp(38), cx + dp(39), base - dp(74));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(7));
        paint.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(tree, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(ORANGE);
        canvas.drawCircle(cx - dp(47), base - dp(63), dp(8), paint);
        canvas.drawCircle(cx + dp(39), base - dp(74), dp(8), paint);
    }

    private void drawLayers(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float y = stage.centerY() - dp(34);
        for (int index = 0; index < 5; index++) {
            float inset = dp(28 + index * 4);
            paint.setColor(index == 4 ? ORANGE : (index % 2 == 0 ? TEAL : BLUE));
            RectF layer = new RectF(stage.left + inset, y + index * dp(18),
                    stage.right - inset, y + dp(11 + index * 18));
            canvas.drawRoundRect(layer, dp(6), dp(6), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(190, 35, 44, 48));
        for (int line = 0; line < 4; line++) {
            float x = stage.left + dp(48 + line * 35);
            canvas.drawLine(x, y + dp(4), x + dp(38), y + dp(74), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawOrientation(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float cy = stage.centerY();
        paint.setColor(BLUE);
        canvas.save();
        canvas.rotate(-12f, cx, cy);
        canvas.drawRoundRect(new RectF(cx - dp(35), cy - dp(33), cx + dp(35), cy + dp(37)), dp(12), dp(12), paint);
        paint.setColor(Color.argb(120, 224, 246, 245));
        canvas.drawRoundRect(new RectF(cx - dp(29), cy - dp(28), cx + dp(29), cy - dp(4)), dp(8), dp(8), paint);
        canvas.restore();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(4));
        paint.setColor(ORANGE);
        RectF arrow = new RectF(cx - dp(57), cy - dp(55), cx + dp(57), cy + dp(55));
        canvas.drawArc(arrow, 210, 170, false, paint);
        paint.setStyle(Paint.Style.FILL);
        Path head = new Path();
        head.moveTo(cx + dp(47), cy - dp(45));
        head.lineTo(cx + dp(62), cy - dp(47));
        head.lineTo(cx + dp(53), cy - dp(32));
        head.close();
        canvas.drawPath(head, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSafety(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float cy = stage.centerY();
        Path sign = new Path();
        for (int index = 0; index < 8; index++) {
            double angle = Math.PI / 8d + Math.PI * index / 4d;
            float x = cx + (float) Math.cos(angle) * dp(45);
            float y = cy + (float) Math.sin(angle) * dp(45);
            if (index == 0) sign.moveTo(x, y); else sign.lineTo(x, y);
        }
        sign.close();
        paint.setColor(RED);
        canvas.drawPath(sign, paint);
        paint.setColor(Color.WHITE);
        canvas.drawRoundRect(new RectF(cx - dp(4), cy - dp(25), cx + dp(4), cy + dp(10)), dp(4), dp(4), paint);
        canvas.drawCircle(cx, cy + dp(24), dp(5), paint);
    }

    private void drawRecovery(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float cy = stage.centerY();
        paint.setColor(INK);
        canvas.drawRoundRect(new RectF(cx - dp(32), cy - dp(47), cx + dp(32), cy + dp(47)), dp(12), dp(12), paint);
        paint.setColor(PANEL);
        canvas.drawRoundRect(new RectF(cx - dp(24), cy - dp(36), cx + dp(24), cy + dp(25)), dp(6), dp(6), paint);
        paint.setColor(TEAL);
        canvas.drawCircle(cx, cy - dp(6), dp(10), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(ORANGE);
        for (int ring = 0; ring < 2; ring++) {
            float r = dp(36 + ring * 15);
            canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), 218, 104, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawPlate(Canvas canvas, RectF stage) {
        float cx = stage.centerX();
        float cy = stage.centerY();
        paint.setColor(INK);
        RectF plate = new RectF(cx - dp(68), cy + dp(16), cx + dp(68), cy + dp(42));
        canvas.drawRoundRect(plate, dp(8), dp(8), paint);
        paint.setColor(BLUE);
        canvas.drawRoundRect(new RectF(cx - dp(31), cy - dp(35), cx + dp(31), cy + dp(19)), dp(12), dp(12), paint);
        paint.setColor(Color.argb(125, 235, 248, 247));
        canvas.drawRoundRect(new RectF(cx - dp(23), cy - dp(29), cx + dp(23), cy - dp(6)), dp(7), dp(7), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(Color.argb(150, 255, 255, 255));
        for (int line = 0; line < 4; line++) {
            float x = plate.left + dp(18 + line * 29);
            canvas.drawLine(x, plate.top + dp(5), x, plate.bottom - dp(5), paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * density;
    }
}
