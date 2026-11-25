package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Compact two-level phone navigation. Both bands are mapped from Hyle's
 * shared curve family, so their arcs stay parallel at every width instead of
 * drifting apart as independently-rounded rectangles do. All destinations remain individual
 * accessible views; the curve is presentation, never the only affordance.
 */
public final class ArcNavigationBar extends FrameLayout {
    // Canonical Hyle stage. Keep these values in lockstep with
    // outputs/hyle/arc-navigation/hyle-arc-navigation.tokens.json.
    private static final float STAGE_WIDTH = 400f;
    private static final float STAGE_HEIGHT = 150f;
    private static final float SAFE_SIDE_INSET = 22f;
    private static final float ARC_START_X = 22f;
    private static final float ARC_CONTROL_X = 200f;
    private static final float ARC_END_X = 378f;
    private static final float LOWER_ARC_END_Y = 118f;
    private static final float LOWER_ARC_CONTROL_Y = 83f;
    private static final float UPPER_ARC_OFFSET = 60f;
    private static final float[][] DESTINATIONS = {
            {46.8f, 113.4f, -9.6f}, {123.6f, 103.7f, -4.83f},
            {200f, 100.5f, 0f}, {276.4f, 103.7f, 4.83f}, {353.2f, 113.4f, 9.6f}
    };
    private static final String[] DESTINATION_LABELS = {
            "Workshop", "Library", "Prepare", "History", "More"
    };
    public interface Listener {
        void onContext();
        void onSearch();
        void onAlerts();
        void onHome();
        void onLibrary();
        void onPrepare();
        void onHistory();
        void onMore();
    }

    private final Paint upper = paint(Color.rgb(255, 255, 255));
    private final Paint lower = paint(Color.rgb(29, 31, 31));
    private final Paint ink = paint(Color.rgb(43, 43, 40));
    private final Paint selected = paint(Color.rgb(69, 73, 73));
    private final TextView context, search, alerts;
    private final TextView[] destinations = new TextView[5];
    private Listener listener;
    private String contextLabel = "Prepare";
    private int previewIndex = -1;

    public ArcNavigationBar(Context context) {
        super(context);
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        this.context = text(contextLabel, 13, Color.rgb(29, 29, 27), "Open preparation actions");
        this.context.setTextColor(Color.TRANSPARENT);
        this.context.setTypeface(null, android.graphics.Typeface.BOLD);
        this.context.setGravity(Gravity.CENTER);
        this.context.setOnClickListener(v -> { if (listener != null) listener.onContext(); });
        addView(this.context);

        search = circular("⌕", "Search models and Library");
        search.setOnClickListener(v -> { if (listener != null) listener.onSearch(); });
        addView(search);
        alerts = circular("!", "Print readiness and alerts");
        alerts.setOnClickListener(v -> { if (listener != null) listener.onAlerts(); });
        addView(alerts);

        String[] glyphs = {"⌂", "▤", "◈", "◷", "⋯"};
        for (int i = 0; i < destinations.length; i++) {
            final int index = i;
            TextView destination = text(glyphs[i], 20, Color.rgb(211, 214, 211), DESTINATION_LABELS[i]);
            destination.setGravity(Gravity.CENTER);
            destination.setOnClickListener(v -> select(index));
            destination.setOnTouchListener((view, event) -> onDestinationTouch(index, event));
            destinations[i] = destination;
            addView(destination);
        }
        setSelectedDestination(0);
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void setContextLabel(String label) {
        contextLabel = label == null || label.trim().isEmpty() ? "Prepare" : label.trim();
        context.setText(contextLabel + "  ›");
        context.setContentDescription("Open actions for " + contextLabel);
    }

    public void setSelectedDestination(int selected) {
        selectedIndex = Math.max(0, Math.min(destinations.length - 1, selected));
        for (int i = 0; i < destinations.length; i++) {
            TextView item = destinations[i];
            // Symbols are drawn by this view (not a device-dependent glyph
            // font), while the TextViews remain the touch/accessibility layer.
            item.setTextColor(Color.TRANSPARENT);
            item.setBackground(null);
            item.setSelected(i == selectedIndex);
            item.setContentDescription(DESTINATION_LABELS[i] + (i == selectedIndex ? ", selected" : ""));
        }
        invalidate();
    }

    private void select(int index) {
        setSelectedDestination(index);
        if (listener == null) return;
        switch (index) {
            case 0: listener.onHome(); break;
            case 1: listener.onLibrary(); break;
            case 2: listener.onPrepare(); break;
            case 3: listener.onHistory(); break;
            default: listener.onMore(); break;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        if (width <= 0) return;
        float scale = stageScale(width);
        float left = stageLeft();
        float top = stageTop(scale);
        Paint lowerRail = new Paint(lower);
        stroke(lowerRail, 61f * scale); lowerRail.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(lowerPath(left, top, scale), lowerRail);
        Paint upperRail = new Paint(upper);
        stroke(upperRail, 44f * scale); upperRail.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawPath(upperPath(left, top, scale), upperRail);
        drawNavigationChrome(canvas, left, top, scale);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        float scale = stageScale(getWidth());
        float stageLeft = stageLeft();
        float stageTop = stageTop(scale);
        layoutTarget(search, x(ARC_START_X, stageLeft, scale),
                y(LOWER_ARC_END_Y - UPPER_ARC_OFFSET, stageTop, scale));
        layoutTarget(alerts, x(ARC_END_X, stageLeft, scale),
                y(LOWER_ARC_END_Y - UPPER_ARC_OFFSET, stageTop, scale));
        context.layout(Math.round(x(82.52f, stageLeft, scale)), 0,
                Math.round(x(317.48f, stageLeft, scale)), Math.round(y(74f, stageTop, scale)));
        for (int i = 0; i < destinations.length; i++) {
            layoutTarget(destinations[i], x(DESTINATIONS[i][0], stageLeft, scale),
                    y(DESTINATIONS[i][1], stageTop, scale));
            destinations[i].setRotation(0f); // visual rotation is canvas-only
        }
    }

    private TextView circular(String glyph, String description) {
        TextView view = text(glyph, 18, Color.rgb(43, 43, 40), description);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(Color.TRANSPARENT);
        view.setBackground(null);
        return view;
    }

    private TextView text(String value, float size, int color, String description) {
        TextView view = new TextView(getContext());
        view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setContentDescription(description); view.setFontFeatureSettings("kern");
        return view;
    }

    private Paint paint(int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(color); return paint;
    }

    /**
     * Canvas-drawn navigation marks avoid missing or substituted Unicode glyphs
     * on OEM font stacks. The invisible child views above them retain actual
     * Android focus, semantics and hit targets.
     */
    private void drawNavigationChrome(Canvas canvas, float stageLeft, float stageTop, float scale) {
        float leftCx = x(ARC_START_X, stageLeft, scale);
        float rightCx = x(ARC_END_X, stageLeft, scale);
        float sideCy = y(LOWER_ARC_END_Y - UPPER_ARC_OFFSET, stageTop, scale);
        float radius = 22f * scale;
        fill(upper);
        canvas.drawCircle(leftCx, sideCy, radius, upper);
        canvas.drawCircle(rightCx, sideCy, radius, upper);
        stroke(ink, dp(1.6f));
        canvas.save(); canvas.rotate(arcTangentDegrees(0f), leftCx, sideCy);
        canvas.drawCircle(leftCx - dp(2), sideCy - dp(2), dp(5), ink);
        canvas.drawLine(leftCx + dp(2), sideCy + dp(2), leftCx + dp(7), sideCy + dp(7), ink);
        canvas.restore();
        fill(ink);
        ink.setTextAlign(Paint.Align.CENTER); ink.setTextSize(dp(17)); ink.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.save(); canvas.rotate(arcTangentDegrees(1f), rightCx, sideCy);
        canvas.drawText("!", rightCx, sideCy + dp(6), ink); canvas.restore();

        Path upperPath = upperPath(stageLeft, stageTop, scale);
        Paint labelPaint = new Paint(ink); fill(labelPaint); labelPaint.setTextSize(dp(13));
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD); labelPaint.setTextAlign(Paint.Align.LEFT);
        PathMeasure measure = new PathMeasure(upperPath, false);
        String shownLabel = displayedContextLabel();
        canvas.drawTextOnPath(shownLabel, upperPath,
                Math.max(0f, (measure.getLength() - labelPaint.measureText(shownLabel)) / 2f), dp(5), labelPaint);
        float actionX = 344f;
        drawEndActionIcon(canvas, x(actionX, stageLeft, scale),
                y(upperArcY((actionX - ARC_START_X) / (ARC_END_X - ARC_START_X)), stageTop, scale));

        int control = dp(32);
        for (int i = 0; i < DESTINATIONS.length; i++) {
            float cx = x(DESTINATIONS[i][0], stageLeft, scale);
            float cy = y(DESTINATIONS[i][1], stageTop, scale);
            canvas.save();
            canvas.rotate(DESTINATIONS[i][2], cx, cy);
            if (i == (previewIndex >= 0 ? previewIndex : selectedIndex)) {
                canvas.drawRoundRect(new RectF(cx - control / 2f, cy - control / 2f,
                        cx + control / 2f, cy + control / 2f), dp(18), dp(18), selected);
            }
            drawDestinationSymbol(canvas, i, cx, cy, i == selectedIndex ? Color.WHITE : Color.rgb(211, 214, 211));
            canvas.restore();
        }
    }

    private void drawEndActionIcon(Canvas canvas, float cx, float cy) {
        Paint mark = new Paint(ink); stroke(mark, dp(1.4f));
        float t = (344f - ARC_START_X) / (ARC_END_X - ARC_START_X);
        canvas.save(); canvas.rotate(arcTangentDegrees(t), cx, cy);
        canvas.drawLine(cx - dp(4), cy + dp(4), cx + dp(4), cy - dp(4), mark);
        canvas.drawLine(cx, cy - dp(4), cx + dp(4), cy - dp(4), mark);
        canvas.drawLine(cx + dp(4), cy - dp(4), cx + dp(4), cy, mark);
        canvas.restore();
    }

    private int selectedIndex;

    private boolean onDestinationTouch(int index, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                previewDestination(index);
                return true;
            case MotionEvent.ACTION_MOVE: {
                int target = nearestDestination(event.getRawX(), event.getRawY());
                if (target >= 0) previewDestination(target);
                return true;
            }
            case MotionEvent.ACTION_UP: {
                int target = nearestDestination(event.getRawX(), event.getRawY());
                // The touch listener consumes the gesture, so Android will not
                // synthesize the usual click. Route the release through the
                // target view to preserve its click event and accessibility
                // semantics (and select the item under a scrub-release).
                if (target >= 0) destinations[target].performClick();
                clearPreview();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                clearPreview();
                return true;
            default:
                return true;
        }
    }

    private void previewDestination(int index) {
        if (previewIndex == index) return;
        previewIndex = index;
        context.announceForAccessibility(DESTINATION_LABELS[index]);
        invalidate();
    }

    private void clearPreview() {
        if (previewIndex < 0) return;
        previewIndex = -1;
        invalidate();
    }

    String displayedContextLabel() {
        return previewIndex >= 0 ? DESTINATION_LABELS[previewIndex] : contextLabel;
    }

    private int nearestDestination(float rawX, float rawY) {
        int[] location = new int[2];
        getLocationOnScreen(location);
        float localX = rawX - location[0];
        float localY = rawY - location[1];
        float scale = stageScale(getWidth());
        float left = stageLeft();
        float top = stageTop(scale);
        int nearest = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < DESTINATIONS.length; i++) {
            float dx = localX - x(DESTINATIONS[i][0], left, scale);
            float dy = localY - y(DESTINATIONS[i][1], top, scale);
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) { bestDistance = distance; nearest = i; }
        }
        float maximum = dp(46);
        return bestDistance <= maximum * maximum ? nearest : -1;
    }

    static float lowerArcY(float t) {
        float inverse = 1f - t;
        return inverse * inverse * LOWER_ARC_END_Y
                + 2f * inverse * t * LOWER_ARC_CONTROL_Y
                + t * t * LOWER_ARC_END_Y;
    }

    static float upperArcY(float t) { return lowerArcY(t) - UPPER_ARC_OFFSET; }

    static float arcTangentDegrees(float t) {
        float dx = 2f * (1f - t) * (ARC_CONTROL_X - ARC_START_X)
                + 2f * t * (ARC_END_X - ARC_CONTROL_X);
        float dy = 2f * (1f - t) * (LOWER_ARC_CONTROL_Y - LOWER_ARC_END_Y)
                + 2f * t * (LOWER_ARC_END_Y - LOWER_ARC_CONTROL_Y);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    private void drawDestinationSymbol(Canvas canvas, int index, float cx, float cy, int color) {
        Paint symbol = paint(color); stroke(symbol, dp(1.5f));
        switch (index) {
            case 0: {
                Path home = new Path();
                home.moveTo(cx - dp(8), cy - dp(1)); home.lineTo(cx, cy - dp(7));
                home.lineTo(cx + dp(8), cy - dp(1)); home.lineTo(cx + dp(6), cy - dp(1));
                home.lineTo(cx + dp(6), cy + dp(7)); home.lineTo(cx - dp(6), cy + dp(7));
                home.lineTo(cx - dp(6), cy - dp(1)); home.close(); canvas.drawPath(home, symbol); break;
            }
            case 1:
                canvas.drawLine(cx - dp(6), cy - dp(8), cx - dp(4), cy + dp(8), symbol);
                canvas.drawLine(cx, cy - dp(8), cx + dp(2), cy + dp(8), symbol);
                canvas.drawLine(cx + dp(6), cy - dp(8), cx + dp(8), cy + dp(8), symbol); break;
            case 2: {
                Path cube = new Path();
                cube.moveTo(cx, cy - dp(8)); cube.lineTo(cx + dp(7), cy - dp(4)); cube.lineTo(cx + dp(7), cy + dp(4));
                cube.lineTo(cx, cy + dp(8)); cube.lineTo(cx - dp(7), cy + dp(4)); cube.lineTo(cx - dp(7), cy - dp(4)); cube.close();
                canvas.drawPath(cube, symbol); canvas.drawLine(cx, cy, cx, cy + dp(8), symbol); break;
            }
            case 3:
                canvas.drawCircle(cx, cy, dp(7), symbol); canvas.drawLine(cx, cy, cx, cy - dp(5), symbol);
                canvas.drawLine(cx, cy, cx + dp(4), cy + dp(3), symbol); break;
            default:
                fill(symbol); canvas.drawCircle(cx - dp(6), cy, dp(1.6f), symbol);
                canvas.drawCircle(cx, cy, dp(1.6f), symbol); canvas.drawCircle(cx + dp(6), cy, dp(1.6f), symbol); break;
        }
    }

    private void stroke(Paint paint, float width) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(width); }
    private void fill(Paint paint) { paint.setStyle(Paint.Style.FILL); }

    private float stageScale(int width) {
        float widthScale = (width - dp(SAFE_SIDE_INSET * 2f)) / STAGE_WIDTH;
        float heightScale = getHeight() / STAGE_HEIGHT;
        return Math.max(0.1f, Math.min(widthScale, heightScale));
    }
    private float stageLeft() { return (getWidth() - STAGE_WIDTH * stageScale(getWidth())) / 2f; }
    private float stageTop(float scale) { return Math.max(0f, (getHeight() - STAGE_HEIGHT * scale) / 2f); }
    private float x(float token, float left, float scale) { return left + token * scale; }
    private float y(float token, float top, float scale) { return top + token * scale; }
    private Path lowerPath(float left, float top, float scale) {
        return arcPath(left, top, scale, 0f);
    }
    private Path upperPath(float left, float top, float scale) {
        return arcPath(left, top, scale, UPPER_ARC_OFFSET);
    }
    private Path arcPath(float left, float top, float scale, float verticalOffset) {
        Path path = new Path();
        path.moveTo(x(ARC_START_X, left, scale), y(LOWER_ARC_END_Y - verticalOffset, top, scale));
        path.quadTo(x(ARC_CONTROL_X, left, scale), y(LOWER_ARC_CONTROL_Y - verticalOffset, top, scale),
                x(ARC_END_X, left, scale), y(LOWER_ARC_END_Y - verticalOffset, top, scale));
        return path;
    }
    private void layoutTarget(View target, float cx, float cy) {
        int hit = dp(44);
        int left = Math.max(0, Math.min(getWidth() - hit, Math.round(cx - hit / 2f)));
        int top = Math.max(0, Math.min(getHeight() - hit, Math.round(cy - hit / 2f)));
        target.layout(left, top, left + hit, top + hit);
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
