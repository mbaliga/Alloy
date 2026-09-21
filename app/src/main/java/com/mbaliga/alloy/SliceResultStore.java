package com.mbaliga.alloy;

import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Durable native/fallback G-code and metadata handoff from the service to the UI. */
public final class SliceResultStore {
    private static final String GCODE = "result.gcode";
    private static final String META = "result.json";
    private static final long MAX_GCODE_BYTES = 128L * 1024L * 1024L;
    private static final int MAX_META_BYTES = 16 * 1024;

    private SliceResultStore() { }

    public static void write(File jobDir, Slicer.Result result) throws IOException {
        if (jobDir == null || result == null || result.gcode == null) throw new IOException("Slice result is missing");
        if (PrinterTransport.isSymbolicLink(jobDir) || !jobDir.isDirectory()) throw new IOException("Slice result directory is invalid");
        GcodeSafetyValidator.requireSafe(result.gcode);
        if (NativeSlicerEngine.isNativeEngineId(result.engineId)) A1MiniTemplatePolicy.requireSafe(result.gcode);
        File gcode = new File(jobDir, GCODE);
        File meta = new File(jobDir, META);
        if (PrinterTransport.isSymbolicLink(gcode) || PrinterTransport.isSymbolicLink(meta)) throw new IOException("Slice result target is unsafe");
        File temporary = new File(jobDir, "." + GCODE + ".part");
        try (FileOutputStream raw = new FileOutputStream(temporary);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8))) {
            output.write(result.gcode); output.flush(); raw.getFD().sync();
        }
        if (temporary.length() <= 0L || temporary.length() > MAX_GCODE_BYTES) throw new IOException("Slice G-code is too large");
        move(temporary, gcode);
        JSONObject value = new JSONObject();
        try {
            value.put("engine_id", safeEngineId(result.engineId));
            value.put("engine_verified", result.engineVerified);
            value.put("filament_mm", result.filamentMm);
            value.put("warnings", result.warnings);
            value.put("print_time_seconds", result.printTimeSeconds);
            value.put("travel_mm", result.travelMm);
        } catch (Exception error) { throw new IOException("Slice result metadata could not be encoded", error); }
        File metaTemporary = new File(jobDir, "." + META + ".part");
        try (FileOutputStream raw = new FileOutputStream(metaTemporary);
             BufferedWriter output = new BufferedWriter(new OutputStreamWriter(raw, StandardCharsets.UTF_8))) {
            output.write(value.toString()); output.flush(); raw.getFD().sync();
        }
        move(metaTemporary, meta);
    }

    public static Slicer.Result read(File jobDir, Slicer.Config config) throws IOException {
        if (jobDir == null || config == null || PrinterTransport.isSymbolicLink(jobDir)) throw new IOException("Slice result directory is invalid");
        File gcode = new File(jobDir, GCODE);
        File meta = new File(jobDir, META);
        if (PrinterTransport.isSymbolicLink(gcode) || PrinterTransport.isSymbolicLink(meta)
                || !gcode.isFile() || !meta.isFile() || gcode.length() <= 0L || gcode.length() > MAX_GCODE_BYTES
                || meta.length() <= 0L || meta.length() > MAX_META_BYTES) throw new IOException("Slice result is unavailable");
        JSONObject value;
        try (InputStream input = new FileInputStream(meta)) {
            byte[] bytes = new byte[(int) meta.length()];
            int offset = 0, read;
            while (offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) != -1) offset += read;
            if (offset != bytes.length) throw new IOException("Slice result metadata is truncated");
            value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IOException("Slice result metadata is invalid", error); }
        String engineId = safeEngineId(value.optString("engine_id", "alloy-unknown"));
        boolean verified = value.optBoolean("engine_verified", false);
        Slicer.Result parsed = NativeSlicerEngine.parseGcode(gcode, config, engineId, verified);
        if (NativeSlicerEngine.isNativeEngineId(engineId)) A1MiniTemplatePolicy.requireSafe(parsed.gcode);
        float filament = finite(value.optDouble("filament_mm", parsed.filamentMm), parsed.filamentMm);
        float time = finite(value.optDouble("print_time_seconds", parsed.printTimeSeconds), parsed.printTimeSeconds);
        float travel = finite(value.optDouble("travel_mm", parsed.travelMm), parsed.travelMm);
        int warnings = value.optInt("warnings", parsed.warnings);
        if (warnings < 0 || warnings > 1_000_000) throw new IOException("Slice warning count is invalid");
        return new Slicer.Result(parsed.gcode, parsed.layers, filament, warnings, engineId, verified, time, travel);
    }

    private static float finite(double value, float fallback) {
        return Double.isNaN(value) || Double.isInfinite(value) || value < 0d || value > 1_000_000_000d ? fallback : (float) value;
    }

    private static String safeEngineId(String value) throws IOException {
        if (value == null || value.trim().length() == 0 || value.length() > 128) throw new IOException("Slice engine identity is invalid");
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) throw new IOException("Slice engine identity is invalid");
        return value.trim();
    }

    private static void move(File source, File target) throws IOException {
        try {
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException unsupported) {
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (PrinterTransport.isSymbolicLink(target) || !target.isFile()) throw new IOException("Slice result write could not be verified");
    }
}
