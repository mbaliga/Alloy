package com.mbaliga.alloy;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Locale;

/** Alloy v1 task-flow shell: Import → Prepare → Slice → Inspect → Export. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 41;
    private static final int REQUEST_EXPORT = 42;
    private static final int BG = Color.rgb(13, 24, 31);
    private static final int PANEL = Color.rgb(21, 38, 47);
    private static final int TEXT = Color.rgb(239, 241, 234);
    private static final int MUTED = Color.rgb(164, 184, 185);
    private static final int GOLD = Color.rgb(232, 184, 106);

    private LinearLayout root, actions;
    private TextView status, details;
    private ViewportView viewport;
    private MeshModel model;
    private Slicer.Result slice;
    private boolean slicing;
    private final Slicer.Config config = new Slicer.Config();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        buildUi();
        config.layerHeight = getPreferences(MODE_PRIVATE).getFloat("layer_height", config.layerHeight);
        config.infill = getPreferences(MODE_PRIVATE).getFloat("infill", config.infill);
        Uri incoming = getIntent().getData();
        if (incoming != null) loadUri(incoming);
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout bar = new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(24, 18, 24, 12);
        TextView title = label("ALLOY", 22, TEXT); title.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView target = label("A1 MINI  ·  V1", 12, GOLD); bar.addView(target);
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        status = label("Import a model to begin", 14, MUTED); status.setPadding(24, 0, 24, 12);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        viewport = new ViewportView(this); root.addView(viewport, new LinearLayout.LayoutParams(-1, 0, 1));
        details = label("STL and 3MF · 180 × 180 × 180 mm build volume", 13, MUTED); details.setPadding(24, 12, 24, 4);
        root.addView(details, new LinearLayout.LayoutParams(-1, -2));
        actions = new LinearLayout(this); actions.setPadding(16, 10, 16, 16); actions.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root); refreshActions();
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); return view;
    }

    private Button action(String text, View.OnClickListener listener) {
        Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setTextColor(TEXT); button.setAllCaps(false);
        button.setOnClickListener(listener); button.setMinHeight(52); button.setPadding(12, 0, 12, 0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setColor(PANEL); bg.setCornerRadius(18); button.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 56, 1); lp.setMargins(4, 0, 4, 0); actions.addView(button, lp); return button;
    }

    private void refreshActions() {
        actions.removeAllViews();
        if (model == null) {
            action("Import model", v -> openModel());
            action("Recipe", v -> showRecipe());
        } else if (slicing) {
            Button busy = action("Slicing…", null); busy.setEnabled(false);
        } else if (slice == null) {
            action("Import another", v -> openModel());
            action("Recipe", v -> showRecipe());
            Button sliceButton = action("Slice", v -> startSlice());
            sliceButton.setTextColor(GOLD);
        } else {
            action("Prepare", v -> { slice = null; viewport.setResult(null); status.setText("Ready to adjust the recipe or slice again"); refreshActions(); });
            action("Layer −", v -> changeLayer(-1));
            action("Layer +", v -> changeLayer(1));
            Button export = action("Export .3mf", v -> exportPackage()); export.setTextColor(GOLD);
        }
    }

    private void openModel() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/stl", "model/3mf", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml", "application/octet-stream"});
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (request == REQUEST_OPEN) loadUri(data.getData());
        if (request == REQUEST_EXPORT) writeExport(data.getData());
    }

    private void loadUri(Uri uri) {
        String name = displayName(uri);
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalArgumentException("The selected file could not be opened");
            model = MeshModel.read(name, input); slice = null; viewport.setModel(model);
            status.setText("Prepare  ·  " + name); details.setText(String.format(Locale.US, "%.1f × %.1f × %.1f mm  ·  %d triangles  ·  centered on A1 Mini plate", model.maxX - model.minX, model.maxY - model.minY, model.maxZ - model.minZ, model.triangles.length / 3)); refreshActions();
        } catch (Exception e) { Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private String displayName(Uri uri) {
        android.database.Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) try { if (cursor.moveToFirst()) return cursor.getString(0); } finally { cursor.close(); }
        String path = uri.getLastPathSegment(); return path == null ? "model.stl" : path;
    }

    private void startSlice() {
        if (model == null) return;
        slice = null; slicing = true; refreshActions(); status.setText("Slice  ·  preparing geometry…");
        new Thread(() -> {
            try {
                Slicer.Result result = new Slicer().slice(model, config, (percent, phase) -> runOnUiThread(() -> status.setText("Slice  ·  " + percent + "%  ·  " + phase)));
                runOnUiThread(() -> { slicing = false; slice = result; viewport.setResult(result); status.setText("Inspect  ·  " + result.layers.size() + " layers"); details.setText(String.format(Locale.US, "%.0f mm filament estimate  ·  layer %.2f mm  ·  %d warning(s)", result.filamentMm, config.layerHeight, result.warnings)); refreshActions(); });
            } catch (Exception e) { runOnUiThread(() -> { slicing = false; status.setText("Prepare  ·  slice failed"); refreshActions(); Toast.makeText(this, "Slice failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }); }
        }, "alloy-slice").start();
    }

    private void changeLayer(int delta) {
        if (slice == null || slice.layers.isEmpty()) return;
        int current = viewport.getSelectedLayer() < 0 ? slice.layers.size() - 1 : viewport.getSelectedLayer();
        int next = Math.max(0, Math.min(slice.layers.size() - 1, current + delta));
        viewport.setSelectedLayer(next); status.setText("Inspect  ·  layer " + (next + 1) + " / " + slice.layers.size());
    }

    private void exportPackage() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(model.displayName) + ".gcode.3mf"); startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void writeExport(Uri uri) {
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IllegalArgumentException("Output destination could not be opened");
            GcodePackageWriter.write(model, slice, out); Toast.makeText(this, "Exported .gcode.3mf", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void showRecipe() {
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(28, 4, 28, 0);
        EditText layer = field(String.format(Locale.US, "%.2f", config.layerHeight), "Layer height (mm)");
        EditText infill = field(String.format(Locale.US, "%.0f", config.infill * 100), "Infill (%)"); fields.addView(layer); fields.addView(infill);
        TextView note = label("A1 Mini · 0.4 mm nozzle · PLA\nProfile values remain conservative in v1. Advanced engine parity is still a documented gate.", 13, MUTED); note.setPadding(0, 18, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this).setTitle("Print recipe").setView(fields).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> {
            try { config.layerHeight = clamp(Float.parseFloat(layer.getText().toString()), 0.08f, 0.40f); config.infill = clamp(Float.parseFloat(infill.getText().toString()) / 100f, 0f, 1f); getPreferences(MODE_PRIVATE).edit().putFloat("layer_height", config.layerHeight).putFloat("infill", config.infill).apply(); details.setText("Recipe applied · A1 Mini · " + String.format(Locale.US, "%.2f mm layer · %.0f%% infill", config.layerHeight, config.infill * 100)); } catch (Exception e) { Toast.makeText(this, "Recipe values were not valid", Toast.LENGTH_SHORT).show(); }
        }).show();
    }

    private EditText field(String value, String hint) { EditText field = new EditText(this); field.setText(value); field.setHint(hint); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); return field; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static String safeName(String name) { return name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("(?i)\\.(stl|3mf)$", ""); }

    @Override public void onBackPressed() {
        if (slice != null) { slice = null; viewport.setResult(null); refreshActions(); status.setText("Prepare  ·  " + model.displayName); return; }
        super.onBackPressed();
    }
}
