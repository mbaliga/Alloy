package com.mbaliga.alloy;

import android.content.ContentResolver;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;

/** Durable per-plate results for a foreground batch slice transaction. */
public final class BatchSliceResultStore {
    private static final String RESULTS = "results";
    private static final String MANIFEST = "manifest.json";
    private static final int MAX_MANIFEST_BYTES = 256 * 1024;

    private BatchSliceResultStore() { }

    /** Persist one already-staged plate before the service advances its checkpoint. */
    public static synchronized void record(File filesDir, File batchDir,
                                            BatchSliceJobController.PlateResult result) throws IOException {
        if (filesDir == null || batchDir == null || result == null || result.plate == null
                || result.slice == null || result.artifact == null)
            throw new IOException("Batch result inputs are invalid");
        if (PrinterTransport.isSymbolicLink(filesDir) || PrinterTransport.isSymbolicLink(batchDir)
                || !batchDir.isDirectory()) throw new IOException("Batch result directory is invalid");
        verifyArtifact(filesDir, result.artifact);
        File root = new File(batchDir, RESULTS);
        if (PrinterTransport.isSymbolicLink(root) || (!root.exists() && !root.mkdirs()) || !root.isDirectory())
            throw new IOException("Batch results directory is invalid");
        File plateDir = plateDirectory(root, result.plate.index);
        if (!plateDir.exists() && !plateDir.mkdirs()) throw new IOException("Could not create plate result directory");
        if (!plateDir.isDirectory()) throw new IOException("Plate result directory is invalid");
        SliceResultStore.write(plateDir, result.slice);

        HashMap<Integer, JSONObject> entries = readEntries(batchDir);
        try {
            entries.put(result.plate.index, encode(result));
        } catch (Exception error) {
            throw new IOException("Batch result manifest could not be encoded", error);
        }
        writeManifest(batchDir, entries);
    }

    /** Rehydrate only a completed, fully manifested batch; partial results are never exposed. */
    public static BatchSliceJobController.BatchResult read(File filesDir, ContentResolver resolver,
                                                            BatchSliceRequestStore.Request request,
                                                            File batchDir) throws Exception {
        if (filesDir == null || resolver == null || request == null || batchDir == null
                || PrinterTransport.isSymbolicLink(batchDir) || !batchDir.isDirectory())
            throw new IOException("Batch result directory is invalid");
        HashMap<Integer, JSONObject> entries = readEntries(batchDir);
        if (entries.size() != request.plates.size()) throw new IOException("Batch result manifest is incomplete");
        HashSet<Integer> expected = new HashSet<>();
        ArrayList<BatchSliceJobController.PlateResult> results = new ArrayList<>();
        for (PlateStore.Plate plate : request.plates) {
            if (plate == null || !expected.add(plate.index)) throw new IOException("Batch request plates are invalid");
            JSONObject encoded = entries.get(plate.index);
            if (encoded == null) throw new IOException("Batch result is missing a plate");
            int index = encoded.getInt("index");
            if (index != plate.index || !plate.name.equals(encoded.getString("name")))
                throw new IOException("Batch result plate identity does not match its request");
            File resultDir = plateDirectory(new File(batchDir, RESULTS), index);
            Slicer.Result slice = SliceResultStore.read(resultDir, request.config);
            String artifactName = encoded.getString("artifact_name");
            long size = encoded.getLong("artifact_size");
            String sha256 = encoded.getString("artifact_sha256");
            PrinterTransport.Artifact artifact = ArtifactStore.recover(filesDir, artifactName, size, sha256);
            verifyMetrics(encoded, slice);
            MeshModel model = BatchSliceJobController.loadPlate(resolver, filesDir, plate, request.config);
            Slicer.validate(model, request.config);
            results.add(new BatchSliceJobController.PlateResult(plate, model, slice, artifact));
        }
        if (results.size() != entries.size()) throw new IOException("Batch result contains an unexpected plate");
        return new BatchSliceJobController.BatchResult(results);
    }

    private static HashMap<Integer, JSONObject> readEntries(File batchDir) throws IOException {
        File file = new File(batchDir, MANIFEST);
        if (PrinterTransport.isSymbolicLink(file) || !file.isFile() || file.length() <= 0L || file.length() > MAX_MANIFEST_BYTES)
            return new HashMap<>();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0, read;
            while (offset < bytes.length && (read = input.read(bytes, offset, bytes.length - offset)) != -1) offset += read;
            if (offset != bytes.length) throw new IOException("Batch result manifest is truncated");
            JSONObject root = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"alloy-batch-result".equals(root.getString("format")) || root.getInt("version") != 1
                    || !batchDir.getName().equals(root.getString("job_id"))) throw new IOException("Batch result manifest identity is invalid");
            JSONArray values = root.getJSONArray("plates");
            if (values.length() > PlateStore.MAX_PLATES) throw new IOException("Batch result manifest is too large");
            HashMap<Integer, JSONObject> entries = new HashMap<>();
            for (int index = 0; index < values.length(); index++) {
                JSONObject value = values.getJSONObject(index);
                int plate = value.getInt("index");
                if (plate < 0 || plate >= PlateStore.MAX_PLATES || entries.put(plate, value) != null)
                    throw new IOException("Batch result manifest contains a duplicate plate");
            }
            return entries;
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Batch result manifest is invalid", error); }
    }

    private static JSONObject encode(BatchSliceJobController.PlateResult result) throws Exception {
        PrinterTransport.Artifact artifact = result.artifact;
        return new JSONObject().put("index", result.plate.index).put("name", result.plate.name)
                .put("result_dir", "results/plate-" + String.format(Locale.US, "%02d", result.plate.index + 1))
                .put("artifact_name", artifact.displayName).put("artifact_size", artifact.sizeBytes)
                .put("artifact_sha256", artifact.sha256).put("layers", result.slice.layers.size())
                .put("filament_mm", result.slice.filamentMm).put("print_time_seconds", result.slice.printTimeSeconds)
                .put("engine", result.slice.engineId).put("engine_verified", result.slice.engineVerified);
    }

    private static void verifyMetrics(JSONObject value, Slicer.Result slice) throws IOException, JSONException {
        if (value.getInt("layers") != slice.layers.size()
                || Math.abs((float) value.getDouble("filament_mm") - slice.filamentMm) > 0.01f
                || Math.abs((float) value.getDouble("print_time_seconds") - slice.printTimeSeconds) > 0.01f
                || !value.getString("engine").equals(slice.engineId)
                || value.getBoolean("engine_verified") != slice.engineVerified)
            throw new IOException("Batch result metrics do not match its durable slice");
    }

    private static void verifyArtifact(File filesDir, PrinterTransport.Artifact artifact) throws IOException {
        if (artifact.sourceFile == null || PrinterTransport.isSymbolicLink(artifact.sourceFile)
                || !artifact.sourceFile.isFile() || artifact.sourceFile.length() != artifact.sizeBytes
                || !ArtifactStore.sha256(artifact.sourceFile).equalsIgnoreCase(artifact.sha256))
            throw new IOException("Batch artifact identity could not be verified");
        GcodePackageValidator.validate(artifact.sourceFile);
    }

    private static File plateDirectory(File root, int index) throws IOException {
        if (root == null || index < 0 || index >= PlateStore.MAX_PLATES) throw new IOException("Batch plate directory is invalid");
        File directory = new File(root, "plate-" + String.format(Locale.US, "%02d", index + 1));
        if (PrinterTransport.isSymbolicLink(directory)) throw new IOException("Batch plate directory must not be a symbolic link");
        return directory;
    }

    private static void writeManifest(File batchDir, HashMap<Integer, JSONObject> entries) throws IOException {
        JSONArray plates = new JSONArray();
        try {
            for (int index = 0; index < PlateStore.MAX_PLATES; index++) if (entries.containsKey(index)) plates.put(entries.get(index));
            JSONObject root = new JSONObject().put("format", "alloy-batch-result").put("version", 1)
                    .put("job_id", batchDir.getName()).put("plates", plates);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MANIFEST_BYTES) throw new IOException("Batch result manifest is too large");
            File target = new File(batchDir, MANIFEST);
            File temporary = new File(batchDir, "." + MANIFEST + ".part");
            try (FileOutputStream output = new FileOutputStream(temporary)) { output.write(bytes); output.flush(); output.getFD().sync(); }
            try { java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException | java.nio.file.FileAlreadyExistsException unsupported) { java.nio.file.Files.move(temporary.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            if (PrinterTransport.isSymbolicLink(target) || !target.isFile() || target.length() != bytes.length)
                throw new IOException("Batch result manifest write could not be verified");
        } catch (IOException error) { throw error; }
        catch (Exception error) { throw new IOException("Batch result manifest could not be written", error); }
    }
}
