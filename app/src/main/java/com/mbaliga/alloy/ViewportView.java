package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;

/** Lightweight touch-first preview. It keeps Alloy usable before the Stage 2 GLES renderer. */
public final class ViewportView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector scaleDetector;
    private MeshModel model;
    private Slicer.Result result;
    private int selectedLayer = -1;
    private float yaw = -0.55f, pitch = 0.55f, zoom = 1f;
    private float lastX, lastY;

    public ViewportView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(13, 24, 31));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.4f);
        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(0.45f, Math.min(3.5f, zoom * detector.getScaleFactor()));
                invalidate(); return true;
            }
        });
    }

    public void setModel(MeshModel model) { this.model = model; this.result = null; this.selectedLayer = -1; invalidate(); }
    public void setResult(Slicer.Result result) { this.result = result; this.selectedLayer = result == null ? -1 : Math.max(0, result.layers.size() - 1); invalidate(); }
    public void setSelectedLayer(int layer) { if (result != null) selectedLayer = Math.max(0, Math.min(result.layers.size() - 1, layer)); invalidate(); }
    public int getSelectedLayer() { return selectedLayer; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        paint.setColor(Color.rgb(36, 61, 69)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1f);
        float plate = Math.min(getWidth(), getHeight()) * 0.74f;
        canvas.drawRect(cx - plate / 2, cy - plate / 2, cx + plate / 2, cy + plate / 2, paint);
        for (int i = 1; i < 9; i++) {
            float d = plate * i / 9f;
            canvas.drawLine(cx - plate / 2 + d, cy - plate / 2, cx - plate / 2 + d, cy + plate / 2, paint);
            canvas.drawLine(cx - plate / 2, cy - plate / 2 + d, cx + plate / 2, cy - plate / 2 + d, paint);
        }
        if (model == null) {
            paint.setColor(Color.rgb(231, 184, 106)); paint.setTextSize(22f); paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Import an STL or 3MF to begin", cx, cy, paint); return;
        }
        drawMesh(canvas, cx, cy, plate);
        if (result != null && selectedLayer >= 0 && selectedLayer < result.layers.size()) drawToolpath(canvas, result.layers.get(selectedLayer), cx, cy, plate);
    }

    private void drawMesh(Canvas canvas, float cx, float cy, float plate) {
        float span = Math.max(model.maxX - model.minX, Math.max(model.maxY - model.minY, model.maxZ - model.minZ));
        float scale = plate * 0.82f / Math.max(1f, span) * zoom;
        PointF[] projected = new PointF[model.vertices.length / 3];
        float[] depth = new float[projected.length];
        float mx = (model.minX + model.maxX) / 2f, my = (model.minY + model.maxY) / 2f, mz = (model.minZ + model.maxZ) / 2f;
        for (int i = 0; i < projected.length; i++) {
            float x = model.vertices[i * 3] - mx, y = model.vertices[i * 3 + 1] - my, z = model.vertices[i * 3 + 2] - mz;
            float xa = x * (float)Math.cos(yaw) - y * (float)Math.sin(yaw);
            float ya = x * (float)Math.sin(yaw) + y * (float)Math.cos(yaw);
            float za = z * (float)Math.cos(pitch) - ya * (float)Math.sin(pitch);
            float yb = ya * (float)Math.cos(pitch) + z * (float)Math.sin(pitch);
            projected[i] = new PointF(cx + xa * scale, cy - yb * scale);
            depth[i] = za;
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < model.triangles.length; i += 3) order.add(i);
        order.sort((a, b) -> Float.compare(avgDepth(depth, model.triangles, a), avgDepth(depth, model.triangles, b)));
        paint.setStyle(Paint.Style.FILL);
        for (int t : order) {
            Path p = new Path(); p.moveTo(projected[model.triangles[t]].x, projected[model.triangles[t]].y);
            p.lineTo(projected[model.triangles[t + 1]].x, projected[model.triangles[t + 1]].y);
            p.lineTo(projected[model.triangles[t + 2]].x, projected[model.triangles[t + 2]].y); p.close();
            paint.setColor(Color.argb(45, 232, 184, 106)); canvas.drawPath(p, paint);
            line.setColor(Color.argb(185, 232, 184, 106)); canvas.drawPath(p, line);
        }
    }

    private void drawToolpath(Canvas canvas, Slicer.Layer layer, float cx, float cy, float plate) {
        float scale = plate / 180f;
        line.setColor(Color.rgb(98, 220, 181)); line.setStrokeWidth(2f);
        for (Slicer.Segment s : layer.segments) canvas.drawLine(cx - 90f * scale + s.a.x * scale, cy + 90f * scale - s.a.y * scale, cx - 90f * scale + s.b.x * scale, cy + 90f * scale - s.b.y * scale, line);
    }

    private static float avgDepth(float[] depth, int[] triangles, int t) { return (depth[triangles[t]] + depth[triangles[t + 1]] + depth[triangles[t + 2]]) / 3f; }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { lastX = event.getX(); lastY = event.getY(); return true; }
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            yaw += (event.getX() - lastX) * 0.01f; pitch = Math.max(-1.35f, Math.min(1.35f, pitch + (event.getY() - lastY) * 0.01f));
            lastX = event.getX(); lastY = event.getY(); invalidate(); return true;
        }
        return true;
    }
}
