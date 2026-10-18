package com.mbaliga.alloy;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

/** Contract tests: safety copy and Bambu capability must not be smuggled into view code. */
@RunWith(AndroidJUnit4.class)
public final class LearningAndCapabilityCatalogTest {
    @Test public void learningCatalogHasOfflineSafetyAndSearchableSymptoms() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LearningCatalog catalog = LearningCatalog.load(context.getAssets());
        Assert.assertEquals(1, catalog.version);
        Assert.assertEquals("first-run learning needs seven short, distinct routes", 7,
                catalog.ofKind("start").size());
        Assert.assertEquals("the first-release field guide ships its complete twelve concepts", 12,
                catalog.ofKind("cheatsheet").size());
        Assert.assertTrue("v1 needs the reviewed 24-card troubleshooting floor, not a token FAQ",
                catalog.ofKind("troubleshoot").size() >= 24);
        LearningCatalog.Article hardStop = null;
        LearningCatalog.Article recoveryVisual = null;
        int hardStops = 0;
        for (LearningCatalog.Article article : catalog.ofKind("troubleshoot")) {
            Assert.assertFalse("every article must retain scope", article.scope.isEmpty());
            Assert.assertFalse("every article must retain source", article.source.isEmpty());
            if ("universal-hard-stop".equals(article.id)) hardStop = article;
            if ("connection-not-confirmed".equals(article.id)) recoveryVisual = article;
            if ("Hard stop".equals(article.safety)) hardStops++;
        }
        Assert.assertNotNull("hard-stop card is mandatory", hardStop);
        Assert.assertEquals("Hard stop", hardStop.safety);
        Assert.assertTrue("five distinct stop paths are required for a novice-safe release", hardStops >= 5);
        Assert.assertTrue("symptom search must find plain language", catalog.ofKind("troubleshoot").get(0).matches("sticking"));
        Assert.assertNotNull("connection/recovery teaching needs an original visual", recoveryVisual);
        Assert.assertEquals("learn/printer-phone-recovery-v1.png", recoveryVisual.artwork);
        try (java.io.InputStream artwork = context.getAssets().open(recoveryVisual.artwork)) {
            Assert.assertTrue("original recovery visual must be non-empty", artwork.read() >= 0);
        }
    }

    @Test public void learningCatalogRejectsBrokenOfflineContentBeforeItReachesTheUi() throws Exception {
        String duplicate = "{\"schema_version\":1,\"scope\":\"offline\","
                + "\"articles\":[" + article("start-safe", "start", "")
                + "," + article("start-safe", "start", "") + ","
                + article("sheet-safe", "cheatsheet", "") + ","
                + article("trouble-safe", "troubleshoot", "") + ","
                + article("trouble-next", "troubleshoot", "") + "]}";
        assertParseFails(duplicate, "duplicate article identifiers");

        String pathTraversal = "{\"schema_version\":1,\"scope\":\"offline\","
                + "\"articles\":[" + article("start-safe", "start", "../secret.png") + ","
                + article("sheet-safe", "cheatsheet", "") + ","
                + article("trouble-safe", "troubleshoot", "") + ","
                + article("trouble-next", "troubleshoot", "") + ","
                + article("trouble-last", "troubleshoot", "") + "]}";
        assertParseFails(pathTraversal, "non-package artwork paths");

        String reducedSafetyBundle = "{\"schema_version\":1,\"scope\":\"offline\","
                + "\"articles\":[" + article("start-safe", "start", "") + ","
                + article("sheet-safe", "cheatsheet", "") + ","
                + article("trouble-safe", "troubleshoot", "") + ","
                + article("trouble-next", "troubleshoot", "") + ","
                + article("trouble-last", "troubleshoot", "") + "]}";
        assertParseFails(reducedSafetyBundle, "a catalogue below the v1 safety coverage floor");
    }

    @Test public void troubleshootingSearchStartsWithHardStopsAndPrioritizesThemLive() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        LearningCatalog catalog = LearningCatalog.load(context.getAssets());
        java.util.List<LearningCatalog.Article> initial = catalog.searchTroubleshooting("");
        Assert.assertTrue("empty search should show safety guidance, not all 26 cards", initial.size() >= 5);
        for (LearningCatalog.Article article : initial) {
            Assert.assertEquals("initial results are restricted to stop-now guidance", "Hard stop", article.safety);
        }

        java.util.List<LearningCatalog.Article> matches = catalog.searchTroubleshooting("smoke");
        Assert.assertFalse("common urgent symptom should return offline guidance", matches.isEmpty());
        boolean routineAdviceSeen = false;
        for (LearningCatalog.Article article : matches) {
            Assert.assertTrue("all visible results must match the typed query", article.matches("smoke"));
            if (!"Hard stop".equals(article.safety)) routineAdviceSeen = true;
            else Assert.assertFalse("hard-stop guidance must appear before routine advice", routineAdviceSeen);
        }
    }

    private static String article(String id, String kind, String artwork) {
        return "{\"id\":\"" + id + "\",\"kind\":\"" + kind + "\",\"title\":\"Safe title\","
                + "\"summary\":\"Safe summary.\",\"safety\":\"Review\",\"scope\":\"Offline only.\","
                + "\"source\":\"Test source\",\"artwork\":\"" + artwork + "\","
                + "\"keywords\":[\"safe\"],\"steps\":[\"Review safely.\"]}";
    }

    private static void assertParseFails(String value, String message) throws Exception {
        try {
            LearningCatalog.parse(value.getBytes(StandardCharsets.UTF_8));
            Assert.fail("Expected catalog parser to reject " + message);
        } catch (java.io.IOException expected) {
            Assert.assertTrue("Failure should identify an invalid catalog", expected.getMessage().contains("Invalid learning catalog"));
        }
    }

    @Test public void nativeLearningFallbacksDrawAndExposeTextAlternatives() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String[] representativeIds = {
                "concept-model", "materials-basics", "concept-supports",
                "layers-and-toolpath", "concept-orientation", "universal-hard-stop",
                "connection-not-confirmed"
        };
        Bitmap surface = Bitmap.createBitmap(600, 280, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(surface);
        for (String articleId : representativeIds) {
            LearningIllustrationView view = new LearningIllustrationView(context, articleId,
                    "Diagram for " + articleId);
            view.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, 600, 280);
            view.draw(canvas);
            Assert.assertEquals("Diagram for " + articleId, view.getContentDescription());
        }
        Assert.assertTrue("native illustrations should produce visible pixels",
                surface.getPixel(300, 140) != 0);
        surface.recycle();
    }

    @Test public void initialCapabilityCatalogIsExactlyThreeModelsAndNeverQualifiesDirectSend() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PrinterCapabilityCatalog catalog = PrinterCapabilityCatalog.load(context.getAssets());
        Assert.assertEquals(1, catalog.version);
        Assert.assertEquals(3, catalog.printers.size());
        PrinterCapabilityCatalog.Printer mini = catalog.byId("a1-mini");
        Assert.assertNotNull(mini);
        Assert.assertNotNull(catalog.byId("a1"));
        Assert.assertNotNull(catalog.byId("p1s"));
        for (PrinterCapabilityCatalog.Printer printer : catalog.printers) {
            Assert.assertFalse("every initial printer needs a spool-form decision surface", printer.spoolForms.isEmpty());
            Assert.assertTrue("every capability claim needs a direct official HTTPS source",
                    printer.sourceUrl.startsWith("https://") && printer.sourceUrl.contains("bambulab.com"));
            for (PrinterCapabilityCatalog.SpoolForm form : printer.spoolForms) {
                Assert.assertFalse(form.id.isEmpty());
                Assert.assertFalse(form.compatibleRoutes.isEmpty());
                Assert.assertFalse(form.geometry.isEmpty());
            }
        }
        Assert.assertTrue(mini.directSendState.toLowerCase().contains("not qualified"));
        boolean pla = false, petg = false, tpu = false, pva = false, supportPla = false, supportPetg = false, blocked = false, external = false, amsLite = false, regularAms = false, ams2Pro = false, amsHt = false;
        for (PrinterCapabilityCatalog.Material material : mini.materials) {
            pla |= material.name.equals("PLA"); petg |= material.name.equals("PETG");
            tpu |= material.name.equals("TPU"); pva |= material.name.equals("PVA");
            supportPla |= material.id.equals("support-pla") && material.name.equals("Support for PLA")
                    && material.directSendState.equals("Not qualified")
                    && material.note.contains("does not imply AMS lite compatibility");
            supportPetg |= material.id.equals("support-petg") && material.name.equals("Support for PETG")
                    && material.directSendState.equals("Not qualified")
                    && material.note.contains("does not imply AMS lite compatibility");
            blocked |= material.directSendState.equals("Blocked");
            Assert.assertFalse("every material needs its own stable id", material.id.isEmpty());
            Assert.assertTrue("every material claim needs an official source URL",
                    material.sourceUrl.startsWith("https://") && material.sourceUrl.contains("bambulab.com"));
        }
        for (PrinterCapabilityCatalog.FeedRoute route : mini.feedRoutes) {
            external |= route.id.equals("external-direct");
            amsLite |= route.id.equals("ams-lite");
            regularAms |= route.id.equals("regular-ams") && route.state.contains("A1 Series AMS Hub");
            ams2Pro |= route.id.equals("ams-2-pro") && route.state.contains("A1 Series AMS Hub");
            amsHt |= route.id.equals("ams-ht") && route.state.contains("A1 Series AMS Hub");
            if (route.id.equals("regular-ams") || route.id.equals("ams-2-pro") || route.id.equals("ams-ht")) {
                Assert.assertTrue("AMS family routes require official source links", route.sourceUrl.startsWith("https://forum.bambulab.com/"));
                Assert.assertTrue("AMS family routes must separate AMS Lite and preserve firmware requirements",
                        route.note.contains("01.06.10.33") && route.note.contains("Do not mix AMS Lite")
                                && route.note.contains("Alloy send remains unqualified"));
            }
        }
        Assert.assertTrue(pla && petg && tpu && pva && blocked);
        Assert.assertTrue("A1 mini support-filament families need explicit non-qualification and AMS lite caveats",
                supportPla && supportPetg);
        Assert.assertTrue("A1 mini must disclose external feed, AMS Lite and all hub-enabled AMS families",
                external && amsLite && regularAms && ams2Pro && amsHt);
        Assert.assertTrue("A1 mini must disclose physical AMS lite spool dimensions", mini.spoolGuidance.toString().contains("40–68 mm"));
        Assert.assertTrue("A1 mini must disclose TPU/PVA AMS lite exclusions", mini.spoolGuidance.toString().contains("PVA"));
        boolean completeSpool = false, refill = false, thirdParty = false, regularAmsSpool = false;
        for (PrinterCapabilityCatalog.SpoolForm form : mini.spoolForms) {
            completeSpool |= form.id.equals("bambu-spooled") && form.compatibleRoutes.contains("AMS lite");
            refill |= form.id.equals("bambu-refill") && form.form.contains("Refill");
            thirdParty |= form.id.equals("third-party-direct") && form.state.contains("Manual review");
            regularAmsSpool |= form.id.equals("regular-ams-spool") && form.state.equals("Manual review required")
                    && form.compatibleRoutes.contains("AMS 2 Pro") && form.compatibleRoutes.contains("AMS HT");
            Assert.assertFalse("every spool form needs a source/scope record", form.source.isEmpty());
            Assert.assertTrue("every spool form needs an official source URL",
                    form.sourceUrl.startsWith("https://") && form.sourceUrl.contains("bambulab.com"));
        }
        Assert.assertTrue("A1 mini needs explicit complete-spool, refill, third-party and hub-enabled AMS spool states",
                completeSpool && refill && thirdParty && regularAmsSpool);
    }

    @Test public void capabilityCatalogRejectsAccidentalDirectSendPromotionAtRuntime() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        byte[] source;
        try (java.io.InputStream input = context.getAssets().open("capabilities/bambu-initial-v1.json")) {
            source = readAll(input);
        }
        String promoted = new String(source, StandardCharsets.UTF_8).replaceFirst(
                "Not qualified — physical acceptance gates remain incomplete", "Qualified for send");
        try {
            PrinterCapabilityCatalog.parse(promoted.getBytes(StandardCharsets.UTF_8));
            Assert.fail("A packaged catalog must not be able to promote direct send");
        } catch (java.io.IOException expected) {
            Assert.assertTrue(expected.getMessage().contains("not qualified"));
        }
    }

    @Test public void initialPlanningProfilesMatchTheThreeDisclosedPrinterEnvelopes() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        java.util.List<ProfileCatalog.Profile> profiles = ProfileCatalog.loadInitial(context.getAssets());
        Assert.assertEquals(3, profiles.size());
        Assert.assertEquals("bambu.a1-mini", profiles.get(0).printerId);
        Assert.assertEquals(180d, profiles.get(0).bedX, 0d);
        Assert.assertEquals(180d, profiles.get(0).buildZ, 0d);
        Assert.assertEquals("bambu.a1", profiles.get(1).printerId);
        Assert.assertEquals(256d, profiles.get(1).bedX, 0d);
        Assert.assertEquals(256d, profiles.get(1).buildZ, 0d);
        Assert.assertEquals("bambu.p1s", profiles.get(2).printerId);
        Assert.assertEquals(256d, profiles.get(2).bedY, 0d);
        // The advertised P1S envelope is 256 mm cubed, but the exact
        // source-pinned Bambu 0.4 mm planning profile intentionally keeps a
        // 250 mm Z limit. Planning must prefer the bounded profile value.
        Assert.assertEquals(250d, profiles.get(2).buildZ, 0d);
        for (ProfileCatalog.Profile profile : profiles) {
            Assert.assertFalse("planning profiles must not qualify a physical send", profile.verified);
            Assert.assertEquals("PLA", profile.material);
            Assert.assertEquals(0.4d, profile.nozzle, 0.0001d);
        }
    }

    @Test public void materialReviewResolvesOnlyTheThreeKnownProfileModelIdentities() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PrinterCapabilityCatalog catalog = PrinterCapabilityCatalog.load(context.getAssets());
        java.util.List<ProfileCatalog.Profile> bundled = ProfileCatalog.loadInitial(context.getAssets());
        Assert.assertEquals("a1-mini", catalog.forProfile(bundled.get(0)).id);
        Assert.assertEquals("a1", catalog.forProfile(bundled.get(1)).id);
        Assert.assertEquals("p1s", catalog.forProfile(bundled.get(2)).id);

        for (String[] model : new String[][]{
                {"Bambu Lab A1 mini", "a1-mini"}, {"Bambu Lab A1 0.4 nozzle", "a1"},
                {"Bambu Lab P1S", "p1s"}, {"Workshop Printer", null},
                {"Bambu Lab A1 mini variant", null}}) {
            ProfileCatalog.Profile imported = importModel(context, model[0]);
            PrinterCapabilityCatalog.Printer capability = catalog.forProfile(imported);
            if (model[1] == null) Assert.assertNull("unknown model must remain unmapped: " + model[0], capability);
            else Assert.assertEquals(model[1], capability.id);
            Assert.assertFalse("capability lookup must not verify an imported profile", imported.verified);
        }
        Assert.assertTrue("the A1 Mini compatibility panel must not unlock Alloy send",
                catalog.forProfile(importModel(context, "Bambu Lab A1 mini")).directSendState.startsWith("Not qualified"));
    }

    private static ProfileCatalog.Profile importModel(Context context, String model) throws Exception {
        ProfileCatalog.Profile baseline = ProfileCatalog.loadDefault(context.getAssets());
        String project = "{\"name\":\"project_settings\",\"printer_model\":\"" + model
                + "\",\"default_filament_profile\":\"PLA Basic\",\"filament_type\":\"PLA\"}";
        return ProfileCatalog.importBambu(baseline,
                new byte[][]{project.getBytes(StandardCharsets.UTF_8)});
    }

    @Test public void a1AndP1sExposeIndividualSourcedMaterialClassesWithoutSendQualification() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        PrinterCapabilityCatalog catalog = PrinterCapabilityCatalog.load(context.getAssets());
        PrinterCapabilityCatalog.Printer a1 = catalog.byId("a1");
        PrinterCapabilityCatalog.Printer p1s = catalog.byId("p1s");
        Assert.assertNotNull(a1);
        Assert.assertNotNull(p1s);
        Assert.assertEquals(10, a1.materials.size());
        Assert.assertEquals(10, p1s.materials.size());
        for (PrinterCapabilityCatalog.Printer printer : new PrinterCapabilityCatalog.Printer[]{a1, p1s}) {
            Assert.assertTrue("printer must stay not qualified", printer.directSendState.startsWith("Not qualified"));
            for (PrinterCapabilityCatalog.Material material : printer.materials) {
                Assert.assertTrue("material must stay unavailable for direct send",
                        material.directSendState.startsWith("Not qualified") || material.directSendState.startsWith("Blocked"));
                Assert.assertTrue("each material class must carry a direct official source",
                        material.sourceUrl.startsWith("https://") && material.sourceUrl.contains("bambulab.com"));
            }
        }
        boolean p1sAms = false, p1sAms2Pro = false, p1sAmsHt = false;
        for (PrinterCapabilityCatalog.FeedRoute route : p1s.feedRoutes) {
            if (route.id.equals("ams") || route.id.equals("ams-2-pro") || route.id.equals("ams-ht")) {
                Assert.assertTrue("P1S AMS family routes must link to official guidance",
                        route.sourceUrl.startsWith("https://forum.bambulab.com/"));
                Assert.assertTrue(route.note.contains("P1 Series"));
            }
            p1sAms |= route.id.equals("ams");
            p1sAms2Pro |= route.id.equals("ams-2-pro") && route.note.contains("before connecting")
                    && route.note.contains("four AMS units");
            p1sAmsHt |= route.id.equals("ams-ht") && route.note.contains("before connecting")
                    && route.note.contains("four AMS units");
        }
        Assert.assertTrue("P1S must disclose supported AMS types separately", p1sAms && p1sAms2Pro && p1sAmsHt);
        for (PrinterCapabilityCatalog.Material material : a1.materials) {
            Assert.assertFalse("A1 AMS Lite route status must be individually sourced", material.amsLiteStatus.isEmpty());
            Assert.assertTrue(material.amsLiteSourceUrl.startsWith("https://")
                    && material.amsLiteSourceUrl.contains("bambulab.com"));
        }
        boolean originalAms = false, ams2Pro = false, amsHt = false, hubSpool = false;
        for (PrinterCapabilityCatalog.FeedRoute route : a1.feedRoutes) {
            if (route.id.equals("regular-ams") || route.id.equals("ams-2-pro") || route.id.equals("ams-ht")) {
                Assert.assertEquals("Requires A1 Series AMS Hub", route.state);
                Assert.assertTrue(route.sourceUrl.startsWith("https://forum.bambulab.com/"));
                Assert.assertTrue(route.note.contains("01.06.10.33") && route.note.contains("Do not mix AMS Lite")
                        && route.note.contains("Alloy send remains unqualified"));
            }
            originalAms |= route.id.equals("regular-ams"); ams2Pro |= route.id.equals("ams-2-pro"); amsHt |= route.id.equals("ams-ht");
        }
        for (PrinterCapabilityCatalog.SpoolForm form : a1.spoolForms)
            hubSpool |= form.id.equals("ams-hub-spool") && form.state.equals("Manual review required");
        Assert.assertTrue("A1 must disclose every A1-series AMS with conservative spool-fit review",
                originalAms && ams2Pro && amsHt && hubSpool);
        Assert.assertEquals("Ideal", materialById(a1, "pla").bambuStatus);
        Assert.assertEquals("Not recommended", materialById(a1, "asa").bambuStatus);
        Assert.assertTrue(materialById(a1, "tpu").amsLiteStatus.startsWith("Unsupported"));
        Assert.assertTrue("dry PVA exception must be shown for A1 AMS Lite",
                materialById(a1, "pva").amsLiteStatus.toLowerCase(java.util.Locale.US).contains("dry pva only"));
        Assert.assertEquals("Ideal", materialById(p1s, "abs").bambuStatus);
        Assert.assertEquals("Capable", materialById(p1s, "pa").bambuStatus);
        Assert.assertEquals("Not recommended", materialById(p1s, "cf-gf-filled").bambuStatus);
        Assert.assertTrue("P1S fiber-filled caveat must require the stated extruder and hotend upgrades",
                materialById(p1s, "cf-gf-filled").note.contains("extruder and hotend upgrade"));
        Assert.assertEquals("Blocked", materialById(p1s, "cf-gf-filled").alloyDirectSend);
    }

    private static PrinterCapabilityCatalog.Material materialById(
            PrinterCapabilityCatalog.Printer printer, String id) {
        for (PrinterCapabilityCatalog.Material material : printer.materials)
            if (id.equals(material.id)) return material;
        return null;
    }

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) >= 0;) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
