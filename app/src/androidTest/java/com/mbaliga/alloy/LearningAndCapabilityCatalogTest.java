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
        boolean pla = false, petg = false, tpu = false, pva = false, supportPla = false, supportPetg = false, blocked = false, external = false, amsLite = false, regularAms = false;
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
            external |= route.id.equals("external-direct"); amsLite |= route.id.equals("ams-lite"); regularAms |= route.id.equals("regular-ams") && route.state.equals("Unsupported");
        }
        Assert.assertTrue(pla && petg && tpu && pva && blocked);
        Assert.assertTrue("A1 mini support-filament families need explicit non-qualification and AMS lite caveats",
                supportPla && supportPetg);
        Assert.assertTrue(external && amsLite && regularAms);
        Assert.assertTrue("A1 mini must disclose physical AMS lite spool dimensions", mini.spoolGuidance.toString().contains("40–68 mm"));
        Assert.assertTrue("A1 mini must disclose TPU/PVA AMS lite exclusions", mini.spoolGuidance.toString().contains("PVA"));
        boolean completeSpool = false, refill = false, thirdParty = false, regularAmsSpool = false;
        for (PrinterCapabilityCatalog.SpoolForm form : mini.spoolForms) {
            completeSpool |= form.id.equals("bambu-spooled") && form.compatibleRoutes.contains("AMS lite");
            refill |= form.id.equals("bambu-refill") && form.form.contains("Refill");
            thirdParty |= form.id.equals("third-party-direct") && form.state.contains("Manual review");
            regularAmsSpool |= form.id.equals("regular-ams-spool") && form.state.equals("Unsupported route");
            Assert.assertFalse("every spool form needs a source/scope record", form.source.isEmpty());
            Assert.assertTrue("every spool form needs an official source URL",
                    form.sourceUrl.startsWith("https://") && form.sourceUrl.contains("bambulab.com"));
        }
        Assert.assertTrue("A1 mini needs explicit complete-spool, refill, third-party and regular-AMS states",
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

    private static byte[] readAll(java.io.InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int count; (count = input.read(buffer)) >= 0;) out.write(buffer, 0, count);
        return out.toByteArray();
    }
}
