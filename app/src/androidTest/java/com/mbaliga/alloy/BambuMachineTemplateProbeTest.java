package com.mbaliga.alloy;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import ru.ytkab0bp.slicebeam.slic3r.Native;

/** Render pinned upstream templates offline. Never stage, share or send this output. */
@RunWith(AndroidJUnit4.class)
public final class BambuMachineTemplateProbeTest {
    private static final String ASSET = "profiles/a1-mini-machine-template-probe.json";
    private static final String SOURCE = "07b81cfdc9e3933ee7cd8539d32bcf937f9d99b4";
    private static final String[] KEYS = {
            "machine_start_gcode", "machine_end_gcode", "layer_change_gcode", "time_lapse_gcode"
    };

    @Test
    public void templateFixtureHasPinnedIdentityAndExactDigests() throws Exception {
        loadBundle();
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        try (InputStream ignored = app.getAssets().open(ASSET)) {
            Assert.fail("unqualified machine templates must not be shipped in application assets");
        } catch (java.io.FileNotFoundException expected) {
            // The instrumentation APK, not the user-facing APK, owns this fixture.
        }
    }

    @Test(timeout = 480_000)
    public void rendersPinnedSingleMaterialTemplatesWithoutQualifyingOutput() throws Exception {
        Assume.assumeTrue("native rendering requires the ARM64 engine", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject bundle = loadBundle();
        ProfileCatalog.Profile profile;
        try (InputStream input = test.getAssets().open("profiles/g3-bambu-a1mini-project-settings.json")) {
            profile = ProfileCatalog.importBambu(ProfileCatalog.loadDefault(app.getAssets()),
                    new byte[][]{ProfileCatalog.readProfileDocument(input)});
        }
        File directory = new File(app.getExternalFilesDir(null), "alloy-bambu-template-probe");
        if (!directory.exists()) Assert.assertTrue(directory.mkdirs());
        File manifest = new File(directory, "manifest.json");
        if (manifest.exists()) Assert.assertTrue("stale success manifest must be removed", manifest.delete());
        String[][] fixtures = {
                {"box-20mm.stl", "cube_20mm"}, {"overhang-support-fixture.stl", "overhang_support"},
                {"thin-wall-frame-fixture.stl", "thin_wall_frame"}, {"travel_obstacle.stl", "travel_obstacle"}
        };
        JSONArray results = new JSONArray();
        for (String[] fixture : fixtures) {
            Slicer.Config config = new Slicer.Config();
            profile.applyTo(config);
            MeshModel mesh;
            try (InputStream input = app.getAssets().open("models/" + fixture[0])) {
                mesh = MeshModel.read(fixture[0], input);
            }
            File model = new File(directory, fixture[1] + ".stl");
            File ini = new File(directory, fixture[1] + ".config.ini");
            File output = new File(directory, fixture[1] + ".gcode");
            if (output.exists()) Assert.assertTrue(output.delete());
            NativeSlicerEngine.writeAsciiStl(mesh, config, model);
            NativeSlicerEngine.writeConfig(config, ini);
            installTestTemplates(ini, bundle.getJSONObject("templates"));
            long modelPtr = 0L, resultPtr = 0L;
            try {
                modelPtr = Native.model_read_from_file(model.getAbsolutePath(), fixture[1], 0);
                Assert.assertNotEquals("native model handle", 0L, modelPtr);
                resultPtr = Native.model_slice(modelPtr, ini.getAbsolutePath(), output.getAbsolutePath(),
                        (progress, message) -> { }, 1, new int[]{0xFFFFFF}, 0, 0.0, 0.0, 0.0);
                Assert.assertNotEquals("native slice handle", 0L, resultPtr);
                byte[] bytes = Files.readAllBytes(output.toPath());
                Assert.assertTrue("rendered output must exist", bytes.length > 0);
                String gcode = new String(bytes, StandardCharsets.UTF_8);
                for (String raw : gcode.split("\\r?\\n")) {
                    String command = raw.split(";", 2)[0];
                    Assert.assertFalse("unresolved template command: " + command,
                            command.matches(".*[{}\\[\\]].*"));
                }
                Assert.assertTrue("layer notification must be rendered",
                        containsGcodeCommand(gcode, "M991 S0 P0"));
                Assert.assertTrue("relative-E compatibility reset must follow the imported layer hook",
                        hasFollowingGcodeCommand(gcode, "M991 S0 P0", "G92 E0"));
                Assert.assertTrue("time-lapse template must be rendered",
                        containsGcodeLine(gcode, "; SKIPPABLE_START"));
                Assert.assertTrue("machine-start template must be rendered",
                        containsGcodeLine(gcode, "M1002 gcode_claim_action : 2"));
                Assert.assertTrue("machine-end template must be rendered",
                        containsGcodeLine(gcode, ";=====printer finish  sound========="));
                Assert.assertFalse("unqualified upstream output must fail the production template policy",
                        A1MiniTemplatePolicy.inspect(gcode).isValid());
                Slicer.Result parsed = NativeSlicerEngine.parseGcode(output, config,
                        NativeSlicerEngine.ORCA_NATIVE_ENGINE_ID, false);
                Assert.assertFalse(parsed.engineVerified);
                Assert.assertFalse("rendered output must retain model layers", parsed.layers.isEmpty());
                results.put(new JSONObject().put("fixture", fixture[1]).put("file", output.getName())
                        .put("sha256", sha256(bytes)).put("rendered", true).put("qualified", false));
            } finally {
                if (resultPtr != 0L) Native.gcoderesult_release(resultPtr);
                if (modelPtr != 0L) Native.model_release(modelPtr);
                if (model.exists()) Assert.assertTrue(model.delete());
            }
        }
        JSONObject evidence = new JSONObject().put("schema_version", 1).put("source_commit", SOURCE)
                .put("scope", "template-rendering-only").put("qualified", false)
                .put("template_sha256", bundle.getJSONObject("sha256")).put("fixtures", results);
        Files.write(manifest.toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    @Test(timeout = 480_000)
    public void rendersPinnedMachineEndTemplateInIsolationWithoutQualifyingOutput() throws Exception {
        Assume.assumeTrue("native rendering requires the ARM64 engine", BuildConfig.NATIVE_ENGINE_ENABLED);
        Context app = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Context test = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject bundle = loadBundle();
        ProfileCatalog.Profile profile;
        try (InputStream input = test.getAssets().open("profiles/g3-bambu-a1mini-project-settings.json")) {
            profile = ProfileCatalog.importBambu(ProfileCatalog.loadDefault(app.getAssets()),
                    new byte[][]{ProfileCatalog.readProfileDocument(input)});
        }
        File root = new File(app.getExternalFilesDir(null), "alloy-bambu-template-probe");
        File directory = new File(root, "end-only");
        if (!directory.exists()) Assert.assertTrue(directory.mkdirs());
        File manifest = new File(root, "end-only-manifest.json");
        if (manifest.exists()) Assert.assertTrue("stale end-only manifest must be removed", manifest.delete());
        String[][] fixtures = {
                {"box-20mm.stl", "cube_20mm"}, {"overhang-support-fixture.stl", "overhang_support"},
                {"thin-wall-frame-fixture.stl", "thin_wall_frame"}, {"travel_obstacle.stl", "travel_obstacle"}
        };
        JSONArray results = new JSONArray();
        for (String[] fixture : fixtures) {
            Slicer.Config config = new Slicer.Config();
            profile.applyTo(config);
            MeshModel mesh;
            try (InputStream input = app.getAssets().open("models/" + fixture[0])) {
                mesh = MeshModel.read(fixture[0], input);
            }
            File model = new File(directory, fixture[1] + ".stl");
            File ini = new File(directory, fixture[1] + ".config.ini");
            File output = new File(directory, fixture[1] + ".gcode");
            if (output.exists()) Assert.assertTrue(output.delete());
            NativeSlicerEngine.writeAsciiStl(mesh, config, model);
            NativeSlicerEngine.writeConfig(config, ini);
            installTestEndTemplate(ini, bundle.getJSONObject("templates").getString("machine_end_gcode"));
            long modelPtr = 0L, resultPtr = 0L;
            try {
                modelPtr = Native.model_read_from_file(model.getAbsolutePath(), fixture[1], 0);
                Assert.assertNotEquals("native model handle", 0L, modelPtr);
                resultPtr = Native.model_slice(modelPtr, ini.getAbsolutePath(), output.getAbsolutePath(),
                        (progress, message) -> { }, 1, new int[]{0xFFFFFF}, 0, 0.0, 0.0, 0.0);
                Assert.assertNotEquals("native slice handle", 0L, resultPtr);
                byte[] bytes = Files.readAllBytes(output.toPath());
                Assert.assertTrue("rendered output must exist", bytes.length > 0);
                String gcode = new String(bytes, StandardCharsets.UTF_8);
                Assert.assertTrue("isolated machine-end template must be rendered",
                        containsGcodeLine(gcode, ";=====printer finish  sound========="));
                Assert.assertFalse("upstream machine-start template must not be installed",
                        containsGcodeLine(gcode, "M1002 gcode_claim_action : 2"));
                Assert.assertFalse("upstream layer-change template must not be installed",
                        containsGcodeCommand(gcode, "M991 S0 P0"));
                Assert.assertFalse("upstream time-lapse hook must not be installed",
                        containsGcodeLine(gcode, "; SKIPPABLE_START"));
                Assert.assertFalse("unqualified upstream output must fail the production template policy",
                        A1MiniTemplatePolicy.inspect(gcode).isValid());
                Slicer.Result parsed = NativeSlicerEngine.parseGcode(output, config,
                        NativeSlicerEngine.ORCA_NATIVE_ENGINE_ID, false);
                Assert.assertFalse(parsed.engineVerified);
                Assert.assertFalse("rendered output must retain model layers", parsed.layers.isEmpty());
                results.put(new JSONObject().put("fixture", fixture[1]).put("file", output.getName())
                        .put("sha256", sha256(bytes)).put("rendered", true).put("qualified", false));
            } finally {
                if (resultPtr != 0L) Native.gcoderesult_release(resultPtr);
                if (modelPtr != 0L) Native.model_release(modelPtr);
                if (model.exists()) Assert.assertTrue(model.delete());
            }
        }
        JSONObject evidence = new JSONObject().put("schema_version", 1).put("source_commit", SOURCE)
                .put("scope", "machine-end-template-only-render-diagnostic").put("qualified", false)
                .put("template_sha256", bundle.getJSONObject("sha256").getString("machine_end_gcode"))
                .put("fixtures", results);
        Files.write(manifest.toPath(), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject loadBundle() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        JSONObject bundle;
        try (InputStream input = context.getAssets().open(ASSET)) {
            bundle = new JSONObject(new String(ProfileCatalog.readProfileDocument(input), StandardCharsets.UTF_8));
        }
        Assert.assertEquals(1, bundle.getInt("schema_version"));
        Assert.assertEquals(SOURCE, bundle.getString("source_commit"));
        Assert.assertEquals("Bambu Lab A1 mini 0.4 nozzle", bundle.getString("profile_name"));
        JSONObject templates = bundle.getJSONObject("templates");
        Assert.assertEquals(KEYS.length, templates.length());
        for (String key : KEYS) {
            String value = templates.getString(key);
            Assert.assertFalse(key + " must not be empty", value.trim().isEmpty());
            Assert.assertEquals(key + " digest", bundle.getJSONObject("sha256").getString(key),
                    sha256(value.getBytes(StandardCharsets.UTF_8)));
        }
        return bundle;
    }

    private static void installTestTemplates(File ini, JSONObject templates) throws Exception {
        String original = new String(Files.readAllBytes(ini.toPath()), StandardCharsets.UTF_8);
        StringBuilder patched = new StringBuilder();
        Set<String> replaced = new HashSet<>();
        for (String line : original.split("\\r?\\n")) {
            String key = line.split(" = ", 2)[0];
            if (templates.has(key)) {
                Assert.assertTrue("duplicate native template key", replaced.add(key));
                String value = templates.getString(key);
                // The pinned Bambu layer hook reports progress but does not
                // reset E. Alloy's native recipe uses relative extrusion and
                // its normal config supplies that reset. This probe replaces
                // the normal hook with the upstream template to inspect its
                // rendering, so add a test-only compatibility shim after it.
                // The upstream digest check above remains on untouched bytes;
                // this keeps the diagnostic render from failing preflight
                // before a file is emitted without qualifying that template.
                if ("layer_change_gcode".equals(key)
                        && !containsGcodeLine(value, "G92 E0")) {
                    value = value + (value.endsWith("\n") ? "" : "\n") + "G92 E0\n";
                }
                patched.append(key).append(" = ").append(value.replace("\n", "\\n")).append('\n');
            } else patched.append(line).append('\n');
        }
        for (String key : KEYS) {
            if (!replaced.contains(key)) {
                Assert.assertEquals("only timelapse is absent from the baseline", "time_lapse_gcode", key);
                patched.append(key).append(" = ").append(templates.getString(key).replace("\n", "\\n")).append('\n');
            }
        }
        Files.write(ini.toPath(), patched.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void installTestEndTemplate(File ini, String template) throws Exception {
        String original = new String(Files.readAllBytes(ini.toPath()), StandardCharsets.UTF_8);
        StringBuilder patched = new StringBuilder();
        int replacements = 0;
        for (String line : original.split("\\r?\\n")) {
            if (line.startsWith("machine_end_gcode = ")) {
                replacements++;
                patched.append("machine_end_gcode = ").append(template.replace("\n", "\\n")).append('\n');
            } else patched.append(line).append('\n');
        }
        Assert.assertEquals("baseline config must contain one machine end hook", 1, replacements);
        Files.write(ini.toPath(), patched.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
            hex.append(String.format(Locale.US, "%02x", value & 255));
        return hex.toString();
    }

    private static boolean containsGcodeLine(String gcode, String expected) {
        for (String line : gcode.split("\\r?\\n"))
            if (line.trim().equals(expected)) return true;
        return false;
    }

    private static boolean containsGcodeCommand(String gcode, String expected) {
        for (String line : gcode.split("\\r?\\n"))
            if (commandBeforeComment(line).equals(expected)) return true;
        return false;
    }

    private static boolean hasFollowingGcodeCommand(String gcode, String command, String following) {
        String[] lines = gcode.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            if (!commandBeforeComment(lines[i]).equals(command)) continue;
            for (int next = i + 1; next < lines.length; next++) {
                String nextCommand = commandBeforeComment(lines[next]);
                if (!nextCommand.isEmpty()) return nextCommand.equals(following);
            }
        }
        return false;
    }

    private static String commandBeforeComment(String line) {
        return line.split(";", 2)[0].trim();
    }
}
