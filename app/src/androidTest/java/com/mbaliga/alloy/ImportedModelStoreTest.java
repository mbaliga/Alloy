package com.mbaliga.alloy;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/** Persistence and cache-boundary coverage for the Model Atlas recent shelf. */
public final class ImportedModelStoreTest {
    @Test public void remembersOnlyValidatedCacheFilesAndReopensNewestFirst() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "model-library-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        SharedPreferences preferences = context.getSharedPreferences("model-library-test-" + System.nanoTime(), Context.MODE_PRIVATE);
        try {
            ModelStore.Materialized older = ModelStore.materializeGenerated(root,
                    "solid older\nendsolid older\n".getBytes(StandardCharsets.US_ASCII));
            ModelStore.Materialized newer = ModelStore.materializeGenerated(root,
                    "solid newer\nendsolid newer\n".getBytes(StandardCharsets.US_ASCII));
            ImportedModelStore store = new ImportedModelStore(preferences);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(older.uri);
            uris.add(newer.uri);
            ArrayList<String> names = new ArrayList<>();
            names.add("older-part.stl");
            names.add("Redmagic chassis.step");
            store.remember(root, uris, names);

            ArrayList<ImportedModelStore.Entry> entries = store.entries(root);
            Assert.assertEquals(2, entries.size());
            Assert.assertEquals("Redmagic chassis.step", entries.get(0).name);
            Assert.assertEquals(newer.uri, entries.get(0).uri);

            ImportedModelStore restored = new ImportedModelStore(preferences);
            Assert.assertEquals(2, restored.entries(root).size());
            Assert.assertEquals("older-part.stl", restored.entries(root).get(1).name);

            Assert.assertTrue(newer.file.delete());
            Assert.assertEquals(1, restored.entries(root).size());
            Assert.assertEquals("older-part.stl", restored.entries(root).get(0).name);
        } finally {
            deleteTree(root);
        }
    }

    @Test public void rejectsProviderAndOutsideUris() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "model-library-invalid-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        SharedPreferences preferences = context.getSharedPreferences("model-library-invalid-test-" + System.nanoTime(), Context.MODE_PRIVATE);
        try {
            ImportedModelStore store = new ImportedModelStore(preferences);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(Uri.parse("content://provider/model.stl"));
            uris.add(Uri.fromFile(new File(root, "outside.stl")));
            ArrayList<String> names = new ArrayList<>();
            names.add("provider.stl");
            names.add("outside.stl");
            store.remember(root, uris, names);
            Assert.assertTrue(store.entries(root).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test public void removesAChangedCacheFileInsteadOfPresentingItAsRecent() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "model-library-corrupt-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        SharedPreferences preferences = context.getSharedPreferences("model-library-corrupt-test-" + System.nanoTime(), Context.MODE_PRIVATE);
        try {
            ModelStore.Materialized materialized = ModelStore.materializeGenerated(root,
                    "solid stable\nendsolid stable\n".getBytes(StandardCharsets.US_ASCII));
            ImportedModelStore store = new ImportedModelStore(preferences);
            store.remember(root, new ArrayList<>(java.util.Collections.singletonList(materialized.uri)),
                    new ArrayList<>(java.util.Collections.singletonList("changed.stl")));
            Assert.assertEquals(1, store.entries(root).size());
            try (FileOutputStream output = new FileOutputStream(materialized.file, true)) {
                output.write('x');
            }
            Assert.assertTrue("cache identity mismatch must evict the recent entry",
                    store.entries(root).isEmpty());
        } finally {
            deleteTree(root);
        }
    }

    @Test public void recentShelfEntriesAreProtectedFromModelCachePruning() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "model-library-prune-" + System.nanoTime());
        Assert.assertTrue(root.mkdirs());
        SharedPreferences preferences = context.getSharedPreferences("model-library-prune-test-" + System.nanoTime(), Context.MODE_PRIVATE);
        try {
            ModelStore.Materialized recent = ModelStore.materializeGenerated(root,
                    "solid recent\nendsolid recent\n".getBytes(StandardCharsets.US_ASCII));
            Assert.assertTrue(recent.file.setLastModified(1L));
            ImportedModelStore store = new ImportedModelStore(preferences);
            store.remember(root, new ArrayList<>(java.util.Collections.singletonList(recent.uri)),
                    new ArrayList<>(java.util.Collections.singletonList("recent.stl")));

            for (int index = 0; index < 32; index++) {
                ModelStore.materializeGenerated(root,
                        ("solid filler-" + index + "\nendsolid filler-" + index + "\n")
                                .getBytes(StandardCharsets.US_ASCII));
            }
            ArrayList<Uri> recentUris = new ArrayList<>();
            for (ImportedModelStore.Entry entry : store.entries(root)) recentUris.add(entry.uri);
            ModelStore.prune(root, new ArrayList<>(), null, recentUris);
            Assert.assertTrue("a validated Recent shelf source must survive pruning", recent.file.isFile());
            Assert.assertEquals(1, store.entries(root).size());
        } finally {
            deleteTree(root);
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
