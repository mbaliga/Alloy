package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * Alloy-owned Stage 2 phone renderer.
 *
 * This is deliberately a small GLES 2.0 renderer rather than a dependency on
 * a browser or a proprietary scene library. Meshes stay in millimetres, the
 * camera is a real perspective camera, and the same scene can show the model,
 * machine envelope and the active toolpath layer. Printer geometry here is a
 * reviewed presentation envelope only; it never authorizes a print.
 */
final class GlesViewportSurface extends GLSurfaceView {
    private static final float BED_X = 180f;
    private static final float BED_Y = 180f;
    private static final float BUILD_Z = 180f;
    private static final int MAX_TOOLPATH_SEGMENTS = 80_000;

    private final int maxDrawTriangles;
    private final ScaleGestureDetector scaleDetector;
    private final SceneRenderer renderer = new SceneRenderer();

    private volatile MeshModel model;
    private volatile Slicer.Result result;
    private volatile int selectedLayer = -1;
    private volatile int selectedPart = -1;
    private volatile boolean toolpathOnly;
    private volatile boolean presentationMode;
    private volatile boolean cleanPresentation;
    private volatile boolean machineStudy;
    /** Presentation-only assembly spacing; never used by preparation/slicing. */
    private volatile boolean explodedPresentation;
    /** Presentation-only appearance controls; they never affect slicing. */
    private volatile int finishMode;
    private volatile boolean nightStage;
    private ViewportView.PartSelectionListener partSelectionListener;

    private float yaw = -0.55f;
    private float pitch = 0.55f;
    private float zoom = 1f;
    private float panX;
    private float panY;
    private float downX;
    private float downY;
    private float lastX;
    private float lastY;
    private float lastFocusX;
    private float lastFocusY;
    private boolean moved;
    private final Context appContext;
    /** Optional debug-only supplied machine mesh, shared by camera and GL upload. */
    private final MeshModel referencePresentationModel;

    GlesViewportSurface(Context context, int maxDrawTriangles) {
        super(context);
        appContext = context.getApplicationContext();
        referencePresentationModel = A1MiniReferenceMesh.tryLoad(appContext.getAssets());
        this.maxDrawTriangles = Math.max(1_000, maxDrawTriangles);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        scaleDetector = new ScaleGestureDetector(context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        zoom = clamp(zoom * detector.getScaleFactor(), 0.45f, 4.5f);
                        requestRender();
                        return true;
                    }
                });
    }

    void setModel(MeshModel value) {
        model = value;
        result = null;
        selectedLayer = -1;
        selectedPart = -1;
        toolpathOnly = false;
        explodedPresentation = false;
        yaw = -0.55f;
        pitch = 0.55f;
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        requestRender();
    }

    void setResult(Slicer.Result value) {
        result = value;
        selectedLayer = value == null || value.layers == null || value.layers.isEmpty()
                ? -1 : Math.max(0, value.layers.size() - 1);
        requestRender();
    }

    void setToolpathOnly(boolean value) {
        toolpathOnly = value && result != null;
        requestRender();
    }

    void setPresentationMode(boolean value) {
        if (presentationMode == value) return;
        presentationMode = value;
        // Match the supplied study's right-front camera when entering the
        // machine frame; Hero keeps the quieter object-first angle.
        yaw = value ? 0.78f : -0.55f;
        pitch = 0.55f;
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        requestRender();
    }

    /** Use a light, object-first stage for the immersive study surface. */
    void setCleanPresentation(boolean value) {
        if (cleanPresentation == value) return;
        cleanPresentation = value;
        renderer.invalidateMeshUpload();
        requestRender();
    }

    /**
     * Pull logical parts apart for inspection without mutating the printable
     * model. This deliberately lives in the renderer boundary: preparation,
     * project persistence, thumbnails and native slicing continue to use the
     * original coordinates.
     */
    void setExplodedPresentation(boolean value) {
        if (explodedPresentation == value) return;
        explodedPresentation = value;
        renderer.invalidateMeshUpload();
        requestRender();
    }

    boolean isExplodedPresentation() { return explodedPresentation; }

    /** Render the printer itself as a standalone, non-printable study subject. */
    void setMachineStudy(boolean value) {
        machineStudy = value;
        if (value) {
            presentationMode = true;
            cleanPresentation = false;
            renderer.invalidateMeshUpload();
            toolpathOnly = false;
            selectedLayer = -1;
            selectedPart = -1;
            yaw = 0.78f;
            pitch = 0.55f;
            zoom = 1f;
            panX = 0f;
            panY = 0f;
        }
        requestRender();
    }

    /** Select a bounded material treatment for the visual presentation only. */
    void setFinishMode(int value) {
        finishMode = Math.max(0, Math.min(4, value));
        requestRender();
    }

    int getFinishMode() { return finishMode; }

    /** Switch the presentation stage between the light and dark treatments. */
    void setNightStage(boolean value) {
        nightStage = value;
        requestRender();
    }

    boolean isNightStage() { return nightStage; }

    boolean hasReferenceMachineModel() { return referencePresentationModel != null; }

    boolean isToolpathOnly() { return toolpathOnly; }

    void setSelectedLayer(int layer) {
        Slicer.Result current = result;
        if (current != null && current.layers != null && !current.layers.isEmpty())
            selectedLayer = Math.max(0, Math.min(current.layers.size() - 1, layer));
        requestRender();
    }

    int getSelectedLayer() { return selectedLayer; }

    void setSelectedPart(int part) {
        MeshModel current = model;
        if (current == null || current.parts == null || part < 0 || part >= current.parts.length)
            selectedPart = -1;
        else selectedPart = part;
        requestRender();
    }

    int getSelectedPart() { return selectedPart; }

    void setPartSelectionListener(ViewportView.PartSelectionListener listener) {
        partSelectionListener = listener;
    }

    void fitModel() {
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        requestRender();
    }

    void resetView() {
        yaw = -0.55f;
        pitch = 0.55f;
        zoom = 1f;
        panX = 0f;
        panY = 0f;
        requestRender();
    }

    /** Capture a bounded portable thumbnail without blocking on the GL thread. */
    byte[] thumbnailPng(int maxSize) {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        int bound = Math.max(64, Math.min(1_024, maxSize));
        float scale = Math.min(1f, bound / (float) Math.max(getWidth(), getHeight()));
        int width = Math.max(1, Math.round(getWidth() * scale));
        int height = Math.max(1, Math.round(getHeight() * scale));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            canvas.scale(scale, scale);
            drawThumbnail(canvas, getWidth(), getHeight());
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(256 * 1024, width * height));
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return null;
            return output.toByteArray();
        } finally {
            bitmap.recycle();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = lastX = event.getX();
            downY = lastY = event.getY();
            moved = false;
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            lastFocusX = focusX(event);
            lastFocusY = focusY(event);
            moved = true;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1) {
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > 10f) moved = true;
            yaw += (event.getX() - lastX) * 0.009f;
            pitch = clamp(pitch + (event.getY() - lastY) * 0.009f, -1.35f, 1.35f);
            lastX = event.getX();
            lastY = event.getY();
            requestRender();
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE && event.getPointerCount() >= 2) {
            float focusX = focusX(event);
            float focusY = focusY(event);
            panX += focusX - lastFocusX;
            panY += focusY - lastFocusY;
            lastFocusX = focusX;
            lastFocusY = focusY;
            moved = true;
            requestRender();
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerCount() > 2) {
                lastFocusX = focusX(event);
                lastFocusY = focusY(event);
            } else if (event.getPointerCount() == 2) {
                int remaining = event.getActionIndex() == 0 ? 1 : 0;
                lastX = event.getX(remaining);
                lastY = event.getY(remaining);
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP && !moved && model != null) {
            int part = hitTest(event.getX(), event.getY());
            if (part >= 0) {
                setSelectedPart(part);
                if (partSelectionListener != null) partSelectionListener.onPartSelected(part);
            }
        }
        return true;
    }

    private int hitTest(float touchX, float touchY) {
        MeshModel current = model;
        if (current == null || current.vertices.length / 3 > 600_000) return -1;
        float[] mvp = new float[16];
        buildMvp(getWidth(), getHeight(), toolpathOnly, mvp);
        int vertexCount = current.vertices.length / 3;
        float[] screenX = new float[vertexCount];
        float[] screenY = new float[vertexCount];
        float[] screenZ = new float[vertexCount];
        float[] input = new float[4];
        float[] output = new float[4];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = vertex * 3;
            input[0] = current.vertices[offset];
            input[1] = current.vertices[offset + 1];
            input[2] = current.vertices[offset + 2];
            input[3] = 1f;
            Matrix.multiplyMV(output, 0, mvp, 0, input, 0);
            if (Math.abs(output[3]) < 0.0001f) continue;
            float inverseW = 1f / output[3];
            float x = output[0] * inverseW;
            float y = output[1] * inverseW;
            screenX[vertex] = (x + 1f) * getWidth() * 0.5f;
            screenY[vertex] = (1f - y) * getHeight() * 0.5f;
            screenZ[vertex] = output[2] * inverseW;
        }
        int best = -1;
        float bestDepth = Float.POSITIVE_INFINITY;
        int triangleCount = current.triangles.length / 3;
        int stride = meshRenderStride(current);
        for (int triangle = 0; triangle < triangleCount; triangle += stride) {
            if (!renderableTriangle(current, triangle)) continue;
            int offset = triangle * 3;
            int a = current.triangles[offset];
            int b = current.triangles[offset + 1];
            int c = current.triangles[offset + 2];
            if (!inside(touchX, touchY, screenX[a], screenY[a], screenX[b], screenY[b], screenX[c], screenY[c])) continue;
            float depth = (screenZ[a] + screenZ[b] + screenZ[c]) / 3f;
            if (depth < bestDepth) {
                bestDepth = depth;
                best = partIndexForTriangle(current, triangle);
            }
        }
        return best;
    }

    private int partIndexForTriangle(MeshModel current, int triangle) {
        if (current.parts == null) return 0;
        for (int index = 0; index < current.parts.length; index++) {
            MeshModel.Part part = current.parts[index];
            if (triangle >= part.triangleStart && triangle < part.triangleStart + part.triangleCount) return index;
        }
        return 0;
    }

    /**
     * Build crease-aware display normals for meshes that share vertices.
     *
     * The first renderer version used one face normal for every triangle
     * corner. That is safe and pleasant for hard-edged boxes, but it makes the
     * supplied A1 study and cylindrical/curved user models look like a stack
     * of cardboard facets. The browser handoff uses a 42 degree crease; keep
     * the same visual rule here while retaining hard edges and material-part
     * boundaries. This is display-only data and never enters the slicer.
     */
    private float[] displayNormals(MeshModel value) {
        if (value == null) return null;
        int vertexCount = value.vertices.length / 3;
        int triangleCount = value.triangles.length / 3;
        // Avoid a second large adjacency allocation for adversarial/imported
        // meshes. The normal fallback below remains bounded by maxDrawTriangles.
        if (vertexCount <= 0 || triangleCount <= 0 || vertexCount > 600_000 || triangleCount > 200_000)
            return null;
        if (value.displayNormals != null && value.displayNormals.length == vertexCount * 3) {
            // A trusted visual asset may carry per-vertex/per-corner normals
            // that were baked with an explicit crease policy. Preserve those
            // normals instead of attempting to weld already-split hard edges.
            float[] output = new float[triangleCount * 9];
            for (int triangle = 0; triangle < triangleCount; triangle++) {
                int triangleOffset = triangle * 3;
                int outputOffset = triangle * 9;
                for (int corner = 0; corner < 3; corner++) {
                    int normalOffset = value.triangles[triangleOffset + corner] * 3;
                    int cornerOffset = outputOffset + corner * 3;
                    output[cornerOffset] = value.displayNormals[normalOffset];
                    output[cornerOffset + 1] = value.displayNormals[normalOffset + 1];
                    output[cornerOffset + 2] = value.displayNormals[normalOffset + 2];
                }
            }
            return output;
        }
        float[] faces = new float[triangleCount * 3];
        int[] faceParts = new int[triangleCount];
        int[] heads = new int[vertexCount];
        java.util.Arrays.fill(heads, -1);
        int[] next = new int[triangleCount * 3];
        java.util.Arrays.fill(next, -1);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int offset = triangle * 3;
            int a = value.triangles[offset] * 3;
            int b = value.triangles[offset + 1] * 3;
            int c = value.triangles[offset + 2] * 3;
            float abx = value.vertices[b] - value.vertices[a];
            float aby = value.vertices[b + 1] - value.vertices[a + 1];
            float abz = value.vertices[b + 2] - value.vertices[a + 2];
            float acx = value.vertices[c] - value.vertices[a];
            float acy = value.vertices[c + 1] - value.vertices[a + 1];
            float acz = value.vertices[c + 2] - value.vertices[a + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 0.000001f) { nx = 0f; ny = 0f; nz = 1f; }
            else { nx /= length; ny /= length; nz /= length; }
            int faceOffset = triangle * 3;
            faces[faceOffset] = nx; faces[faceOffset + 1] = ny; faces[faceOffset + 2] = nz;
            faceParts[triangle] = partIndexForTriangle(value, triangle);
            for (int corner = 0; corner < 3; corner++) {
                int index = offset + corner;
                int vertex = value.triangles[index];
                next[index] = heads[vertex];
                heads[vertex] = index;
            }
        }
        float[] output = new float[triangleCount * 9];
        final float creaseCosine = (float) Math.cos(Math.toRadians(42.0));
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int faceOffset = triangle * 3;
            float fnx = faces[faceOffset], fny = faces[faceOffset + 1], fnz = faces[faceOffset + 2];
            for (int corner = 0; corner < 3; corner++) {
                int vertex = value.triangles[faceOffset + corner];
                float nx = 0f, ny = 0f, nz = 0f;
                for (int link = heads[vertex]; link >= 0; link = next[link]) {
                    int neighbor = link / 3;
                    if (faceParts[neighbor] != faceParts[triangle]) continue;
                    int neighborOffset = neighbor * 3;
                    float dot = fnx * faces[neighborOffset]
                            + fny * faces[neighborOffset + 1]
                            + fnz * faces[neighborOffset + 2];
                    if (dot >= creaseCosine) {
                        nx += faces[neighborOffset];
                        ny += faces[neighborOffset + 1];
                        nz += faces[neighborOffset + 2];
                    }
                }
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 0.000001f) { nx = fnx; ny = fny; nz = fnz; }
                else { nx /= length; ny /= length; nz /= length; }
                int outputOffset = triangle * 9 + corner * 3;
                output[outputOffset] = nx;
                output[outputOffset + 1] = ny;
                output[outputOffset + 2] = nz;
            }
        }
        return output;
    }

    private void drawThumbnail(Canvas canvas, int sourceWidth, int sourceHeight) {
        int width = Math.max(1, Math.round(sourceWidth * Math.min(1f, 1_024f / Math.max(sourceWidth, sourceHeight))));
        int height = Math.max(1, Math.round(sourceHeight * Math.min(1f, 1_024f / Math.max(sourceWidth, sourceHeight))));
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new LinearGradient(0, 0, 0, height, Color.rgb(255, 255, 254), Color.rgb(230, 227, 220), Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, sourceWidth, sourceHeight, paint);
        paint.setShader(null);
        float cx = sourceWidth / 2f;
        float cy = sourceHeight / 2f;
        float plate = Math.min(sourceWidth, sourceHeight) * 0.72f;
        // Keep thumbnails useful after an Activity/process restart. This is
        // a bounded CPU mirror of the GLES product pass rather than a second
        // geometry authority: it only affects presentation pixels and never
        // enters slicing, collision or printer transport decisions.
        Path platePath = new Path();
        platePath.moveTo(cx - plate * 0.48f, cy - plate * 0.23f);
        platePath.lineTo(cx + plate * 0.40f, cy - plate * 0.34f);
        platePath.lineTo(cx + plate * 0.48f, cy + plate * 0.34f);
        platePath.lineTo(cx - plate * 0.40f, cy + plate * 0.45f);
        platePath.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(37, 39, 42));
        canvas.drawPath(platePath, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, sourceWidth / 720f));
        paint.setColor(Color.argb(120, 175, 169, 158));
        for (int grid = 1; grid < 9; grid++) {
            float t = grid / 9f;
            canvas.drawLine(cx - plate * 0.48f + plate * 0.88f * t,
                    cy - plate * 0.23f - plate * 0.11f * t,
                    cx - plate * 0.40f + plate * 0.88f * t,
                    cy + plate * 0.45f - plate * 0.11f * t, paint);
            canvas.drawLine(cx - plate * 0.48f + plate * 0.88f * t,
                    cy - plate * 0.23f + plate * 0.68f * t,
                    cx + plate * 0.40f - plate * 0.88f * (1f - t),
                    cy - plate * 0.34f + plate * 0.68f * t, paint);
        }
        // A soft contact shadow anchors the object to the presentation plate.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(92, 0, 0, 0));
        canvas.drawOval(new android.graphics.RectF(cx - plate * 0.23f, cy + plate * 0.10f,
                cx + plate * 0.27f, cy + plate * 0.27f), paint);
        MeshModel current = model;
        if (current == null) return;
        float span = Math.max(current.maxX - current.minX,
                Math.max(current.maxY - current.minY, current.maxZ - current.minZ));
        float scale = plate * 0.82f / Math.max(1f, span) * zoom;
        float mx = (current.minX + current.maxX) / 2f;
        float my = (current.minY + current.maxY) / 2f;
        float mz = (current.minZ + current.maxZ) / 2f;
        int vertexCount = current.vertices.length / 3;
        float[] x = new float[vertexCount];
        float[] y = new float[vertexCount];
        float[] z = new float[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int offset = vertex * 3;
            float vx = current.vertices[offset] - mx;
            float vy = current.vertices[offset + 1] - my;
            float vz = current.vertices[offset + 2] - mz;
            float xa = vx * (float) Math.cos(yaw) - vy * (float) Math.sin(yaw);
            float ya = vx * (float) Math.sin(yaw) + vy * (float) Math.cos(yaw);
            float za = vz * (float) Math.cos(pitch) - ya * (float) Math.sin(pitch);
            float yb = ya * (float) Math.cos(pitch) + vz * (float) Math.sin(pitch);
            // A mild perspective term keeps the thumbnail from looking like
            // a flat orthographic CAD export while remaining stable for very
            // large or very small imported meshes.
            float perspective = clamp(1f - za / Math.max(1f, span * 3.5f), 0.82f, 1.18f);
            x[vertex] = cx + xa * scale * perspective + panX;
            y[vertex] = cy - yb * scale * perspective + panY - plate * 0.03f;
            z[vertex] = za;
        }
        int triangleCount = current.triangles.length / 3;
        int stride = meshRenderStride(current);
        ArrayList<Integer> order = new ArrayList<>();
        for (int triangle = 0; triangle < triangleCount; triangle += stride)
            if (renderableTriangle(current, triangle)) order.add(triangle);
        order.sort((a, b) -> Float.compare(avgDepth(z, current.triangles, b), avgDepth(z, current.triangles, a)));
        paint.setStyle(Paint.Style.FILL);
        for (int triangle : order) {
            int offset = triangle * 3;
            int a = current.triangles[offset], b = current.triangles[offset + 1], c = current.triangles[offset + 2];
            int ao = a * 3, bo = b * 3, co = c * 3;
            float abx = current.vertices[bo] - current.vertices[ao];
            float aby = current.vertices[bo + 1] - current.vertices[ao + 1];
            float abz = current.vertices[bo + 2] - current.vertices[ao + 2];
            float acx = current.vertices[co] - current.vertices[ao];
            float acy = current.vertices[co + 1] - current.vertices[ao + 1];
            float acz = current.vertices[co + 2] - current.vertices[ao + 2];
            float nx = aby * acz - abz * acy;
            float ny = abz * acx - abx * acz;
            float nz = abx * acy - aby * acx;
            float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (length < 0.000001f) { nx = 0f; ny = 0f; nz = 1f; }
            else { nx /= length; ny /= length; nz /= length; }
            // Rotate the face normal with the same camera transform used for
            // vertices, then use a small three-point studio rig. The values
            // intentionally mirror the GLES fragment shader closely enough
            // that an archive/BYOK reference does not suddenly become flat.
            float nxa = nx * (float) Math.cos(yaw) - ny * (float) Math.sin(yaw);
            float nya = nx * (float) Math.sin(yaw) + ny * (float) Math.cos(yaw);
            float nza = nz * (float) Math.cos(pitch) - nya * (float) Math.sin(pitch);
            float nyb = nya * (float) Math.cos(pitch) + nz * (float) Math.sin(pitch);
            float key = Math.max(0f, nxa * -0.42f + nyb * 0.56f + nza * 0.96f);
            float fill = Math.max(0f, nxa * 0.72f + nyb * -0.30f + nza * 0.58f);
            Path path = new Path();
            path.moveTo(x[a], y[a]);
            path.lineTo(x[b], y[b]);
            path.lineTo(x[c], y[c]);
            path.close();
            int part = partIndexForTriangle(current, triangle);
            boolean focused = selectedPart < 0 || selectedPart == part;
            int[] palette = {0xFFC27D2C, 0xFF9B542C, 0xFFE3AF45, 0xFF4E5963, 0xFF8D3E25, 0xFFD7D0C3};
            int base = palette[Math.floorMod(part, palette.length)];
            float light = clamp(0.30f + 0.80f * key + 0.18f * fill, 0.22f, 1.40f);
            int red = clampColor(Math.round(Color.red(base) * light));
            int green = clampColor(Math.round(Color.green(base) * light));
            int blue = clampColor(Math.round(Color.blue(base) * light));
            int alpha = focused && selectedPart >= 0 ? 225 : 205;
            paint.setColor(Color.argb(alpha, red, green, blue));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(0.5f, sourceWidth / 1_400f));
            paint.setColor(Color.argb(focused ? 70 : 35, 25, 24, 22));
            canvas.drawPath(path, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private static int clampColor(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static float avgDepth(float[] depth, int[] triangles, int triangle) {
        int offset = triangle * 3;
        return (depth[triangles[offset]] + depth[triangles[offset + 1]] + depth[triangles[offset + 2]]) / 3f;
    }

    private static float focusX(MotionEvent event) {
        float value = 0f;
        for (int index = 0; index < event.getPointerCount(); index++) value += event.getX(index);
        return value / Math.max(1, event.getPointerCount());
    }

    private static float focusY(MotionEvent event) {
        float value = 0f;
        for (int index = 0; index < event.getPointerCount(); index++) value += event.getY(index);
        return value / Math.max(1, event.getPointerCount());
    }

    private static boolean inside(float x, float y, float ax, float ay, float bx, float by, float cx, float cy) {
        float ab = (bx - ax) * (y - ay) - (by - ay) * (x - ax);
        float bc = (cx - bx) * (y - by) - (cy - by) * (x - bx);
        float ca = (ax - cx) * (y - cy) - (ay - cy) * (x - cx);
        return (ab >= 0f && bc >= 0f && ca >= 0f) || (ab <= 0f && bc <= 0f && ca <= 0f);
    }

    private void buildMvp(int width, int height, boolean topDown, float[] output) {
        float aspect = Math.max(0.1f, width / (float) Math.max(1, height));
        // Imported and generated meshes retain their real build-volume
        // coordinates; their origin is not guaranteed to be the plate
        // centre. Aim the initial camera at the model bounds so a valid part
        // near X/Y=0 is not hidden behind a marker or pushed off-screen. Pan
        // remains relative to that world-space subject rather than silently
        // changing the geometry displayed to the user.
        MeshModel current = model;
        // Machine Study can draw the supplied A1 Mini shell around the print
        // subject. Frame that complete presentation mesh when it is present;
        // framing only the subject leaves the machine columns outside the
        // viewport even though the model itself is correctly positioned.
        MeshModel framing = (machineStudy || (presentationMode && !cleanPresentation)) && referencePresentationModel != null
                ? referencePresentationModel : current;
        float targetX = sceneCenterX(framing) - panX / Math.max(1f, width) * BED_X;
        float targetY = sceneCenterY(framing) + panY / Math.max(1f, height) * BED_Y;
        float targetZ = sceneCenterZ(framing);
        float[] view = new float[16];
        float[] projection = new float[16];
        if (topDown) {
            Matrix.setLookAtM(view, 0, targetX, targetY, 420f, targetX, targetY, 0f, 0f, 1f, 0f);
        } else {
            // Frame the actual subject, not the full 180 mm machine volume.
            // This keeps a small imported part legible while the bed/grid
            // remains a contextual presentation surface around it.
            // The clean Hero surface is an object study, so frame the actual
            // imported geometry even when it is much smaller than the A1
            // build volume. Only the technical Machine view needs the wider
            // printer-context envelope; otherwise a small-unit OBJ/STEP
            // source becomes a postage stamp in a beautiful but empty stage.
            float span = presentationMode && !cleanPresentation
                    ? Math.max(sceneSpan(framing), BUILD_Z * 0.82f)
                    : sceneSpan(framing);
            // The release-safe standalone printer shell has no mesh bounds to
            // drive framing. Give that full-height study a wider phone-first
            // camera distance; otherwise the vertical machine envelope is
            // cropped on tall screens even though the print volume is valid.
            float fallbackDistance = machineStudy ? 720f : 315f;
            // The supplied A1 study is taller and deeper than a normal print
            // model. Give it a wider first frame on narrow phone screens so
            // the gantry, spool and bed all remain visible; the user can
            // still pinch in for the detailed inspection view.
            // The technical plate view is still sized against the real model
            // bounds, but should feel like a prepared object rather than a
            // postage stamp in a large empty machine volume. Imported parts
            // remain bounded by the same max distance and can be pinched out
            // when the user wants more surrounding context.
            float frameScale = machineStudy ? 6.20f : 3.35f;
            float frameMax = machineStudy ? 1_100f : 620f;
            float frameMin = cleanPresentation ? 12f : 92f;
            float distance = (framing == null ? fallbackDistance : clamp(span * frameScale, frameMin, frameMax))
                    / Math.max(0.45f, zoom);
            float horizontal = distance * (float) Math.cos(pitch);
            float eyeX = targetX + (float) Math.sin(yaw) * horizontal;
            float eyeY = targetY + (float) Math.cos(yaw) * horizontal;
            float eyeZ = targetZ + (float) Math.sin(pitch) * distance;
            // The elevated printer focal point belongs only to the technical
            // Machine view. Hero must look directly at the imported object's
            // own centre, especially for compact or small-unit OBJ sources.
            float lookAtZ = presentationMode && !cleanPresentation
                    ? Math.max(targetZ, BUILD_Z * 0.34f) : targetZ;
            Matrix.setLookAtM(view, 0, eyeX, eyeY, eyeZ, targetX, targetY, lookAtZ, 0f, 0f, 1f);
        }
        Matrix.perspectiveM(projection, 0, 42f, aspect, 0.2f, 2_000f);
        Matrix.multiplyMM(output, 0, projection, 0, view, 0);
    }

    private static float sceneCenterX(MeshModel value) {
        return value == null || !finite(value.minX) || !finite(value.maxX)
                ? BED_X / 2f : (value.minX + value.maxX) / 2f;
    }

    private static float sceneCenterY(MeshModel value) {
        return value == null || !finite(value.minY) || !finite(value.maxY)
                ? BED_Y / 2f : (value.minY + value.maxY) / 2f;
    }

    private static float sceneCenterZ(MeshModel value) {
        return value == null || !finite(value.minZ) || !finite(value.maxZ)
                ? 18f : Math.max(0f, (value.minZ + value.maxZ) / 2f);
    }

    private static float sceneSpan(MeshModel value) {
        if (value == null) return 180f;
        float x = Math.max(0.1f, value.maxX - value.minX);
        float y = Math.max(0.1f, value.maxY - value.minY);
        float z = Math.max(0.1f, value.maxZ - value.minZ);
        return Math.max(x, Math.max(y, z));
    }

    /** Presentation-only zero-area test shared by GLES, thumbnail and picking paths. */
    private static boolean renderableTriangle(MeshModel value, int triangle) {
        if (value == null || value.triangles == null || triangle < 0
                || triangle * 3 + 2 >= value.triangles.length) return false;
        int offset = triangle * 3;
        int ia = value.triangles[offset] * 3;
        int ib = value.triangles[offset + 1] * 3;
        int ic = value.triangles[offset + 2] * 3;
        if (ia == ib || ia == ic || ib == ic) return false;
        float ux = value.vertices[ib] - value.vertices[ia];
        float uy = value.vertices[ib + 1] - value.vertices[ia + 1];
        float uz = value.vertices[ib + 2] - value.vertices[ia + 2];
        float vx = value.vertices[ic] - value.vertices[ia];
        float vy = value.vertices[ic + 1] - value.vertices[ia + 1];
        float vz = value.vertices[ic + 2] - value.vertices[ia + 2];
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        return finite(nx) && finite(ny) && finite(nz)
                && nx * nx + ny * ny + nz * nz > 0.00000001f;
    }

    /**
     * Keep normal meshes bounded while preserving valid surfaces in noisy
     * community exports. A fixed stride can accidentally turn a model with
     * many invalid facets into a dotted outline; this adaptive branch is
     * presentation-only and never changes the source mesh or slice input.
     */
    private int meshRenderStride(MeshModel value) {
        if (value == null || value.triangles == null) return 1;
        int triangleCount = value.triangles.length / 3;
        int stride = Math.max(1, (triangleCount + maxDrawTriangles - 1) / maxDrawTriangles);
        if (stride > 1 && triangleCount <= 250_000) {
            MeshModel.GeometryReport report = value.geometryReport();
            if (report.degenerateTriangles >= Math.max(1, triangleCount / 5)) return 1;
        }
        return stride;
    }

    /** Build stable, bounded offsets for the renderer-only exploded study. */
    private static float[] presentationExplodeOffsets(MeshModel value) {
        if (value == null || value.parts == null || value.parts.length < 2) return null;
        float[] output = new float[value.parts.length * 3];
        float modelCenterX = sceneCenterX(value);
        float modelCenterY = sceneCenterY(value);
        float modelCenterZ = sceneCenterZ(value);
        float distance = clamp(sceneSpan(value) * 0.075f, 4f, 16f);
        for (int index = 0; index < value.parts.length; index++) {
            MeshModel.PartBounds bounds = value.partBounds(index);
            float dx = (bounds.minX + bounds.maxX) * 0.5f - modelCenterX;
            float dy = (bounds.minY + bounds.maxY) * 0.5f - modelCenterY;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 0.001f) {
                // Coincident CAD solids (such as a bezel and chassis) still
                // need a legible separation. Use a deterministic radial fan
                // so the same assembly produces the same visual every time.
                double angle = -Math.PI / 2d + index * Math.PI * 2d / value.parts.length;
                dx = (float) Math.cos(angle);
                dy = (float) Math.sin(angle);
                length = 1f;
            }
            float radial = distance * (0.72f + 0.28f * Math.min(1f, length / Math.max(1f, sceneSpan(value))));
            int offset = index * 3;
            output[offset] = dx / length * radial;
            output[offset + 1] = dy / length * radial;
            // A small stagger is enough to reveal stacked parts without
            // turning a product assembly into an exploded technical drawing.
            output[offset + 2] = (index - (value.parts.length - 1) * 0.5f) * Math.min(3.5f, distance * 0.22f)
                    + ((bounds.minZ + bounds.maxZ) * 0.5f - modelCenterZ) * 0.035f;
        }
        return output;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private final class SceneRenderer implements GLSurfaceView.Renderer {
        private int backdropProgram;
        private int meshProgram;
        private int lineProgram;
        private int backdropPosition;
        private int backdropTheme;
        private int meshPosition;
        private int meshNormal;
        private int meshPart;
        private int meshMvp;
        private int meshSelectedPart;
        private int meshOutOfBounds;
        private int meshMachine;
        private int meshAlpha;
        private int meshFinish;
        private int meshNight;
        private int linePosition;
        private int lineColor;
        private int lineMvp;
        private FloatBuffer machineBuffer;
        private FloatBuffer backdropVertices;
        private int machineVertexCount;
        private FloatBuffer machineSolidBuffer;
        private int machineSolidVertexCount;
        private float machineAnchorX = Float.NaN;
        private float machineAnchorY = Float.NaN;
        private boolean machinePresentationMode;
        private boolean machineCleanPresentation;
        private MeshModel machineModel;
        private MeshModel referenceMachineModel;
        private FloatBuffer referenceMachineBuffer;
        private int referenceMachineVertexCount;
        private FloatBuffer studyAccessoryBuffer;
        private int studyAccessoryVertexCount;
        private FloatBuffer studyPlateBuffer;
        private int studyPlateVertexCount;
        private FloatBuffer meshBuffer;
        private int meshVertexCount;
        private MeshModel uploadedModel;
        private boolean uploadedExplodedPresentation;

        private void invalidateMeshUpload() {
            uploadedModel = null;
        }

        @Override public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 unused,
                                               javax.microedition.khronos.egl.EGLConfig config) {
            GLES20.glClearColor(0.965f, 0.958f, 0.94f, 1f);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            backdropProgram = program(BACKDROP_VERTEX_SHADER, BACKDROP_FRAGMENT_SHADER);
            meshProgram = program(MESH_VERTEX_SHADER, MESH_FRAGMENT_SHADER);
            lineProgram = program(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER);
            backdropPosition = GLES20.glGetAttribLocation(backdropProgram, "aPosition");
            backdropTheme = GLES20.glGetUniformLocation(backdropProgram, "uTheme");
            meshPosition = GLES20.glGetAttribLocation(meshProgram, "aPosition");
            meshNormal = GLES20.glGetAttribLocation(meshProgram, "aNormal");
            meshPart = GLES20.glGetAttribLocation(meshProgram, "aPart");
            meshMvp = GLES20.glGetUniformLocation(meshProgram, "uMvp");
            meshSelectedPart = GLES20.glGetUniformLocation(meshProgram, "uSelectedPart");
            meshOutOfBounds = GLES20.glGetUniformLocation(meshProgram, "uOutOfBounds");
            meshMachine = GLES20.glGetUniformLocation(meshProgram, "uMachine");
            meshAlpha = GLES20.glGetUniformLocation(meshProgram, "uAlpha");
            meshFinish = GLES20.glGetUniformLocation(meshProgram, "uFinish");
            meshNight = GLES20.glGetUniformLocation(meshProgram, "uNight");
            linePosition = GLES20.glGetAttribLocation(lineProgram, "aPosition");
            lineColor = GLES20.glGetAttribLocation(lineProgram, "aColor");
            lineMvp = GLES20.glGetUniformLocation(lineProgram, "uMvp");
            machineBuffer = floatBuffer(machineLines(BED_X / 2f, BED_Y / 2f, cleanPresentation));
            machineVertexCount = machineBuffer.limit() / 7;
            machineSolidBuffer = floatBuffer(machineSolids(BED_X / 2f, BED_Y / 2f,
                    presentationMode, cleanPresentation, null));
            machineSolidVertexCount = machineSolidBuffer.limit() / 7;
            referenceMachineModel = referencePresentationModel;
            if (referenceMachineModel != null) {
                referenceMachineBuffer = machineMeshBuffer(referenceMachineModel);
                referenceMachineVertexCount = referenceMachineBuffer.limit() / 7;
            }
            // The handoff uses familiar objects to communicate scale. Keep
            // those objects as Alloy-authored presentation geometry rather
            // than bundling another third-party model or mixing them into
            // the printable scene.
            studyAccessoryBuffer = floatBuffer(studyAccessories());
            studyAccessoryVertexCount = studyAccessoryBuffer.limit() / 7;
            studyPlateBuffer = floatBuffer(studyPlates());
            studyPlateVertexCount = studyPlateBuffer.limit() / 7;
        }

        @Override public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
        }

        @Override public void onDrawFrame(javax.microedition.khronos.opengles.GL10 unused) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            drawBackdrop();
            // The backdrop owns the colour buffer; start the scene with a
            // clean depth buffer so the presentation surface never changes
            // the model's draw ordering.
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT);
            float[] mvp = new float[16];
            buildMvp(getWidth(), getHeight(), toolpathOnly, mvp);
            MeshModel current = model;
            MeshModel machineFrame = (machineStudy || (presentationMode && !cleanPresentation)) && referenceMachineModel != null
                    ? referenceMachineModel : current;
            float anchorX = sceneCenterX(machineFrame);
            float anchorY = sceneCenterY(machineFrame);
            if (!finite(machineAnchorX) || Math.abs(anchorX - machineAnchorX) > 0.01f
                    || Math.abs(anchorY - machineAnchorY) > 0.01f
                    || machinePresentationMode != presentationMode
                    || machineCleanPresentation != cleanPresentation || machineModel != current) {
                machineAnchorX = anchorX;
                machineAnchorY = anchorY;
                machinePresentationMode = presentationMode;
                machineCleanPresentation = cleanPresentation;
                machineModel = current;
                machineBuffer = floatBuffer(machineLines(anchorX, anchorY, cleanPresentation));
                machineVertexCount = machineBuffer.limit() / 7;
                machineSolidBuffer = floatBuffer(machineSolids(anchorX, anchorY,
                        presentationMode, cleanPresentation, current));
                machineSolidVertexCount = machineSolidBuffer.limit() / 7;
            }
            if ((machineStudy || (presentationMode && !cleanPresentation)) && referenceMachineBuffer != null) {
                drawReferenceMachine(referenceMachineBuffer, referenceMachineVertexCount, mvp);
            } else {
                drawMachineSolids(machineSolidBuffer, machineSolidVertexCount, mvp);
            }
            if (machineStudy && studyPlateBuffer != null) {
                // The receding plates are presentation geometry only. They
                // establish depth and scale for the marketing study and are
                // never part of the printable model, plate planner, or
                // collision envelope.
                drawMeshBuffer(studyPlateBuffer, studyPlateVertexCount, mvp, -1, 0, 2, 1f);
            }
            if (machineStudy && studyAccessoryBuffer != null) {
                drawMeshBuffer(studyAccessoryBuffer, studyAccessoryVertexCount, mvp, -1, 0, 3, 1f);
            }
            drawLines(machineBuffer, machineVertexCount, mvp);
            if (current != uploadedModel || uploadedExplodedPresentation != explodedPresentation)
                uploadMesh(current);
            if (toolpathOnly) {
                Slicer.Result currentResult = result;
                if (currentResult != null && selectedLayer >= 0 && selectedLayer < currentResult.layers.size())
                    drawToolpath(currentResult.layers.get(selectedLayer), mvp);
            } else if (current != null && meshBuffer != null) {
                drawMesh(current, mvp);
                Slicer.Result currentResult = result;
                if (currentResult != null && selectedLayer >= 0 && selectedLayer < currentResult.layers.size())
                    drawToolpath(currentResult.layers.get(selectedLayer), mvp);
            }
        }

        private void drawBackdrop() {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(backdropProgram);
            GLES20.glUniform1i(backdropTheme, nightStage ? 1 : 0);
            FloatBuffer buffer = backdropBuffer();
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(backdropPosition);
            GLES20.glVertexAttribPointer(backdropPosition, 2, GLES20.GL_FLOAT, false, 8, buffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(backdropPosition);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        }

        private FloatBuffer backdropBuffer() {
            if (backdropVertices == null) {
                backdropVertices = floatBuffer(new float[]{
                        -1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f
                });
            }
            return backdropVertices;
        }

        private void uploadMesh(MeshModel value) {
            uploadedModel = value;
            uploadedExplodedPresentation = explodedPresentation;
            meshBuffer = null;
            meshVertexCount = 0;
            if (value == null || value.triangles.length < 3) return;
            int triangleCount = value.triangles.length / 3;
            int stride = meshRenderStride(value);
            int drawnTriangles = (triangleCount + stride - 1) / stride;
            ByteBuffer bytes = ByteBuffer.allocateDirect(drawnTriangles * 3 * 7 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer buffer = bytes.asFloatBuffer();
            float[] creaseNormals = displayNormals(value);
            float[] explode = explodedPresentation && cleanPresentation && !machineStudy
                    ? presentationExplodeOffsets(value) : null;
            for (int triangle = 0; triangle < triangleCount; triangle += stride) {
                if (!renderableTriangle(value, triangle)) continue;
                int offset = triangle * 3;
                int a = value.triangles[offset];
                int b = value.triangles[offset + 1];
                int c = value.triangles[offset + 2];
                int ao = a * 3, bo = b * 3, co = c * 3;
                float abx = value.vertices[bo] - value.vertices[ao];
                float aby = value.vertices[bo + 1] - value.vertices[ao + 1];
                float abz = value.vertices[bo + 2] - value.vertices[ao + 2];
                float acx = value.vertices[co] - value.vertices[ao];
                float acy = value.vertices[co + 1] - value.vertices[ao + 1];
                float acz = value.vertices[co + 2] - value.vertices[ao + 2];
                float nx = aby * acz - abz * acy;
                float ny = abz * acx - abx * acz;
                float nz = abx * acy - aby * acx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 0.000001f) { nx = 0f; ny = 0f; nz = 1f; }
                else { nx /= length; ny /= length; nz /= length; }
                float part = partIndexForTriangle(value, triangle);
                int partOffset = ((int) part) * 3;
                float ox = explode == null || partOffset + 2 >= explode.length ? 0f : explode[partOffset];
                float oy = explode == null || partOffset + 2 >= explode.length ? 0f : explode[partOffset + 1];
                float oz = explode == null || partOffset + 2 >= explode.length ? 0f : explode[partOffset + 2];
                if (creaseNormals == null) {
                    putMeshVertex(buffer, value.vertices, ao, nx, ny, nz, part, ox, oy, oz);
                    putMeshVertex(buffer, value.vertices, bo, nx, ny, nz, part, ox, oy, oz);
                    putMeshVertex(buffer, value.vertices, co, nx, ny, nz, part, ox, oy, oz);
                } else {
                    int normalOffset = triangle * 9;
                    putMeshVertex(buffer, value.vertices, ao,
                            creaseNormals[normalOffset], creaseNormals[normalOffset + 1], creaseNormals[normalOffset + 2], part, ox, oy, oz);
                    putMeshVertex(buffer, value.vertices, bo,
                            creaseNormals[normalOffset + 3], creaseNormals[normalOffset + 4], creaseNormals[normalOffset + 5], part, ox, oy, oz);
                    putMeshVertex(buffer, value.vertices, co,
                            creaseNormals[normalOffset + 6], creaseNormals[normalOffset + 7], creaseNormals[normalOffset + 8], part, ox, oy, oz);
                }
            }
            buffer.flip();
            meshBuffer = buffer;
            meshVertexCount = buffer.limit() / 7;
        }

        /** Build a full-fidelity buffer for the optional supplied machine mesh. */
        private FloatBuffer machineMeshBuffer(MeshModel value) {
            if (value == null || value.triangles.length < 3) return floatBuffer(new float[0]);
            int triangleCount = value.triangles.length / 3;
            int stride = Math.max(1, (triangleCount + maxDrawTriangles - 1) / maxDrawTriangles);
            int drawnTriangles = (triangleCount + stride - 1) / stride;
            ByteBuffer bytes = ByteBuffer.allocateDirect(drawnTriangles * 3 * 7 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer buffer = bytes.asFloatBuffer();
            float[] creaseNormals = displayNormals(value);
            for (int triangle = 0; triangle < triangleCount; triangle += stride) {
                if (!renderableTriangle(value, triangle)) continue;
                int offset = triangle * 3;
                int a = value.triangles[offset], b = value.triangles[offset + 1], c = value.triangles[offset + 2];
                int ao = a * 3, bo = b * 3, co = c * 3;
                float abx = value.vertices[bo] - value.vertices[ao];
                float aby = value.vertices[bo + 1] - value.vertices[ao + 1];
                float abz = value.vertices[bo + 2] - value.vertices[ao + 2];
                float acx = value.vertices[co] - value.vertices[ao];
                float acy = value.vertices[co + 1] - value.vertices[ao + 1];
                float acz = value.vertices[co + 2] - value.vertices[ao + 2];
                float nx = aby * acz - abz * acy;
                float ny = abz * acx - abx * acz;
                float nz = abx * acy - aby * acx;
                float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (length < 0.000001f) { nx = 0f; ny = 0f; nz = 1f; }
                else { nx /= length; ny /= length; nz /= length; }
                float cx = (value.vertices[ao] + value.vertices[bo] + value.vertices[co]) / 3f;
                float cy = (value.vertices[ao + 1] + value.vertices[bo + 1] + value.vertices[co + 1]) / 3f;
                float cz = (value.vertices[ao + 2] + value.vertices[bo + 2] + value.vertices[co + 2]) / 3f;
                float part = referenceMachinePalette(cx, cy, cz, nx, ny, nz);
                if (creaseNormals == null) {
                    putMachineVertex(buffer, value.vertices, ao, nx, ny, nz, part);
                    putMachineVertex(buffer, value.vertices, bo, nx, ny, nz, part);
                    putMachineVertex(buffer, value.vertices, co, nx, ny, nz, part);
                } else {
                    int normalOffset = triangle * 9;
                    putMachineVertex(buffer, value.vertices, ao,
                            creaseNormals[normalOffset], creaseNormals[normalOffset + 1], creaseNormals[normalOffset + 2], part);
                    putMachineVertex(buffer, value.vertices, bo,
                            creaseNormals[normalOffset + 3], creaseNormals[normalOffset + 4], creaseNormals[normalOffset + 5], part);
                    putMachineVertex(buffer, value.vertices, co,
                            creaseNormals[normalOffset + 6], creaseNormals[normalOffset + 7], creaseNormals[normalOffset + 8], part);
                }
            }
            buffer.flip();
            return buffer;
        }

        private void putMachineVertex(FloatBuffer buffer, float[] vertices, int offset,
                                      float nx, float ny, float nz, float part) {
            buffer.put(vertices[offset]).put(vertices[offset + 1]).put(vertices[offset + 2]);
            buffer.put(nx).put(ny).put(nz).put(part);
        }

        /** Spatial material regions keep the welded reference mesh readable. */
        private float referenceMachinePalette(float x, float y, float z,
                                              float nx, float ny, float nz) {
            if (z < 4f) return -1f;                 // black PEI / base
            // The extracted handoff mesh is centred on the physical bed but
            // the spool and toolhead are not at the nominal 90/110 anchors
            // used by the first Android port. These regions are deliberately
            // visual-only: they provide material separation for the study,
            // never machine collision or print geometry.
            if (x > 190f && y > 75f && y < 125f && z > 55f) return -12f; // spool
            if (x > 55f && x < 110f && y > 55f && y < 135f && z > 100f && z < 122f)
                return -11f; // carriage/nozzle assembly
            if (z > 145f) return -7f;               // light upper rails
            if (Math.abs(nx) > 0.75f || Math.abs(ny) > 0.75f) return -8f;
            return -6f;
        }

        private void drawReferenceMachine(FloatBuffer buffer, int vertexCount, float[] mvp) {
            if (buffer == null || vertexCount <= 0) return;
            // The supplied preview deliberately lets the printed object show
            // through the machine shell when a print is present. A standalone
            // study has no subject underneath, so render it opaque and let
            // the depth buffer resolve the welded shell cleanly. This is a
            // presentation-only change; it never alters the model, toolpath
            // or collision decisions.
            GLES20.glDepthMask(machineStudy);
            drawMeshBuffer(buffer, vertexCount, mvp, -1, 0, 1, machineStudy ? 1.0f : 0.46f);
            GLES20.glDepthMask(true);
        }

        private void putMeshVertex(FloatBuffer buffer, float[] vertices, int offset,
                                   float nx, float ny, float nz, float part) {
            putMeshVertex(buffer, vertices, offset, nx, ny, nz, part, 0f, 0f, 0f);
        }

        private void putMeshVertex(FloatBuffer buffer, float[] vertices, int offset,
                                   float nx, float ny, float nz, float part,
                                   float offsetX, float offsetY, float offsetZ) {
            buffer.put(vertices[offset] + offsetX);
            buffer.put(vertices[offset + 1] + offsetY);
            buffer.put(vertices[offset + 2] + offsetZ);
            buffer.put(nx).put(ny).put(nz).put(part);
        }

        private void drawMesh(MeshModel value, float[] mvp) {
            // The immersive Hero view is an object study, not a placement
            // verdict. Imported CAD/STL files commonly use a centred origin
            // (for example X=-84..84 for a 169 mm chassis), while the print
            // preparation path intentionally evaluates the leveled model
            // against the real build-volume coordinates. Do not let that
            // source-coordinate convention flatten a valid presentation into
            // the warning-gray material; Machine/Prepare views still expose
            // the true out-of-volume state.
            boolean placementWarning = !cleanPresentation && outsideBuildVolume(value);
            drawMeshBuffer(meshBuffer, meshVertexCount, mvp, selectedPart,
                    placementWarning ? 1 : 0, 0);
        }

        private void drawMachineSolids(FloatBuffer buffer, int vertexCount, float[] mvp) {
            if (buffer == null || vertexCount <= 0) return;
            // This shell is a presentation aid only. Disable depth writes so
            // it never occludes the imported model that is drawn afterward.
            GLES20.glDepthMask(false);
            drawMeshBuffer(buffer, vertexCount, mvp, -1, 0, cleanPresentation ? 2 : 1,
                    cleanPresentation ? 0.92f : 0.82f);
            GLES20.glDepthMask(true);
        }

        private void drawMeshBuffer(FloatBuffer buffer, int vertexCount, float[] mvp,
                                    int selectedPart, int outOfBounds, int machine) {
            drawMeshBuffer(buffer, vertexCount, mvp, selectedPart, outOfBounds, machine, 1f);
        }

        private void drawMeshBuffer(FloatBuffer buffer, int vertexCount, float[] mvp,
                                    int selectedPart, int outOfBounds, int machine, float alpha) {
            if (buffer == null || vertexCount <= 0) return;
            GLES20.glUseProgram(meshProgram);
            GLES20.glUniformMatrix4fv(meshMvp, 1, false, mvp, 0);
            GLES20.glUniform1i(meshSelectedPart, selectedPart);
            GLES20.glUniform1i(meshOutOfBounds, outOfBounds);
            GLES20.glUniform1i(meshMachine, machine);
            GLES20.glUniform1f(meshAlpha, alpha);
            GLES20.glUniform1i(meshFinish, finishMode);
            GLES20.glUniform1i(meshNight, nightStage ? 1 : 0);
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(meshPosition);
            GLES20.glVertexAttribPointer(meshPosition, 3, GLES20.GL_FLOAT, false, 28, buffer);
            buffer.position(3);
            GLES20.glEnableVertexAttribArray(meshNormal);
            GLES20.glVertexAttribPointer(meshNormal, 3, GLES20.GL_FLOAT, false, 28, buffer);
            buffer.position(6);
            GLES20.glEnableVertexAttribArray(meshPart);
            GLES20.glVertexAttribPointer(meshPart, 1, GLES20.GL_FLOAT, false, 28, buffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount);
            GLES20.glDisableVertexAttribArray(meshPosition);
            GLES20.glDisableVertexAttribArray(meshNormal);
            GLES20.glDisableVertexAttribArray(meshPart);
        }

        private void drawToolpath(Slicer.Layer layer, float[] mvp) {
            boolean classified = layer.perimeterSegments != null && !layer.perimeterSegments.isEmpty()
                    || layer.supportSegments != null && !layer.supportSegments.isEmpty();
            if (classified) {
                drawSegments(layer.perimeterSegments, layer.z + 0.35f, 0.38f, 0.86f, 0.70f, 1f, mvp);
                drawSegments(layer.supportSegments, layer.z + 0.45f, 0.95f, 0.64f, 0.22f, 1f, mvp);
                HashSet<Slicer.Segment> classifiedSegments = new HashSet<>();
                if (layer.perimeterSegments != null) classifiedSegments.addAll(layer.perimeterSegments);
                if (layer.supportSegments != null) classifiedSegments.addAll(layer.supportSegments);
                ArrayList<Slicer.Segment> other = new ArrayList<>();
                if (layer.segments != null) {
                    for (Slicer.Segment segment : layer.segments)
                        if (!classifiedSegments.contains(segment)) other.add(segment);
                }
                drawSegments(other, layer.z + 0.30f, 0.30f, 0.62f, 0.96f, 1f, mvp);
            } else {
                drawSegments(layer.segments, layer.z + 0.35f, 0.38f, 0.86f, 0.70f, 1f, mvp);
            }
        }

        private void drawSegments(ArrayList<Slicer.Segment> segments, float z,
                                  float r, float g, float b, float a, float[] mvp) {
            if (segments == null || segments.isEmpty()) return;
            int count = Math.min(MAX_TOOLPATH_SEGMENTS, segments.size());
            ByteBuffer bytes = ByteBuffer.allocateDirect(count * 2 * 7 * 4).order(ByteOrder.nativeOrder());
            FloatBuffer buffer = bytes.asFloatBuffer();
            int written = 0;
            for (int index = 0; index < count; index++) {
                Slicer.Segment segment = segments.get(index);
                if (segment == null || segment.a == null || segment.b == null) continue;
                putLineVertex(buffer, segment.a.x, segment.a.y, z, r, g, b, a);
                putLineVertex(buffer, segment.b.x, segment.b.y, z, r, g, b, a);
                written += 2;
            }
            buffer.flip();
            drawLines(buffer, written, mvp);
        }

        private void drawLines(FloatBuffer buffer, int vertexCount, float[] mvp) {
            if (buffer == null || vertexCount <= 0) return;
            GLES20.glUseProgram(lineProgram);
            GLES20.glUniformMatrix4fv(lineMvp, 1, false, mvp, 0);
            buffer.position(0);
            GLES20.glEnableVertexAttribArray(linePosition);
            GLES20.glVertexAttribPointer(linePosition, 3, GLES20.GL_FLOAT, false, 28, buffer);
            buffer.position(3);
            GLES20.glEnableVertexAttribArray(lineColor);
            GLES20.glVertexAttribPointer(lineColor, 4, GLES20.GL_FLOAT, false, 28, buffer);
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount);
            GLES20.glDisableVertexAttribArray(linePosition);
            GLES20.glDisableVertexAttribArray(lineColor);
        }

        private void putLineVertex(FloatBuffer buffer, float x, float y, float z,
                                   float r, float g, float b, float a) {
            buffer.put(x).put(y).put(z).put(r).put(g).put(b).put(a);
        }

        private int program(String vertexSource, String fragmentSource) {
            int vertex = shader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int value = GLES20.glCreateProgram();
            GLES20.glAttachShader(value, vertex);
            GLES20.glAttachShader(value, fragment);
            GLES20.glLinkProgram(value);
            int[] status = new int[1];
            GLES20.glGetProgramiv(value, GLES20.GL_LINK_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException("Alloy GLES program failed: " + GLES20.glGetProgramInfoLog(value));
            GLES20.glDeleteShader(vertex);
            GLES20.glDeleteShader(fragment);
            return value;
        }

        private int shader(int type, String source) {
            int value = GLES20.glCreateShader(type);
            GLES20.glShaderSource(value, source);
            GLES20.glCompileShader(value);
            int[] status = new int[1];
            GLES20.glGetShaderiv(value, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) throw new IllegalStateException("Alloy GLES shader failed: " + GLES20.glGetShaderInfoLog(value));
            return value;
        }
    }

    private static FloatBuffer floatBuffer(float[] values) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer buffer = bytes.asFloatBuffer();
        buffer.put(values).flip();
        return buffer;
    }

    private static float[] machineLines(float centerX, float centerY, boolean cleanPresentation) {
        ArrayList<Float> values = new ArrayList<>();
        if (cleanPresentation) return toArray(values);
        // Black PEI-style bed with a restrained grid and a neutral conceptual
        // frame. The clearance envelope is visual only and is not a print gate.
        float x0 = centerX - BED_X / 2f, x1 = centerX + BED_X / 2f;
        float y0 = centerY - BED_Y / 2f, y1 = centerY + BED_Y / 2f;
        float[] bed = {x0, y0, 0.15f, x1, y0, 0.15f, x1, y1, 0.15f, x0, y1, 0.15f};
        for (int i = 0; i < 4; i++) addLine(values, bed[i * 3], bed[i * 3 + 1], bed[i * 3 + 2],
                bed[((i + 1) % 4) * 3], bed[((i + 1) % 4) * 3 + 1], bed[((i + 1) % 4) * 3 + 2],
                0.16f, 0.15f, 0.13f, 0.95f);
        for (int grid = 30; grid < 180; grid += 30) {
            float gx = x0 + grid, gy = y0 + grid;
            addLine(values, gx, y0, 0.2f, gx, y1, 0.2f, 0.30f, 0.28f, 0.25f, 0.55f);
            addLine(values, x0, gy, 0.2f, x1, gy, 0.2f, 0.30f, 0.28f, 0.25f, 0.55f);
        }
        // A phone-first product viewport keeps the printer context quiet. A
        // future machine-inspection mode can add the reviewed envelope back.
        return toArray(values);
    }

    /**
     * Alloy-owned, deliberately simplified presentation shell for the A1
     * Mini workspace. This is not redistributed Bambu geometry and is never
     * used by PreparationValidator or the native slicer.
     */
    private static float[] machineSolids(float centerX, float centerY, boolean fullMachine,
                                         boolean cleanPresentation, MeshModel subject) {
        ArrayList<Float> values = new ArrayList<>();
        float x0 = centerX - BED_X / 2f;
        float x1 = centerX + BED_X / 2f;
        float y0 = centerY - BED_Y / 2f;
        float y1 = centerY + BED_Y / 2f;
        if (cleanPresentation) {
            // Hero mode is a configurator-style studio field, not a second
            // build plate. Keep only an understated contact shadow under the
            // object so the model remains the visual focal point.
            if (subject != null) {
                // Keep the shadow proportional to compact imported models;
                // the technical Prepare view retains its larger bed-context
                // minimum below. A fixed 16 mm minimum makes a small-unit
                // OBJ look as if it is sitting on a grey table top.
                float radiusX = clamp((subject.maxX - subject.minX) * 0.68f, 1.2f, 58f);
                float radiusY = clamp((subject.maxY - subject.minY) * 0.68f, 1.2f, 58f);
                addShadow(values, centerX, centerY, radiusX, radiusY, -0.18f, -20f);
            }
            return toArray(values);
        }
        addBox(values, x0, y0, -2.2f, x1, y1, 0.25f, -1f);
        addBox(values, x0 + 4f, y0 + 4f, 0.25f, x1 - 4f, y1 - 4f, 0.85f, -2f);
        addBox(values, x0 + 7f, y1 - 5f, 0.85f, x1 - 7f, y1 - 2f, 2.2f, -3f);
        if (subject != null) {
            float radiusX = clamp((subject.maxX - subject.minX) * 0.62f, 14f, 52f);
            float radiusY = clamp((subject.maxY - subject.minY) * 0.62f, 14f, 52f);
            addShadow(values, centerX, centerY, radiusX, radiusY, 0.87f, -8f);
        }
        if (fullMachine) {
            // Alloy-owned presentation geometry only. It communicates the
            // machine envelope in a dedicated inspection mode; it is not the
            // collision model and is never used to authorize a print.
            // The column, beam and carriage are intentionally segmented so
            // the machine reads as an object with depth instead of a single
            // grey bounding-box wireframe. All of this remains presentation
            // geometry; the slicer still uses only the published volume.
            addBox(values, x0 + 6f, y1 - 16f, 0.85f, x0 + 16f, y1 - 4f, 168f, -4f);
            addBox(values, x1 - 16f, y1 - 16f, 0.85f, x1 - 6f, y1 - 4f, 168f, -4f);
            addBox(values, x0 + 9f, y1 - 18f, 7f, x0 + 13f, y1 - 2f, 164f, -8f);
            addBox(values, x1 - 13f, y1 - 18f, 7f, x1 - 9f, y1 - 2f, 164f, -8f);
            addBox(values, x0 + 6f, y1 - 16f, 164f, x1 - 6f, y1 - 4f, 172f, -5f);
            addBox(values, x0 + 12f, y1 - 12f, 168f, x1 - 12f, y1 - 5f, 175f, -9f);
            float carriage = centerX;
            addBox(values, carriage - 16f, y1 - 17f, 157f, carriage + 16f, y1 - 1f, 170f, -6f);
            addBox(values, carriage - 8f, y1 - 15f, 145f, carriage + 8f, y1 - 3f, 159f, -7f);
            addBox(values, carriage - 6f, y1 - 14f, 135f, carriage + 6f, y1 - 4f, 150f, -10f);
            addCylinderZ(values, carriage, y1 - 9f, 132f, 4.1f, 11f, 20, -11f);
            addCylinderZ(values, carriage, y1 - 9f, 126f, 1.6f, 7f, 20, -12f);

            // A restrained filament spool and a small front control panel
            // make the reference frame legible on a phone without pretending
            // to reproduce vendor CAD or hidden collision dimensions.
            addCylinderY(values, x1 - 25f, y1 - 21f, 121f, 22f, 9f, 28, -13f);
            addCylinderY(values, x1 - 25f, y1 - 27f, 121f, 7f, 3f, 24, -14f);
            addBox(values, x1 - 31f, y0 + 10f, 30f, x1 - 8f, y0 + 13f, 48f, -15f);
            addBox(values, x1 - 28f, y0 + 8.5f, 34f, x1 - 11f, y0 + 10f, 44f, -16f);
        }
        return toArray(values);
    }

    /**
     * Small dimensional study props from the handoff's product-view idea.
     * These are deliberately not user models: they are simple Alloy-owned
     * solids, placed outside the print bed, and rendered only by A1 study.
     */
    private static float[] studyAccessories() {
        ArrayList<Float> values = new ArrayList<>();
        // A compact can silhouette, a tennis-ball sphere and a key-shaped
        // object sit in the quiet foreground of the machine study. Their
        // reduced scale keeps the full printer frame legible on a phone.
        addCylinderZ(values, -102f, 226f, -30f, 10f, 34f, 24, 0f);
        addCylinderZ(values, -102f, 226f, -12f, 8.6f, 1.2f, 24, 0f);
        addSphere(values, -69f, 226f, -35f, 17f, 17f, 17f, 20, 12, 1f);
        addCylinderZ(values, -34f, 226f, -43f, 6.5f, 2.5f, 20, 2f);
        addBox(values, -43f, 224f, -42f, -27f, 228f, -34f, 2f);
        addBox(values, -22f, 225f, -41f, 4f, 227f, -37f, 2f);
        addCylinderZ(values, -34f, 226f, -31f, 2.3f, 2.8f, 16, 3f);
        return toArray(values);
    }

    /** A receding row of empty presentation plates for the A1 study scene. */
    private static float[] studyPlates() {
        ArrayList<Float> values = new ArrayList<>();
        final float centerX = BED_X / 2f;
        for (int index = 0; index < 5; index++) {
            float depth = 54f - index * 4f;
            float width = 168f - index * 8f;
            float centerY = 78f - index * 72f;
            float z = -3.4f - index * 0.12f;
            addBox(values, centerX - width / 2f, centerY - depth / 2f, z,
                    centerX + width / 2f, centerY + depth / 2f, z + 1.4f, -20f);
            // A restrained inset stripe makes each plate readable without
            // turning the study into a second slicer viewport.
            addBox(values, centerX - width * 0.43f, centerY - 1.5f, z + 1.4f,
                    centerX + width * 0.43f, centerY + 1.5f, z + 1.7f, -20f);
        }
        return toArray(values);
    }

    /** A low contact shadow makes the subject read as an object on the plate. */
    private static void addShadow(ArrayList<Float> values, float centerX, float centerY,
                                  float radiusX, float radiusY, float z, float part) {
        final int slices = 28;
        for (int index = 0; index < slices; index++) {
            double a = index * Math.PI * 2d / slices;
            double b = (index + 1) * Math.PI * 2d / slices;
            addFace(values, centerX, centerY, z,
                    centerX + radiusX * (float) Math.cos(a), centerY + radiusY * (float) Math.sin(a), z,
                    centerX + radiusX * (float) Math.cos(b), centerY + radiusY * (float) Math.sin(b), z,
                    0f, 0f, 1f, part);
        }
    }

    private static void addBox(ArrayList<Float> values, float x0, float y0, float z0,
                               float x1, float y1, float z1, float part) {
        addFace(values, x0, y0, z0, x1, y0, z0, x1, y1, z0, 0f, 0f, -1f, part);
        addFace(values, x0, y0, z0, x1, y1, z0, x0, y1, z0, 0f, 0f, -1f, part);
        addFace(values, x0, y0, z1, x1, y1, z1, x1, y0, z1, 0f, 0f, 1f, part);
        addFace(values, x0, y0, z1, x0, y1, z1, x1, y1, z1, 0f, 0f, 1f, part);
        addFace(values, x0, y0, z0, x0, y0, z1, x1, y0, z1, 0f, -1f, 0f, part);
        addFace(values, x0, y0, z0, x1, y0, z1, x1, y0, z0, 0f, -1f, 0f, part);
        addFace(values, x0, y1, z0, x1, y1, z1, x0, y1, z1, 0f, 1f, 0f, part);
        addFace(values, x0, y1, z0, x1, y1, z0, x1, y1, z1, 0f, 1f, 0f, part);
        addFace(values, x0, y0, z0, x0, y1, z1, x0, y0, z1, -1f, 0f, 0f, part);
        addFace(values, x0, y0, z0, x0, y1, z0, x0, y1, z1, -1f, 0f, 0f, part);
        addFace(values, x1, y0, z0, x1, y0, z1, x1, y1, z1, 1f, 0f, 0f, part);
        addFace(values, x1, y0, z0, x1, y1, z1, x1, y1, z0, 1f, 0f, 0f, part);
    }

    /** Cylinder with its axis along Z, used for the toolhead/nozzle stack. */
    private static void addCylinderZ(ArrayList<Float> values, float centerX, float centerY,
                                     float centerZ, float radius, float height, int slices, float part) {
        int count = Math.max(12, Math.min(48, slices));
        float z0 = centerZ - height / 2f, z1 = centerZ + height / 2f;
        for (int index = 0; index < count; index++) {
            double a = index * Math.PI * 2d / count;
            double b = (index + 1) * Math.PI * 2d / count;
            float ax = centerX + radius * (float) Math.cos(a), ay = centerY + radius * (float) Math.sin(a);
            float bx = centerX + radius * (float) Math.cos(b), by = centerY + radius * (float) Math.sin(b);
            addFace(values, ax, ay, z0, bx, by, z0, bx, by, z1, 0f, 0f, -1f, part);
            addFace(values, ax, ay, z0, bx, by, z1, ax, ay, z1, 0f, 0f, 1f, part);
            addFace(values, ax, ay, z0, ax, ay, z1, bx, by, z1,
                    (float) Math.cos(a), (float) Math.sin(a), 0f, part);
        }
    }

    /** Cylinder with its axis along Y, used for the presentation spool. */
    private static void addCylinderY(ArrayList<Float> values, float centerX, float centerY,
                                     float centerZ, float radius, float depth, int slices, float part) {
        int count = Math.max(12, Math.min(48, slices));
        float y0 = centerY - depth / 2f, y1 = centerY + depth / 2f;
        for (int index = 0; index < count; index++) {
            double a = index * Math.PI * 2d / count;
            double b = (index + 1) * Math.PI * 2d / count;
            float ax = centerX + radius * (float) Math.cos(a), az = centerZ + radius * (float) Math.sin(a);
            float bx = centerX + radius * (float) Math.cos(b), bz = centerZ + radius * (float) Math.sin(b);
            addFace(values, ax, y0, az, bx, y0, bz, bx, y1, bz, 0f, -1f, 0f, part);
            addFace(values, ax, y0, az, bx, y1, bz, ax, y1, az, 0f, 1f, 0f, part);
            addFace(values, ax, y0, az, ax, y1, az, bx, y1, bz,
                    (float) Math.cos(a), 0f, (float) Math.sin(a), part);
        }
    }

    /** Low-poly sphere used only for the bounded scale-reference study prop. */
    private static void addSphere(ArrayList<Float> values, float centerX, float centerY,
                                  float centerZ, float radiusX, float radiusY, float radiusZ,
                                  int segments, int rings, float part) {
        int around = Math.max(12, Math.min(32, segments));
        int bands = Math.max(6, Math.min(18, rings));
        for (int ring = 0; ring < bands; ring++) {
            double a0 = -Math.PI / 2d + Math.PI * ring / bands;
            double a1 = -Math.PI / 2d + Math.PI * (ring + 1) / bands;
            for (int segment = 0; segment < around; segment++) {
                double b0 = Math.PI * 2d * segment / around;
                double b1 = Math.PI * 2d * (segment + 1) / around;
                float[] p0 = spherePoint(centerX, centerY, centerZ, radiusX, radiusY, radiusZ, a0, b0);
                float[] p1 = spherePoint(centerX, centerY, centerZ, radiusX, radiusY, radiusZ, a0, b1);
                float[] p2 = spherePoint(centerX, centerY, centerZ, radiusX, radiusY, radiusZ, a1, b1);
                float[] p3 = spherePoint(centerX, centerY, centerZ, radiusX, radiusY, radiusZ, a1, b0);
                float[] n0 = sphereNormal(p0, centerX, centerY, centerZ, radiusX, radiusY, radiusZ);
                float[] n1 = sphereNormal(p2, centerX, centerY, centerZ, radiusX, radiusY, radiusZ);
                addFace(values, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], n0, part);
                addFace(values, p0[0], p0[1], p0[2], p2[0], p2[1], p2[2], p3[0], p3[1], p3[2], n1, part);
            }
        }
    }

    private static float[] spherePoint(float centerX, float centerY, float centerZ,
                                       float radiusX, float radiusY, float radiusZ,
                                       double latitude, double longitude) {
        float cosLatitude = (float) Math.cos(latitude);
        return new float[]{
                centerX + radiusX * cosLatitude * (float) Math.cos(longitude),
                centerY + radiusY * cosLatitude * (float) Math.sin(longitude),
                centerZ + radiusZ * (float) Math.sin(latitude)
        };
    }

    private static float[] sphereNormal(float[] point, float centerX, float centerY, float centerZ,
                                        float radiusX, float radiusY, float radiusZ) {
        float nx = (point[0] - centerX) / Math.max(0.001f, radiusX);
        float ny = (point[1] - centerY) / Math.max(0.001f, radiusY);
        float nz = (point[2] - centerZ) / Math.max(0.001f, radiusZ);
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        return length < 0.000001f ? new float[]{0f, 0f, 1f}
                : new float[]{nx / length, ny / length, nz / length};
    }

    private static void addFace(ArrayList<Float> values,
                                float ax, float ay, float az,
                                float bx, float by, float bz,
                                float cx, float cy, float cz,
                                float[] normal, float part) {
        if (normal == null || normal.length < 3) {
            addFace(values, ax, ay, az, bx, by, bz, cx, cy, cz, 0f, 0f, 1f, part);
        } else {
            addFace(values, ax, ay, az, bx, by, bz, cx, cy, cz,
                    normal[0], normal[1], normal[2], part);
        }
    }

    private static void addFace(ArrayList<Float> values,
                                float ax, float ay, float az,
                                float bx, float by, float bz,
                                float cx, float cy, float cz,
                                float nx, float ny, float nz, float part) {
        addMeshVertex(values, ax, ay, az, nx, ny, nz, part);
        addMeshVertex(values, bx, by, bz, nx, ny, nz, part);
        addMeshVertex(values, cx, cy, cz, nx, ny, nz, part);
    }

    private static void addMeshVertex(ArrayList<Float> values, float x, float y, float z,
                                      float nx, float ny, float nz, float part) {
        values.add(x); values.add(y); values.add(z);
        values.add(nx); values.add(ny); values.add(nz); values.add(part);
    }

    private static void addLine(ArrayList<Float> values, float x1, float y1, float z1,
                                float x2, float y2, float z2, float r, float g, float b, float a) {
        values.add(x1); values.add(y1); values.add(z1); values.add(r); values.add(g); values.add(b); values.add(a);
        values.add(x2); values.add(y2); values.add(z2); values.add(r); values.add(g); values.add(b); values.add(a);
    }

    private static float[] toArray(ArrayList<Float> values) {
        float[] output = new float[values.size()];
        for (int index = 0; index < values.size(); index++) output[index] = values.get(index);
        return output;
    }

    private static boolean outsideBuildVolume(MeshModel value) {
        return value.minX < -0.01f || value.maxX > BED_X + 0.01f
                || value.minY < -0.01f || value.maxY > BED_Y + 0.01f
                || value.minZ < -0.01f || value.maxZ > BUILD_Z + 0.01f;
    }

    private static final String BACKDROP_VERTEX_SHADER =
            "attribute vec2 aPosition; varying vec2 vUv;"
                    + "void main(){ vUv=aPosition*0.5+0.5; gl_Position=vec4(aPosition,0.0,1.0); }";

    private static final String BACKDROP_FRAGMENT_SHADER =
            "precision mediump float; uniform int uTheme; varying vec2 vUv;"
                    + "void main(){"
                    + "vec3 top=uTheme==1?vec3(0.040,0.047,0.070):vec3(0.985,0.983,0.978);"
                    + "vec3 bottom=uTheme==1?vec3(0.010,0.012,0.020):vec3(0.875,0.850,0.812);"
                    + "vec3 color=mix(bottom,top,smoothstep(0.0,1.0,vUv.y));"
                    + "float glow=1.0-smoothstep(0.02,0.72,distance(vUv,vec2(0.50,0.52)));"
                    + "color+=vec3(0.045,0.060,0.085)*glow*(uTheme==1?1.0:0.20);"
                    + "float edge=smoothstep(0.30,0.82,distance(vUv,vec2(0.50,0.48)));"
                    + "color*=1.0-edge*(uTheme==1?0.22:0.08);"
                    + "gl_FragColor=vec4(color,1.0); }";

    private static final String MESH_VERTEX_SHADER =
            "uniform mat4 uMvp;"
                    + "attribute vec3 aPosition; attribute vec3 aNormal; attribute float aPart;"
                    + "varying vec3 vNormal; varying vec3 vPosition; varying float vPart;"
                    + "void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vNormal=aNormal; vPosition=aPosition; vPart=aPart; }";

            private static final String MESH_FRAGMENT_SHADER =
            "precision mediump float; uniform int uSelectedPart; uniform int uOutOfBounds; uniform int uMachine; uniform float uAlpha; uniform int uFinish; uniform int uNight;"
                    + "varying vec3 vNormal; varying float vPart;"
                    + "varying vec3 vPosition;"
                    // The supplied preview uses a procedural PEI grain. Keep
                    // the same idea in GLES2 so the physical bed does not
                    // collapse into a flat black rectangle on Android. This
                    // is presentation-only; it is never sampled by the
                    // slicer or used for collision decisions.
                    + "float alloyHash(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}"
                    + "float alloyNoise(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.0-2.0*f);return mix(mix(alloyHash(i),alloyHash(i+vec2(1.0,0.0)),f.x),mix(alloyHash(i+vec2(0.0,1.0)),alloyHash(i+vec2(1.0,1.0)),f.x),f.y);}"
                    + "void main(){"
                    + "vec3 base;"
                    + "if(uMachine==2 && vPart<-19.0){base=uNight==1?vec3(0.010,0.012,0.020):vec3(0.18,0.16,0.14);}"
                    + "else if(uMachine==2){base=uNight==1?vec3(0.13,0.15,0.19):vec3(0.22,0.17,0.13);}"
                    + "else if(uMachine==1 && vPart>-1.5){base=vec3(0.055,0.060,0.065);}"
                    + "else if(uMachine==1 && vPart>-2.5){base=vec3(0.17,0.18,0.19);}"
                    + "else if(uMachine==1 && vPart>-3.5){base=vec3(0.28,0.25,0.22);}"
                    + "else if(uMachine==1 && vPart>-4.5){base=vec3(0.10,0.11,0.13);}"
                    + "else if(uMachine==1 && vPart>-5.5){base=vec3(0.13,0.14,0.16);}"
                    + "else if(uMachine==1 && vPart>-6.5){base=vec3(0.18,0.20,0.23);}"
                    + "else if(uMachine==1 && vPart>-7.5){base=vec3(0.82,0.84,0.86);}"
                    + "else if(uMachine==1 && vPart>-8.5){base=vec3(0.58,0.61,0.65);}"
                    + "else if(uMachine==1 && vPart>-9.5){base=vec3(0.24,0.27,0.31);}"
                    + "else if(uMachine==1 && vPart>-10.5){base=vec3(0.12,0.14,0.17);}"
                    + "else if(uMachine==1 && vPart>-11.5){base=vec3(0.88,0.68,0.25);}"
                    + "else if(uMachine==1 && vPart>-12.5){base=vec3(0.16,0.18,0.23);}"
                    + "else if(uMachine==1 && vPart>-13.5){base=vec3(0.78,0.80,0.84);}"
                    + "else if(uMachine==1 && vPart>-14.5){base=vec3(0.22,0.24,0.27);}"
                    + "else if(uMachine==1 && vPart>-15.5){base=vec3(0.08,0.10,0.13);}"
                    + "else if(uMachine==1){base=vec3(0.24,0.27,0.31);}"
                    + "else if(vPart<0.5){base=vec3(0.56,0.24,0.09);}"
                    + "else if(vPart<1.5){base=vec3(0.83,0.64,0.33);}"
                    + "else if(vPart<2.5){base=vec3(0.70,0.47,0.20);}"
                    + "else if(vPart<3.5){base=vec3(0.44,0.28,0.12);}"
                    + "else if(vPart<4.5){base=vec3(0.12,0.15,0.17);}"
                    + "else if(vPart<5.5){base=vec3(0.64,0.32,0.10);}"
                    + "else{base=vec3(0.93,0.73,0.27);}"
                    + "if(uMachine==3 && vPart<0.5){base=vec3(0.58,0.62,0.69);}"
                    + "else if(uMachine==3 && vPart<1.5){base=vec3(0.78,0.86,0.12);}"
                    + "else if(uMachine==3 && vPart<2.5){base=vec3(0.74,0.67,0.42);}"
                    + "else if(uMachine==3){base=vec3(0.88,0.72,0.22);}"
                    + "if(uMachine==1 && vPart>-1.5 && vPosition.z<4.5){"
                    + "vec2 grainUv=vPosition.xy*0.22; float n=alloyNoise(grainUv)*0.58+alloyNoise(grainUv*2.7)*0.27+alloyNoise(grainUv*6.1)*0.15;"
                    + "float dx=alloyNoise(grainUv+vec2(0.55,0.0))-n; float dy=alloyNoise(grainUv+vec2(0.0,0.55))-n;"
                    + "float grainLight=clamp(0.52+(dx*0.72-dy*0.46)*7.0,0.0,1.0);"
                    + "base=mix(vec3(0.025,0.028,0.034),vec3(0.095,0.100,0.115),n*0.52+grainLight*0.48);"
                    + "}"
                    // Appearance is intentionally evaluated after the
                    // authored per-part palette and only for printable model
                    // geometry. Machine colours remain stable, while the
                    // same mesh can be previewed as a different filament
                    // finish without changing the recipe or generated G-code.
                    + "if(uMachine==0 && uFinish==1){float lum=dot(base,vec3(0.299,0.587,0.114)); base=mix(base,vec3(lum),0.82); base=mix(base,vec3(0.035,0.045,0.060),0.72);}"
                    + "else if(uMachine==0 && uFinish==2){base=mix(base,vec3(0.94,0.95,0.98),0.78);}"
                    + "else if(uMachine==0 && uFinish==3){base=mix(base,vec3(0.96,0.19,0.025),0.84);}"
                    + "else if(uMachine==0 && uFinish==4){float lum=dot(base,vec3(0.299,0.587,0.114)); base=mix(vec3(lum),vec3(0.34,0.40,0.48),0.72);}"
                    + "if(uOutOfBounds==1) base=vec3(0.42,0.43,0.45);"
                    + "float focus=(uMachine==1 || uSelectedPart<0 || abs(vPart-float(uSelectedPart))<0.5)?1.0:0.22;"
                    // A small three-point studio rig makes the supplied
                    // product-study mesh read as an object rather than a
                    // debug wireframe. Keep it in the fragment pass so it
                    // works for both the authored assembly and the imported
                    // reference mesh without changing coordinates or assets.
                    + "vec3 normal=normalize(vNormal);"
                    + "vec3 key=normalize(vec3(-0.42,0.56,0.96));"
                    + "vec3 fill=normalize(vec3(0.72,-0.30,0.58));"
                    + "vec3 view=normalize(vec3(-0.10,0.18,1.0));"
                    + "float keyDiffuse=max(dot(normal,key),0.0);"
                    + "float fillDiffuse=max(dot(normal,fill),0.0);"
                    + "float rim=pow(1.0-max(dot(normal,view),0.0),2.1);"
                    + "vec3 halfKey=normalize(key+view);"
                    + "float highlight=pow(max(dot(normal,halfKey),0.0),uMachine==1?30.0:42.0);"
                    + "float edgeLight=pow(max(1.0-abs(dot(normal,view)),0.0),3.0);"
                    + "float heightTint=0.96+0.04*smoothstep(-20.0,180.0,vPosition.z);"
                    + "vec3 shaded=base*(0.30+0.80*keyDiffuse+0.18*fillDiffuse)*heightTint;"
                    + "shaded+=vec3(0.30,0.25,0.19)*highlight;"
                    + "shaded+=base*rim*(uNight==1?0.34:0.20);"
                    + "shaded+=vec3(0.12,0.16,0.22)*edgeLight*(uNight==1?0.58:0.28);"
                    + "float shadowAlpha=(uMachine==2 && vPart<-19.0)?(uNight==1?0.48:0.25):1.0;"
                    + "gl_FragColor=vec4(shaded*focus,uAlpha*shadowAlpha); }";

    private static final String LINE_VERTEX_SHADER =
            "uniform mat4 uMvp; attribute vec3 aPosition; attribute vec4 aColor; varying vec4 vColor;"
                    + "void main(){ gl_Position=uMvp*vec4(aPosition,1.0); vColor=aColor; }";

    private static final String LINE_FRAGMENT_SHADER =
            "precision mediump float; varying vec4 vColor; void main(){ gl_FragColor=vColor; }";
}
