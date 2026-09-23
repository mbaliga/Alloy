package com.mbaliga.alloy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/** Alloy v1 task-flow shell: Import → Prepare → Slice → Inspect → Export. */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN = 41;
    private static final int REQUEST_EXPORT = 42;
    private static final int REQUEST_PROJECT_EXPORT = 43;
    private static final int REQUEST_PROJECT_OPEN = 44;
    private static final int REQUEST_BATCH_EXPORT = 45;
    private static final int REQUEST_NOTIFICATIONS = 46;
    private static final int REQUEST_PROFILE = 47;
    private static final int REQUEST_PROFILE_EXPORT = 48;
    private static final int REQUEST_VIEW_EXPORT = 49;
    private static final int REQUEST_VISUALIZATION_EXPORT = 50;
    private static final String IMPORTED_PROFILE_FILE = "profiles/imported-bambu.json";
    private static final int HISTORY_RESET = 0;
    private static final int HISTORY_RESTORE = 1;
    private static final int HISTORY_MUTATION = 2;
    private static final String NOTIFICATION_PERMISSION_PROMPTED = "notification_permission_prompted";
    private static final String IMMERSIVE_INTRO_SHOWN = "immersive_intro_shown";
    // Keep a versioned marker so an install that already dismissed the early
    // prototype intro still receives the first polished supplied-model Hero
    // view once after this visual-review milestone.
    private static final String IMMERSIVE_INTRO_VERSION = "immersive_intro_version";
    private static final int CURRENT_IMMERSIVE_INTRO_VERSION = 2;
    private static final int BG = Color.rgb(246, 245, 241);
    private static final int SURFACE = Color.WHITE;
    private static final int PANEL = Color.rgb(237, 234, 227);
    private static final int TEXT = Color.rgb(30, 29, 27);
    private static final int MUTED = Color.rgb(112, 108, 101);
    private static final int INK = Color.rgb(66, 61, 54);
    private static final int GOLD = Color.rgb(146, 103, 48);
    private static final int GREEN = Color.rgb(43, 125, 92);
    private static final int AMBER = Color.rgb(184, 121, 31);
    private static final int RED = Color.rgb(181, 69, 54);
    private static final String[] STUDIO_FINISHES = {
            "Natural PLA", "Matte black", "Arctic white", "Safety orange", "Metallic"
    };

    private LinearLayout root, actions, layerInspector;
    private SeekBar layerSeek;
    private TextView layerInspectorLabel;
    private TextView status, details, modelMeta, inventorySummary, inventoryStatusDot;
    private TextView qualityMarkerValue, supportsMarkerValue, plateMarkerValue;
    private ViewportView viewport;
    private MeshModel model, sourceModel, unmodifiedSourceModel;
    private final ArrayList<MeshModel.PartTransform> partTransforms = new ArrayList<>();
    private final ArrayList<Uri> modelUris = new ArrayList<>();
    private final ArrayList<String> modelNames = new ArrayList<>();
    private float modelScale = 1f, modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees;
    private boolean geometryRepairEnabled;
    private boolean repairingGeometry;
    private Slicer.Result slice;
    private boolean slicing;
    private boolean batchSlicing;
    private BatchSliceJobController.BatchResult lastBatch;
    private final Slicer.Config config = new Slicer.Config();
    private InventoryStore inventoryStore;
    private ImportedModelStore importedModelStore;
    private ProjectStore projectStore;
    private ProjectHistoryStore projectHistoryStore;
    private ModelHistoryStore modelHistoryStore;
    private PlateStore plateStore;
    private int activePlateIndex;
    private PrinterJobStore printerJobStore;
    private PrinterCredentialStore credentialStore;
    private VisualizationCredentialStore visualizationCredentialStore;
    private BambuLanTransport printerTransport;
    private BambuPrinterDiscovery.Scan activeDiscovery;
    private PrinterTransport.Artifact stagedArtifact;
    /**
     * A recovered artifact belongs to the interrupted printer transaction,
     * not to whichever model the user loads next. Keep it separate from the
     * current slice so model import/plate switching cannot accidentally erase
     * the evidence needed for explicit recovery review.
     */
    private PrinterTransport.Artifact recoveredArtifact;
    private PrinterTransport.PrinterTarget activePrinterTarget;
    private String activePrinterJobId;
    private boolean printActive;
    private boolean printerBusy;
    private BroadcastReceiver printerEventReceiver;
    private BroadcastReceiver sliceEventReceiver;
    private BroadcastReceiver batchSliceEventReceiver;
    private SliceJobStore sliceJobStore;
    private String activeSliceJobId;
    private BatchSliceJobStore batchSliceJobStore;
    private String activeBatchSliceJobId;
    private boolean batchRestoring;
    private boolean importing;
    private boolean projectTransferring;
    private boolean batchTransferring;
    private boolean profileImporting;
    private boolean visualizing;
    private boolean openingPrivateA1Study;
    private boolean modeling;
    private boolean plateImportInFlight;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService importExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "alloy-import");
        thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    });
    private final AtomicLong importIds = new AtomicLong();
    private Future<?> activeImport;
    private long activeImportId;
    /** Prevent a stalled document provider from trapping a phone-only session. */
    private Runnable importTimeoutRunnable;
    private final AtomicLong geometryRepairIds = new AtomicLong();
    private Future<?> activeGeometryRepair;
    private long activeGeometryRepairId;
    private final AtomicLong projectTransferIds = new AtomicLong();
    private Future<?> activeProjectTransfer;
    private long activeProjectTransferId;
    private final AtomicLong batchTransferIds = new AtomicLong();
    private Future<?> activeBatchTransfer;
    private long activeBatchTransferId;
    private Future<?> activeVisualization;
    private final AtomicLong visualizationIds = new AtomicLong();
    private long activeVisualizationId;
    private byte[] pendingVisualizationPng;
    private Future<?> activeCertificateInspection;
    private final AtomicLong certificateInspectionIds = new AtomicLong();
    private long activeCertificateInspectionId;
    private Future<?> activeModeling;
    private final AtomicLong modelingIds = new AtomicLong();
    private long activeModelingId;
    private Future<?> activeBatchSnapshot;
    private Runnable pendingNotificationAction;
    private SliceJobController sliceJobs;
    private BatchSliceJobController batchSliceJobs;
    private ProfileCatalog.Profile profile;
    private Future<?> activeProfileImport;
    private final AtomicLong profileImportIds = new AtomicLong();
    private long activeProfileImportId;
    private Runnable profileImportTimeoutRunnable;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(BG);
        window.setNavigationBarColor(BG);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        inventoryStore = new InventoryStore(getSharedPreferences("alloy_inventory", MODE_PRIVATE));
        new InventoryReconciliationStore(getSharedPreferences("alloy_inventory_reconciliation", MODE_PRIVATE))
                .drain(inventoryStore);
        importedModelStore = new ImportedModelStore(getSharedPreferences("alloy_model_library", MODE_PRIVATE));
        projectStore = new ProjectStore(getPreferences(MODE_PRIVATE));
        projectHistoryStore = new ProjectHistoryStore(getSharedPreferences("alloy_project_history", MODE_PRIVATE));
        plateStore = new PlateStore(getSharedPreferences("alloy_plates", MODE_PRIVATE));
        modelHistoryStore = new ModelHistoryStore(getSharedPreferences("alloy_model_history", MODE_PRIVATE));
        ProjectArchive.pruneStoredProjects(new java.io.File(getFilesDir(), "projects"), plateStore.plates());
        pruneModelCache();
        activePlateIndex = plateStore.activeIndex();
        printerJobStore = new PrinterJobStore(getSharedPreferences("alloy_printer_job", MODE_PRIVATE));
        PrinterJobStore.Job recoveredJob = printerJobStore.recoverAfterRestart(PrinterJobService.ownsAnyJob());
        if (recoveredJob != null && recoveredJob.state == PrinterTransport.State.COMPLETED
                && recoveredJob.filamentMm > 0f) {
            try {
                InventoryReconciliationStore reconciliation = new InventoryReconciliationStore(
                        getSharedPreferences("alloy_inventory_reconciliation", MODE_PRIVATE));
                reconciliation.enqueue(recoveredJob.jobId, recoveredJob.filament,
                        recoveredJob.filamentMm, recoveredJob.filamentDiameterMm);
                reconciliation.drain(inventoryStore);
            } catch (Exception ignored) {
                // The durable queue remains the source of truth for the next launch.
            }
        }
        if (recoveredJob != null && recoveredJob.state == PrinterTransport.State.RECOVERY_REQUIRED) {
            try {
                recoveredArtifact = ArtifactStore.recoverForPhysicalPrint(getFilesDir(), recoveredJob.artifactName,
                        recoveredJob.artifactSize, recoveredJob.artifactSha256);
            } catch (Exception ignored) {
                // A missing or evicted artifact never becomes a reason to
                // retry a printer job; the checkpoint remains fail-closed.
            }
        }
        credentialStore = new PrinterCredentialStore(getSharedPreferences("alloy_printer", MODE_PRIVATE));
        visualizationCredentialStore = new VisualizationCredentialStore(
                getSharedPreferences("alloy_visualization", MODE_PRIVATE));
        sliceJobStore = new SliceJobStore(getSharedPreferences("alloy_slice_job", MODE_PRIVATE));
        SliceJobStore.Job existingSlice = sliceJobStore.load();
        boolean liveSlice = existingSlice != null && SliceJobService.ownsJob(existingSlice.jobId);
        SliceJobStore.Job recoveredSlice = sliceJobStore.recoverAfterRestart(liveSlice);
        if (recoveredSlice != null && (recoveredSlice.state == SliceJobStore.State.QUEUED
                || recoveredSlice.state == SliceJobStore.State.RUNNING) && SliceJobService.ownsJob(recoveredSlice.jobId)) {
            activeSliceJobId = recoveredSlice.jobId;
            slicing = true;
        }
        batchSliceJobStore = new BatchSliceJobStore(getSharedPreferences("alloy_batch_slice_job", MODE_PRIVATE));
        BatchSliceJobStore.Job existingBatch = batchSliceJobStore.load();
        boolean liveBatch = existingBatch != null && BatchSliceJobService.ownsJob(existingBatch.jobId);
        BatchSliceJobStore.Job recoveredBatch = batchSliceJobStore.recoverAfterRestart(liveBatch);
        if (recoveredBatch != null && (recoveredBatch.state == BatchSliceJobStore.State.QUEUED
                || recoveredBatch.state == BatchSliceJobStore.State.RUNNING) && BatchSliceJobService.ownsJob(recoveredBatch.jobId)) {
            activeBatchSliceJobId = recoveredBatch.jobId;
            batchSlicing = true;
        }
        try {
            profile = ProfileCatalog.loadDefault(getAssets());
            java.io.File importedProfileFile = new java.io.File(getFilesDir(), IMPORTED_PROFILE_FILE);
            if (importedProfileFile.isFile()) {
                try {
                    try (InputStream input = new java.io.FileInputStream(importedProfileFile)) {
                        profile = ProfileCatalog.load(input);
                    }
                } catch (Exception ignored) {
                    // A damaged private override must fall back to the
                    // packaged profile rather than leaving an un-applied
                    // recipe in memory.
                }
            }
            profile.applyTo(config);
        } catch (Exception ignored) {
            // Keep the conservative in-code defaults if the packaged profile is unavailable.
        }
        projectStore.restoreRecipe(config);
        SlicerEngine engine = BuildConfig.NATIVE_ENGINE_ENABLED
                ? new NativeSlicerEngine(getCacheDir(), BuildConfig.NATIVE_ENGINE_VERIFIED)
                : new LegacyOfflineEngine();
        sliceJobs = new SliceJobController(engine);
        batchSliceJobs = new BatchSliceJobController(engine);
        buildUi();
        registerPrinterEvents();
        registerSliceEvents();
        registerBatchSliceEvents();
        refreshPrinterUi();
        refreshSliceUi();
        refreshBatchSliceUi();
        Uri incoming = modelUriFromIntent(getIntent());
        if (incoming != null) {
            rememberUriPermission(incoming, getIntent().getFlags());
            loadUri(incoming, false);
        } else {
            PlateStore.Plate activePlate = plateStore.activePlate();
            if (!activePlate.uris.isEmpty()) {
                restorePlateCheckpoint(activePlate);
                loadUris(activePlate.uris, activePlate.names, true);
            } else {
                ProjectStore.SavedProject saved = projectStore.savedProject();
                if (saved != null) {
                    loadUris(saved.uris, saved.names, true);
                } else {
                    // Give a fresh install an immediately inspectable 3D model.
                    // Owner visual-review builds add the supplied Redmagic
                    // design through an optional catalog overlay; ordinary
                    // builds retain the Alloy-authored showcase. Both paths
                    // materialize through the same offline cache boundary.
                    ModelCatalog.Entry ownerStartup = privateOwnerStartupModel();
                    if (ownerStartup == null) loadShowcaseModel();
                    else loadAssetModel(ownerStartup.assetPath, ownerStartup.name);
                    openingPrivateA1Study = hasBundledA1Reference();
                    // The supplied handoff is an immersive presentation first,
                    // not merely a slicer canvas. Make that experience visible
                    // once on a genuinely fresh install, or once after a
                    // visual-review upgrade; subsequent launches return
                    // directly to the project so this never becomes a
                    // blocking welcome screen. The old boolean is retained
                    // for backwards compatibility but the versioned marker
                    // makes the upgrade path explicit.
                    int introVersion = 0;
                    try {
                        introVersion = getPreferences(MODE_PRIVATE)
                                .getInt(IMMERSIVE_INTRO_VERSION, 0);
                    } catch (ClassCastException ignored) {
                        // A very early build used only IMMERSIVE_INTRO_SHOWN;
                        // treat that legacy preference shape as version zero.
                    }
                    if (introVersion < CURRENT_IMMERSIVE_INTRO_VERSION) {
                        getPreferences(MODE_PRIVATE).edit()
                                .putInt(IMMERSIVE_INTRO_VERSION, CURRENT_IMMERSIVE_INTRO_VERSION)
                                .putBoolean(IMMERSIVE_INTRO_SHOWN, true)
                                .apply();
                        mainHandler.postDelayed(() -> {
                            if (isFinishing() || model == null) return;
                            // The private visual-review build is meant to
                            // make the supplied A1 Mini study discoverable
                            // immediately: land on the machine, receding
                            // plates and scale props first. The supplied
                            // Redmagic/box/parts catalog remains available
                            // through the model workspace and atlas.
                            if (hasBundledA1Reference()) {
                                openingPrivateA1Study = false;
                                showPrinterStudy();
                            }
                            else showImmersiveView();
                        }, 350L);
                    }
                }
            }
        }
    }

    /**
     * Ask only when the user starts a background-capable job. Requesting this
     * on launch obscures the first-run 3D study and gives no context for why
     * Alloy needs notifications. The job still starts if the user declines.
     */
    private void requestNotificationPermissionIfNeeded(Runnable afterPermission) {
        if (afterPermission == null) return;
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                || getPreferences(MODE_PRIVATE).getBoolean(NOTIFICATION_PERMISSION_PROMPTED, false)) {
            afterPermission.run();
            return;
        }
        pendingNotificationAction = afterPermission;
        getPreferences(MODE_PRIVATE).edit().putBoolean(NOTIFICATION_PERMISSION_PROMPTED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            Runnable afterPermission = pendingNotificationAction;
            pendingNotificationAction = null;
            if (grantResults == null || grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications are off; job progress remains available inside Alloy", Toast.LENGTH_LONG).show();
            }
            if (afterPermission != null) mainHandler.post(afterPermission);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri incoming = modelUriFromIntent(intent);
        if (incoming == null) return;
        rememberUriPermission(incoming, intent.getFlags());
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation before opening another model", Toast.LENGTH_SHORT).show();
            return;
        }
        loadUri(incoming, false);
    }

    /** Accept both normal file opens and Android share-sheet handoffs. */
    @SuppressWarnings("deprecation")
    static Uri modelUriFromIntent(Intent intent) {
        if (intent == null) return null;
        if (intent.getData() != null) return intent.getData();
        Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return stream instanceof Uri ? (Uri) stream : null;
    }

    private void registerPrinterEvents() {
        printerEventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                handlePrinterEvent(intent);
            }
        };
        IntentFilter filter = new IntentFilter(PrinterJobService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(printerEventReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(printerEventReceiver, filter);
    }

    private void registerSliceEvents() {
        sliceEventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                handleSliceEvent(intent);
            }
        };
        IntentFilter filter = new IntentFilter(SliceJobService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(sliceEventReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(sliceEventReceiver, filter);
    }

    private void registerBatchSliceEvents() {
        batchSliceEventReceiver = new BroadcastReceiver() {
            @Override public void onReceive(android.content.Context context, Intent intent) {
                handleBatchSliceEvent(intent);
            }
        };
        IntentFilter filter = new IntentFilter(BatchSliceJobService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(batchSliceEventReceiver, filter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(batchSliceEventReceiver, filter);
    }

    private void handleBatchSliceEvent(Intent intent) {
        if (intent == null || batchSliceJobStore == null || status == null) return;
        String jobId = intent.getStringExtra(BatchSliceJobService.EXTRA_JOB_ID);
        BatchSliceJobStore.Job current = batchSliceJobStore.load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)) return;
        BatchSliceJobStore.State state;
        try { state = BatchSliceJobStore.State.valueOf(intent.getStringExtra(BatchSliceJobService.EXTRA_STATE)); }
        catch (Exception ignored) { return; }
        int progress = intent.getIntExtra(BatchSliceJobService.EXTRA_PROGRESS, current.progress);
        String phase = intent.getStringExtra(BatchSliceJobService.EXTRA_PHASE);
        String detail = intent.getStringExtra(BatchSliceJobService.EXTRA_DETAIL);
        if (state == BatchSliceJobStore.State.QUEUED || state == BatchSliceJobStore.State.RUNNING) {
            activeBatchSliceJobId = jobId;
            batchSlicing = true;
            status.setText("Batch  ·  " + (progress < 0 ? "working" : progress + "%")
                    + "  ·  " + (phase == null || phase.length() == 0 ? "processing" : phase));
        } else if (state == BatchSliceJobStore.State.COMPLETED) {
            activeBatchSliceJobId = null;
            batchSlicing = false;
            status.setText("Batch  ·  validating plate results…");
            refreshActions();
            restoreForegroundBatchAsync(true);
            return;
        } else if (state == BatchSliceJobStore.State.CANCELLED) {
            activeBatchSliceJobId = null;
            batchSlicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  batch slice cancelled");
        } else if (state == BatchSliceJobStore.State.RECOVERY_REQUIRED) {
            activeBatchSliceJobId = null;
            batchSlicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  previous batch needs review");
        } else if (state == BatchSliceJobStore.State.FAILED) {
            activeBatchSliceJobId = null;
            batchSlicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  batch slice failed");
            if (detail != null && detail.length() > 0)
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
        }
        refreshActions();
    }

    private void refreshBatchSliceUi() {
        if (batchSliceJobStore == null || status == null) return;
        BatchSliceJobStore.Job job = batchSliceJobStore.load();
        if (job == null) return;
        if ((job.state == BatchSliceJobStore.State.QUEUED || job.state == BatchSliceJobStore.State.RUNNING)
                && BatchSliceJobService.ownsJob(job.jobId)) {
            activeBatchSliceJobId = job.jobId;
            batchSlicing = true;
            status.setText("Batch  ·  " + (job.progress > 0 ? job.progress + "%  ·  " : "") + job.phase);
        } else if (job.state == BatchSliceJobStore.State.COMPLETED && model != null && lastBatch == null) {
            restoreForegroundBatchAsync(false);
        } else if (job.state == BatchSliceJobStore.State.RECOVERY_REQUIRED && !batchSlicing) {
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  previous batch needs review");
        }
        refreshActions();
    }

    /** Rehydrate a completed durable batch only when its project fingerprint still matches. */
    private void restoreForegroundBatchAsync(boolean showSummary) {
        if (batchRestoring || batchSliceJobStore == null || model == null) return;
        BatchSliceJobStore.Job job = batchSliceJobStore.load();
        if (job == null || job.state != BatchSliceJobStore.State.COMPLETED) return;
        final String jobId = job.jobId;
        final ArrayList<PlateStore.Plate> currentPlates = plateStore.plates();
        final Slicer.Config currentConfig = config.copy();
        final String currentProjectHash;
        try { currentProjectHash = BatchSliceRequestStore.fingerprint(currentPlates, currentConfig); }
        catch (Exception error) { return; }
        batchRestoring = true;
        activeBatchSnapshot = importExecutor.submit(() -> {
            try {
                BatchSliceRequestStore.Request request = BatchSliceRequestStore.read(getFilesDir(), jobId);
                if (!request.projectSha256.equalsIgnoreCase(currentProjectHash) || !sameConfig(request.config, currentConfig))
                    throw new IOException("The completed batch belongs to a different project or recipe");
                BatchSliceJobController.BatchResult restored = BatchSliceResultStore.read(getFilesDir(), getContentResolver(), request,
                        BatchSliceRequestStore.jobDirectory(getFilesDir(), jobId));
                mainHandler.post(() -> {
                    batchRestoring = false;
                    activeBatchSnapshot = null;
                    if (!jobId.equals(activeBatchJobIdOrEmpty()) || isFinishing()) return;
                    lastBatch = restored;
                    bindBatchResult(restored);
                    status.setText("Inspect  ·  " + restored.plates.size() + " plates ready");
                    details.setText(String.format(Locale.US, "%d plates  ·  %d layers  ·  %.0f mm filament  ·  %s",
                            restored.plates.size(), restored.totalLayers(), restored.totalFilamentMm(),
                            restored.totalPrintTimeSeconds() > 0f ? formatDuration(restored.totalPrintTimeSeconds()) : "time pending"));
                    refreshActions();
                    if (showSummary) showBatchSummary(restored);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    batchRestoring = false;
                    activeBatchSnapshot = null;
                    if (isFinishing()) return;
                    status.setText("Prepare  ·  completed batch needs review");
                    refreshActions();
                    Toast.makeText(this, "Batch result could not be revalidated: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String activeBatchJobIdOrEmpty() {
        BatchSliceJobStore.Job job = batchSliceJobStore == null ? null : batchSliceJobStore.load();
        return job == null ? "" : job.jobId;
    }

    private void bindBatchResult(BatchSliceJobController.BatchResult result) {
        if (result == null) return;
        for (BatchSliceJobController.PlateResult plate : result.plates) {
            if (plate.plate.index != activePlateIndex) continue;
            model = plate.model;
            slice = plate.slice;
            stagedArtifact = plate.artifact;
            viewport.setModel(model);
            viewport.setResult(slice);
            modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
            return;
        }
    }

    private void handleSliceEvent(Intent intent) {
        if (intent == null || sliceJobStore == null) return;
        String jobId = intent.getStringExtra(SliceJobService.EXTRA_JOB_ID);
        SliceJobStore.Job current = sliceJobStore.load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)) return;
        SliceJobStore.State state;
        try { state = SliceJobStore.State.valueOf(intent.getStringExtra(SliceJobService.EXTRA_STATE)); }
        catch (Exception ignored) { return; }
        int progress = intent.getIntExtra(SliceJobService.EXTRA_PROGRESS, current.progress);
        String phase = intent.getStringExtra(SliceJobService.EXTRA_PHASE);
        String detail = intent.getStringExtra(SliceJobService.EXTRA_DETAIL);
        if (state == SliceJobStore.State.QUEUED || state == SliceJobStore.State.RUNNING) {
            activeSliceJobId = jobId;
            slicing = true;
            status.setText("Slice  ·  " + (progress < 0 ? "working" : progress + "%")
                    + "  ·  " + (phase == null || phase.length() == 0 ? "processing" : phase));
        } else if (state == SliceJobStore.State.COMPLETED) {
            activeSliceJobId = null;
            slicing = false;
            if (!restoreForegroundSliceIfMatching()) {
                status.setText(model == null ? "Import a model to begin" : "Prepare  ·  slice result needs review");
                Toast.makeText(this, "Slice completed but its local result could not be revalidated", Toast.LENGTH_LONG).show();
            }
        } else if (state == SliceJobStore.State.CANCELLED) {
            activeSliceJobId = null;
            slicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  slice cancelled");
        } else if (state == SliceJobStore.State.RECOVERY_REQUIRED) {
            activeSliceJobId = null;
            slicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  previous slice needs review");
        } else if (state == SliceJobStore.State.FAILED) {
            activeSliceJobId = null;
            slicing = false;
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  slice failed");
            if (detail != null && detail.length() > 0)
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
        }
        refreshActions();
    }

    private void refreshSliceUi() {
        if (sliceJobStore == null || status == null) return;
        SliceJobStore.Job job = sliceJobStore.load();
        if (job == null) return;
        if ((job.state == SliceJobStore.State.QUEUED || job.state == SliceJobStore.State.RUNNING)
                && SliceJobService.ownsJob(job.jobId)) {
            activeSliceJobId = job.jobId;
            slicing = true;
            status.setText("Slice  ·  " + (job.progress > 0 ? job.progress + "%  ·  " : "") + job.phase);
        } else if (job.state == SliceJobStore.State.COMPLETED && model != null) {
            if (restoreForegroundSliceIfMatching()) {
                status.setText("Inspect  ·  " + slice.layers.size() + " layers");
                details.setText(String.format(Locale.US, "%.0f mm filament  ·  %s  ·  %d warning(s)", slice.filamentMm,
                        slice.printTimeSeconds < 0f ? "time pending" : formatDuration(slice.printTimeSeconds), slice.warnings));
            }
        }
        refreshActions();
    }

    /** Reattach a completed service result only to the exact model and recipe that created it. */
    private boolean restoreForegroundSliceIfMatching() {
        if (sliceJobStore == null || model == null) return false;
        SliceJobStore.Job job = sliceJobStore.load();
        if (job == null || job.state != SliceJobStore.State.COMPLETED) return false;
        try {
            if (!job.modelSha256.equalsIgnoreCase(SliceRequestStore.fingerprint(model))) return false;
            SliceRequestStore.Request request = SliceRequestStore.read(getFilesDir(), job.jobId);
            if (!sameConfig(request.config, config)) return false;
            Slicer.Result restored = SliceResultStore.read(SliceRequestStore.jobDirectory(getFilesDir(), job.jobId), request.config);
            PrinterTransport.Artifact artifact = ArtifactStore.recover(getFilesDir(), job.artifactName, job.artifactSize, job.artifactSha256);
            slice = restored;
            stagedArtifact = artifact;
            lastBatch = null;
            viewport.setModel(model);
            viewport.setResult(restored);
            modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
            details.setText(String.format(Locale.US, "%.0f mm filament  ·  %s  ·  %d warning(s)", restored.filamentMm,
                    restored.printTimeSeconds < 0f ? "time pending" : formatDuration(restored.printTimeSeconds), restored.warnings));
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean sameConfig(Slicer.Config left, Slicer.Config right) {
        if (left == null || right == null) return false;
        return close(left.layerHeight, right.layerHeight) && close(left.firstLayerHeight, right.firstLayerHeight)
                && close(left.nozzle, right.nozzle) && close(left.filamentDiameter, right.filamentDiameter)
                && close(left.infill, right.infill) && close(left.bedX, right.bedX) && close(left.bedY, right.bedY)
                && close(left.bedZ, right.bedZ) && safeEquals(left.printer, right.printer) && safeEquals(left.filament, right.filament)
                && close(left.nozzleTemperature, right.nozzleTemperature) && close(left.firstLayerNozzleTemperature, right.firstLayerNozzleTemperature)
                && close(left.bedTemperature, right.bedTemperature) && close(left.firstLayerBedTemperature, right.firstLayerBedTemperature)
                && close(left.extrusionMultiplier, right.extrusionMultiplier) && close(left.maxVolumetricSpeed, right.maxVolumetricSpeed)
                && close(left.travelSpeed, right.travelSpeed) && close(left.outerWallSpeed, right.outerWallSpeed)
                && close(left.innerWallSpeed, right.innerWallSpeed) && close(left.infillSpeed, right.infillSpeed)
                && close(left.initialLayerSpeed, right.initialLayerSpeed) && close(left.fanMinPercent, right.fanMinPercent)
                && close(left.fanMaxPercent, right.fanMaxPercent) && left.supports == right.supports
                && close(left.supportThresholdDegrees, right.supportThresholdDegrees) && left.perimeters == right.perimeters
                && left.topLayers == right.topLayers && left.bottomLayers == right.bottomLayers
                && left.nativeSettings.equals(right.nativeSettings);
    }

    private static boolean close(float left, float right) { return Math.abs(left - right) <= 0.0001f; }
    private static boolean safeEquals(String left, String right) { return left == null ? right == null : left.equals(right); }

    private void handlePrinterEvent(Intent intent) {
        if (intent == null || printerJobStore == null) return;
        String jobId = intent.getStringExtra(PrinterJobService.EXTRA_JOB_ID);
        PrinterJobStore.Job current = printerJobStore.load();
        if (current == null || jobId == null || !jobId.equals(current.jobId)) return;
        String encoded = intent.getStringExtra(PrinterJobService.EXTRA_STATE);
        PrinterTransport.State state;
        try { state = PrinterTransport.State.valueOf(encoded); }
        catch (Exception ignored) { return; }
        int progress = intent.getIntExtra(PrinterJobService.EXTRA_PROGRESS, -1);
        String phase = intent.getStringExtra(PrinterJobService.EXTRA_PHASE);
        String detail = intent.getStringExtra(PrinterJobService.EXTRA_DETAIL);
        if (state == PrinterTransport.State.UPLOADING && progress >= 0) {
            status.setText("Printer  ·  " + progress + "%  ·  " + (phase == null ? "Uploading artifact" : phase));
        } else if (state == PrinterTransport.State.UPLOADED) {
            activePrinterJobId = current.jobId;
            activePrinterTarget = printerTarget(current);
            printActive = false;
            printerBusy = false;
            recoverUploadedArtifact(current);
            status.setText("Printer  ·  artifact uploaded  ·  ready to start");
        } else if (state == PrinterTransport.State.CONNECTING || state == PrinterTransport.State.START_REQUESTED
                || state == PrinterTransport.State.RUNNING || state == PrinterTransport.State.PAUSE_REQUESTED
                || state == PrinterTransport.State.PAUSED || state == PrinterTransport.State.RESUME_REQUESTED
                || state == PrinterTransport.State.CANCEL_REQUESTED) {
            activePrinterJobId = current.jobId;
            activePrinterTarget = printerTarget(current);
            printActive = state == PrinterTransport.State.START_REQUESTED
                    || state == PrinterTransport.State.RUNNING || state == PrinterTransport.State.PAUSE_REQUESTED
                    || state == PrinterTransport.State.PAUSED || state == PrinterTransport.State.RESUME_REQUESTED
                    || state == PrinterTransport.State.CANCEL_REQUESTED;
            printerBusy = true;
            status.setText("Printer  ·  " + state.name());
        } else if (state == PrinterTransport.State.RECOVERY_REQUIRED) {
            printActive = false;
            printerBusy = false;
            activePrinterTarget = null;
            activePrinterJobId = null;
            recoverUploadedArtifact(current);
            status.setText("Printer  ·  recovery review required");
        } else if (state == PrinterTransport.State.FAILED || state == PrinterTransport.State.COMPLETED
                || state == PrinterTransport.State.CANCELLED) {
            printActive = false;
            printerBusy = false;
            activePrinterTarget = null;
            activePrinterJobId = null;
            if (state == PrinterTransport.State.COMPLETED) updateInventorySummary();
            status.setText("Printer  ·  " + state.name());
            if (state == PrinterTransport.State.FAILED && detail != null && detail.length() > 0)
                Toast.makeText(this, "Printer job failed: " + detail, Toast.LENGTH_LONG).show();
        }
        refreshActions();
    }

    private void refreshPrinterUi() {
        if (printerJobStore == null) return;
        PrinterJobStore.Job job = printerJobStore.load();
        if (job == null) return;
        if (job.state == PrinterTransport.State.UPLOADED || job.state == PrinterTransport.State.RECOVERY_REQUIRED)
            recoverUploadedArtifact(job);
        if (job.state == PrinterTransport.State.CONNECTING || job.state == PrinterTransport.State.UPLOADING
                || job.state == PrinterTransport.State.START_REQUESTED || job.state == PrinterTransport.State.RUNNING
                || job.state == PrinterTransport.State.PAUSE_REQUESTED || job.state == PrinterTransport.State.PAUSED
                || job.state == PrinterTransport.State.RESUME_REQUESTED || job.state == PrinterTransport.State.CANCEL_REQUESTED) {
            activePrinterJobId = job.jobId;
            activePrinterTarget = printerTarget(job);
            printActive = job.state == PrinterTransport.State.START_REQUESTED
                    || job.state == PrinterTransport.State.RUNNING || job.state == PrinterTransport.State.PAUSE_REQUESTED
                    || job.state == PrinterTransport.State.PAUSED || job.state == PrinterTransport.State.RESUME_REQUESTED
                    || job.state == PrinterTransport.State.CANCEL_REQUESTED;
            printerBusy = true;
            status.setText("Printer  ·  " + job.state.name());
        } else if (job.state == PrinterTransport.State.UPLOADED) {
            activePrinterJobId = job.jobId;
            activePrinterTarget = printerTarget(job);
            printActive = false;
            printerBusy = false;
            status.setText("Printer  ·  artifact uploaded  ·  ready to start");
        } else if (job.state == PrinterTransport.State.RECOVERY_REQUIRED) {
            activePrinterTarget = null;
            activePrinterJobId = null;
            printActive = false;
            printerBusy = false;
        }
        refreshActions();
    }

    private void recoverUploadedArtifact(PrinterJobStore.Job job) {
        try {
            recoveredArtifact = ArtifactStore.recoverForPhysicalPrint(getFilesDir(), job.artifactName, job.artifactSize, job.artifactSha256);
        } catch (Exception ignored) {
            recoveredArtifact = null;
        }
    }

    private PrinterTransport.PrinterTarget printerTarget(PrinterJobStore.Job job) {
        try { return new PrinterTransport.PrinterTarget(job.printerName, job.host, job.serial); }
        catch (Exception ignored) { return null; }
    }

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(18), dp(12), dp(18), 0);
        // Android 15 enforces edge-to-edge for target 35. Keep the workshop
        // header and bottom action rail clear of system bars on every API.
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            view.setPadding(dp(18), dp(12) + top, dp(18), bottom);
            return insets;
        });

        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, dp(76)));

        status = label("Import a model to begin", 13, MUTED);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(14), 0, dp(14), 0);
        status.setBackground(round(Color.rgb(239, 236, 230), Color.rgb(222, 216, 206), 1, 14));
        root.addView(status, new LinearLayout.LayoutParams(-1, dp(42)));

        FrameLayout workspace = new FrameLayout(this);
        workspace.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 2, 28));
        workspace.setClipToOutline(true);
        LinearLayout.LayoutParams workspaceLp = new LinearLayout.LayoutParams(-1, 0, 1);
        workspaceLp.topMargin = dp(10);
        workspaceLp.bottomMargin = dp(10);
        viewport = new ViewportView(this);
        viewport.setPartSelectionListener(part -> {
            if (model == null || model.parts == null || part < 0 || part >= model.parts.length) return;
            if (!modelUris.isEmpty()) projectStore.saveSelectedPart(part);
            saveCurrentPlate();
            status.setText("Prepare  ·  focused on " + model.parts[part].name);
        });
        workspace.addView(viewport, new FrameLayout.LayoutParams(-1, -1));

        modelMeta = label("NEW PROJECT\n" + profileShortLabel(), 10, MUTED);
        modelMeta.setLetterSpacing(0.12f);
        modelMeta.setLineSpacing(2, 1.0f);
        modelMeta.setMaxWidth(dp(150));
        modelMeta.setMaxLines(3);
        FrameLayout.LayoutParams metaLp = new FrameLayout.LayoutParams(dp(132), -2, Gravity.TOP | Gravity.START);
        metaLp.setMargins(dp(16), dp(16), 0, 0);
        modelMeta.setPadding(dp(12), dp(9), dp(12), dp(9));
        modelMeta.setBackground(round(Color.argb(236, 255, 255, 255), Color.rgb(226, 222, 213), 1, 14));
        modelMeta.setElevation(dp(2));
        workspace.addView(modelMeta, metaLp);

        TextView fit = control("⊙", "Fit model", v -> { viewport.fitModel(); Toast.makeText(this, "Model fitted to the build plate", Toast.LENGTH_SHORT).show(); });
        FrameLayout.LayoutParams fitLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        fitLp.setMargins(0, dp(16), dp(16), 0);
        workspace.addView(fit, fitLp);

        TextView orbit = control("↻", "Reset view", v -> { viewport.resetView(); Toast.makeText(this, "View reset", Toast.LENGTH_SHORT).show(); });
        FrameLayout.LayoutParams orbitLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        orbitLp.setMargins(0, dp(70), dp(16), 0);
        workspace.addView(orbit, orbitLp);

        // Keep the subject unobstructed: these tags sit in the same orbit as
        // the reference configurator instead of becoming a second toolbar on
        // top of the model.
        // The header already owns the study shortcut. Reserve the left and
        // right lanes beside the subject for context pills so they do not
        // collide with the metadata card or the fit/reset controls.
        workspace.addView(marker("▣", "Printer", profilePrinterLabel(), v -> showPrinterStatus(), Gravity.TOP | Gravity.START, dp(16), dp(150)));
        workspace.addView(marker("M", "Material", profileMaterialLabel(), v -> showRecipe(), Gravity.TOP | Gravity.END, dp(16), dp(150)));
        workspace.addView(marker(Integer.toString(activePlateIndex + 1), "Plate", currentPlateLabel(), v -> showPlates(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(16)));
        workspace.addView(marker(String.format(Locale.US, "%.2f", config.layerHeight), "Quality", "Layer height", v -> showRecipe(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(70)));
        workspace.addView(marker("⌁", "Supports", config.supports ? "Auto supports" : "Off", v -> showRecipe(), Gravity.BOTTOM | Gravity.END, dp(22), dp(22)));
        workspace.addView(marker("↗", "Arrange", "Scale / tilt / pack", v -> showPrepare(), Gravity.BOTTOM | Gravity.START, dp(22), dp(22)));

        root.addView(workspace, workspaceLp);
        root.addView(buildInventoryStrip(), new LinearLayout.LayoutParams(-1, dp(76)));

        details = label("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume", 12, MUTED);
        details.setGravity(Gravity.CENTER_VERTICAL);
        details.setPadding(dp(14), 0, dp(14), 0);
        details.setBackground(round(Color.rgb(239, 236, 230), Color.rgb(222, 216, 206), 1, 14));
        details.setOnClickListener(v -> showParts());
        details.setContentDescription("Model dimensions and part list");
        LinearLayout.LayoutParams detailsLp = new LinearLayout.LayoutParams(-1, dp(42));
        detailsLp.bottomMargin = dp(8);
        root.addView(details, detailsLp);

        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        // Leave a real trailing inset so the final action can be fully brought
        // into view on narrow phones instead of appearing cut off at the edge.
        actions.setPadding(0, dp(5), dp(16), dp(5));
        actions.setGravity(Gravity.CENTER_VERTICAL);
        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.setClipToPadding(false);
        actionScroll.setContentDescription("More preparation and print actions");
        actionScroll.addView(actions, new HorizontalScrollView.LayoutParams(-2, dp(62)));
        root.addView(actionScroll, new LinearLayout.LayoutParams(-1, dp(62)));

        layerInspector = new LinearLayout(this);
        layerInspector.setOrientation(LinearLayout.VERTICAL);
        layerInspector.setPadding(dp(12), dp(2), dp(12), 0);
        layerInspector.setBackground(round(Color.rgb(239, 236, 230), Color.rgb(222, 216, 206), 1, 14));
        layerInspectorLabel = label("LAYER INSPECTION", 10, MUTED);
        layerInspectorLabel.setContentDescription("Selected toolpath layer details");
        layerInspector.addView(layerInspectorLabel, new LinearLayout.LayoutParams(-1, dp(22)));
        layerSeek = new SeekBar(this);
        layerSeek.setContentDescription("Toolpath layer scrubber");
        layerSeek.setMax(0);
        layerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || slice == null || slice.layers == null || slice.layers.isEmpty()) return;
                viewport.setSelectedLayer(progress);
                Slicer.Layer layer = slice.layers.get(progress);
                layerInspectorLabel.setText(String.format(Locale.US, "LAYER %d / %d  ·  Z %.2f mm  ·  %d segments",
                        progress + 1, slice.layers.size(), layer.z, layer.segments.size()));
                status.setText(String.format(Locale.US, "Inspect  ·  layer %d / %d  ·  Z %.2f mm  ·  %d segments",
                        progress + 1, slice.layers.size(), layer.z, layer.segments.size()));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        layerInspector.addView(layerSeek, new LinearLayout.LayoutParams(-1, dp(34)));
        LinearLayout.LayoutParams inspectorLp = new LinearLayout.LayoutParams(-1, dp(60));
        inspectorLp.bottomMargin = dp(8);
        root.addView(layerInspector, inspectorLp);
        setContentView(root);
        root.requestApplyInsets();
        updateRecipeMarkers();
        refreshActions();
    }

    /** A distraction-free presentation surface for the model and toolpath. */
    private void showImmersiveView() {
        if (model == null) {
            Toast.makeText(this, "Import or create a model before opening the 3D view", Toast.LENGTH_SHORT).show();
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(0, 0, 0, 0);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.WHITE);
        stage.setClipToOutline(false);
        ViewportView scene = new ViewportView(this);
        // Immersive study keeps the selected model as the hero, matching the
        // supplied configurator reference. The main workspace retains the
        // contextual printer envelope; this view is for inspecting the part.
        scene.setPresentationMode(false);
        scene.setCleanPresentation(true);
        scene.setModel(model);
        scene.setResult(slice);
        scene.setSelectedPart(viewport == null ? -1 : viewport.getSelectedPart());
        stage.addView(scene, new FrameLayout.LayoutParams(-1, -1));

        TextView title = label(presentationTitle(model.displayName) + "\n"
                + profileMaterialLabel() + "  ·  " + currentPlateLabel(), 13, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLineSpacing(3, 1.0f);
        // Keep the plate badge in its own visual lane on narrow phones; the
        // supplied configurator reference has generous desktop whitespace,
        // while Android presentation surfaces can be only 360dp wide.
        title.setMaxWidth(dp(185));
        title.setMaxLines(2);
        title.setPadding(dp(16), dp(12), dp(16), dp(12));
        title.setBackground(round(Color.argb(248, 255, 255, 255), Color.rgb(231, 228, 221), 1, 18));
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        titleLp.setMargins(dp(14), dp(14), 0, 0);
        stage.addView(title, titleLp);

        TextView wordmark = label("ALLOY", 19, TEXT);
        wordmark.setTypeface(null, android.graphics.Typeface.BOLD);
        wordmark.setLetterSpacing(0.18f);
        wordmark.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams wordmarkLp = new FrameLayout.LayoutParams(dp(150), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wordmarkLp.topMargin = dp(14);
        stage.addView(wordmark, wordmarkLp);

        TextView close = control("×", "Close immersive 3D view", v -> dialog.dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        closeLp.setMargins(0, dp(14), dp(14), 0);
        stage.addView(close, closeLp);
        TextView fit = control("⊙", "Fit model in immersive view", v -> scene.fitModel());
        FrameLayout.LayoutParams fitLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        fitLp.setMargins(0, dp(68), dp(14), 0);
        stage.addView(fit, fitLp);
        addPresentationAppearanceControls(stage, scene, dp(122));

        TextView hint = label("DRAG TO ORBIT  ·  PINCH TO ZOOM  ·  TAP A PART TO FOCUS", 9, MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setLetterSpacing(0.08f);
        FrameLayout.LayoutParams hintLp = new FrameLayout.LayoutParams(-2, dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hintLp.bottomMargin = dp(12);
        stage.addView(hint, hintLp);

        // These are deliberately lightweight callouts: the model remains the
        // hero while the same product vocabulary as the supplied reference
        // identifies the print context around it.
        // The reference has a desktop-sized centered badge. On a phone, drop
        // it into the open upper stage so it never competes with the title.
        stage.addView(sceneTag("01", "PLATE", currentPlateLabel(), Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(88)));
        // Give each callout a dedicated visual lane. Center-vertical
        // placement looks balanced on a desktop canvas, but on a tall phone
        // it puts pills over the hero mesh or the appearance controls and
        // makes the study feel like an early debug overlay.
        stage.addView(sceneTag("M", "MATERIAL", profileMaterialLabel(), Gravity.TOP | Gravity.START, dp(16), dp(136)));
        stage.addView(sceneTag("⌁", "SUPPORTS", config.supports ? "AUTO" : "OFF", Gravity.BOTTOM | Gravity.END, dp(16), dp(154)));
        stage.addView(sceneTag("▣", "PRINTER", "A1 MINI", Gravity.BOTTOM | Gravity.START, dp(22), dp(62)));
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(14), dp(10), dp(14), dp(14));
        footer.setBackgroundColor(Color.WHITE);
        TextView meta = label(currentPlateLabel() + "  ·  " + profileShortLabel(), 11, MUTED);
        meta.setGravity(Gravity.CENTER_VERTICAL);
        meta.setSingleLine(true);
        meta.setEllipsize(android.text.TextUtils.TruncateAt.END);
        meta.setMinWidth(dp(110));
        footer.addView(meta, new LinearLayout.LayoutParams(0, dp(50), 1));
        Button study = dialogButton("A1 study", v -> {
            dialog.dismiss();
            showPrinterStudy();
        });
        study.setContentDescription("Open A1 Mini visual study");
        footer.addView(study, new LinearLayout.LayoutParams(-2, dp(50)));
        if (slice != null) {
            Button path = dialogButton("Path view", null);
            path.setOnClickListener(v -> {
                scene.setToolpathOnly(!scene.isToolpathOnly());
                path.setText(scene.isToolpathOnly() ? "Model view" : "Path view");
            });
            footer.addView(path, new LinearLayout.LayoutParams(-2, dp(50)));
        }
        if (model.parts != null && model.parts.length > 1) {
            Button assembly = dialogButton("Explode", null);
            assembly.setContentDescription("Toggle exploded assembly view");
            assembly.setOnClickListener(v -> {
                boolean exploded = !scene.isExplodedPresentation();
                scene.setExplodedPresentation(exploded);
                assembly.setText(exploded ? "Assemble" : "Explode");
                Toast.makeText(this, exploded ? "Exploded inspection view" : "Assembled inspection view", Toast.LENGTH_SHORT).show();
            });
            footer.addView(assembly, new LinearLayout.LayoutParams(-2, dp(50)));
        }
        Button framing = dialogButton("Machine view", null);
        framing.setOnClickListener(v -> {
            boolean machine = framing.getText().toString().equals("Machine view");
            scene.setPresentationMode(machine);
            scene.setCleanPresentation(!machine);
            framing.setText(machine ? "Hero view" : "Machine view");
        });
        footer.addView(framing, new LinearLayout.LayoutParams(-2, dp(50)));
        Button done = dialogButton("Done", v -> dialog.dismiss());
        done.setTextSize(13);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(0, dp(54), 1);
        doneLp.leftMargin = dp(8);
        footer.addView(done, doneLp);
        page.addView(footer, new LinearLayout.LayoutParams(-1, dp(78)));

        dialog.setContentView(page);
        dialog.setOnDismissListener(ignored -> scene.onHostPause());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1, -1);
            enterPresentationMode(window);
        }
    }

    private Button dialogButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setStateListAnimator(null);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 1, 18));
        button.setOnClickListener(listener);
        return button;
    }

    /** Remove platform chrome while a presentation study is on screen. */
    private void enterPresentationMode(Window window) {
        if (window == null) return;
        // Dialog windows can briefly inherit the host activity's system-bar
        // policy during a cold launch. The study is a full-bleed product
        // surface, so make fullscreen an explicit window property as well as
        // an insets request; otherwise status/navigation chrome can appear
        // over marketing captures and change the composition.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /**
     * Presentation controls are intentionally inside the 3D stage so the
     * visual treatment is discoverable. They only change renderer uniforms;
     * geometry, recipes, slices and printer commands remain untouched.
     */
    private void addPresentationAppearanceControls(FrameLayout stage, ViewportView scene, int topMargin) {
        Button finish = dialogButton(STUDIO_FINISHES[scene.getFinishMode()], null);
        finish.setTextSize(10);
        finish.setMinWidth(dp(112));
        finish.setContentDescription("Cycle studio finish");
        finish.setOnClickListener(v -> {
            int next = (scene.getFinishMode() + 1) % STUDIO_FINISHES.length;
            scene.setFinishMode(next);
            finish.setText(STUDIO_FINISHES[next]);
            Toast.makeText(this, "Studio finish · " + STUDIO_FINISHES[next], Toast.LENGTH_SHORT).show();
        });
        FrameLayout.LayoutParams finishLp = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.END);
        finishLp.setMargins(0, topMargin, dp(14), 0);
        stage.addView(finish, finishLp);

        Button theme = dialogButton("Light stage", null);
        theme.setTextSize(10);
        theme.setMinWidth(dp(112));
        theme.setContentDescription("Toggle studio stage theme");
        theme.setOnClickListener(v -> {
            boolean dark = !scene.isNightStage();
            scene.setNightStage(dark);
            theme.setText(dark ? "Dark stage" : "Light stage");
            Toast.makeText(this, dark ? "Dark studio stage" : "Light studio stage", Toast.LENGTH_SHORT).show();
        });
        FrameLayout.LayoutParams themeLp = new FrameLayout.LayoutParams(-2, dp(40), Gravity.TOP | Gravity.END);
        themeLp.setMargins(0, topMargin + dp(44), dp(14), 0);
        stage.addView(theme, themeLp);
    }

    private View sceneTag(String glyph, String eyebrow, String value, int gravity,
                          int horizontalMargin, int verticalMargin) {
        LinearLayout tag = new LinearLayout(this);
        tag.setOrientation(LinearLayout.HORIZONTAL);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.setPadding(dp(6), dp(5), dp(12), dp(5));
        tag.setBackground(round(Color.argb(246, 255, 255, 255), Color.rgb(226, 222, 213), 1, 22));
        tag.setElevation(dp(4));
        TextView icon = label(glyph, glyph.length() > 2 ? 9 : 13, INK);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        icon.setBackground(round(Color.rgb(247, 244, 237), Color.rgb(191, 184, 171), 1, 17));
        tag.addView(icon, new LinearLayout.LayoutParams(dp(32), dp(32)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(7), 0, 0, 0);
        TextView small = label(eyebrow, 8, MUTED);
        small.setLetterSpacing(0.08f);
        TextView main = label(value, 11, TEXT);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        copy.addView(small);
        copy.addView(main);
        tag.addView(copy);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        if ((gravity & Gravity.START) != 0) lp.leftMargin = horizontalMargin;
        if ((gravity & Gravity.END) != 0) lp.rightMargin = horizontalMargin;
        if ((gravity & Gravity.TOP) != 0) lp.topMargin = verticalMargin;
        if ((gravity & Gravity.BOTTOM) != 0) lp.bottomMargin = verticalMargin;
        tag.setLayoutParams(lp);
        return tag;
    }

    /** A quieter configurator pill for the standalone machine marketing study. */
    private View compactSceneTag(String glyph, String eyebrow, String value, int gravity,
                                 int horizontalMargin, int verticalMargin) {
        LinearLayout tag = new LinearLayout(this);
        tag.setOrientation(LinearLayout.HORIZONTAL);
        tag.setGravity(Gravity.CENTER_VERTICAL);
        tag.setPadding(dp(3), dp(2), dp(7), dp(2));
        tag.setBackground(round(Color.argb(238, 255, 255, 255), Color.rgb(226, 222, 213), 1, 18));
        tag.setElevation(dp(3));
        TextView icon = label(glyph, glyph.length() > 2 ? 7 : 10, INK);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        icon.setBackground(round(Color.rgb(247, 244, 237), Color.rgb(191, 184, 171), 1, 12));
        tag.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(6), 0, 0, 0);
        TextView small = label(eyebrow, 6, MUTED);
        small.setLetterSpacing(0.07f);
        TextView main = label(value, 9, TEXT);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        copy.addView(small);
        copy.addView(main);
        tag.addView(copy);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        if ((gravity & Gravity.START) != 0) lp.leftMargin = horizontalMargin;
        if ((gravity & Gravity.END) != 0) lp.rightMargin = horizontalMargin;
        if ((gravity & Gravity.TOP) != 0) lp.topMargin = verticalMargin;
        if ((gravity & Gravity.BOTTOM) != 0) lp.bottomMargin = verticalMargin;
        tag.setLayoutParams(lp);
        return tag;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        // The bundled profile is source-backed, but physical-print promotion
        // remains a separate gate until the native/runtime and real-printer
        // acceptance evidence is complete. Avoid presenting that intentional
        // fail-closed state as if the profile were malformed or untrusted.
        String profileState = profile != null && profile.verified ? "PROFILE VERIFIED" : "PROFILE REVIEW REQUIRED";
        TextView context = label("WORKSHOP\nPHONE-FIRST  ·  " + profileState, 9, MUTED);
        context.setLetterSpacing(0.11f);
        context.setLineSpacing(2, 1.0f);
        // Keep the contextual copy below the centered wordmark on narrow
        // phones; a long profile state must never render underneath the logo.
        FrameLayout.LayoutParams contextLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        contextLp.leftMargin = dp(8);
        contextLp.bottomMargin = 0;
        header.addView(context, contextLp);

        TextView title = label("ALLOY", 23, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.14f);
        // Keep the wordmark in its own left header lane now that the phone
        // header also exposes the A1 Mini study shortcut. A centered wordmark
        // would collide with the four compact controls on narrow screens.
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(dp(132), -2, Gravity.TOP | Gravity.START);
        titleLp.leftMargin = dp(8);
        titleLp.topMargin = 0;
        header.addView(title, titleLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView printerStudy = control("3D", "Open model 3D study", v -> {
            if (model == null) showModelLibrary();
            else showImmersiveView();
        });
        TextView machineStudy = control("A1", "Open A1 Mini 3D study", v -> showPrinterStudy());
        TextView inventory = control("▦", "Workshop inventory", v -> showInventory());
        TextView menu = control("·", "Project menu", v -> showProjectMenu());
        controls.addView(printerStudy, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams machineStudyLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        machineStudyLp.leftMargin = dp(6);
        controls.addView(machineStudy, machineStudyLp);
        LinearLayout.LayoutParams printerInventoryLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        printerInventoryLp.leftMargin = dp(6);
        controls.addView(inventory, printerInventoryLp);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        menuLp.leftMargin = dp(6);
        controls.addView(menu, menuLp);
        FrameLayout.LayoutParams controlsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        controlsLp.topMargin = 0;
        controlsLp.rightMargin = dp(2);
        header.addView(controls, controlsLp);
        return header;
    }

    /** A standalone A1 Mini study makes the supplied/reviewed printer model discoverable. */
    private void showPrinterStudy() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(0, 0, 0, 0);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.WHITE);
        stage.setClipToOutline(false);
        ViewportView scene = new ViewportView(this);
        scene.setMachineStudy(true);
        scene.setPresentationMode(true);
        scene.setCleanPresentation(false);
        // Start in the light, airy treatment used by the supplied product
        // reference. The appearance control still exposes the dark study,
        // but the first frame should make the machine and its scale props
        // immediately discoverable instead of hiding them in a black field.
        scene.setNightStage(false);
        scene.setModel(null);
        stage.addView(scene, new FrameLayout.LayoutParams(-1, -1));

        TextView title = label("A1 MINI\nBambu Lab\n180 × 180 × 180 MM", 9, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(0.06f);
        title.setLineSpacing(3, 1.0f);
        title.setPadding(dp(2), dp(2), dp(2), dp(2));
        title.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(dp(215), -2, Gravity.TOP | Gravity.START);
        titleLp.setMargins(dp(20), dp(18), 0, 0);
        stage.addView(title, titleLp);
        TextView wordmark = label("ALLOY", 18, TEXT);
        wordmark.setTypeface(null, android.graphics.Typeface.BOLD);
        wordmark.setLetterSpacing(0.18f);
        wordmark.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams wordmarkLp = new FrameLayout.LayoutParams(dp(150), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        wordmarkLp.topMargin = dp(12);
        stage.addView(wordmark, wordmarkLp);
        TextView close = control("×", "Close A1 Mini 3D study", v -> dialog.dismiss());
        FrameLayout.LayoutParams closeLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        closeLp.setMargins(0, dp(14), dp(14), 0);
        stage.addView(close, closeLp);
        TextView fit = control("⊙", "Fit A1 Mini in study view", v -> scene.fitModel());
        FrameLayout.LayoutParams fitLp = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.END);
        fitLp.setMargins(0, dp(68), dp(14), 0);
        stage.addView(fit, fitLp);
        // The supplied reference is a calm product configurator: callouts
        // orbit the hero subject while controls stay quiet at the edges.
        // Keep the renderer controls available through the scene itself, but
        // do not let them dominate the first marketing frame.
        // Match the supplied configurator's visual grammar: one quiet size
        // selector above the hero, material and nozzle selectors orbiting it,
        // and only two small context selectors near the lower edge. The
        // labels are presentation-only; printer configuration remains in the
        // actual preparation and pairing flows.
        // Keep the configurable labels in quiet top/bottom bands. The older
        // orbit layout put material and nozzle pills directly over the
        // machine's gantry and uprights on portrait phones, obscuring the
        // very mesh this study is meant to present.
        View sizeTag = compactSceneTag("□", "SIZE", "180 × 180 × 180 MM",
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(78));
        sizeTag.setOnClickListener(v -> {
            scene.fitModel();
            Toast.makeText(this, "A1 Mini volume · 180 × 180 × 180 mm", Toast.LENGTH_SHORT).show();
        });
        stage.addView(sizeTag);
        View materialTag = compactSceneTag("◌", "MATERIAL", "NATURAL PLA",
                Gravity.TOP | Gravity.START, dp(8), dp(78));
        materialTag.setOnClickListener(v -> {
            int next = (scene.getFinishMode() + 1) % 5;
            scene.setFinishMode(next);
            String[] finishes = {"Natural PLA", "Matte black", "Silk white", "Signal orange", "Steel blue"};
            Toast.makeText(this, "Preview finish · " + finishes[next], Toast.LENGTH_SHORT).show();
        });
        stage.addView(materialTag);
        View nozzleTag = compactSceneTag("⌁", "NOZZLE", "0.4 MM",
                Gravity.TOP | Gravity.END, dp(8), dp(78));
        nozzleTag.setOnClickListener(v -> showProfileReview());
        stage.addView(nozzleTag);
        View printerTag = compactSceneTag("A1", "PRINTER", "A1 MINI",
                Gravity.BOTTOM | Gravity.START, dp(12), dp(18));
        printerTag.setOnClickListener(v -> showPrinterStatus());
        stage.addView(printerTag);
        View scaleTag = compactSceneTag("≈", "SCALE", "CAN  ·  BALL  ·  KEY",
                Gravity.BOTTOM | Gravity.END, dp(12), dp(18));
        scaleTag.setOnClickListener(v -> {
            if (model == null) {
                Toast.makeText(this, "Import a model to use known-dimension scaling", Toast.LENGTH_SHORT).show();
            } else {
                showScaleToKnownDimension();
            }
        });
        stage.addView(scaleTag);
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = new LinearLayout(this);
        footer.setOrientation(LinearLayout.VERTICAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(18), dp(10), dp(18), dp(12));
        footer.setBackgroundColor(Color.WHITE);
        TextView note = label("ALLOY  ·  A1 MINI", 9, MUTED);
        note.setGravity(Gravity.CENTER);
        note.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        note.setLineSpacing(1, 1.0f);
        footer.addView(note, new LinearLayout.LayoutParams(-1, dp(22)));
        LinearLayout footerActions = new LinearLayout(this);
        footerActions.setOrientation(LinearLayout.HORIZONTAL);
        footerActions.setGravity(Gravity.CENTER);
        Button done = dialogButton("Done", v -> dialog.dismiss());
        done.setTextSize(13);
        done.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(-1, dp(44));
        footerActions.addView(done, doneLp);
        footer.addView(footerActions, new LinearLayout.LayoutParams(-1, dp(44)));
        page.addView(footer, new LinearLayout.LayoutParams(-1, dp(88)));

        dialog.setContentView(page);
        dialog.setOnDismissListener(ignored -> scene.onHostPause());
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1, -1);
            enterPresentationMode(window);
        }
    }

    private LinearLayout buildInventoryStrip() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(14), dp(8), dp(10), dp(8));
        card.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 1, 20));

        inventoryStatusDot = label("●", 16, GREEN);
        inventoryStatusDot.setContentDescription("Workshop inventory status");
        card.addView(inventoryStatusDot, new LinearLayout.LayoutParams(dp(26), -2));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("Workshop inventory", 13, TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        inventorySummary = label("", 11, MUTED);
        copy.addView(title);
        copy.addView(inventorySummary);
        card.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        TextView open = label("VIEW", 10, GOLD);
        open.setTypeface(null, android.graphics.Typeface.BOLD);
        open.setGravity(Gravity.CENTER);
        open.setOnClickListener(v -> showInventory());
        card.addView(open, new LinearLayout.LayoutParams(dp(58), -1));
        updateInventorySummary();
        return card;
    }

    private void updateInventorySummary() {
        int reorder = 0;
        int service = 0;
        int attention = 0;
        for (InventoryStore.Item item : inventoryStore.items()) {
            boolean low = item.needsReorder();
            boolean upkeep = item.needsServiceAttention();
            if (low) reorder++;
            if (upkeep) service++;
            if (low || upkeep) attention++;
        }
        String summary = attention == 0
                ? "All stocked  ·  no service due"
                : attention + " item" + (attention == 1 ? "" : "s") + " need attention  ·  "
                        + reorder + " reorder  ·  " + service + " service due";
        inventorySummary.setText(summary);
        if (inventoryStatusDot != null) {
            int color = reorder > 0 ? RED : service > 0 ? AMBER : GREEN;
            inventoryStatusDot.setTextColor(color);
            inventoryStatusDot.setContentDescription(summary);
        }
    }

    private TextView label(String text, float size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setFontFeatureSettings("kern");
        return view;
    }

    /** Keep the hand-built product shell consistent across phone densities. */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String profileShortLabel() {
        return profile == null ? "A1 MINI  ·  0.4 MM NOZZLE" : profile.name.toUpperCase(Locale.US);
    }

    private String viewportDisplayName(String value) {
        String name = value == null ? "MODEL" : value.trim();
        if (name.length() == 0) name = "MODEL";
        if ("ALLOY SHOWCASE BOX".equalsIgnoreCase(name)) name = "SHOWCASE BOX";
        name = name.toUpperCase(Locale.US);
        return name.length() > 24 ? name.substring(0, 24).trim() + "…" : name;
    }

    /** Keep filesystem-heavy CAD names legible in the small presentation title. */
    private String presentationTitle(String value) {
        String name = value == null ? "MODEL" : value.trim().toLowerCase(Locale.US);
        if (name.contains("redmagic") && name.contains("assembly")) return "REDMAGIC ASSEMBLY";
        if (name.contains("redmagic") && name.contains("chassis")) return "REDMAGIC CHASSIS";
        if (name.contains("redmagic") && name.contains("bezel")) return "REDMAGIC BEZEL";
        if (name.contains("showcase")) return "SHOWCASE BOX";
        String compact = viewportDisplayName(value);
        return compact.length() > 18 ? compact.substring(0, 18).trim() + "…" : compact;
    }

    private String profilePrinterLabel() {
        if (profile == null) return "A1 Mini";
        String label = profile.name == null ? "A1 Mini" : profile.name;
        int separator = label.indexOf(" · ");
        if (separator > 0) label = label.substring(0, separator);
        if (label.toLowerCase(Locale.US).contains("a1 mini")) return "A1 Mini";
        return label.length() > 24 ? label.substring(0, 24).trim() + "…" : label;
    }

    private String profileMaterialLabel() {
        return profile == null ? "PLA Basic" : profile.filamentName.replace("Bambu ", "");
    }

    private String profileBuildVolumeLabel() {
        return profile == null ? "180 × 180 × 180 mm" : profile.buildVolumeLabel();
    }

    private String currentPlateLabel() {
        return "Plate " + (activePlateIndex + 1);
    }

    private String currentPlateName() {
        if (model == null || model.displayName == null || model.displayName.trim().length() == 0)
            return currentPlateLabel();
        return currentPlateLabel() + "  ·  " + model.displayName;
    }

    private void updatePlateMarker() {
        if (plateMarkerValue != null) plateMarkerValue.setText(currentPlateLabel());
    }

    private Button action(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        button.setMinHeight(dp(48));
        button.setPadding(dp(14), 0, dp(14), 0);
        button.setStateListAnimator(null);
        button.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 1, 18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(50));
        lp.setMargins(dp(3), 0, dp(3), 0);
        actions.addView(button, lp);
        return button;
    }

    private TextView control(String glyph, String description, View.OnClickListener listener) {
        TextView view = label(glyph, 20, INK);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(description);
        view.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 1, 24));
        view.setElevation(dp(3));
        view.setOnClickListener(listener);
        return view;
    }

    private View marker(String glyph, String eyebrow, String value, View.OnClickListener listener, int gravity, int margin, int verticalMargin) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(6), dp(5), dp(11), dp(5));
        pill.setBackground(round(SURFACE, Color.rgb(216, 211, 201), 1, 24));
        pill.setElevation(dp(5));
        TextView badge = label(glyph, glyph.length() > 2 ? 8 : 13, INK);
        badge.setGravity(Gravity.CENTER);
        badge.setTypeface(null, android.graphics.Typeface.BOLD);
        badge.setBackground(round(Color.rgb(247, 244, 237), Color.rgb(191, 184, 171), 1, 17));
        pill.addView(badge, new LinearLayout.LayoutParams(dp(33), dp(33)));
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(6), 0, 0, 0);
        TextView small = label(eyebrow.toUpperCase(Locale.US), 8, MUTED);
        small.setLetterSpacing(0.08f);
        TextView main = label(value, 11, TEXT);
        main.setTypeface(null, android.graphics.Typeface.BOLD);
        if ("Quality".equals(eyebrow)) qualityMarkerValue = main;
        if ("Supports".equals(eyebrow)) supportsMarkerValue = main;
        if ("Plate".equals(eyebrow)) plateMarkerValue = main;
        copy.addView(small);
        copy.addView(main);
        pill.addView(copy);
        pill.setContentDescription(eyebrow + ": " + value);
        pill.setOnClickListener(listener);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, gravity);
        if ((gravity & Gravity.START) != 0) lp.leftMargin = margin;
        if ((gravity & Gravity.END) != 0) lp.rightMargin = margin;
        if ((gravity & Gravity.TOP) != 0) lp.topMargin = verticalMargin;
        if ((gravity & Gravity.BOTTOM) != 0) lp.bottomMargin = verticalMargin;
        pill.setLayoutParams(lp);
        return pill;
    }

    private void updateRecipeMarkers() {
        if (qualityMarkerValue != null) qualityMarkerValue.setText(String.format(Locale.US, "%.2f mm", config.layerHeight));
        if (supportsMarkerValue != null) supportsMarkerValue.setText(config.supports ? "Auto supports" : "Off");
        if (viewport != null) viewport.setBuildVolume(config.bedX, config.bedY, config.bedZ);
    }

    private android.graphics.drawable.Drawable round(int fill, int stroke, int strokeWidth, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(strokeWidth), stroke);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private void showPrinterStatus() {
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        String pairing = credentials == null ? "No printer is paired." : "Paired target: " + credentials.name + " · " + credentials.host
                + "\nModel: " + (credentials.model.length() == 0 ? "not confirmed" : credentials.model)
                + (credentials.certificateFingerprint.length() > 0 ? "\nCertificate pin: configured" : "\nCertificate pin: not configured");
        PrinterJobStore.Job previousJob = printerJobStore.load();
        PrinterReadiness.Report readiness = printerReadinessReport();
        StringBuilder readinessText = new StringBuilder(readiness.summary());
        for (PrinterReadiness.Check check : readiness.checks()) {
            readinessText.append("\n").append(check.passed() ? "✓ " : "! ")
                    .append(check.label).append(" · ").append(check.detail);
        }
        if (previousJob != null && previousJob.state == PrinterTransport.State.RECOVERY_REQUIRED) {
            pairing += "\n\nUNCONFIRMED JOB\n" + previousJob.summary() + "\n" + previousJob.detail
                    + "\n\n" + (recoveredArtifact == null
                    ? "The staged artifact could not be revalidated; sending is blocked."
                    : "The staged artifact was recovered and its package, size and SHA-256 identity were revalidated.")
                    + "\nVerify the printer's physical/app state before dismissing this record.";
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("A1 Mini")
                .setMessage("" + (profile == null ? "Conservative fallback profile" : profile.name)
                        + "\n\nProfile status: " + (profile != null && profile.verified ? "verified" : "unverified")
                        + "\nSource: " + (profile == null ? "built-in fallback" : profile.provenanceSource)
                        + "\n\n" + pairing
                        + "\n\nPRINT READINESS\n" + readinessText
                        + "\n\nLAN upload/control stays behind the transport and physical-printer gates. This screen can probe the paired services; it does not start a print.")
                .setNegativeButton("Close", null)
                .setPositiveButton(credentials == null ? "Pair / test LAN" : "Refresh telemetry", (ignored, which) -> {
                    if (credentials == null) showPrinterPairing();
                    else refreshPrinterStatus();
                });
        if (credentials != null && (previousJob == null || previousJob.state != PrinterTransport.State.RECOVERY_REQUIRED))
            dialog.setNeutralButton("Pair / test LAN", (ignored, which) -> showPrinterPairing());
        if (previousJob != null && previousJob.state == PrinterTransport.State.RECOVERY_REQUIRED) {
            dialog.setNeutralButton("Dismiss record", (ignored, which) -> {
                printerJobStore.clear();
                recoveredArtifact = null;
                status.setText("Printer  ·  recovery record dismissed");
                refreshActions();
            });
        }
        dialog.show();
    }

    private void refreshPrinterStatus() {
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        if (credentials == null) {
            showPrinterPairing();
            return;
        }
        try {
            if (printerTransport != null) printerTransport.close();
            if (!credentials.hasCertificatePin()) {
                Toast.makeText(this, "Read and save the printer certificate fingerprint before checking LAN status", Toast.LENGTH_LONG).show();
                showPrinterPairing();
                return;
            }
            BambuLanTransport transport = BambuLanTransport.pinned(credentials, credentials.certificateFingerprint);
            printerTransport = transport;
            PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(
                    credentials.name, credentials.host, credentials.serial);
            status.setText("Printer  ·  reading telemetry…");
            transport.readStatus(target, (state, detail) -> runOnUiThread(() -> {
                if (isFinishing() || printerTransport != transport) return;
                if (state == PrinterTransport.State.READY) status.setText("Printer  ·  " + detail);
                else if (state == PrinterTransport.State.STATUS_UNCONFIRMED) {
                    status.setText("Printer  ·  status unconfirmed");
                    Toast.makeText(this, detail, Toast.LENGTH_LONG).show();
                }
                else if (state == PrinterTransport.State.FAILED) {
                    status.setText("Printer  ·  status unavailable");
                    Toast.makeText(this, "Printer status failed: " + detail, Toast.LENGTH_LONG).show();
                }
            }));
        } catch (Exception error) {
            Toast.makeText(this, "Printer status could not begin: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPrinterPairing() {
        if (printerBusy || slicing || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        PrinterCredentialStore.Credentials current = savedCredentials();
        EditText name = textField(current == null ? "A1 Mini" : current.name, "Printer name");
        EditText host = textField(current == null ? "" : current.host, "LAN IP or hostname");
        EditText serial = textField(current == null ? "" : current.serial, "Printer serial");
        EditText modelCode = textField(current == null ? "" : current.model, "Bambu model code (N1 = A1 Mini)");
        EditText accessCode = textField(current == null ? "" : current.accessCode, "Developer/LAN access code");
        accessCode.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText fingerprint = textField(current == null ? "" : current.certificateFingerprint, "Optional SHA-256 certificate fingerprint");
        fields.addView(name); fields.addView(host); fields.addView(serial); fields.addView(modelCode);
        fields.addView(accessCode); fields.addView(fingerprint);
        final AlertDialog[] pairingDialogRef = new AlertDialog[1];
        Button findPrinter = new Button(this);
        findPrinter.setText("Find printer on Wi-Fi");
        findPrinter.setAllCaps(false);
        findPrinter.setTextColor(GOLD);
        findPrinter.setOnClickListener(view -> {
            if (activeDiscovery != null) activeDiscovery.close();
            findPrinter.setEnabled(false);
            findPrinter.setText("Searching local network…");
            activeDiscovery = BambuPrinterDiscovery.discover(this, 5_000L, new BambuPrinterDiscovery.Callback() {
                @Override public void onComplete(List<BambuPrinterDiscovery.Printer> printers) {
                    runOnUiThread(() -> {
                        if (isFinishing() || pairingDialogRef[0] == null || !pairingDialogRef[0].isShowing()) return;
                        findPrinter.setEnabled(true);
                        findPrinter.setText("Find printer on Wi-Fi");
                        if (printers.isEmpty()) {
                            Toast.makeText(MainActivity.this,
                                    "No Bambu printer announced itself. Try manual IP entry on blocked/VLAN networks.",
                                    Toast.LENGTH_LONG).show();
                        } else {
                            showDiscoveredPrinters(printers, name, host, serial, modelCode);
                        }
                    });
                }

                @Override public void onError(Exception error) {
                    runOnUiThread(() -> {
                        if (isFinishing() || pairingDialogRef[0] == null || !pairingDialogRef[0].isShowing()) return;
                        findPrinter.setEnabled(true);
                        findPrinter.setText("Find printer on Wi-Fi");
                        Toast.makeText(MainActivity.this,
                                "Wi-Fi discovery unavailable: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        fields.addView(findPrinter);
        TextView note = label("Discovery is read-only and fills the printer name, host, serial and model code; the access code is never broadcast. LAN status and physical sending require model N1 (A1 Mini) plus an explicitly saved certificate fingerprint. Certificate inspection is the only unauthenticated network step; Alloy never sends MQTT/FTP commands over an unpinned session. Pairing stores the secret only in Android Keystore-backed encrypted preferences.", 12, MUTED);
        note.setPadding(0, 16, 0, 0); fields.addView(note);
        Button inspectCertificate = new Button(this);
        inspectCertificate.setText("Read printer certificate");
        inspectCertificate.setAllCaps(false);
        inspectCertificate.setTextColor(GOLD);
        inspectCertificate.setOnClickListener(view -> {
            String hostValue = host.getText().toString().trim();
            activeCertificateInspectionId = certificateInspectionIds.incrementAndGet();
            long inspectionId = activeCertificateInspectionId;
            if (activeCertificateInspection != null) activeCertificateInspection.cancel(true);
            inspectCertificate.setEnabled(false);
            inspectCertificate.setText("Reading certificate…");
            activeCertificateInspection = importExecutor.submit(() -> {
                try {
                    String digest = BambuLanTransport.inspectCertificateFingerprint(hostValue);
                    mainHandler.post(() -> {
                        if (isFinishing() || inspectionId != activeCertificateInspectionId) return;
                        fingerprint.setText(displayFingerprint(digest));
                        inspectCertificate.setText("Fingerprint loaded");
                        inspectCertificate.setEnabled(true);
                        Toast.makeText(this, "Fingerprint loaded; tap Save and probe to pair", Toast.LENGTH_LONG).show();
                    });
                } catch (Exception error) {
                    mainHandler.post(() -> {
                        if (isFinishing() || inspectionId != activeCertificateInspectionId) return;
                        inspectCertificate.setText("Read printer certificate");
                        inspectCertificate.setEnabled(true);
                        Toast.makeText(this, "Certificate inspection failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            });
        });
        fields.addView(inspectCertificate);
        AlertDialog pairingDialog = new AlertDialog.Builder(this)
                .setTitle("Pair printer")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save and probe", (dialog, which) -> {
                    try {
                        PrinterCredentialStore.Credentials credentials = new PrinterCredentialStore.Credentials(
                                name.getText().toString(), host.getText().toString(), serial.getText().toString(),
                                accessCode.getText().toString(), fingerprint.getText().toString(),
                                modelCode.getText().toString());
                        if (!credentials.hasCertificatePin()) {
                            throw new IllegalArgumentException("Read and save the printer certificate fingerprint before probing LAN services");
                        }
                        credentialStore.save(credentials);
                        if (printerTransport != null) printerTransport.close();
                        printerTransport = BambuLanTransport.pinned(credentials, credentials.certificateFingerprint);
                        PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(credentials.name, credentials.host, credentials.serial);
                        status.setText("Printer  ·  probing LAN services…");
                        printerTransport.probe(target, (state, detail) -> runOnUiThread(() -> {
                            status.setText(state == PrinterTransport.State.READY ? "Printer  ·  LAN services ready" : "Printer  ·  " + state);
                            if (state == PrinterTransport.State.FAILED) Toast.makeText(this, "Printer probe failed: " + detail, Toast.LENGTH_LONG).show();
                        }));
                    } catch (Exception error) {
                        Toast.makeText(this, "Printer pairing failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .create();
        pairingDialogRef[0] = pairingDialog;
        pairingDialog.setOnDismissListener(dialog -> {
            if (activeDiscovery != null) {
                activeDiscovery.close();
                activeDiscovery = null;
            }
        });
        pairingDialog.show();
    }

    private void showDiscoveredPrinters(List<BambuPrinterDiscovery.Printer> printers,
                                        EditText name, EditText host, EditText serial, EditText modelCode) {
        if (printers == null || printers.isEmpty()) return;
        String[] labels = new String[printers.size()];
        for (int index = 0; index < printers.size(); index++) {
            BambuPrinterDiscovery.Printer printer = printers.get(index);
            labels[index] = printer.summary() + (printer.isA1Mini() ? "" : " · profile review needed");
        }
        new AlertDialog.Builder(this)
                .setTitle("Printers found on Wi-Fi")
                .setItems(labels, (dialog, which) -> {
                    if (which < 0 || which >= printers.size()) return;
                    BambuPrinterDiscovery.Printer printer = printers.get(which);
                    name.setText(printer.name);
                    host.setText(printer.host);
                    serial.setText(printer.serial);
                    modelCode.setText(printer.model);
                    Toast.makeText(this,
                            printer.isLanMode() ? "Printer details filled; enter the access code to continue"
                                    : "Printer is not advertising LAN mode; review before pairing",
                            Toast.LENGTH_LONG).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private PrinterCredentialStore.Credentials savedCredentials() {
        try { return credentialStore == null ? null : credentialStore.load(); }
        catch (Exception ignored) { return null; }
    }

    private static String displayFingerprint(String digest) {
        if (digest == null || !digest.matches("[0-9a-fA-F]{64}")) return digest == null ? "" : digest;
        String normalized = digest.toLowerCase(Locale.US);
        StringBuilder display = new StringBuilder(95);
        for (int index = 0; index < normalized.length(); index++) {
            if (index > 0 && index % 2 == 0) display.append(':');
            display.append(normalized.charAt(index));
        }
        return display.toString();
    }

    private void explainUnsupported(String feature) {
        Toast.makeText(this, feature + " is reserved for the validated native engine", Toast.LENGTH_SHORT).show();
    }

    private void showInventory() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(4, 6, 4, 4);
        TextView intro = label("A small workshop ledger for consumables, tools and care routines. Status is intentionally conservative: missing stock means reorder, while a due service stays visible until checked off.", 12, MUTED);
        intro.setLineSpacing(3, 1.0f);
        intro.setPadding(4, 0, 4, 14);
        content.addView(intro, new LinearLayout.LayoutParams(-1, -2));
        Button add = new Button(this);
        add.setText("Add workshop item");
        add.setAllCaps(false);
        add.setTextColor(GOLD);
        add.setOnClickListener(v -> showAddInventoryItem());
        add.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 1, 16));
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(-1, 48);
        addLp.setMargins(0, 0, 0, 10);
        content.addView(add, addLp);
        for (InventoryStore.Item item : inventoryStore.items()) content.addView(inventoryRow(item));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content);
        new AlertDialog.Builder(this)
                .setTitle("Workshop inventory")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .show();
    }

    private void showAddInventoryItem() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        EditText name = textField("", "Item name");
        EditText category = textField("Tool", "Category (tool, consumable, spare…)");
        EditText unit = textField("each", "Unit (each, spools, bottles…)");
        EditText quantity = field("0", "Current quantity");
        EditText minimum = field("0", "Reorder below this quantity");
        EditText interval = field("0", "Service interval in days (0 = none)");
        EditText care = textField("Inspect, clean, or replace as needed", "Care notes");
        fields.addView(name); fields.addView(category); fields.addView(unit);
        fields.addView(quantity); fields.addView(minimum); fields.addView(interval); fields.addView(care);
        TextView note = label("Items are stored only on this phone. A low quantity shows REORDER; a service interval creates a visible upkeep reminder.", 12, MUTED);
        note.setPadding(0, 16, 0, 0);
        fields.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Add workshop item")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (dialog, which) -> {
                    try {
                        InventoryStore.Item added = inventoryStore.addCustom(
                                name.getText().toString(), category.getText().toString(), unit.getText().toString(),
                                Integer.parseInt(quantity.getText().toString()),
                                Integer.parseInt(minimum.getText().toString()),
                                Integer.parseInt(interval.getText().toString()), care.getText().toString());
                        updateInventorySummary();
                        Toast.makeText(this, added.name + " added to inventory", Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                        showInventory();
                    } catch (Exception error) {
                        Toast.makeText(this, "Inventory values were not valid", Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private View inventoryRow(InventoryStore.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(10, 10, 10, 10);
        int statusColor = inventoryStatusColor(item);
        row.setBackground(round(statusColor == RED ? Color.rgb(255, 245, 242) : statusColor == AMBER ? Color.rgb(255, 250, 239) : SURFACE, Color.rgb(226, 222, 213), 1, 16));
        row.setElevation(2);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(-1, -2);
        rowLp.setMargins(0, 0, 0, 8);

        TextView icon = label(item.glyph(), 15, statusColor);
        icon.setGravity(Gravity.CENTER);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        icon.setBackground(round(Color.rgb(247, 244, 237), Color.rgb(216, 211, 201), 1, 22));
        row.addView(icon, new LinearLayout.LayoutParams(42, 42));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(10, 0, 8, 0);
        TextView name = label(item.name, 13, TEXT);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        String usage = item.usageLabel();
        TextView meta = label(item.category + "  ·  " + item.quantityLabel()
                + (usage.length() == 0 ? "" : "  ·  " + usage), 11, MUTED);
        TextView schedule = label(item.serviceLabel(), 10, item.needsServiceAttention() ? statusColor : MUTED);
        TextView care = label(item.care, 10, MUTED);
        care.setMaxLines(2);
        // A compact stock gauge makes the reorder state legible without
        // opening the item. The scale is intentionally relative to four
        // minimum-stock units, so a full spool or spare does not disappear
        // into a nearly-empty bar while a zero-stock item remains obvious.
        android.widget.ProgressBar stock = new android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal);
        int gaugeMax = Math.max(1, Math.max(item.quantity, Math.max(1, item.minimum * 4)));
        stock.setMax(gaugeMax);
        stock.setProgress(Math.max(0, Math.min(gaugeMax, item.quantity)));
        stock.setProgressTintList(ColorStateList.valueOf(statusColor));
        stock.setContentDescription(item.name + " stock level: " + item.quantityLabel());
        copy.addView(name);
        copy.addView(meta);
        copy.addView(stock, new LinearLayout.LayoutParams(-1, dp(5)));
        copy.addView(schedule);
        copy.addView(care);
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));

        TextView status = label(item.statusLabel(), 9, statusColor);
        status.setGravity(Gravity.CENTER);
        status.setTypeface(null, android.graphics.Typeface.BOLD);
        status.setLetterSpacing(0.08f);
        status.setBackground(round(Color.WHITE, statusColor, 1, 14));
        row.addView(status, new LinearLayout.LayoutParams(76, 30));
        row.setOnClickListener(v -> showInventoryItem(item));
        row.setContentDescription(item.name + " inventory status: " + item.statusLabel());
        row.setLayoutParams(rowLp);
        return row;
    }

    private int inventoryStatusColor(InventoryStore.Item item) {
        if (item.needsReorder()) return RED;
        if (item.needsServiceAttention()) return AMBER;
        return GREEN;
    }

    private void showInventoryItem(InventoryStore.Item item) {
        boolean grams = item.usesGrams();
        String[] actions = item.isCustom()
                ? new String[]{grams ? "Add 100 g" : "Add one", grams ? "Use 100 g" : "Use one", "Mark serviced", "Remove item"}
                : new String[]{grams ? "Add 100 g" : "Add one", grams ? "Use 100 g" : "Use one", "Mark serviced"};
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setMessage(item.category + "\n" + item.quantityLabel()
                        + (item.usageLabel().length() == 0 ? "" : "\n" + item.usageLabel())
                        + "\n\n" + item.care + "\n" + item.serviceLabel()
                        + "\n\nCurrent state: " + item.statusLabel())
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        if (grams) inventoryStore.addQuantity(item, 100); else inventoryStore.addOne(item);
                    }
                    if (which == 1) {
                        if (grams) inventoryStore.useQuantity(item, 100); else inventoryStore.useOne(item);
                    }
                    if (which == 2) inventoryStore.markServiced(item);
                    if (which == 3 && item.isCustom()) {
                        new AlertDialog.Builder(this)
                                .setTitle("Remove " + item.name + "?")
                                .setMessage("This removes the custom inventory record from this phone.")
                                .setNegativeButton("Keep", null)
                                .setPositiveButton("Remove", (ignored, confirmed) -> {
                                    inventoryStore.delete(item);
                                    updateInventorySummary();
                                    Toast.makeText(this, item.name + " removed", Toast.LENGTH_SHORT).show();
                                })
                                .show();
                        return;
                    }
                    updateInventorySummary();
                    Toast.makeText(this, item.name + " updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showParts() {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (model == null || model.parts == null || model.parts.length == 0) {
            Toast.makeText(this, "Import a model before viewing parts", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[model.parts.length + 1];
        labels[0] = "All parts  ·  " + (model.triangles.length / 3) + " triangles";
        for (int index = 0; index < model.parts.length; index++) {
            MeshModel.Part part = model.parts[index];
            MeshModel.PartBounds bounds = model.partBounds(index);
            labels[index + 1] = String.format(Locale.US, "%s  ·  %.1f × %.1f × %.1f mm  ·  %d triangles",
                    part.name, bounds.width(), bounds.depth(), bounds.height(), part.triangleCount);
        }
        new AlertDialog.Builder(this)
                .setTitle("Model parts")
                .setMessage("Select a part to bring it forward in the 3D view.\n\nGeometry: " + model.geometryReport().summary()
                        + (geometryRepairEnabled ? "\n\nConservative repair is active for this plate." : ""))
                .setSingleChoiceItems(labels, viewport.getSelectedPart() + 1, (dialog, which) -> {
                    viewport.setSelectedPart(which - 1);
                    if (!modelUris.isEmpty()) projectStore.saveSelectedPart(which - 1);
                    saveCurrentPlate();
                    status.setText(which == 0 ? "Prepare  ·  showing all parts" : "Prepare  ·  focused on " + model.parts[which - 1].name);
                })
                .setNeutralButton("Transform", (dialog, which) -> {
                    int selected = viewport.getSelectedPart();
                    if (selected < 0) {
                        Toast.makeText(this, "Select a part before transforming it", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    dialog.dismiss();
                    showPartTransform(selected);
                })
                .setNegativeButton(geometryRepairEnabled ? "Original geometry" : "Repair geometry",
                        (dialog, which) -> {
                            if (geometryRepairEnabled) restoreOriginalGeometry();
                            else repairGeometry();
                        })
                .setPositiveButton("Done", null)
                .show();
    }

    private void showPartTransform(int partIndex) {
        if (sourceModel == null || model == null || partIndex < 0 || partIndex >= model.parts.length) return;
        MeshModel.PartTransform current = partTransformAt(partIndex);
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        EditText scale = field(String.format(Locale.US, "%.0f", current.scale * 100f), "Part scale (%)");
        EditText rotation = field(String.format(Locale.US, "%.0f", current.rotationDegrees), "Part rotation (degrees)", true);
        EditText tiltX = field(String.format(Locale.US, "%.0f", current.tiltXDegrees), "Tilt X (degrees)", true);
        EditText tiltY = field(String.format(Locale.US, "%.0f", current.tiltYDegrees), "Tilt Y (degrees)", true);
        EditText offsetX = field(String.format(Locale.US, "%.1f", current.offsetX), "Move X (mm)", true);
        EditText offsetY = field(String.format(Locale.US, "%.1f", current.offsetY), "Move Y (mm)", true);
        fields.addView(scale); fields.addView(rotation); fields.addView(tiltX); fields.addView(tiltY); fields.addView(offsetX); fields.addView(offsetY);
        TextView note = label("Transforming one part preserves the other parts. Alloy re-levels the assembly on the bed and discards any previous slice.", 13, MUTED);
        note.setPadding(0, 18, 0, 0);
        fields.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Transform · " + model.parts[partIndex].name)
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        float nextScale = clamp(Float.parseFloat(scale.getText().toString()) / 100f, 0.1f, 10f);
                        float nextRotation = clamp(Float.parseFloat(rotation.getText().toString()), -360f, 360f);
                        float nextTiltX = clamp(Float.parseFloat(tiltX.getText().toString()), -360f, 360f);
                        float nextTiltY = clamp(Float.parseFloat(tiltY.getText().toString()), -360f, 360f);
                        float nextOffsetX = clamp(Float.parseFloat(offsetX.getText().toString()), -1_000f, 1_000f);
                        float nextOffsetY = clamp(Float.parseFloat(offsetY.getText().toString()), -1_000f, 1_000f);
                        prepareModelMutation("Transform  ·  " + model.parts[partIndex].name);
                        while (partTransforms.size() <= partIndex) partTransforms.add(MeshModel.PartTransform.identity());
                        partTransforms.set(partIndex, new MeshModel.PartTransform(nextScale, nextRotation, nextTiltX, nextTiltY, nextOffsetX, nextOffsetY));
                        projectStore.savePartTransforms(partTransforms);
                        saveCurrentPlate();
                        model = rebuildModel(model.displayName);
                        slice = null;
                        stagedArtifact = null;
                        viewport.setModel(model);
                        viewport.setSelectedPart(partIndex);
                        modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
                        status.setText("Prepare  ·  transformed " + model.parts[partIndex].name);
                        details.setText(modelDetails(model));
                        finishModelMutation("Transform  ·  " + model.parts[partIndex].name);
                        refreshActions();
                    } catch (Exception error) {
                        Toast.makeText(this, "Part transform values were not valid", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void repairGeometry() {
        if (printerBusy || slicing || batchSlicing || importing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation before repairing geometry", Toast.LENGTH_SHORT).show();
            return;
        }
        if (repairingGeometry || sourceModel == null || model == null) return;
        prepareModelMutation("Repair geometry");
        final MeshModel input = sourceModel;
        final String name = model.displayName;
        final long repairId = geometryRepairIds.incrementAndGet();
        activeGeometryRepairId = repairId;
        repairingGeometry = true;
        status.setText("Prepare  ·  repairing geometry…");
        refreshActions();
        activeGeometryRepair = importExecutor.submit(() -> {
            try {
                MeshModel.RepairResult result = input.repair(name);
                mainHandler.post(() -> {
                    if (repairId != activeGeometryRepairId || !repairingGeometry || isFinishing()) return;
                    repairingGeometry = false;
                    activeGeometryRepair = null;
                    try {
                        sourceModel = result.mesh;
                        geometryRepairEnabled = true;
                        model = rebuildModel(name);
                        slice = null;
                        stagedArtifact = null;
                        lastBatch = null;
                        projectStore.saveGeometryRepair(true);
                        saveCurrentPlate();
                        viewport.setModel(model);
                        viewport.setResult(null);
                        status.setText("Prepare  ·  geometry repaired");
                        details.setText(modelDetails(model));
                        finishModelMutation("Repair geometry");
                        refreshActions();
                        Toast.makeText(this, result.report.summary(), Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Repaired geometry could not be applied: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        refreshActions();
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (repairId != activeGeometryRepairId || !repairingGeometry || isFinishing()) return;
                    repairingGeometry = false;
                    activeGeometryRepair = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Geometry repair failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void restoreOriginalGeometry() {
        if (printerBusy || slicing || batchSlicing || importing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation before restoring geometry", Toast.LENGTH_SHORT).show();
            return;
        }
        if (unmodifiedSourceModel == null || model == null) return;
        try {
            prepareModelMutation("Restore original geometry");
            sourceModel = unmodifiedSourceModel;
            geometryRepairEnabled = false;
            projectStore.saveGeometryRepair(false);
            model = rebuildModel(model.displayName);
            slice = null;
            stagedArtifact = null;
            lastBatch = null;
            saveCurrentPlate();
            viewport.setModel(model);
            viewport.setResult(null);
            status.setText("Prepare  ·  original geometry restored");
            details.setText(modelDetails(model));
            finishModelMutation("Restore original geometry");
            refreshActions();
        } catch (Exception error) {
            Toast.makeText(this, "Original geometry could not be restored: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private synchronized void cancelGeometryRepair() {
        activeGeometryRepairId = geometryRepairIds.incrementAndGet();
        if (activeGeometryRepair != null) {
            activeGeometryRepair.cancel(true);
            activeGeometryRepair = null;
        }
        if (!repairingGeometry) return;
        repairingGeometry = false;
        if (status != null) status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        if (actions != null) refreshActions();
    }

    private MeshModel.PartTransform partTransformAt(int index) {
        return index >= 0 && index < partTransforms.size()
                ? partTransforms.get(index) : MeshModel.PartTransform.identity();
    }

    private String modelDetails(MeshModel value) {
        int partCount = value.parts == null ? 1 : value.parts.length;
        return String.format(Locale.US, "%.1f × %.1f × %.1f mm  ·  %d triangles  ·  %d %s  ·  %s  ·  centered on %s plate",
                value.maxX - value.minX, value.maxY - value.minY, value.maxZ - value.minZ,
                value.triangles.length / 3, partCount, partCount == 1 ? "part" : "parts",
                value.geometryReport().summary(), profilePrinterLabel());
    }

    private void refreshActions() {
        actions.removeAllViews();
        if (projectTransferring || batchTransferring) {
            Button busy = action(projectTransferring ? "Project file…" : "Batch archive…", null);
            busy.setEnabled(false);
        } else if (visualizing) {
            Button busy = action("Visualizing…", null);
            busy.setEnabled(false);
            action("Cancel", v -> cancelVisualization()).setTextColor(RED);
        } else if (modeling) {
            Button busy = action("Modeling…", null);
            busy.setEnabled(false);
            action("Cancel", v -> cancelNativeBoolean()).setTextColor(RED);
        } else if (repairingGeometry) {
            Button busy = action("Repairing geometry…", null);
            busy.setEnabled(false);
            Button cancel = action("Cancel", v -> cancelGeometryRepair());
            cancel.setTextColor(RED);
        } else if (importing) {
            Button busy = action("Loading model…", null);
            busy.setEnabled(false);
            Button cancel = action("Cancel", v -> cancelImport());
            cancel.setTextColor(RED);
        } else if (model == null && (batchSlicing || slicing)) {
            Button busy = action(batchSlicing ? "Slicing plates…" : "Slicing…", null);
            busy.setEnabled(false);
            Button cancel = action("Cancel", v -> {
                if (batchSlicing) cancelBatchSlice(); else cancelSlice();
            });
            cancel.setTextColor(RED);
        } else if (model == null) {
            action("Import model", v -> openModel());
            action("A1 3D", v -> showPrinterStudy());
            action("Model", v -> showModelWorkbench());
            action("Model atlas", v -> showModelLibrary());
            action("Recipe", v -> showRecipe());
            action("Print readiness", v -> showPrinterReadiness());
            if (hasPrinterRecovery()) {
                Button review = action("Review printer job", v -> showPrinterStatus());
                review.setTextColor(RED);
            }
            addUploadedPrintAction();
        } else if (batchSlicing) {
            Button busy = action("Slicing plates…", null); busy.setEnabled(false);
            Button cancel = action("Cancel", v -> cancelBatchSlice()); cancel.setTextColor(RED);
        } else if (slicing) {
            Button busy = action("Slicing…", null); busy.setEnabled(false);
            Button cancel = action("Cancel", v -> cancelSlice()); cancel.setTextColor(RED);
        } else if (slice == null) {
            action("Import another", v -> openModel());
            action("A1 3D", v -> showPrinterStudy());
            action("Model", v -> showModelWorkbench());
            action("Model atlas", v -> showModelLibrary());
            action("Model 3D", v -> showImmersiveView());
            action("Print readiness", v -> showPrinterReadiness());
            addHistoryActions();
            action("Visualize", v -> showVisualization());
            if (model.parts != null && model.parts.length > 1) action("Parts", v -> showParts());
            if (model.parts != null && model.parts.length > 1) action("Arrange", v -> autoArrangeParts());
            action("Prepare", v -> showPrepare());
            Button sliceButton = action("Slice", v -> startSlice());
            sliceButton.setTextColor(GOLD);
            if (hasPrinterRecovery()) {
                Button review = action("Review printer job", v -> showPrinterStatus());
                review.setTextColor(RED);
            }
            addUploadedPrintAction();
        } else {
            action("Model", v -> showModelWorkbench());
            action("A1 3D", v -> showPrinterStudy());
            action("Model atlas", v -> showModelLibrary());
            action("Model 3D", v -> showImmersiveView());
            action("Print readiness", v -> showPrinterReadiness());
            addHistoryActions();
            action("Visualize", v -> showVisualization());
            action("Prepare", v -> showPrepare());
            if (model.parts != null && model.parts.length > 1) action("Parts", v -> showParts());
            action("Inspect", v -> showInspection());
            action(viewport.isToolpathOnly() ? "Model view" : "Path view", v -> {
                viewport.setToolpathOnly(!viewport.isToolpathOnly());
                status.setText(viewport.isToolpathOnly() ? "Inspect  ·  top-down toolpath" : "Inspect  ·  model view");
                refreshActions();
            });
            action("Layer −", v -> changeLayer(-1));
            action("Layer +", v -> changeLayer(1));
            if (lastBatch != null && !lastBatch.plates.isEmpty()) {
                Button batch = action("Export all plates", v -> exportBatchArchive());
                batch.setTextColor(GOLD);
            }
            if (printActive && activePrinterTarget != null) {
                PrinterJobStore.Job activeJob = printerJobStore.load();
                if (activeJob != null && activeJob.state == PrinterTransport.State.PAUSED) {
                    Button resume = action("Resume print", v -> resumePrint());
                    resume.setTextColor(GOLD);
                } else if (activeJob != null && activeJob.state == PrinterTransport.State.RUNNING) {
                    Button pause = action("Pause print", v -> pausePrint());
                    pause.setTextColor(GOLD);
                }
                Button cancel = action("Cancel print", v -> cancelPrint());
                cancel.setTextColor(RED);
            } else if (printerBusy) {
                Button busy = action("Uploading…", null);
                busy.setEnabled(false);
            } else {
                PrinterJobStore.Job previousJob = printerJobStore.load();
                if (previousJob != null && previousJob.state == PrinterTransport.State.RECOVERY_REQUIRED) {
                    Button review = action("Review printer job", v -> showPrinterStatus());
                    review.setTextColor(RED);
                } else if (hasUploadedPrinterArtifact()) {
                    addUploadedPrintAction();
                } else if (printerReadinessReport().canSend()) {
                    Button send = action("Send to printer", v -> sendToPrinter());
                    send.setTextColor(GOLD);
                }
            }
            Button export = action("Export .3mf", v -> exportPackage()); export.setTextColor(GOLD);
        }
        updateLayerInspector();
    }

    /** Scrub the selected toolpath layer directly on a phone. */
    private void updateLayerInspector() {
        if (layerInspector == null || layerSeek == null || layerInspectorLabel == null) return;
        boolean available = slice != null && slice.layers != null && !slice.layers.isEmpty();
        layerInspector.setVisibility(available ? View.VISIBLE : View.GONE);
        if (!available) return;
        int max = Math.max(0, slice.layers.size() - 1);
        int selected = viewport == null ? max : viewport.getSelectedLayer();
        if (selected < 0) selected = max;
        selected = Math.max(0, Math.min(max, selected));
        layerSeek.setMax(max);
        if (layerSeek.getProgress() != selected) layerSeek.setProgress(selected);
        Slicer.Layer layer = slice.layers.get(selected);
        layerInspectorLabel.setText(String.format(Locale.US, "LAYER %d / %d  ·  Z %.2f mm  ·  %d segments",
                selected + 1, slice.layers.size(), layer.z, layer.segments.size()));
    }

    private void addHistoryActions() {
        if (modelHistoryStore == null || model == null) return;
        boolean undo = modelHistoryStore.canUndo(activePlateIndex);
        boolean redo = modelHistoryStore.canRedo(activePlateIndex);
        if (undo) {
            Button button = action("Undo", v -> undoModel());
            button.setTextColor(GOLD);
        }
        if (redo) {
            Button button = action("Redo", v -> redoModel());
            button.setTextColor(GOLD);
        }
        if (undo || redo) action("History", v -> showModelHistory());
    }

    private void sendToPrinter() {
        PrinterReadiness.Report readiness = printerReadinessReport();
        if (!readiness.canSend()) {
            Toast.makeText(this, readiness.summary(), Toast.LENGTH_LONG).show();
            showPrinterReadiness();
            return;
        }
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        if (credentials == null) {
            Toast.makeText(this, "Pair a printer and slice a validated artifact first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!credentials.hasCertificatePin()) {
            Toast.makeText(this, "Add the printer's SHA-256 certificate pin before physical printing", Toast.LENGTH_LONG).show();
            showPrinterPairing();
            return;
        }
        PrinterJobStore.Job previousJob = printerJobStore.load();
        if (previousJob != null && previousJob.state == PrinterTransport.State.RECOVERY_REQUIRED) {
            Toast.makeText(this, "Review the unconfirmed printer job in Printer status first", Toast.LENGTH_LONG).show();
            showPrinterStatus();
            return;
        }
        if (previousJob != null && previousJob.state == PrinterTransport.State.UPLOADED && recoveredArtifact != null) {
            PrinterTransport.PrinterTarget uploadedTarget = printerTarget(previousJob);
            if (uploadedTarget != null) {
                activePrinterTarget = uploadedTarget;
                activePrinterJobId = previousJob.jobId;
                showStartConfirmation(uploadedTarget,
                        previousJob.remotePath.length() == 0 ? "/" + recoveredArtifact.displayName : previousJob.remotePath,
                        recoveredArtifact, previousJob.jobId);
                return;
            }
        }
        if (stagedArtifact == null) {
            Toast.makeText(this, "Pair a printer and slice a validated artifact first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // A foreground slice can finish without a GLES surface. Re-stage
            // only at the user-visible handoff so LAN uploads always carry a
            // Bambu-recognized preview without coupling the service to UI.
            if (!ArtifactStore.hasThumbnail(stagedArtifact.sourceFile)) {
                stagedArtifact = ArtifactStore.stage(getFilesDir(), model, slice, config,
                        artifactDisplayName(), viewport.thumbnailPng(512));
            }
        } catch (Exception error) {
            Toast.makeText(this, "Printer preview staging failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        final PrinterTransport.Artifact artifact = stagedArtifact;
        final String filament = config.filament;
        final float filamentMm = slice.filamentMm;
        final float filamentDiameterMm = config.filamentDiameter;
        try {
            PrinterTransport.PrinterTarget target = new PrinterTransport.PrinterTarget(credentials.name, credentials.host, credentials.serial);
            final String jobId = printerJobStore.begin(target, artifact, filament, filamentMm, filamentDiameterMm);
            activePrinterJobId = jobId;
            activePrinterTarget = target;
            printActive = false;
            printerBusy = true;
            refreshActions();
            status.setText("Printer  ·  uploading artifact…");
            PrinterJobService.startUpload(this, jobId);
        } catch (Exception error) {
            try { printerJobStore.update(PrinterTransport.State.FAILED, "Printer upload could not start: " + error.getMessage(), ""); }
            catch (Exception ignored) { }
            activePrinterTarget = null;
            activePrinterJobId = null;
            printActive = false;
            printerBusy = false;
            refreshActions();
            Toast.makeText(this, "Printer upload could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasPrinterRecovery() {
        PrinterJobStore.Job job = printerJobStore == null ? null : printerJobStore.load();
        return job != null && job.state == PrinterTransport.State.RECOVERY_REQUIRED;
    }

    private boolean physicalPrintCredentialsReady() {
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        return credentials != null && credentials.isA1Mini() && credentials.hasCertificatePin();
    }

    /**
     * Snapshot the current physical-print prerequisites without probing or
     * mutating the printer. The report is informational until every gate is
     * true; sendToPrinter() evaluates it again immediately before beginning a
     * durable transaction.
     */
    private PrinterReadiness.Report printerReadinessReport() {
        return printerReadinessReport(stagedArtifact);
    }

    private PrinterReadiness.Report printerReadinessReport(PrinterTransport.Artifact artifact) {
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        boolean artifactReady = artifact != null && artifact.hasSourceFile()
                && artifact.sourceFile.isFile()
                && artifact.sourceFile.length() == artifact.sizeBytes;
        return PrinterReadiness.evaluate(
                BuildConfig.NATIVE_ENGINE_ENABLED,
                BuildConfig.NATIVE_ENGINE_VERIFIED,
                profile != null,
                profile != null && profile.verified,
                slice != null,
                slice != null && slice.engineVerified,
                artifactReady,
                credentials != null,
                credentials != null && credentials.isA1Mini(),
                credentials != null && credentials.hasCertificatePin(),
                !hasPrinterRecovery(),
                SupportEngineStatus.physicalPrintReady(config, slice),
                SupportEngineStatus.detail(config, slice));
    }

    private void showPrinterReadiness() {
        PrinterReadiness.Report report = printerReadinessReport();
        StringBuilder message = new StringBuilder(report.summary()).append("\n\n");
        for (PrinterReadiness.Check check : report.checks()) {
            message.append(check.passed() ? "✓ " : "! ")
                    .append(check.label).append("  ·  ")
                    .append(check.detail).append('\n');
        }
        message.append("\nThis checklist is local and fail-closed. A green checklist still requires the target A1 Mini's physical acceptance evidence before this build can be promoted.");
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Print readiness")
                .setMessage(message.toString())
                .setNegativeButton("Close", null);
        if (savedCredentials() == null) {
            dialog.setPositiveButton("Pair printer", (ignored, which) -> showPrinterPairing());
        } else {
            dialog.setPositiveButton("Printer status", (ignored, which) -> showPrinterStatus());
        }
        dialog.show();
    }

    private boolean hasUploadedPrinterArtifact() {
        PrinterJobStore.Job job = printerJobStore == null ? null : printerJobStore.load();
        return job != null && job.state == PrinterTransport.State.UPLOADED
                && recoveredArtifact != null && printerTarget(job) != null
                && physicalPrintCredentialsReady();
    }

    private void addUploadedPrintAction() {
        if (!hasUploadedPrinterArtifact()) return;
        PrinterJobStore.Job job = printerJobStore.load();
        PrinterTransport.PrinterTarget target = printerTarget(job);
        if (!printerReadinessReport(recoveredArtifact).canSend()) {
            Button review = action("Print readiness", v -> showPrinterReadiness());
            review.setTextColor(RED);
            return;
        }
        Button start = action("Start uploaded print", v -> showStartConfirmation(target,
                job.remotePath.length() == 0 ? "/" + recoveredArtifact.displayName : job.remotePath,
                recoveredArtifact, job.jobId));
        start.setTextColor(GOLD);
    }

    private void showStartConfirmation(PrinterTransport.PrinterTarget target, String remotePath,
                                       PrinterTransport.Artifact artifact, String jobId) {
        if (target == null || artifact == null || jobId == null) return;
        PrinterReadiness.Report readiness = printerReadinessReport(artifact);
        if (!readiness.canSend()) {
            Toast.makeText(this, readiness.summary(), Toast.LENGTH_LONG).show();
            showPrinterReadiness();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Start print?")
                .setMessage("The artifact was uploaded to " + target.name + ". The printer must confirm PREPARE/RUNNING telemetry before Alloy reports a start.")
                .setNegativeButton("Keep uploaded", (dialog, which) -> {
                    if (!printerJobStore.updateIfMatches(jobId, target, artifact, PrinterTransport.State.UPLOADED,
                            "Artifact uploaded; start was not requested", remotePath)) return;
                    activePrinterTarget = null;
                    activePrinterJobId = null;
                    printerBusy = false;
                    refreshActions();
                })
                .setPositiveButton("Start", (dialog, which) -> {
                    PrinterJobStore.Job current = printerJobStore.load();
                    if (current == null || !jobId.equals(current.jobId) || current.state != PrinterTransport.State.UPLOADED) {
                        Toast.makeText(this, "This printer job is no longer active", Toast.LENGTH_LONG).show();
                        return;
                    }
                    PrinterReadiness.Report latest = printerReadinessReport(artifact);
                    if (!latest.canSend()) {
                        Toast.makeText(this, latest.summary(), Toast.LENGTH_LONG).show();
                        showPrinterReadiness();
                        return;
                    }
                    printActive = true;
                    printerBusy = true;
                    refreshActions();
                    status.setText("Printer  ·  requesting start…");
                    try {
                        PrinterJobService.startPrint(this, jobId);
                    } catch (Exception error) {
                        printActive = false;
                        printerBusy = false;
                        activePrinterTarget = null;
                        activePrinterJobId = null;
                        refreshActions();
                        Toast.makeText(this, "Print start could not begin: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void cancelPrint() {
        if (!printActive || activePrinterTarget == null || activePrinterJobId == null) return;
        final String jobId = activePrinterJobId;
        status.setText("Printer  ·  requesting cancellation…");
        try {
            PrinterJobService.cancelPrint(this, jobId);
        } catch (Exception error) {
            Toast.makeText(this, "Cancellation could not begin: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pausePrint() {
        if (!printActive || activePrinterTarget == null || activePrinterJobId == null) return;
        final String jobId = activePrinterJobId;
        status.setText("Printer  ·  requesting pause…");
        try {
            PrinterJobService.pausePrint(this, jobId);
        } catch (Exception error) {
            Toast.makeText(this, "Pause could not begin: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resumePrint() {
        if (!printActive || activePrinterTarget == null || activePrinterJobId == null) return;
        final String jobId = activePrinterJobId;
        status.setText("Printer  ·  requesting resume…");
        try {
            PrinterJobService.resumePrint(this, jobId);
        } catch (Exception error) {
            Toast.makeText(this, "Resume could not begin: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openModel() {
        if (printerBusy || slicing || batchSlicing || batchTransferring || profileImporting) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"model/stl", "model/obj", "model/3mf", "model/step", "model/stp", "application/step", "application/vnd.ms-package.3dmanufacturing-3dmodel+xml", "application/octet-stream", "application/zip", "application/x-zip-compressed"});
        startActivityForResult(intent, REQUEST_OPEN);
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        super.onActivityResult(request, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (request == REQUEST_PROFILE) {
            ArrayList<Uri> sources = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int index = 0; index < data.getClipData().getItemCount(); index++)
                    sources.add(data.getClipData().getItemAt(index).getUri());
            } else if (data.getData() != null) {
                sources.add(data.getData());
            }
            if (sources.isEmpty()) return;
            for (Uri uri : sources) rememberUriPermission(uri, data.getFlags());
            importBambuProfiles(sources);
            return;
        }
        if (request == REQUEST_PROFILE_EXPORT && data.getData() != null) {
            writeProfileExport(data.getData());
            return;
        }
        if (request == REQUEST_OPEN) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int index = 0; index < data.getClipData().getItemCount(); index++)
                    uris.add(data.getClipData().getItemAt(index).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (uris.isEmpty()) return;
            for (Uri uri : uris) rememberUriPermission(uri, data.getFlags());
            loadUris(uris, false);
        }
        if (request == REQUEST_EXPORT && data.getData() != null) writeExport(data.getData());
        if (request == REQUEST_VIEW_EXPORT && data.getData() != null) writeViewExport(data.getData());
        if (request == REQUEST_VISUALIZATION_EXPORT && data.getData() != null) writeVisualizationExport(data.getData());
        if (request == REQUEST_PROJECT_EXPORT && data.getData() != null) writeProjectArchive(data.getData());
        if (request == REQUEST_BATCH_EXPORT && data.getData() != null) writeBatchArchive(data.getData());
        if (request == REQUEST_PROJECT_OPEN && data.getData() != null) {
            rememberUriPermission(data.getData(), data.getFlags());
            readProjectArchive(data.getData());
        }
    }

    private void openBambuProfile() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring || profileImporting) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "application/octet-stream", "text/plain"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PROFILE);
    }

    private void importBambuProfiles(ArrayList<Uri> sources) {
        if (sources == null || sources.isEmpty()) return;
        final ProfileCatalog.Profile baseline = profile;
        final long importId = profileImportIds.incrementAndGet();
        activeProfileImportId = importId;
        profileImporting = true;
        status.setText("Profile  ·  importing Bambu preset chain…");
        refreshActions();
        profileImportTimeoutRunnable = () -> {
            synchronized (MainActivity.this) {
                if (importId != activeProfileImportId || !profileImporting || isFinishing()) return;
            }
            cancelProfileImport();
            Toast.makeText(MainActivity.this,
                    "Profile import timed out. Check the selected files and try again.",
                    Toast.LENGTH_LONG).show();
        };
        mainHandler.postDelayed(profileImportTimeoutRunnable, 60_000L);
        activeProfileImport = importExecutor.submit(() -> {
            try {
                byte[][] documents = new byte[sources.size()][];
                for (int index = 0; index < sources.size(); index++) {
                    try (InputStream input = getContentResolver().openInputStream(sources.get(index))) {
                        if (input == null) throw new IOException("A selected profile could not be opened");
                        documents[index] = ProfileCatalog.readProfileDocument(input);
                    }
                }
                ProfileCatalog.Profile imported = ProfileCatalog.importBambu(baseline, documents);
                byte[] normalized = ProfileCatalog.serialize(imported);
                writeImportedProfile(imported, normalized);
                mainHandler.post(() -> {
                    synchronized (MainActivity.this) {
                        if (importId != activeProfileImportId || !profileImporting || isFinishing()) return;
                        if (profileImportTimeoutRunnable != null) {
                            mainHandler.removeCallbacks(profileImportTimeoutRunnable);
                            profileImportTimeoutRunnable = null;
                        }
                    }
                    profileImporting = false;
                    activeProfileImport = null;
                    if (isFinishing()) return;
                    profile = imported;
                    profile.applyTo(config);
                    projectStore.saveRecipe(config);
                    slice = null;
                    stagedArtifact = null;
                    lastBatch = null;
                    if (viewport != null) viewport.setResult(null);
                    if (model != null) {
                        modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
                        details.setText(modelDetails(model));
                    } else {
                        modelMeta.setText("NEW PROJECT\n" + profileShortLabel());
                        details.setText("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume");
                    }
                    updateRecipeMarkers();
                    status.setText("Profile  ·  imported · review required");
                    refreshActions();
                    Toast.makeText(this, "Imported Bambu profile · review required before printing", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    synchronized (MainActivity.this) {
                        if (importId != activeProfileImportId || !profileImporting || isFinishing()) return;
                        if (profileImportTimeoutRunnable != null) {
                            mainHandler.removeCallbacks(profileImportTimeoutRunnable);
                            profileImportTimeoutRunnable = null;
                        }
                    }
                    profileImporting = false;
                    activeProfileImport = null;
                    if (isFinishing()) return;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Profile import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private synchronized void cancelProfileImport() {
        activeProfileImportId = profileImportIds.incrementAndGet();
        if (profileImportTimeoutRunnable != null) {
            mainHandler.removeCallbacks(profileImportTimeoutRunnable);
            profileImportTimeoutRunnable = null;
        }
        if (activeProfileImport != null) {
            activeProfileImport.cancel(true);
            activeProfileImport = null;
        }
        if (!profileImporting) return;
        profileImporting = false;
        status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        refreshActions();
    }

    /** Persist a normalized profile with a same-directory temporary replacement. */
    private void writeImportedProfile(ProfileCatalog.Profile imported, byte[] normalized) throws IOException {
        if (imported == null || normalized == null || normalized.length == 0)
            throw new IOException("The imported profile is empty");
        java.io.File destination = new java.io.File(getFilesDir(), IMPORTED_PROFILE_FILE);
        java.io.File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("The private profile store could not be created");
        if (parent == null) throw new IOException("The private profile store has no parent");
        java.io.File temporary = new java.io.File(parent, destination.getName() + ".tmp");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
            output.write(normalized);
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(destination)) {
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(destination)) {
                output.write(normalized);
                output.flush();
                output.getFD().sync();
            }
            temporary.delete();
        }
    }

    private boolean importedProfileActive() {
        return profile != null && "Bambu JSON preset import".equals(profile.provenanceSource);
    }

    private void showProfileReview() {
        if (profile == null) {
            new AlertDialog.Builder(this).setTitle("Active profile")
                    .setMessage("The bundled fallback profile is unavailable. Slicing and printing are blocked until a valid profile is loaded.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        String statusLabel = profile.verified ? "verified" : "review required · source-backed";
        String message = profile.name
                + "\n\nPrinter envelope\n" + profile.buildVolumeLabel()
                + "\nNozzle " + String.format(Locale.US, "%.2f mm", profile.nozzle)
                + "\n\nMaterial\n" + profile.filamentName + " · " + profile.material
                + "\n\nProfile status\n" + statusLabel
                + "\nSource\n" + profile.provenanceSource
                + "\nRevision\n" + profile.provenanceRevision
                + "\n\nOnly allowlisted scalar settings are projected into the native engine. G-code templates and unknown settings stay out of the job package.";
        AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                .setTitle("Active profile")
                .setMessage(message)
                .setNegativeButton("Close", null)
                .setPositiveButton("Export JSON", (ignored, which) -> openProfileExport());
        if (importedProfileActive()) {
            dialog.setNeutralButton("Reset to bundled", (ignored, which) -> resetImportedProfile());
        }
        dialog.show();
    }

    private void openProfileExport() {
        if (profile == null) {
            Toast.makeText(this, "There is no active profile to export", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(profile.name) + ".alloy-profile.json");
        startActivityForResult(intent, REQUEST_PROFILE_EXPORT);
    }

    private void writeProfileExport(Uri uri) {
        try {
            byte[] data = ProfileCatalog.serialize(profile);
            try (java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new IOException("Output destination could not be opened");
                output.write(data);
                output.flush();
            }
            Toast.makeText(this, "Exported normalized Alloy profile", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Profile export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetImportedProfile() {
        if (!importedProfileActive()) {
            Toast.makeText(this, "The bundled A1 Mini profile is already active", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Reset imported profile?")
                .setMessage("This removes only Alloy's private imported-profile override and restores the bundled A1 Mini / PLA profile. Saved models, plates, project archives, and inventory remain unchanged. Any slice must be reviewed again.")
                .setNegativeButton("Keep it", null)
                .setPositiveButton("Reset profile", (ignored, which) -> {
                    java.io.File destination = new java.io.File(getFilesDir(), IMPORTED_PROFILE_FILE);
                    if (destination.exists() && !destination.delete()) {
                        Toast.makeText(this, "The private profile override could not be removed", Toast.LENGTH_LONG).show();
                        return;
                    }
                    try {
                        ProfileCatalog.Profile restored = ProfileCatalog.loadDefault(getAssets());
                        profile = restored;
                        profile.applyTo(config);
                        projectStore.saveRecipe(config);
                        slice = null;
                        stagedArtifact = null;
                        lastBatch = null;
                        if (viewport != null) viewport.setResult(null);
                        if (model != null) {
                            modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
                            details.setText(modelDetails(model));
                        } else {
                            modelMeta.setText("NEW PROJECT\n" + profileShortLabel());
                            details.setText("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume");
                        }
                        updateRecipeMarkers();
                        status.setText(model == null ? "Profile  ·  bundled A1 Mini restored" : "Prepare  ·  recipe reset, review model");
                        refreshActions();
                        Toast.makeText(this, "Bundled A1 Mini profile restored", Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Profile reset failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void rememberUriPermission(Uri uri, int flags) {
        int takeFlags = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (takeFlags == 0) takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        try {
            getContentResolver().takePersistableUriPermission(uri, takeFlags);
        } catch (SecurityException ignored) {
            // Some providers expose a readable URI without persistable grants.
        }
    }

    private void loadUri(Uri uri) { loadUri(uri, false); }

    private void loadUri(Uri uri, boolean restoreTransform) {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(uri);
        loadUris(uris, restoreTransform);
    }

    private void loadUris(ArrayList<Uri> uris, boolean restoreTransform) {
        loadUris(uris, null, restoreTransform);
    }

    private void loadUris(ArrayList<Uri> uris, ArrayList<String> preferredNames, boolean restoreTransform) {
        if (uris == null || uris.isEmpty()) return;
        final ArrayList<Uri> requestedUris = new ArrayList<>(uris);
        final ArrayList<String> requestedNames = preferredNames == null ? null : new ArrayList<>(preferredNames);
        // A document import runs off the UI thread. Snapshot the recipe used
        // for STEP conversion so a concurrent editor change cannot produce a
        // converted mesh whose cache identity and visible recipe disagree.
        final Slicer.Config importConfig = config.copy();
        final ProfileCatalog.Profile importProfileBaseline = profile;
        final boolean restoreGeometryRepair = restoreTransform && projectStore.savedGeometryRepair();
        cancelImport();
        sliceJobs.cancel();
        final long importId = importIds.incrementAndGet();
        activeImportId = importId;
        importing = true;
        status.setText("Import  ·  reading model…");
        refreshActions();
        importTimeoutRunnable = () -> {
            synchronized (MainActivity.this) {
                if (importId != activeImportId || !importing || isFinishing()) return;
            }
            cancelImport();
            Toast.makeText(MainActivity.this,
                    "Model import timed out. Check the file provider or try a smaller model.",
                    Toast.LENGTH_LONG).show();
        };
        mainHandler.postDelayed(importTimeoutRunnable, 90_000L);
        activeImport = importExecutor.submit(() -> {
            try {
            ArrayList<MeshModel> loaded = new ArrayList<>();
            ArrayList<MeshModel> pristineLoaded = new ArrayList<>();
            ArrayList<Uri> loadedUris = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            byte[] embeddedProjectSettings = null;
            for (int index = 0; index < requestedUris.size(); index++) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("Import cancelled");
                Uri uri = requestedUris.get(index);
                if (uri == null) throw new IllegalArgumentException("A selected model URI is missing");
                String preferred = requestedNames != null && index < requestedNames.size() ? requestedNames.get(index) : null;
                String name = preferred == null || preferred.trim().length() == 0 ? displayName(uri) : preferred;
                try (InputStream raw = getContentResolver().openInputStream(uri)) {
                    if (raw == null) throw new IllegalArgumentException("The selected file could not be opened");
                    java.io.BufferedInputStream probe = new java.io.BufferedInputStream(raw);
                    boolean direct3mf = ModelBundleExtractor.isDirect3mf(name, getContentResolver().getType(uri));
                    if (ModelBundleExtractor.isZip(probe) && !direct3mf) {
                        if (name.toLowerCase(Locale.US).endsWith(".alloy.zip"))
                            throw new IOException("This is an Alloy project archive; use Open project archive");
                        ArrayList<ModelBundleExtractor.Extracted> extracted = ModelBundleExtractor.extract(getFilesDir(), probe);
                        for (ModelBundleExtractor.Extracted item : extracted) {
                            String itemName = item.displayName;
                            if (".3mf".equals(item.materialized.extension)) {
                                embeddedProjectSettings = mergeProjectSettings(embeddedProjectSettings,
                                        BambuProjectSettingsExtractor.extract(item.materialized.file));
                            }
                            ModelStore.Materialized renderable = renderableMaterialized(item.materialized, itemName, importConfig);
                            loadedUris.add(renderable.uri);
                            try (InputStream input = getContentResolver().openInputStream(renderable.uri)) {
                                if (input == null) throw new IllegalArgumentException("The extracted model could not be opened");
                                MeshModel parsed = MeshModel.read(ModelStore.parserName(itemName, renderable.extension), input);
                                pristineLoaded.add(parsed);
                                loaded.add(restoreGeometryRepair ? parsed.repair(itemName).mesh : parsed);
                                names.add(itemName);
                            }
                        }
                    } else {
                        ModelStore.Materialized materialized = ModelStore.materialize(getFilesDir(), getContentResolver(), uri, name);
                        if (".3mf".equals(materialized.extension)) {
                            embeddedProjectSettings = mergeProjectSettings(embeddedProjectSettings,
                                    BambuProjectSettingsExtractor.extract(materialized.file));
                        }
                        ModelStore.Materialized renderable = renderableMaterialized(materialized, name, importConfig);
                        loadedUris.add(renderable.uri);
                        try (InputStream input = getContentResolver().openInputStream(renderable.uri)) {
                            if (input == null) throw new IllegalArgumentException("The selected file could not be opened");
                            // Parse from the content-detected suffix, while keeping
                            // the provider's friendly name for project labels.
                            MeshModel parsed = MeshModel.read(ModelStore.parserName(name, renderable.extension), input);
                            pristineLoaded.add(parsed);
                            loaded.add(restoreGeometryRepair ? parsed.repair(name).mesh : parsed);
                            names.add(name);
                        }
                    }
                }
            }
            String projectName = loaded.size() == 1 ? names.get(0) : "Project · " + loaded.size() + " models";
            MeshModel combined = loaded.size() == 1 ? loaded.get(0) : MeshModel.combine(projectName, loaded);
            MeshModel pristineCombined = pristineLoaded.size() == 1
                    ? pristineLoaded.get(0) : MeshModel.combine(projectName, pristineLoaded);
            float scale = restoreTransform ? projectStore.savedScale() : 1f;
            float rotation = restoreTransform ? projectStore.savedRotation() : 0f;
            float tiltX = restoreTransform ? projectStore.savedTiltX() : 0f;
            float tiltY = restoreTransform ? projectStore.savedTiltY() : 0f;
            final ArrayList<Uri> importedUris = new ArrayList<>(loadedUris);
            final ArrayList<String> importedNames = new ArrayList<>(names);
            final ProfileCatalog.Profile importedProjectProfile;
            if (embeddedProjectSettings == null) {
                importedProjectProfile = null;
            } else {
                importedProjectProfile = ProfileCatalog.importBambu(importProfileBaseline,
                        new byte[][]{embeddedProjectSettings});
                writeImportedProfile(importedProjectProfile,
                        ProfileCatalog.serialize(importedProjectProfile));
            }
            mainHandler.post(() -> {
                synchronized (MainActivity.this) {
                    if (importId != activeImportId || !importing || isFinishing()) return;
                    importing = false;
                    activeImport = null;
                    if (importTimeoutRunnable != null) {
                        mainHandler.removeCallbacks(importTimeoutRunnable);
                        importTimeoutRunnable = null;
                    }
                }
                try {
                    if (importedProjectProfile != null) {
                        profile = importedProjectProfile;
                        profile.applyTo(config);
                        projectStore.saveRecipe(config);
                    }
                    applyLoadedModel(combined, pristineCombined, projectName, importedUris, importedNames,
                            scale, rotation, tiltX, tiltY, restoreTransform, restoreGeometryRepair);
                    if (!restoreTransform && importedModelStore != null)
                        importedModelStore.remember(getFilesDir(), importedUris, importedNames);
                    // A new import is a visual handoff as much as it is a
                    // preparation event. Land on the object-first surface so
                    // a supplied box, part, assembly or CAD conversion is
                    // immediately inspectable; restored projects still open
                    // in the normal workspace to preserve their task context.
                    if (!restoreTransform && !openingPrivateA1Study) mainHandler.postDelayed(() -> {
                        if (!isFinishing() && model != null && !importing && !slicing && !batchSlicing)
                            showImmersiveView();
                    }, 120L);
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "Import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    refreshActions();
                }
            });
            } catch (CancellationException ignored) {
                // Cancellation is an expected user action; no error toast is needed.
            } catch (Exception error) {
                mainHandler.post(() -> {
                    synchronized (MainActivity.this) {
                        if (importId != activeImportId || !importing || isFinishing()) return;
                        importing = false;
                        activeImport = null;
                        if (importTimeoutRunnable != null) {
                            mainHandler.removeCallbacks(importTimeoutRunnable);
                            importTimeoutRunnable = null;
                        }
                    }
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    pruneModelCache();
                    refreshActions();
                    if (restoreTransform && model == null) {
                        // Keep the saved URI/project intact for a later retry,
                        // but never strand a phone-only session on an empty
                        // workspace because a provider document disappeared.
                        loadShowcaseWithoutReplacingSavedProject();
                        status.setText("Prepare  ·  saved model needs review");
                        // The workspace itself is already the recovery affordance.
                        // Keep the explanation in the durable status line so a
                        // stale provider URI cannot cover the first useful 3D
                        // frame with a transient warning surface.
                    } else {
                        Toast.makeText(MainActivity.this, "Import failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** Turn CAD sources into the mesh format consumed by the renderer/project store. */
    private ModelStore.Materialized renderableMaterialized(ModelStore.Materialized materialized,
                                                            String displayName,
                                                            Slicer.Config importConfig) throws IOException {
        if (materialized == null) throw new IOException("Imported model is missing");
        if (!".step".equals(materialized.extension)) return materialized;
        return NativeStepImporter.convertTo3mf(getFilesDir(), materialized, importConfig, displayName);
    }

    private static byte[] mergeProjectSettings(byte[] existing, byte[] candidate) throws IOException {
        if (candidate == null) return existing;
        if (existing != null && !Arrays.equals(existing, candidate))
            throw new IOException("Select one Bambu 3MF project when embedded recipes differ");
        return candidate;
    }

    private synchronized void cancelImport() {
        activeImportId = importIds.incrementAndGet();
        if (importTimeoutRunnable != null) {
            mainHandler.removeCallbacks(importTimeoutRunnable);
            importTimeoutRunnable = null;
        }
        if (activeImport != null) {
            activeImport.cancel(true);
            activeImport = null;
        }
        if (!importing) return;
        importing = false;
        status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        refreshActions();
    }

    private void showModelLibrary() {
        if (printerBusy || slicing || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ArrayList<ModelCatalog.Entry> entries = ModelCatalog.load(getAssets());
            final Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setBackgroundColor(BG);
            page.setPadding(dp(14), dp(12), dp(14), dp(12));

            TextView eyebrow = label("MODEL ATLAS", 11, MUTED);
            eyebrow.setLetterSpacing(0.16f);
            page.addView(eyebrow, new LinearLayout.LayoutParams(-1, dp(28)));
            TextView title = label("See the object before you slice it", 22, TEXT);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            page.addView(title, new LinearLayout.LayoutParams(-1, dp(42)));
            boolean hasOwnerVisuals = false;
            for (ModelCatalog.Entry entry : entries) {
                if (entry.author.toLowerCase(Locale.US).contains("owner-provided")) {
                    hasOwnerVisuals = true;
                    break;
                }
            }
            TextView intro = label(hasOwnerVisuals
                    ? "Every catalog item is a live 3D preview. This private visual-review build includes the supplied owner models; open the A1 Mini study or bring another box, bow or part into the same immersive surface."
                    : "Every catalog item is a live 3D preview. This ordinary/public build contains Alloy-owned examples; the supplied owner models stay out until you use the private visual-review build or import them from the phone.", 12, MUTED);
            intro.setLineSpacing(2, 1.0f);
            page.addView(intro, new LinearLayout.LayoutParams(-1, dp(58)));

            // Make the supplied/user-owned geometry path obvious at the point
            // where a person is looking for models. The catalog remains
            // license-audited, while imported STL/OBJ/3MF files go through
            // the normal content-addressed cache and the same live renderer.
            HorizontalScrollView quickActionsScroll = new HorizontalScrollView(this);
            quickActionsScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout quickActions = new LinearLayout(this);
            quickActions.setOrientation(LinearLayout.HORIZONTAL);
            Button import3d = dialogButton("Import 3D file", v -> {
                dialog.dismiss();
                openModel();
            });
            quickActions.addView(import3d, new LinearLayout.LayoutParams(-2, dp(46)));
            Button create3d = dialogButton("Create / edit", v -> {
                dialog.dismiss();
                showModelWorkbench();
            });
            LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(-2, dp(46));
            createLp.leftMargin = dp(8);
            quickActions.addView(create3d, createLp);
            Button printer3d = dialogButton("A1 Mini study", v -> {
                dialog.dismiss();
                showPrinterStudy();
            });
            LinearLayout.LayoutParams printerLp = new LinearLayout.LayoutParams(-2, dp(46));
            printerLp.leftMargin = dp(8);
            quickActions.addView(printer3d, printerLp);
            quickActionsScroll.addView(quickActions, new HorizontalScrollView.LayoutParams(-2, dp(46)));
            page.addView(quickActionsScroll, new LinearLayout.LayoutParams(-1, dp(54)));

            TextView recentTitle = label("RECENT ON THIS PHONE", 10, MUTED);
            recentTitle.setLetterSpacing(0.12f);
            recentTitle.setPadding(dp(2), dp(8), 0, 0);
            page.addView(recentTitle, new LinearLayout.LayoutParams(-1, dp(30)));
            HorizontalScrollView recentScroll = new HorizontalScrollView(this);
            recentScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout recent = new LinearLayout(this);
            recent.setOrientation(LinearLayout.HORIZONTAL);
            recent.setPadding(0, 0, dp(4), dp(6));
            ArrayList<ImportedModelStore.Entry> recentEntries = importedModelStore == null
                    ? new ArrayList<>() : importedModelStore.entries(getFilesDir());
            if (recentEntries.isEmpty()) {
                TextView emptyRecent = label("Import a box, bow part, STEP or 3MF and it will stay here for offline reopen.", 11, MUTED);
                emptyRecent.setGravity(Gravity.CENTER_VERTICAL);
                recent.addView(emptyRecent, new LinearLayout.LayoutParams(-1, dp(42)));
            } else {
                for (ImportedModelStore.Entry entry : recentEntries) {
                    Button recentChip = dialogButton(galleryShortName(entry.name), null);
                    recentChip.setTextSize(11);
                    recentChip.setMinWidth(dp(126));
                    recentChip.setContentDescription("Open imported model " + entry.name);
                    recentChip.setOnClickListener(v -> {
                        dialog.dismiss();
                        loadUri(entry.uri, false);
                    });
                    LinearLayout.LayoutParams recentLp = new LinearLayout.LayoutParams(-2, dp(42));
                    if (recent.getChildCount() > 0) recentLp.leftMargin = dp(6);
                    recent.addView(recentChip, recentLp);
                }
            }
            recentScroll.addView(recent, new HorizontalScrollView.LayoutParams(-2, dp(48)));
            page.addView(recentScroll, new LinearLayout.LayoutParams(-1, dp(52)));

            HorizontalScrollView chooserScroll = new HorizontalScrollView(this);
            chooserScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout chooser = new LinearLayout(this);
            chooser.setOrientation(LinearLayout.HORIZONTAL);
            chooser.setPadding(0, dp(2), dp(4), dp(8));
            chooserScroll.addView(chooser, new HorizontalScrollView.LayoutParams(-2, dp(56)));
            page.addView(chooserScroll, new LinearLayout.LayoutParams(-1, dp(58)));

            FrameLayout stage = new FrameLayout(this);
            stage.setBackground(round(SURFACE, Color.rgb(226, 222, 213), 2, 28));
            stage.setClipToOutline(true);
            ViewportView preview = new ViewportView(this);
            preview.setPresentationMode(false);
            preview.setCleanPresentation(true);
            stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            addPresentationAppearanceControls(stage, preview, dp(14));
            stage.addView(sceneTag("◇", "STUDY", "LIVE MODEL", Gravity.TOP | Gravity.START, dp(16), dp(18)));
            stage.addView(sceneTag("01", "PLATE", "EXAMPLE", Gravity.BOTTOM | Gravity.START, dp(22), dp(18)));
            page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1f));

            TextView selectedName = label("", 16, TEXT);
            selectedName.setTypeface(null, android.graphics.Typeface.BOLD);
            selectedName.setPadding(dp(2), dp(10), dp(2), 0);
            page.addView(selectedName, new LinearLayout.LayoutParams(-1, dp(34)));
            TextView selectedProvenance = label("", 11, MUTED);
            selectedProvenance.setLineSpacing(1, 1.0f);
            page.addView(selectedProvenance, new LinearLayout.LayoutParams(-1, dp(46)));

            int initialIndex = 0;
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).name.toLowerCase(Locale.US).contains("assembly")) {
                    initialIndex = index;
                    break;
                }
            }
            final ModelCatalog.Entry[] selected = new ModelCatalog.Entry[]{entries.get(initialIndex)};
            for (int index = 0; index < entries.size(); index++) {
                ModelCatalog.Entry entry = entries.get(index);
                Button chip = dialogButton(galleryShortName(entry.name), null);
                chip.setTextSize(11);
                chip.setMinWidth(dp(108));
                LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(-2, dp(46));
                if (index > 0) chipLp.leftMargin = dp(6);
                chooser.addView(chip, chipLp);
                final ModelCatalog.Entry chosen = entry;
                chip.setOnClickListener(v -> {
                    try {
                        ModelCatalog.verify(getAssets(), chosen);
                        try (InputStream input = getAssets().open(chosen.assetPath)) {
                            MeshModel parsed = MeshModel.read(chosen.name, input);
                            selected[0] = chosen;
                            preview.setModel(parsed);
                            selectedName.setText(chosen.name);
                            selectedProvenance.setText(chosen.provenanceLabel() + "  ·  checksum verified");
                        }
                    } catch (Exception error) {
                        Toast.makeText(this, "Example rejected: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            }

            LinearLayout footer = new LinearLayout(this);
            footer.setGravity(Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(8), 0, 0);
            Button community = dialogButton("Community sources", v -> {
                dialog.dismiss();
                showCommunityModelSources();
            });
            footer.addView(community, new LinearLayout.LayoutParams(0, dp(50), 1f));
            Button open = dialogButton("Use & open 3D", v -> {
                dialog.dismiss();
                loadAssetModel(selected[0].assetPath, selected[0].name);
            });
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(-2, dp(50));
            openLp.leftMargin = dp(8);
            footer.addView(open, openLp);
            Button close = dialogButton("Done", v -> dialog.dismiss());
            LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(-2, dp(50));
            closeLp.leftMargin = dp(8);
            footer.addView(close, closeLp);
            page.addView(footer, new LinearLayout.LayoutParams(-1, dp(58)));

            // Load only the first preview up front; the remaining surfaces are
            // parsed when selected, keeping the phone's initial memory cost
            // bounded while making every catalog entry visible in the atlas.
            try {
                ModelCatalog.Entry first = entries.get(initialIndex);
                ModelCatalog.verify(getAssets(), first);
                try (InputStream input = getAssets().open(first.assetPath)) {
                    preview.setModel(MeshModel.read(first.name, input));
                }
                selectedName.setText(first.name);
                selectedProvenance.setText(first.provenanceLabel() + "  ·  checksum verified");
            } catch (Exception error) {
                selectedName.setText("No preview available");
                selectedProvenance.setText(error.getMessage());
            }
            dialog.setContentView(page);
            dialog.setOnDismissListener(ignored -> preview.onHostPause());
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.setLayout(-1, -1);
            }
        } catch (Exception error) {
            Toast.makeText(this, "Model library unavailable: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String galleryShortName(String value) {
        if (value == null || value.trim().length() == 0) return "Model";
        int separator = value.indexOf(" · ");
        String shortName = separator > 0 ? value.substring(0, separator) : value;
        return shortName.length() > 18 ? shortName.substring(0, 18) + "…" : shortName;
    }

    private void showCommunityModelSources() {
        List<CommunityModelCatalog.Entry> entries = CommunityModelCatalog.entries();
        String[] labels = new String[entries.size()];
        for (int index = 0; index < entries.size(); index++) labels[index] = entries.get(index).summary();
        new AlertDialog.Builder(this)
                .setTitle("Community model sources")
                .setMessage("Open the original page to download a model. Alloy does not mirror these files; the source page remains the authority for license, attribution and revision terms.")
                .setItems(labels, (dialog, which) -> {
                    CommunityModelCatalog.Entry entry = entries.get(which);
                    new AlertDialog.Builder(this)
                            .setTitle(entry.name)
                            .setMessage(entry.details())
                            .setNegativeButton("Back", null)
                            .setPositiveButton("Open source", (info, open) -> {
                                try {
                                    startActivity(entry.browserIntent());
                                } catch (Exception error) {
                                    Toast.makeText(this, "No browser is available for this source", Toast.LENGTH_LONG).show();
                                }
                            })
                            .show();
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /** Open the bounded primitive/assembly workbench. */
    private void showModelWorkbench() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring || visualizing || modeling) {
            Toast.makeText(this, "Finish the current operation before modeling", Toast.LENGTH_SHORT).show();
            return;
        }
        ModelWorkbench.Primitive[] primitives = ModelWorkbench.Primitive.values();
        int customIndex = primitives.length;
        int booleanIndex = primitives.length + 1;
        int arrayIndex = model == null ? -1 : primitives.length + 2;
        int mirrorIndex = model == null ? -1 : primitives.length + 3;
        int layFlatIndex = model == null ? -1 : primitives.length + 4;
        int autoOrientIndex = model == null ? -1 : primitives.length + 5;
        String[] labels = new String[primitives.length + 2 + (model == null ? 0 : 4)];
        for (int index = 0; index < primitives.length; index++) labels[index] = primitives[index].label;
        labels[customIndex] = "Custom sketch  ·  extrude";
        labels[booleanIndex] = "Boolean with primitive  ·  native OCCT";
        if (arrayIndex >= 0) {
            labels[arrayIndex] = "Duplicate current model  ·  array";
            labels[mirrorIndex] = "Mirror current model  ·  X / Y";
            labels[layFlatIndex] = "Lay flat  ·  largest face";
            labels[autoOrientIndex] = "Auto orient  ·  axis-aligned";
        }
        final int[] selectedPrimitive = new int[]{0};
        new AlertDialog.Builder(this)
                .setTitle("Model workbench")
                .setMessage("Create a printable primitive, extrude a convex sketch, or use exact native union/subtract/intersect editing. Dimensions are bounded to the " + profilePrinterLabel() + " build volume.")
                .setSingleChoiceItems(labels, 0, (dialog, which) -> selectedPrimitive[0] = which)
                .setNegativeButton("Close", null)
                .setPositiveButton("Continue", (dialog, which) -> {
                    if (selectedPrimitive[0] == customIndex) showSketchExtrusion();
                    else if (selectedPrimitive[0] == booleanIndex) showBooleanOperation();
                    else if (selectedPrimitive[0] == arrayIndex) showModelArray();
                    else if (selectedPrimitive[0] == mirrorIndex) showMirrorModel();
                    else if (selectedPrimitive[0] == layFlatIndex) layCurrentModelFlat();
                    else if (selectedPrimitive[0] == autoOrientIndex) autoOrientCurrentModel();
                    else showPrimitiveDimensions(primitives[Math.max(0, selectedPrimitive[0])]);
                })
                .show();
    }

    private void layCurrentModelFlat() {
        if (model == null) {
            Toast.makeText(this, "Import or create a model before laying it flat", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MeshModel flattened = model.layFlat(model.displayName + " · largest face on bed");
            installGeneratedModel(flattened, flattened.displayName);
            Toast.makeText(this, "Largest face placed on the build plate; review orientation before slicing", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Model could not be laid flat: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void autoOrientCurrentModel() {
        if (model == null) {
            Toast.makeText(this, "Import or create a model before auto-orienting it", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MeshModel oriented = model.autoOrient(model.displayName + " · auto-oriented");
            installGeneratedModel(oriented, oriented.displayName);
            Toast.makeText(this, "Best bounded axis-aligned orientation applied; review the bed and supports before slicing", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Model could not be auto-oriented: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showMirrorModel() {
        if (model == null) {
            Toast.makeText(this, "Import or create a model before mirroring it", Toast.LENGTH_SHORT).show();
            return;
        }
        RadioGroup axes = new RadioGroup(this);
        axes.setPadding(28, 4, 28, 0);
        RadioButton x = new RadioButton(this);
        x.setId(View.generateViewId());
        x.setText("Mirror across X centreline"); x.setTextColor(TEXT); x.setTag(Boolean.TRUE);
        RadioButton y = new RadioButton(this);
        y.setId(View.generateViewId());
        y.setText("Mirror across Y centreline"); y.setTextColor(TEXT); y.setTag(Boolean.FALSE);
        axes.addView(x); axes.addView(y); axes.check(x.getId());
        TextView note = label("Mirrors the current printable mesh around the centre of its footprint, preserves named parts, and keeps the same bounds and bed placement. This is a geometry edit and can be undone from History.", 12, MUTED);
        note.setPadding(28, 14, 28, 0);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL); content.addView(axes); content.addView(note);
        new AlertDialog.Builder(this).setTitle("Mirror current model").setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Mirror", (dialog, which) -> {
                    try {
                        boolean xAxis = Boolean.TRUE.equals(axes.findViewById(axes.getCheckedRadioButtonId()).getTag());
                        String axis = xAxis ? "X" : "Y";
                        MeshModel mirrored = ModelWorkbench.mirror(model.displayName + " · mirror " + axis, model, xAxis);
                        installGeneratedModel(mirrored, model.displayName + " · mirror " + axis);
                    } catch (Exception error) {
                        Toast.makeText(this, "Mirror could not be created: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void showModelArray() {
        if (model == null) {
            Toast.makeText(this, "Import or create a model before duplicating it", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(28, 4, 28, 0);
        EditText copies = field("2", "Copies (2–32)");
        fields.addView(copies);
        TextView note = label("Creates repeated copies with a deterministic 5 mm clearance shelf pack. The result remains an editable named-part assembly and must fit the " + profileBuildVolumeLabel() + " " + profilePrinterLabel() + " envelope.", 12, MUTED);
        note.setPadding(0, 14, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this).setTitle("Duplicate current model").setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create array", (dialog, which) -> {
                    try {
                        int count = Math.round(clamp(Float.parseFloat(copies.getText().toString()), 2f, 32f));
                        MeshModel generated = ModelWorkbench.createArray(model.displayName + " · array", model, count);
                        installGeneratedModel(generated, model.displayName + " · " + count + " copies");
                    } catch (Exception error) {
                        Toast.makeText(this, "Array could not be created: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    /** Configure a native exact-solid operation using the current model as the first operand. */
    private void showBooleanOperation() {
        if (!BuildConfig.NATIVE_ENGINE_ENABLED) {
            explainUnsupported("Exact solid modeling");
            return;
        }
        if (model == null) {
            Toast.makeText(this, "Import or create a model before boolean editing", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(28, 4, 28, 0);

        TextView operationLabel = label("OPERATION", 10, GOLD);
        operationLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        fields.addView(operationLabel);
        RadioGroup operations = new RadioGroup(this);
        String[] operationNames = {"Union  ·  add primitive", "Subtract  ·  cut primitive", "Intersect  ·  keep overlap"};
        for (int index = 0; index < operationNames.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(operationNames[index]); option.setTextColor(TEXT); option.setTag(index);
            operations.addView(option);
        }
        operations.check(operations.getChildAt(NativeGeometry.CUT).getId());
        fields.addView(operations);

        TextView primitiveLabel = label("TOOL PRIMITIVE", 10, GOLD);
        primitiveLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        primitiveLabel.setPadding(0, 12, 0, 0); fields.addView(primitiveLabel);
        RadioGroup primitives = new RadioGroup(this);
        ModelWorkbench.Primitive[] available = ModelWorkbench.Primitive.values();
        for (int index = 0; index < available.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(available[index].label); option.setTextColor(TEXT); option.setTag(index);
            primitives.addView(option);
        }
        primitives.check(primitives.getChildAt(0).getId());
        fields.addView(primitives);

        EditText width = field("30", "Width / outer diameter (mm)");
        EditText depth = field("30", "Depth / outer diameter (mm)");
        EditText height = field("20", "Height (mm)");
        fields.addView(width); fields.addView(depth); fields.addView(height);
        TextView note = label("Uses the bundled OCCT solid kernel. Both inputs must be one watertight solid; the result is revalidated, cached and returned to the normal 3D/slicing path. Native runtime required.", 12, MUTED);
        note.setPadding(0, 14, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this).setTitle("Exact solid edit").setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        int operation = (Integer) operations.findViewById(operations.getCheckedRadioButtonId()).getTag();
                        int primitiveIndex = (Integer) primitives.findViewById(primitives.getCheckedRadioButtonId()).getTag();
                        float w = clamp(Float.parseFloat(width.getText().toString()), 0.5f, 180f);
                        float d = clamp(Float.parseFloat(depth.getText().toString()), 0.5f, 180f);
                        float h = clamp(Float.parseFloat(height.getText().toString()), 0.5f, 180f);
                        startNativeBoolean(operation, available[primitiveIndex], w, d, h);
                    } catch (Exception error) {
                        Toast.makeText(this, "Boolean edit could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void startNativeBoolean(int operation, ModelWorkbench.Primitive primitive,
                                    float width, float depth, float height) {
        if (model == null || primitive == null) return;
        final MeshModel source = model;
        final String sourceName = model.displayName;
        prepareModelMutation("Boolean  ·  " + NativeGeometry.label(operation));
        final long id = modelingIds.incrementAndGet();
        activeModelingId = id; modeling = true;
        status.setText("Model  ·  " + NativeGeometry.label(operation).toLowerCase(Locale.US) + "…");
        refreshActions();
        activeModeling = importExecutor.submit(() -> {
            try {
                if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Modeling cancelled");
                ModelStore.Materialized first = ModelStore.materializeGenerated(getFilesDir(),
                        ModelWorkbench.toBinaryStl(source, 200_000));
                MeshModel toolMesh = ModelWorkbench.create(primitive, "Boolean tool", width, depth, height);
                ModelStore.Materialized second = ModelStore.materializeGenerated(getFilesDir(),
                        ModelWorkbench.toBinaryStl(toolMesh));
                ModelStore.Materialized resultFile = NativeGeometry.apply(getFilesDir(), first.file, second.file, operation);
                MeshModel result;
                try (InputStream input = new java.io.FileInputStream(resultFile.file)) {
                    result = MeshModel.read(ModelStore.parserName("Boolean result", resultFile.extension), input);
                }
                final MeshModel resultModel = result;
                final String resultName = sourceName + "  ·  " + NativeGeometry.label(operation).toLowerCase(Locale.US);
                mainHandler.post(() -> {
                    if (id != activeModelingId || isFinishing()) return;
                    modeling = false; activeModeling = null;
                    try {
                        ArrayList<Uri> uris = new ArrayList<>(); uris.add(resultFile.uri);
                        ArrayList<String> names = new ArrayList<>(); names.add(resultName);
                        applyLoadedModel(resultModel, resultModel, resultName, uris, names,
                                1f, 0f, 0f, 0f, false, false, HISTORY_MUTATION);
                        finishModelMutation("Boolean  ·  " + NativeGeometry.label(operation));
                        status.setText("Prepare  ·  " + resultName);
                    } catch (Exception error) {
                        status.setText("Prepare  ·  " + sourceName);
                        Toast.makeText(this, "Boolean result could not be opened: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        refreshActions();
                    }
                });
            } catch (java.util.concurrent.CancellationException ignored) {
                // Cancellation is expected; stale completion is suppressed by the operation id.
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (id != activeModelingId || isFinishing()) return;
                    modeling = false; activeModeling = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Boolean edit failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private synchronized void cancelNativeBoolean() {
        activeModelingId = modelingIds.incrementAndGet();
        if (activeModeling != null) { activeModeling.cancel(true); activeModeling = null; }
        if (!modeling) return;
        modeling = false;
        status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        refreshActions();
    }

    private void showSketchExtrusion() {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(28, 4, 28, 0);
        EditText sketch = textField("-20,-15\n20,-15\n20,15\n-20,15", "One x,y point per line");
        sketch.setMinLines(4); sketch.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        EditText height = field("25", "Extrusion height (mm)");
        CheckBox addToAssembly = new CheckBox(this);
        addToAssembly.setText("Add to current assembly"); addToAssembly.setTextColor(TEXT);
        addToAssembly.setChecked(model != null); addToAssembly.setEnabled(model != null);
        fields.addView(sketch); fields.addView(height); fields.addView(addToAssembly);
        TextView note = label("Convex, consistently wound sketches only · 3–16 points · millimetres. The result is watertight and uses the same repair, cache, project and slicing gates as imported geometry.", 12, MUTED);
        note.setPadding(0, 14, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this).setTitle("Extrude sketch").setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    try {
                        float[][] points = parseSketchPoints(sketch.getText().toString());
                        float h = clamp(Float.parseFloat(height.getText().toString()), 0.5f, 180f);
                        MeshModel generated = ModelWorkbench.createExtrudedPolygon("Sketch extrusion", points, h);
                        MeshModel result = generated; String name = "Sketch extrusion  ·  " + points.length + " points";
                        if (addToAssembly.isChecked() && model != null) {
                            ArrayList<MeshModel> sources = new ArrayList<>(); sources.add(model); sources.add(generated);
                            result = MeshModel.combine("Assembly", sources); name = "Assembly  ·  " + model.displayName;
                        }
                        installGeneratedModel(result, name);
                    } catch (Exception error) {
                        Toast.makeText(this, "Sketch could not be created: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private static float[][] parseSketchPoints(String text) throws IOException {
        if (text == null) throw new IOException("Sketch is empty");
        String[] rows = text.trim().split("(?:\\r?\\n|;)+");
        ArrayList<float[]> points = new ArrayList<>();
        for (String row : rows) {
            String[] pair = row.trim().split("[,\\s]+", 3);
            if (pair.length != 2) throw new IOException("Each sketch row must be x,y");
            try { points.add(new float[]{Float.parseFloat(pair[0]), Float.parseFloat(pair[1])}); }
            catch (NumberFormatException error) { throw new IOException("Sketch coordinates must be numbers", error); }
        }
        return points.toArray(new float[0][]);
    }

    private void showPrimitiveDimensions(ModelWorkbench.Primitive primitive) {
        if (primitive == null) return;
        float defaultWidth = primitive == ModelWorkbench.Primitive.CYLINDER || primitive == ModelWorkbench.Primitive.SPHERE ? 30f : 40f;
        float defaultDepth = primitive == ModelWorkbench.Primitive.SPHERE ? 30f : 40f;
        float defaultHeight = primitive == ModelWorkbench.Primitive.SPHERE ? 30f : 25f;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        EditText width = field(String.format(Locale.US, "%.1f", defaultWidth), "Width / outer diameter (mm)");
        EditText depth = field(String.format(Locale.US, "%.1f", defaultDepth), "Depth / outer diameter (mm)");
        EditText height = field(String.format(Locale.US, "%.1f", defaultHeight), "Height (mm)");
        fields.addView(width); fields.addView(depth); fields.addView(height);
        EditText bevel = null;
        EditText wall = null;
        EditText bottom = null;
        if (primitive == ModelWorkbench.Primitive.CHAMFERED_BOX) {
            bevel = field("4.8", "Corner chamfer (mm; max 24% of smaller side)");
            fields.addView(bevel);
        } else if (primitive == ModelWorkbench.Primitive.HOLLOW_BOX) {
            wall = field("2.0", "Wall thickness (mm; min 0.5)");
            bottom = field("2.0", "Floor thickness (mm; min 0.5)");
            fields.addView(wall); fields.addView(bottom);
        }
        final EditText bevelField = bevel;
        final EditText wallField = wall;
        final EditText bottomField = bottom;
        CheckBox addToAssembly = new CheckBox(this);
        addToAssembly.setText("Add to current assembly");
        addToAssembly.setTextColor(TEXT);
        addToAssembly.setChecked(model != null);
        addToAssembly.setEnabled(model != null);
        fields.addView(addToAssembly);
        TextView note = label(primitive == ModelWorkbench.Primitive.TUBE
                        ? "Tube uses a 55% inner-radius opening. Boolean cuts, fillets and freeform sculpting remain future workbench gates."
                        : primitive == ModelWorkbench.Primitive.CHAMFERED_BOX
                        ? "A deterministic eight-sided enclosure profile with a bounded, printable corner chamfer. Use native Boolean editing for openings and cut-outs."
                        : primitive == ModelWorkbench.Primitive.HOLLOW_BOX
                        ? "A watertight open-top enclosure with a printable floor, interior cavity and explicit wall thickness."
                        : "All dimensions are millimetres. The result is validated, cached as STL, and can be sliced like an imported model.",
                12, MUTED);
        note.setPadding(0, 14, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Create " + primitive.label.toLowerCase(Locale.US))
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    try {
                        float w = clamp(Float.parseFloat(width.getText().toString()), 0.5f, 180f);
                        float d = clamp(Float.parseFloat(depth.getText().toString()), 0.5f, 180f);
                        float h = clamp(Float.parseFloat(height.getText().toString()), 0.5f, 180f);
                        MeshModel generated;
                        if (primitive == ModelWorkbench.Primitive.CHAMFERED_BOX) {
                            float maxBevel = Math.min(w, d) * 0.24f;
                            float b = clamp(Float.parseFloat(bevelField.getText().toString()), 0.5f, maxBevel);
                            generated = ModelWorkbench.createChamferedBox(primitive.label, w, d, h, b);
                        } else if (primitive == ModelWorkbench.Primitive.HOLLOW_BOX) {
                            float wallThickness = Float.parseFloat(wallField.getText().toString());
                            float floorThickness = Float.parseFloat(bottomField.getText().toString());
                            generated = ModelWorkbench.createHollowBox(primitive.label, w, d, h,
                                    wallThickness, floorThickness);
                        } else {
                            generated = ModelWorkbench.create(primitive, primitive.label, w, d, h);
                        }
                        MeshModel result = generated;
                        String name = primitive.label + "  ·  " + String.format(Locale.US, "%.0f × %.0f × %.0f mm", w, d, h);
                        if (addToAssembly.isChecked() && model != null) {
                            ArrayList<MeshModel> sources = new ArrayList<>();
                            sources.add(model); sources.add(generated);
                            result = MeshModel.combine("Assembly", sources);
                            name = "Assembly  ·  " + model.displayName;
                        }
                        installGeneratedModel(result, name);
                    } catch (Exception error) {
                        Toast.makeText(this, "Primitive could not be created: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private void installGeneratedModel(MeshModel generated, String name) throws Exception {
        boolean mutation = model != null;
        if (mutation) prepareModelMutation("Create  ·  " + name);
        byte[] stl = ModelWorkbench.toBinaryStl(generated);
        ModelStore.Materialized materialized = ModelStore.materializeGenerated(getFilesDir(), stl);
        try (InputStream input = getContentResolver().openInputStream(materialized.uri)) {
            if (input == null) throw new IOException("Generated model cache could not be opened");
            MeshModel parsed = MeshModel.read(ModelStore.parserName(name, materialized.extension), input);
            ArrayList<Uri> uris = new ArrayList<>(); uris.add(materialized.uri);
            ArrayList<String> names = new ArrayList<>(); names.add(name);
            applyLoadedModel(parsed, parsed, name, uris, names, 1f, 0f, 0f, 0f, false, false,
                    mutation ? HISTORY_MUTATION : HISTORY_RESET);
            if (mutation) finishModelMutation("Create  ·  " + name);
        }
    }

    /** Let the user preview a bounded finish and environment locally without misrepresenting it as generative AI. */
    private void showOnDevicePreview(byte[] thumbnail) {
        StudioPreviewRenderer.Finish[] finishes = StudioPreviewRenderer.Finish.values();
        String[] labels = new String[finishes.length];
        for (int index = 0; index < finishes.length; index++) labels[index] = finishes[index].label;
        new AlertDialog.Builder(this)
                .setTitle("Local finish preview")
                .setMessage("Choose a presentation finish. This stays on the phone and does not change printable geometry or material settings.")
                .setItems(labels, (dialog, which) -> showLocalEnvironmentPreview(thumbnail, finishes[which]))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showLocalEnvironmentPreview(byte[] thumbnail, StudioPreviewRenderer.Finish finish) {
        StudioPreviewRenderer.Environment[] environments = StudioPreviewRenderer.Environment.values();
        String[] labels = new String[environments.length];
        for (int index = 0; index < environments.length; index++) labels[index] = environments[index].label;
        new AlertDialog.Builder(this)
                .setTitle("Local environment")
                .setMessage("Choose the scene framing for the concept preview.")
                .setItems(labels, (dialog, which) -> showStyledLocalPreview(thumbnail, finish, environments[which]))
                .setNegativeButton("Back", null)
                .show();
    }

    private void showStyledLocalPreview(byte[] thumbnail, StudioPreviewRenderer.Finish finish,
                                        StudioPreviewRenderer.Environment environment) {
        try {
            VisualizationProvider.Result result = new OnDeviceVisualizationProvider(getFilesDir()).generate(
                    new VisualizationProvider.Request(thumbnail, finish.label + " in a " + environment.label + " scene"));
            Bitmap bitmap = BitmapFactory.decodeByteArray(result.imagePng, 0, result.imagePng.length);
            if (bitmap == null) throw new IOException("On-device preview returned an unsupported image");
            ImageView image = new ImageView(this);
            image.setAdjustViewBounds(true); image.setPadding(12, 12, 12, 12); image.setImageBitmap(bitmap);
            pendingVisualizationPng = result.imagePng.clone();
            new AlertDialog.Builder(this).setTitle("On-device studio preview")
                    .setMessage(result.providerLabel + " · " + finish.label + " · " + environment.label
                            + "\nGeometry, recipe and printer commands remain unchanged. This is not a generative AI result.")
                    .setView(image).setNegativeButton("Done", null)
                    .setPositiveButton("Save PNG", (dialog, which) -> openVisualizationExport()).show();
        } catch (Exception error) {
            Toast.makeText(this, "The local preview could not be rendered: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showVisualization() {
        if (model == null || viewport == null) {
            Toast.makeText(this, "Create or import a model before visualizing it", Toast.LENGTH_SHORT).show();
            return;
        }
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring || visualizing || modeling) {
            Toast.makeText(this, "Finish the current operation before visualizing", Toast.LENGTH_SHORT).show();
            return;
        }
        final byte[] thumbnail = viewport.thumbnailPng(768);
        if (thumbnail == null) {
            Toast.makeText(this, "The 3D workspace is not ready for a reference image", Toast.LENGTH_SHORT).show();
            return;
        }
        VisualizationCredentialStore.Credentials current = null;
        try { current = visualizationCredentialStore.load(); } catch (Exception ignored) { }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(28, 4, 28, 0);
        EditText endpoint = textField(current == null ? "" : current.endpoint, "HTTPS image-edit endpoint");
        EditText modelName = textField(current == null ? "" : current.model, "Cloud model name");
        EditText apiKey = textField("", "BYOK API key (blank keeps saved key)");
        apiKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String dimensions = String.format(Locale.US, "%.1f × %.1f × %.1f mm", model.maxX - model.minX, model.maxY - model.minY, model.maxZ - model.minZ);
        EditText prompt = textField("Show this 3D-printed model painted in a premium workshop environment; preserve its shape and proportions.", "Visualization prompt");
        fields.addView(endpoint); fields.addView(modelName); fields.addView(apiKey); fields.addView(prompt);
        TextView note = label("On-device visualization: " + new OnDeviceVisualizationProvider(getFilesDir()).availabilityLabel()
                + "\nBYOK sends only this bounded rendered thumbnail and your prompt to your HTTPS endpoint; it does not upload the STL, project, printer credentials or inventory.", 12, MUTED);
        note.setPadding(0, 14, 0, 0); fields.addView(note);
        new AlertDialog.Builder(this).setTitle("Visualize model")
                .setMessage("Model size: " + dimensions + "\nUse presets such as painted, assembled, workshop, installed, or product-photo.")
                .setView(fields)
                .setNeutralButton("On-device preview", (dialog, which) -> showOnDevicePreview(thumbnail))
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Visualize with BYOK", (dialog, which) -> {
                    try {
                        VisualizationCredentialStore.Credentials saved = visualizationCredentialStore.load();
                        String key = apiKey.getText().toString().trim();
                        if (key.length() == 0 && saved != null) key = saved.apiKey;
                        VisualizationCredentialStore.Credentials credentials = new VisualizationCredentialStore.Credentials(
                                endpoint.getText().toString(), modelName.getText().toString(), key);
                        visualizationCredentialStore.save(credentials);
                        startByokVisualization(credentials, new VisualizationProvider.Request(thumbnail, prompt.getText().toString()));
                    } catch (Exception error) {
                        Toast.makeText(this, "BYOK visualization could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }).show();
    }

    private void startByokVisualization(VisualizationCredentialStore.Credentials credentials,
                                        VisualizationProvider.Request request) {
        final long id = visualizationIds.incrementAndGet();
        activeVisualizationId = id; visualizing = true;
        status.setText("Visualize  ·  sending bounded reference…"); refreshActions();
        activeVisualization = importExecutor.submit(() -> {
            try {
                VisualizationProvider.Result result = new ByokVisualizationProvider(credentials).generate(request);
                mainHandler.post(() -> {
                    if (id != activeVisualizationId || isFinishing()) return;
                    visualizing = false; activeVisualization = null;
                    status.setText("Visualize  ·  result ready"); refreshActions(); showVisualizationResult(result);
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (id != activeVisualizationId || isFinishing()) return;
                    visualizing = false; activeVisualization = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions(); Toast.makeText(this, "Visualization failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showVisualizationResult(VisualizationProvider.Result result) {
        if (result == null) return;
        Bitmap bitmap = BitmapFactory.decodeByteArray(result.imagePng, 0, result.imagePng.length);
        if (bitmap == null) {
            Toast.makeText(this, "Visualization returned an unsupported image", Toast.LENGTH_LONG).show();
            return;
        }
        ImageView image = new ImageView(this);
        image.setAdjustViewBounds(true); image.setPadding(12, 12, 12, 12); image.setImageBitmap(bitmap);
        ScrollView scroll = new ScrollView(this); scroll.addView(image);
        pendingVisualizationPng = result.imagePng.clone();
        new AlertDialog.Builder(this).setTitle("Visualization ready")
                .setMessage(result.providerLabel + " · reference geometry remains unchanged")
                .setView(scroll).setNegativeButton("Done", null)
                .setPositiveButton("Save PNG", (dialog, which) -> openVisualizationExport()).show();
    }

    private void openVisualizationExport() {
        if (pendingVisualizationPng == null || !VisualizationProvider.isBoundedPng(pendingVisualizationPng)) {
            Toast.makeText(this, "Visualization image is no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, "alloy-visualization.png");
        startActivityForResult(intent, REQUEST_VISUALIZATION_EXPORT);
    }

    private void writeVisualizationExport(Uri uri) {
        byte[] png = pendingVisualizationPng;
        pendingVisualizationPng = null;
        if (png == null || !VisualizationProvider.isBoundedPng(png)) {
            Toast.makeText(this, "Visualization image is no longer available", Toast.LENGTH_SHORT).show();
            return;
        }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
            if (out == null) throw new IOException("Output destination could not be opened");
            out.write(png);
            out.flush();
            Toast.makeText(this, "Saved visualization PNG", Toast.LENGTH_LONG).show();
        } catch (Exception error) {
            Toast.makeText(this, "Visualization export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            java.util.Arrays.fill(png, (byte) 0);
        }
    }

    private synchronized void cancelVisualization() {
        activeVisualizationId = visualizationIds.incrementAndGet();
        if (activeVisualization != null) { activeVisualization.cancel(true); activeVisualization = null; }
        if (!visualizing) return;
        visualizing = false;
        if (status != null) status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        if (actions != null) refreshActions();
    }

    private void loadAssetModel(String assetPath, String name) {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ModelStore.Materialized materialized = ModelStore.materializeAsset(getFilesDir(), getAssets(), assetPath);
            ArrayList<Uri> uris = new ArrayList<>();
            uris.add(materialized.uri);
            ArrayList<String> names = new ArrayList<>();
            names.add(name);
            // Route bundled sources through the same worker-backed import
            // path as phone-selected files. This is important for the private
            // STEP assembly: OCCT tessellation must never block the UI thread.
            loadUris(uris, names, false);
        } catch (Exception e) {
            Toast.makeText(this, "Example failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Optional owner-only startup design, absent from ordinary/public APKs. */
    private ModelCatalog.Entry privateOwnerStartupModel() {
        try {
            ModelCatalog.Entry chassis = null;
            for (ModelCatalog.Entry entry : ModelCatalog.load(getAssets())) {
                // The positioned STEP assembly is the most useful first-run
                // study because it preserves the owner's editable multi-part
                // layout. Keep the single STL as a fallback for builds that
                // deliberately omit the native OCCT importer.
                if (BuildConfig.NATIVE_ENGINE_ENABLED
                        && entry.assetPath.endsWith("redmagic-keyboard-case-assembly-v04-angled.step"))
                    return entry;
                if (entry.assetPath.endsWith("redmagic-keyboard-main-chassis-v04.stl")) chassis = entry;
            }
            return chassis;
        } catch (Exception ignored) {
            // A missing private overlay must not prevent the normal showcase.
        }
        return null;
    }

    /** Asset presence is the stable owner-review switch during first launch. */
    private boolean hasBundledA1Reference() {
        try (InputStream input = getAssets().open("visuals/a1-mini-reference.mesh")) {
            return input.read() >= 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void loadShowcaseModel() {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            MeshModel showcase = ModelWorkbench.createShowcaseBoxAssembly();
            byte[] stl = ModelWorkbench.toBinaryStl(showcase);
            ModelStore.Materialized materialized = ModelStore.materializeGenerated(getFilesDir(), stl);
            ArrayList<Uri> uris = new ArrayList<>(); uris.add(materialized.uri);
            ArrayList<String> names = new ArrayList<>(); names.add("Alloy showcase box");
            // Keep the authored part names for the first-run study view. The
            // generated STL is still materialized for offline persistence and
            // export; reopening a saved project remains parser-backed.
            applyLoadedModel(showcase, showcase, "Alloy showcase box", uris, names,
                    1f, 0f, 0f, 0f, false, false);
        } catch (Exception error) {
            Toast.makeText(this, "Showcase failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** Show a local recovery model without overwriting the user's saved plate. */
    private void loadShowcaseWithoutReplacingSavedProject() {
        ProjectStore.SavedProject savedProject = projectStore.savedProject();
        PlateStore.Plate savedPlate = plateStore.activePlate();
        int savedActivePlate = plateStore.activeIndex();
        ArrayList<ModelHistoryStore.Timeline> savedHistory = modelHistoryStore == null
                ? new ArrayList<>() : modelHistoryStore.exportTimelines();
        loadShowcaseModel();
        try {
            if (savedProject == null) projectStore.clearModel();
            else projectStore.saveModels(savedProject.uris, savedProject.names);
            projectStore.saveTransform(savedPlate.scale, savedPlate.rotationDegrees,
                    savedPlate.tiltXDegrees, savedPlate.tiltYDegrees);
            projectStore.saveSelectedPart(savedPlate.selectedPart);
            projectStore.savePartTransforms(savedPlate.partTransforms);
            projectStore.saveGeometryRepair(savedPlate.geometryRepairEnabled);
            plateStore.save(savedPlate);
            plateStore.setActiveIndex(savedActivePlate);
            if (modelHistoryStore != null) modelHistoryStore.importTimelines(savedHistory);
        } catch (Exception ignored) {
            // The local showcase remains a valid recovery surface even if an
            // older checkpoint is too damaged to restore every sidecar.
        }
    }

    private void applyLoadedModel(MeshModel loaded, MeshModel pristine, String name, ArrayList<Uri> uris,
                                  ArrayList<String> names, float scale, float rotationDegrees,
                                  float tiltXDegrees, float tiltYDegrees, boolean restoreTransform,
                                  boolean repairEnabled) throws Exception {
        applyLoadedModel(loaded, pristine, name, uris, names, scale, rotationDegrees,
                tiltXDegrees, tiltYDegrees, restoreTransform, repairEnabled,
                restoreTransform ? HISTORY_RESTORE : HISTORY_RESET);
    }

    private void applyLoadedModel(MeshModel loaded, MeshModel pristine, String name, ArrayList<Uri> uris,
                                  ArrayList<String> names, float scale, float rotationDegrees,
                                  float tiltXDegrees, float tiltYDegrees, boolean restoreTransform,
                                  boolean repairEnabled, int historyMode) throws Exception {
        // A foreground slice owns an immutable request snapshot. Loading the
        // same project again during Activity recreation must not cancel it;
        // a genuinely new model is blocked by the slicing UI until the job is
        // explicitly cancelled or completed.
        if (activeSliceJobId == null) {
            sliceJobs.cancel();
            slicing = false;
        }
        sourceModel = loaded;
        unmodifiedSourceModel = pristine == null ? loaded : pristine;
        geometryRepairEnabled = repairEnabled;
        modelScale = finite(scale) ? clamp(scale, MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE) : 1f;
        modelRotationDegrees = finite(rotationDegrees) ? clamp(rotationDegrees, -360f, 360f) : 0f;
        modelTiltXDegrees = finite(tiltXDegrees) ? clamp(tiltXDegrees, -360f, 360f) : 0f;
        modelTiltYDegrees = finite(tiltYDegrees) ? clamp(tiltYDegrees, -360f, 360f) : 0f;
        partTransforms.clear();
        modelNames.clear();
        if (names != null) modelNames.addAll(names);
        if (restoreTransform) partTransforms.addAll(projectStore.savedPartTransforms(loaded.parts.length));
        else projectStore.savePartTransforms(partTransforms);
        model = rebuildModel(name);
        modelUris.clear();
        if (uris != null) modelUris.addAll(uris);
        slice = null;
        stagedArtifact = null;
        if (!restoreTransform) lastBatch = null;
        viewport.setModel(model);
        viewport.setSelectedPart(restoreTransform ? projectStore.savedSelectedPart() : -1);
        if (!modelUris.isEmpty()) {
            projectStore.saveModels(modelUris, names);
            projectStore.saveTransform(modelScale, modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees);
            if (!restoreTransform) projectStore.saveSelectedPart(-1);
        }
        plateImportInFlight = false;
        saveCurrentPlate();
        pruneModelCache();
        if (modelHistoryStore != null) {
            if (historyMode == HISTORY_RESET) {
                modelHistoryStore.resetDocument(activePlateIndex, currentPlateSnapshot(), "Opened  ·  " + name);
            } else if (historyMode == HISTORY_RESTORE) {
                modelHistoryStore.ensureCurrent(activePlateIndex, currentPlateSnapshot(), "Recovered plate");
            }
            pruneModelCache();
        }
        boolean restoredBatch = restoreBatchResultForActivePlate(restoreTransform);
        boolean restoredForeground = !restoredBatch && restoreForegroundSliceIfMatching();
        modelMeta.setText(viewportDisplayName(name) + "\n" + profileShortLabel());
        status.setText(restoredBatch || restoredForeground ? "Inspect  ·  " + slice.layers.size() + " layers" : "Prepare  ·  " + name);
        details.setText(restoredBatch || restoredForeground
                ? String.format(Locale.US, "%.0f mm filament  ·  %s  ·  %d warning(s)", slice.filamentMm,
                slice.printTimeSeconds < 0f ? "time pending" : formatDuration(slice.printTimeSeconds), slice.warnings)
                : modelDetails(model));
        refreshActions();
        refreshBatchSliceUi();
    }

    private boolean restoreBatchResultForActivePlate(boolean restoreTransform) {
        if (!restoreTransform || lastBatch == null) return false;
        for (BatchSliceJobController.PlateResult plate : lastBatch.plates) {
            if (plate.plate.index != activePlateIndex) continue;
            model = plate.model;
            slice = plate.slice;
            stagedArtifact = plate.artifact;
            viewport.setModel(model);
            viewport.setResult(slice);
            return true;
        }
        return false;
    }

    private MeshModel rebuildModel(String name) throws Exception {
        if (sourceModel == null) throw new IllegalStateException("No source model is selected");
        MeshModel rebuilt = sourceModel.transformed(name, modelScale, modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees);
        int count = Math.min(partTransforms.size(), rebuilt.parts.length);
        for (int index = 0; index < count; index++) {
            MeshModel.PartTransform transform = partTransforms.get(index);
            if (transform == null) continue;
            if (transform.scale == 1f && transform.rotationDegrees == 0f
                    && transform.tiltXDegrees == 0f && transform.tiltYDegrees == 0f
                    && transform.offsetX == 0f && transform.offsetY == 0f) continue;
            rebuilt = rebuilt.transformedPart(name, index, transform.scale, transform.rotationDegrees,
                    transform.tiltXDegrees, transform.tiltYDegrees, transform.offsetX, transform.offsetY);
        }
        return rebuilt.leveledOnBed(name);
    }

    /**
     * Pack logical parts into a bounded phone-side layout. The planner may
     * rotate each part by 90 degrees in XY, but never silently changes its
     * Z-up/tilt orientation because that can alter supports and first-layer
     * behavior. Existing scale, tilt and global transforms remain intact.
     */
    private void autoArrangeParts() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (sourceModel == null || model == null || model.parts == null || model.parts.length < 2) {
            Toast.makeText(this, "Arrange is available for assemblies with multiple parts", Toast.LENGTH_SHORT).show();
            return;
        }
        prepareModelMutation("Arrange parts");
        try {
            final float margin = Math.max(2f, config.nozzle * 2f);
            MeshModel base = sourceModel.transformed(model.displayName, modelScale, modelRotationDegrees,
                    modelTiltXDegrees, modelTiltYDegrees);
            ArrayList<PlateArrangementPlanner.Part> specs = new ArrayList<>();
            for (int index = 0; index < base.parts.length; index++) {
                MeshModel.PartTransform current = partTransformAt(index);
                MeshModel preview = base.transformedPart(model.displayName, index, current.scale,
                        current.rotationDegrees, current.tiltXDegrees, current.tiltYDegrees, 0f, 0f);
                MeshModel.PartBounds bounds = preview.partBounds(index);
                specs.add(new PlateArrangementPlanner.Part(index, bounds.width(), bounds.depth()));
            }
            ArrayList<PlateArrangementPlanner.Placement> placements = PlateArrangementPlanner.plan(
                    specs, config.bedX - margin * 2f, config.bedY - margin * 2f, margin);
            MeshModel arranged = base;
            ArrayList<MeshModel.PartTransform> next = new ArrayList<>();
            for (int index = 0; index < arranged.parts.length; index++)
                next.add(MeshModel.PartTransform.identity());
            for (PlateArrangementPlanner.Placement placement : placements) {
                int index = placement.sourceIndex;
                MeshModel.PartTransform current = partTransformAt(index);
                float rotation = current.rotationDegrees + placement.rotationDegrees;
                MeshModel oriented = arranged.transformedPart(model.displayName, index, current.scale,
                        rotation, current.tiltXDegrees, current.tiltYDegrees, 0f, 0f);
                MeshModel.PartBounds bounds = oriented.partBounds(index);
                float targetCenterX = margin + placement.x + placement.width / 2f;
                float targetCenterY = margin + placement.y + placement.depth / 2f;
                float currentCenterX = (bounds.minX + bounds.maxX) / 2f;
                float currentCenterY = (bounds.minY + bounds.maxY) / 2f;
                float offsetX = targetCenterX - currentCenterX;
                float offsetY = targetCenterY - currentCenterY;
                arranged = arranged.transformedPart(model.displayName, index, current.scale,
                        rotation, current.tiltXDegrees, current.tiltYDegrees, offsetX, offsetY);
                next.set(index, new MeshModel.PartTransform(current.scale, rotation,
                        current.tiltXDegrees, current.tiltYDegrees, offsetX, offsetY));
            }
            int selectedPart = viewport.getSelectedPart();
            partTransforms.clear();
            partTransforms.addAll(next);
            model = arranged.leveledOnBed(model.displayName);
            projectStore.savePartTransforms(partTransforms);
            saveCurrentPlate();
            viewport.setModel(model);
            viewport.setSelectedPart(selectedPart);
            slice = null;
            stagedArtifact = null;
            lastBatch = null;
            status.setText("Prepare  ·  parts auto-oriented and packed");
            details.setText(modelDetails(model));
            finishModelMutation("Auto-pack parts");
            refreshActions();
        } catch (Exception error) {
            Toast.makeText(this, "Parts could not be arranged: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showPrepare() {
        if (printerBusy || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (model == null || sourceModel == null) {
            Toast.makeText(this, "Import a model before preparing it", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        EditText scale = field(String.format(Locale.US, "%.0f", modelScale * 100f), "Scale (%)");
        EditText rotation = field(String.format(Locale.US, "%.0f", modelRotationDegrees), "Rotate around Z (degrees)", true);
        EditText tiltX = field(String.format(Locale.US, "%.0f", modelTiltXDegrees), "Tilt around X (degrees)", true);
        EditText tiltY = field(String.format(Locale.US, "%.0f", modelTiltYDegrees), "Tilt around Y (degrees)", true);
        fields.addView(scale);
        fields.addView(rotation);
        fields.addView(tiltX);
        fields.addView(tiltY);
        Button scaleHelper = dialogButton("Scale to known dimension", null);
        scaleHelper.setContentDescription("Scale model to a known dimension");
        scaleHelper.setOnClickListener(v -> showScaleToKnownDimension());
        fields.addView(scaleHelper);
        TextView note = label("The lowest point is placed on the bed. X/Y tilt and Z rotation apply before slicing; Alloy keeps the current model inside the selected build-volume gate.", 13, MUTED);
        note.setPadding(0, 18, 0, 0);
        fields.addView(note);
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Prepare model")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        float nextScale = clamp(Float.parseFloat(scale.getText().toString()) / 100f,
                                MeshModel.MIN_MODEL_SCALE, MeshModel.MAX_MODEL_SCALE);
                        float nextRotation = clamp(Float.parseFloat(rotation.getText().toString()), -360f, 360f);
                        float nextTiltX = clamp(Float.parseFloat(tiltX.getText().toString()), -360f, 360f);
                        float nextTiltY = clamp(Float.parseFloat(tiltY.getText().toString()), -360f, 360f);
                        prepareModelMutation("Prepare model");
                        modelScale = nextScale;
                        modelRotationDegrees = nextRotation;
                        modelTiltXDegrees = nextTiltX;
                        modelTiltYDegrees = nextTiltY;
                        model = rebuildModel(model.displayName);
                        slice = null;
                        stagedArtifact = null;
                        lastBatch = null;
                        viewport.setModel(model);
                        if (!modelUris.isEmpty()) projectStore.saveTransform(modelScale, modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees);
                        saveCurrentPlate();
                        modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
                        status.setText("Prepare  ·  model updated");
                        details.setText(modelDetails(model));
                        finishModelMutation("Prepare model");
                        refreshActions();
                    } catch (Exception error) {
                        Toast.makeText(this, "Preparation values were not valid", Toast.LENGTH_SHORT).show();
                    }
                });
        if (model.parts != null && model.parts.length > 1)
            builder.setNeutralButton("Auto-arrange", (dialog, which) -> autoArrangeParts());
        builder.show();
    }

    /**
     * Unitless STL/OBJ files are common in community libraries. Let the user
     * anchor one physical dimension without making an unsafe automatic guess;
     * the normal build-volume preflight still decides whether the result fits.
     */
    private void showScaleToKnownDimension() {
        if (model == null || sourceModel == null) return;
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation before scaling the model", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] axes = {"Width (X)", "Depth (Y)", "Height (Z)"};
        final RadioGroup axis = new RadioGroup(this);
        axis.setOrientation(RadioGroup.VERTICAL);
        for (String label : axes) {
            RadioButton button = new RadioButton(this);
            button.setText(label);
            button.setTextColor(TEXT);
            axis.addView(button, new RadioGroup.LayoutParams(-1, dp(44)));
        }
        ((RadioButton) axis.getChildAt(0)).setChecked(true);
        EditText target = field(String.format(Locale.US, "%.1f", model.maxX - model.minX),
                "Target dimension (mm)");
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        fields.addView(axis);
        fields.addView(target);
        String current = String.format(Locale.US, "Current size: %.1f × %.1f × %.1f mm",
                model.maxX - model.minX, model.maxY - model.minY, model.maxZ - model.minZ);
        TextView note = label(current + "\nUniform scale only; existing part placement offsets remain unchanged.\nAllowed scale: 10%–10,000%.", 12, MUTED);
        note.setPadding(0, 12, 0, 0);
        fields.addView(note);
        new AlertDialog.Builder(this)
                .setTitle("Scale to known dimension")
                .setMessage("Use this for unitless STL/OBJ files when you know one real-world measurement.")
                .setView(fields)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", (dialog, which) -> {
                    try {
                        float targetMm = Float.parseFloat(target.getText().toString());
                        if (!finite(targetMm) || targetMm <= 0f || targetMm > 10_000f)
                            throw new IllegalArgumentException("Target dimension is invalid");
                        int selected = axis.indexOfChild(axis.findViewById(axis.getCheckedRadioButtonId()));
                        float currentMm = selected == 1 ? model.maxY - model.minY
                                : selected == 2 ? model.maxZ - model.minZ : model.maxX - model.minX;
                        if (!finite(currentMm) || currentMm <= 0f)
                            throw new IllegalArgumentException("Current dimension is invalid");
                        float nextScale = modelScale * targetMm / currentMm;
                        if (!finite(nextScale) || nextScale < MeshModel.MIN_MODEL_SCALE
                                || nextScale > MeshModel.MAX_MODEL_SCALE)
                            throw new IllegalArgumentException("The resulting scale must be between 10% and 10,000%");
                        prepareModelMutation("Scale to known dimension");
                        modelScale = nextScale;
                        model = rebuildModel(model.displayName);
                        slice = null;
                        stagedArtifact = null;
                        lastBatch = null;
                        viewport.setModel(model);
                        if (!modelUris.isEmpty())
                            projectStore.saveTransform(modelScale, modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees);
                        saveCurrentPlate();
                        modelMeta.setText(viewportDisplayName(model.displayName) + "\n" + profileShortLabel());
                        status.setText("Prepare  ·  model scaled to known dimension");
                        details.setText(modelDetails(model));
                        finishModelMutation("Scale to known dimension");
                        refreshActions();
                    } catch (Exception error) {
                        Toast.makeText(this, "Scale could not be applied: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                })
                .show();
    }

    private String displayName(Uri uri) {
        android.database.Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null && value.trim().length() > 0) return value;
            }
        } catch (Exception ignored) {
            // Cloud/document providers are allowed to omit metadata; use the URI below.
        } finally {
            if (cursor != null) cursor.close();
        }
        String path = uri.getLastPathSegment(); return path == null ? "model.stl" : path;
    }

    private void startSlice() {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (model == null) return;
        PreparationValidator.Report preflight = PreparationValidator.validate(model, config, profile);
        if (!preflight.isReady()) {
            new AlertDialog.Builder(this)
                    .setTitle("Cannot slice yet")
                    .setMessage(preflight.message())
                    .setPositiveButton("Done", null)
                    .show();
            return;
        }
        if (!preflight.warnings.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Review before slicing")
                    .setMessage(preflight.message())
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Slice anyway", (dialog, which) -> requestNotificationPermissionIfNeeded(this::beginSlice))
                    .show();
            return;
        }
        requestNotificationPermissionIfNeeded(this::beginSlice);
    }

    private void beginSlice() {
        if (model == null) return;
        lastBatch = null;
        if (batchSliceJobStore != null) batchSliceJobStore.clear();
        activeBatchSliceJobId = null;
        slice = null;
        stagedArtifact = null;
        if (sliceJobStore == null) return;
        String jobId = SliceRequestStore.newJobId();
        try {
            String modelHash = SliceRequestStore.write(getFilesDir(), jobId, model, config, artifactDisplayName());
            sliceJobStore.clear();
            sliceJobStore.begin(jobId, artifactDisplayName(), modelHash);
            activeSliceJobId = jobId;
            slicing = true;
            refreshActions();
            status.setText("Slice  ·  preparing foreground job…");
            SliceJobService.start(this, jobId);
        } catch (Exception error) {
            activeSliceJobId = null;
            slicing = false;
            sliceJobStore.fail(jobId, "Could not start slice: " + error.getMessage());
            status.setText("Prepare  ·  slice could not start");
            refreshActions();
            Toast.makeText(this, "Slice could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void cancelSlice() {
        if (!slicing && activeSliceJobId == null) return;
        String jobId = activeSliceJobId;
        if (jobId == null) return;
        try {
            SliceJobService.cancel(this, jobId);
            status.setText("Slice  ·  cancelling…");
            refreshActions();
        } catch (Exception error) {
            Toast.makeText(this, "Slice cancellation failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void changeLayer(int delta) {
        if (slice == null || slice.layers.isEmpty()) return;
        int current = viewport.getSelectedLayer() < 0 ? slice.layers.size() - 1 : viewport.getSelectedLayer();
        int next = Math.max(0, Math.min(slice.layers.size() - 1, current + delta));
        viewport.setSelectedLayer(next);
        Slicer.Layer layer = slice.layers.get(next);
        status.setText(String.format(Locale.US, "Inspect  ·  layer %d / %d  ·  Z %.2f mm  ·  %d segments",
                next + 1, slice.layers.size(), layer.z, layer.segments.size()));
    }

    private void showInspection() {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (slice == null || slice.layers == null || slice.layers.isEmpty()) {
            Toast.makeText(this, "Slice the model before inspecting the toolpath", Toast.LENGTH_SHORT).show();
            return;
        }
        int selected = viewport.getSelectedLayer() < 0 ? slice.layers.size() - 1 : viewport.getSelectedLayer();
        Slicer.Layer layer = slice.layers.get(Math.max(0, Math.min(slice.layers.size() - 1, selected)));
        String time = slice.printTimeSeconds < 0f ? "Not reported by engine" : formatDuration(slice.printTimeSeconds);
        String engine = slice.engineId == null ? "Unknown" : slice.engineId;
        if (!slice.engineVerified) engine += " · unverified";
        String message = String.format(Locale.US,
                "Selected layer\n%d / %d  ·  Z %.2f mm  ·  %d segments\n\nFilament estimate\n%.1f mm\n\nEstimated time\n%s\n\nTravel\n%s\n\nWarnings\n%d\n\nEngine\n%s",
                layer.index + 1, slice.layers.size(), layer.z, layer.segments.size(), slice.filamentMm, time,
                slice.travelMm < 0f ? "Not reported" : String.format(Locale.US, "%.1f mm", slice.travelMm), slice.warnings, engine);
        new AlertDialog.Builder(this)
                .setTitle("Toolpath inspection")
                .setMessage(message)
                .setPositiveButton("Done", null)
                .show();
    }

    private static String formatDuration(float seconds) {
        int total = Math.max(0, Math.round(seconds));
        int hours = total / 3600;
        int minutes = (total % 3600) / 60;
        int remainder = total % 60;
        return hours > 0 ? String.format(Locale.US, "%dh %02dm", hours, minutes)
                : String.format(Locale.US, "%dm %02ds", minutes, remainder);
    }

    private String artifactDisplayName() {
        return (model == null ? "alloy-job" : model.displayName) + "-plate-" + (activePlateIndex + 1);
    }

    private void exportPackage() {
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, safeName(artifactDisplayName()) + ".gcode.3mf"); startActivityForResult(intent, REQUEST_EXPORT);
    }

    private void openViewExport() {
        if (viewport == null) {
            Toast.makeText(this, "The 3D view is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/png");
        intent.putExtra(Intent.EXTRA_TITLE, viewport.isMachineStudy()
                ? "alloy-a1-mini-study.png" : "alloy-3d-view.png");
        startActivityForResult(intent, REQUEST_VIEW_EXPORT);
    }

    private void writeViewExport(Uri uri) {
        if (viewport == null) {
            Toast.makeText(this, "The 3D view is not ready", Toast.LENGTH_SHORT).show();
            return;
        }
        // Capture the live GLES surface so A1 study exports contain the
        // supplied machine mesh, real material pass and receding plates.
        // thumbnailPng remains intentionally reserved for bounded archive and
        // BYOK references, where deterministic CPU rendering is preferable.
        viewport.capturePng(1_024, png -> {
            if (png == null || png.length == 0) {
                Toast.makeText(this, "3D view capture failed", Toast.LENGTH_LONG).show();
                return;
            }
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IOException("Output destination could not be opened");
                out.write(png);
                out.flush();
                Toast.makeText(this, "Saved 3D view PNG", Toast.LENGTH_LONG).show();
            } catch (Exception error) {
                Toast.makeText(this, "3D view export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void writeExport(Uri uri) {
        ArtifactValidator.Report report = ArtifactValidator.validate(model, slice, config);
        if (!report.isValid()) {
            Toast.makeText(this, "Export blocked: " + report.summary(), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (stagedArtifact == null || stagedArtifact.sourceFile == null
                    || stagedArtifact.sourceFile.length() != stagedArtifact.sizeBytes
                    || !ArtifactStore.sha256(stagedArtifact.sourceFile).equalsIgnoreCase(stagedArtifact.sha256)
                    || !ArtifactStore.hasThumbnail(stagedArtifact.sourceFile)) {
                stagedArtifact = ArtifactStore.stage(getFilesDir(), model, slice, config, artifactDisplayName(), viewport.thumbnailPng(512));
            }
        } catch (Exception error) {
            Toast.makeText(this, "Export staging failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        try (java.io.OutputStream out = getContentResolver().openOutputStream(uri);
             java.io.InputStream input = new java.io.FileInputStream(stagedArtifact.sourceFile)) {
            if (out == null) throw new IllegalArgumentException("Output destination could not be opened");
            byte[] buffer = new byte[32 * 1024];
            int read;
            long written = 0L;
            while ((read = input.read(buffer)) != -1) {
                if (read == 0) continue;
                written += read;
                if (written > stagedArtifact.sizeBytes) throw new IOException("Export source changed while copying");
                out.write(buffer, 0, read);
            }
            if (written != stagedArtifact.sizeBytes) throw new IOException("Export destination received an incomplete artifact");
            out.flush();
            Toast.makeText(this, "Exported .gcode.3mf", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void showRecipe() {
        if (printerBusy || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (importing) {
            Toast.makeText(this, "Finish or cancel the current model import first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (slicing || batchSlicing) {
            Toast.makeText(this, "Cancel the current slice before changing the recipe", Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(28, 4, 28, 0);
        EditText layer = field(String.format(Locale.US, "%.2f", config.layerHeight), "Layer height (mm)");
        EditText firstLayer = field(String.format(Locale.US, "%.2f", config.firstLayerHeight), "First layer height (mm)");
        EditText infill = field(String.format(Locale.US, "%.0f", config.infill * 100), "Infill (%)");
        EditText perimeters = field(Integer.toString(config.perimeters), "Perimeters");
        EditText topLayers = field(Integer.toString(config.topLayers), "Top solid layers");
        EditText bottomLayers = field(Integer.toString(config.bottomLayers), "Bottom solid layers");
        EditText nozzleTemperature = field(String.format(Locale.US, "%.0f", config.nozzleTemperature), "Nozzle temperature (°C)");
        EditText firstLayerNozzleTemperature = field(String.format(Locale.US, "%.0f", config.firstLayerNozzleTemperature), "First layer nozzle temperature (°C)");
        String currentBuildPlate = nativeSetting(config, "curr_bed_type", "Hot Plate");
        float displayedBedTemperature = selectedPlateTemperature(config, currentBuildPlate, false, config.bedTemperature);
        float displayedFirstLayerBedTemperature = selectedPlateTemperature(config, currentBuildPlate, true, config.firstLayerBedTemperature);
        EditText bedTemperature = field(String.format(Locale.US, "%.0f", displayedBedTemperature), "Selected plate temperature (°C)");
        EditText firstLayerBedTemperature = field(String.format(Locale.US, "%.0f", displayedFirstLayerBedTemperature), "Selected plate first-layer temperature (°C)");
        EditText extrusionMultiplier = field(String.format(Locale.US, "%.2f", config.extrusionMultiplier), "Flow / extrusion multiplier");
        EditText maxVolumetricSpeed = field(String.format(Locale.US, "%.1f", config.maxVolumetricSpeed), "Max volumetric speed (mm³/s)");
        EditText travelSpeed = field(String.format(Locale.US, "%.0f", config.travelSpeed), "Travel speed (mm/s)");
        EditText outerWallSpeed = field(String.format(Locale.US, "%.0f", config.outerWallSpeed), "Outer wall speed (mm/s)");
        EditText innerWallSpeed = field(String.format(Locale.US, "%.0f", config.innerWallSpeed), "Inner wall speed (mm/s)");
        EditText infillSpeed = field(String.format(Locale.US, "%.0f", config.infillSpeed), "Infill speed (mm/s)");
        EditText initialLayerSpeed = field(String.format(Locale.US, "%.0f", config.initialLayerSpeed), "Initial layer speed (mm/s)");
        EditText fanMin = field(String.format(Locale.US, "%.0f", config.fanMinPercent), "Minimum fan (%)");
        EditText fanMax = field(String.format(Locale.US, "%.0f", config.fanMaxPercent), "Maximum fan (%)");
        EditText threshold = field(String.format(Locale.US, "%.0f", config.supportThresholdDegrees), "Support overhang threshold (degrees)");
        RadioGroup buildPlateTypes = choiceGroup(new String[][]{
                {"Cool Plate", "Cool Plate"},
                {"Engineering Plate", "Engineering Plate"},
                {"Textured PEI Plate", "Textured PEI Plate"},
                {"SuperTack Plate", "SuperTack Plate"},
                {"Hot Plate", "Hot Plate"}
        }, currentBuildPlate);
        RadioGroup topSurfacePatterns = choiceGroup(new String[][]{
                {"Monotonic", "monotoniclines"}, {"Rectilinear", "rectilinear"}, {"Concentric", "concentric"}
        }, nativeSetting(config, "top_fill_pattern", "monotoniclines"));
        RadioGroup bottomSurfacePatterns = choiceGroup(new String[][]{
                {"Monotonic", "monotonic"}, {"Rectilinear", "rectilinear"}, {"Concentric", "concentric"}
        }, nativeSetting(config, "bottom_fill_pattern", "monotonic"));
        RadioGroup seamPositions = choiceGroup(new String[][]{
                {"Aligned", "aligned"}, {"Nearest", "nearest"}, {"Rear", "rear"}, {"Random", "random"}
        }, nativeSetting(config, "seam_position", "aligned"));
        CheckBox ironing = new CheckBox(this);
        ironing.setText("Iron top surfaces");
        ironing.setTextColor(TEXT);
        ironing.setChecked(nativeBoolean(config, "ironing", false));
        EditText ironingFlow = field(nativeSetting(config, "ironing_flowrate", "10"), "Ironing flow (%)");
        EditText bridgeSpeed = field(nativeSetting(config, "bridge_speed", "30"), "Bridge speed (mm/s)");
        EditText defaultAcceleration = field(nativeSetting(config, "default_acceleration", "5000"), "Default acceleration (mm/s²)");
        CheckBox supports = new CheckBox(this);
        supports.setText("Generate automatic supports");
        supports.setTextColor(TEXT);
        supports.setChecked(config.supports);

        // Keep the common tree-support controls in the recipe surface instead
        // of forcing phone users to edit a project file. These values travel
        // through the same allowlisted native-settings boundary as the
        // packaged profile, so edits remain portable and auditable.
        String supportStyleValue = nativeSetting(config, "support_material_style", "organic");
        RadioGroup supportStyles = new RadioGroup(this);
        String[][] supportStyleOptions = {
                {"Organic tree", "organic"}, {"Slim tree", "tree"}, {"Grid", "grid"}
        };
        int selectedSupportStyle = 0;
        for (int index = 0; index < supportStyleOptions.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(supportStyleOptions[index][0]);
            option.setTextColor(TEXT);
            option.setTag(supportStyleOptions[index][1]);
            supportStyles.addView(option);
            if (supportStyleOptions[index][1].equalsIgnoreCase(supportStyleValue)) selectedSupportStyle = index;
        }
        supportStyles.check(supportStyles.getChildAt(selectedSupportStyle).getId());
        EditText supportTopLayers = field(nativeSetting(config, "support_material_interface_layers", "2"), "Top interface layers (0–20)");
        EditText supportBottomLayers = field(nativeSetting(config, "support_material_bottom_interface_layers", "2"), "Bottom interface layers (0–20)");
        EditText supportXyDistance = field(nativeSetting(config, "support_material_xy_spacing", "0.35"), "Support XY distance (mm)");
        EditText supportContactDistance = field(nativeSetting(config, "support_material_contact_distance", "0.2"), "Top contact gap (mm)");
        EditText supportBottomContactDistance = field(nativeSetting(config, "support_material_bottom_contact_distance", "0.2"), "Bottom contact gap (mm)");
        EditText supportSpacing = field(nativeSetting(config, "support_material_spacing", "2.5"), "Support spacing (mm)");
        EditText supportSpeed = field(nativeSetting(config, "support_material_speed", "150"), "Support speed (mm/s)");
        EditText supportInterfaceSpeed = field(nativeSetting(config, "support_material_interface_speed", "80"), "Interface speed (mm/s)");
        EditText treeAngle = field(nativeSetting(config, "support_tree_angle", "45"), "Tree branch angle (degrees)");
        EditText treeDistance = field(nativeSetting(config, "support_tree_branch_distance", "5"), "Tree branch distance (mm)");
        EditText treeDiameter = field(nativeSetting(config, "support_tree_branch_diameter", "2"), "Tree branch diameter (mm)");
        EditText treeDiameterAngle = field(nativeSetting(config, "support_tree_branch_diameter_angle", "5"), "Branch thickening angle (degrees)");
        EditText treeWallCount = field(nativeSetting(config, "support_tree_branch_diameter_double_wall", "0"), "Tree wall mode (0–4)");
        fields.addView(sectionLabel("QUALITY"));
        fields.addView(layer); fields.addView(firstLayer); fields.addView(infill);
        fields.addView(perimeters); fields.addView(topLayers); fields.addView(bottomLayers);
        fields.addView(sectionLabel("MATERIAL"));
        fields.addView(nozzleTemperature); fields.addView(firstLayerNozzleTemperature);
        fields.addView(label("BUILD PLATE", 10, GOLD)); fields.addView(buildPlateTypes);
        fields.addView(label("The selected surface and its temperatures are used for this job. Confirm the physical plate before sending.", 12, MUTED));
        fields.addView(bedTemperature); fields.addView(firstLayerBedTemperature);
        fields.addView(extrusionMultiplier); fields.addView(maxVolumetricSpeed);
        fields.addView(sectionLabel("MOTION & COOLING"));
        fields.addView(travelSpeed); fields.addView(outerWallSpeed); fields.addView(innerWallSpeed);
        fields.addView(infillSpeed); fields.addView(initialLayerSpeed); fields.addView(fanMin); fields.addView(fanMax);
        fields.addView(sectionLabel("SURFACES & PATH"));
        fields.addView(label("TOP SURFACE", 10, GOLD)); fields.addView(topSurfacePatterns);
        fields.addView(label("BOTTOM SURFACE", 10, GOLD)); fields.addView(bottomSurfacePatterns);
        fields.addView(label("SEAM", 10, GOLD)); fields.addView(seamPositions);
        fields.addView(ironing); fields.addView(ironingFlow); fields.addView(bridgeSpeed); fields.addView(defaultAcceleration);
        fields.addView(sectionLabel("SUPPORTS"));
        fields.addView(supports); fields.addView(threshold);
        fields.addView(label("STYLE", 10, GOLD));
        fields.addView(supportStyles);
        fields.addView(supportTopLayers); fields.addView(supportBottomLayers);
        fields.addView(supportXyDistance); fields.addView(supportContactDistance); fields.addView(supportBottomContactDistance);
        fields.addView(supportSpacing); fields.addView(supportSpeed); fields.addView(supportInterfaceSpeed);
        fields.addView(label("TREE GEOMETRY", 10, GOLD));
        fields.addView(treeAngle); fields.addView(treeDistance); fields.addView(treeDiameter);
        fields.addView(treeDiameterAngle); fields.addView(treeWallCount);
        String profileNote = (profile == null ? "A1 Mini · 0.4 mm nozzle · PLA" : profile.name)
                + "\nTyped values are loaded from the pinned profile snapshot. Native engine parity is still a documented gate.";
        if (supports.isChecked()) {
            profileNote += BuildConfig.NATIVE_ENGINE_ENABLED
                    ? "\nNative support output uses the integrated Orca TreeSupport3D path; Bambu tree(auto) parity is still not available for production printing."
                    : "\nOffline fallback uses conservative grid supports for simple overhangs; native engine support remains required for production printing.";
        }
        if (profile == null || !profile.verified) profileNote += "\nThis profile is not approved for physical printing.";
        TextView note = label(profileNote, 13, MUTED); note.setPadding(0, 18, 0, 0); fields.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("Print recipe").setView(scroll).setNegativeButton("Cancel", null).setPositiveButton("Apply", (d, w) -> {
            try {
                float nextLayer = clamp(Float.parseFloat(layer.getText().toString()), 0.08f, 0.40f);
                float nextFirstLayer = clamp(Float.parseFloat(firstLayer.getText().toString()), 0.08f, 0.40f);
                float nextInfill = clamp(Float.parseFloat(infill.getText().toString()) / 100f, 0f, 1f);
                int nextPerimeters = Math.round(clamp(Float.parseFloat(perimeters.getText().toString()), 1f, 20f));
                int nextTopLayers = Math.round(clamp(Float.parseFloat(topLayers.getText().toString()), 0f, 100f));
                int nextBottomLayers = Math.round(clamp(Float.parseFloat(bottomLayers.getText().toString()), 0f, 100f));
                float nextNozzleTemperature = clamp(Float.parseFloat(nozzleTemperature.getText().toString()), 0f, 400f);
                float nextFirstLayerNozzleTemperature = clamp(Float.parseFloat(firstLayerNozzleTemperature.getText().toString()), 0f, 400f);
                float nextBedTemperature = clamp(Float.parseFloat(bedTemperature.getText().toString()), 0f, 150f);
                float nextFirstLayerBedTemperature = clamp(Float.parseFloat(firstLayerBedTemperature.getText().toString()), 0f, 150f);
                String nextBuildPlate = selectedChoice(buildPlateTypes, "Hot Plate");
                float nextExtrusionMultiplier = clamp(Float.parseFloat(extrusionMultiplier.getText().toString()), 0.5f, 2f);
                float nextMaxVolumetricSpeed = clamp(Float.parseFloat(maxVolumetricSpeed.getText().toString()), 0.1f, 200f);
                float nextTravelSpeed = clamp(Float.parseFloat(travelSpeed.getText().toString()), 1f, 2_000f);
                float nextOuterWallSpeed = clamp(Float.parseFloat(outerWallSpeed.getText().toString()), 1f, 1_000f);
                float nextInnerWallSpeed = clamp(Float.parseFloat(innerWallSpeed.getText().toString()), 1f, 1_000f);
                float nextInfillSpeed = clamp(Float.parseFloat(infillSpeed.getText().toString()), 1f, 1_000f);
                float nextInitialLayerSpeed = clamp(Float.parseFloat(initialLayerSpeed.getText().toString()), 1f, 1_000f);
                float nextFanMin = clamp(Float.parseFloat(fanMin.getText().toString()), 0f, 100f);
                float nextFanMax = Math.max(nextFanMin, clamp(Float.parseFloat(fanMax.getText().toString()), 0f, 100f));
                float nextThreshold = clamp(Float.parseFloat(threshold.getText().toString()), 0f, 90f);
                float nextIroningFlow = clamp(Float.parseFloat(ironingFlow.getText().toString().replace("%", "").trim()), 0f, 100f);
                float nextBridgeSpeed = clamp(Float.parseFloat(bridgeSpeed.getText().toString()), 1f, 1_000f);
                float nextDefaultAcceleration = clamp(Float.parseFloat(defaultAcceleration.getText().toString()), 1f, 100_000f);
                int nextSupportTopLayers = Math.round(clamp(Float.parseFloat(supportTopLayers.getText().toString()), 0f, 20f));
                int nextSupportBottomLayers = Math.round(clamp(Float.parseFloat(supportBottomLayers.getText().toString()), 0f, 20f));
                float nextSupportXyDistance = clamp(Float.parseFloat(supportXyDistance.getText().toString()), 0.01f, 5f);
                float nextSupportContactDistance = clamp(Float.parseFloat(supportContactDistance.getText().toString()), 0f, 2f);
                float nextSupportBottomContactDistance = clamp(Float.parseFloat(supportBottomContactDistance.getText().toString()), 0f, 2f);
                float nextSupportSpacing = clamp(Float.parseFloat(supportSpacing.getText().toString()), 0.5f, 10f);
                float nextSupportSpeed = clamp(Float.parseFloat(supportSpeed.getText().toString()), 1f, 1_000f);
                float nextSupportInterfaceSpeed = clamp(Float.parseFloat(supportInterfaceSpeed.getText().toString()), 1f, 1_000f);
                float nextTreeAngle = clamp(Float.parseFloat(treeAngle.getText().toString()), 0f, 89f);
                float nextTreeDistance = clamp(Float.parseFloat(treeDistance.getText().toString()), 0.1f, 100f);
                float nextTreeDiameter = clamp(Float.parseFloat(treeDiameter.getText().toString()), 0.5f, 10f);
                float nextTreeDiameterAngle = clamp(Float.parseFloat(treeDiameterAngle.getText().toString()), 0f, 89f);
                int nextTreeWallCount = Math.round(clamp(Float.parseFloat(treeWallCount.getText().toString()), 0f, 4f));
                config.layerHeight = nextLayer;
                config.firstLayerHeight = nextFirstLayer;
                config.infill = nextInfill;
                config.perimeters = nextPerimeters;
                config.topLayers = nextTopLayers;
                config.bottomLayers = nextBottomLayers;
                config.nozzleTemperature = nextNozzleTemperature;
                config.firstLayerNozzleTemperature = nextFirstLayerNozzleTemperature;
                config.bedTemperature = nextBedTemperature;
                config.firstLayerBedTemperature = nextFirstLayerBedTemperature;
                config.nativeSettings.put("curr_bed_type", nextBuildPlate);
                config.nativeSettings.put(plateTemperatureKey(nextBuildPlate, false), number(nextBedTemperature));
                config.nativeSettings.put(plateTemperatureKey(nextBuildPlate, true), number(nextFirstLayerBedTemperature));
                config.extrusionMultiplier = nextExtrusionMultiplier;
                config.maxVolumetricSpeed = nextMaxVolumetricSpeed;
                config.travelSpeed = nextTravelSpeed;
                config.outerWallSpeed = nextOuterWallSpeed;
                config.innerWallSpeed = nextInnerWallSpeed;
                config.infillSpeed = nextInfillSpeed;
                config.initialLayerSpeed = nextInitialLayerSpeed;
                config.fanMinPercent = nextFanMin;
                config.fanMaxPercent = nextFanMax;
                config.supports = supports.isChecked();
                config.supportThresholdDegrees = nextThreshold;
                config.nativeSettings.put("top_fill_pattern", selectedChoice(topSurfacePatterns, "monotoniclines"));
                config.nativeSettings.put("bottom_fill_pattern", selectedChoice(bottomSurfacePatterns, "monotonic"));
                config.nativeSettings.put("seam_position", selectedChoice(seamPositions, "aligned"));
                config.nativeSettings.put("ironing", ironing.isChecked() ? "1" : "0");
                config.nativeSettings.put("ironing_flowrate", number(nextIroningFlow) + "%");
                config.nativeSettings.put("bridge_speed", number(nextBridgeSpeed));
                config.nativeSettings.put("default_acceleration", number(nextDefaultAcceleration));
                config.nativeSettings.put("support_material_style",
                        String.valueOf(supportStyles.findViewById(supportStyles.getCheckedRadioButtonId()).getTag()));
                config.nativeSettings.put("support_material_interface_layers", Integer.toString(nextSupportTopLayers));
                config.nativeSettings.put("support_material_bottom_interface_layers", Integer.toString(nextSupportBottomLayers));
                config.nativeSettings.put("support_material_xy_spacing", number(nextSupportXyDistance));
                config.nativeSettings.put("support_material_contact_distance", number(nextSupportContactDistance));
                config.nativeSettings.put("support_material_bottom_contact_distance", number(nextSupportBottomContactDistance));
                config.nativeSettings.put("support_material_spacing", number(nextSupportSpacing));
                config.nativeSettings.put("support_material_speed", number(nextSupportSpeed));
                config.nativeSettings.put("support_material_interface_speed", number(nextSupportInterfaceSpeed));
                config.nativeSettings.put("support_tree_angle", number(nextTreeAngle));
                config.nativeSettings.put("support_tree_branch_distance", number(nextTreeDistance));
                config.nativeSettings.put("support_tree_branch_diameter", number(nextTreeDiameter));
                config.nativeSettings.put("support_tree_branch_diameter_angle", number(nextTreeDiameterAngle));
                config.nativeSettings.put("support_tree_branch_diameter_double_wall", Integer.toString(nextTreeWallCount));
                projectStore.saveRecipe(config);
                slice = null;
                stagedArtifact = null;
                lastBatch = null;
                viewport.setResult(null);
                updateRecipeMarkers();
                details.setText("Recipe applied · " + profilePrinterLabel() + " · " + String.format(Locale.US, "%.2f mm layer · %.0f%% infill · %d walls · supports %s", config.layerHeight, config.infill * 100, config.perimeters, config.supports ? "on" : "off"));
                refreshActions();
            } catch (Exception e) { Toast.makeText(this, "Recipe values were not valid", Toast.LENGTH_SHORT).show(); }
        }).show();
    }

    private void showPlates() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current import, slice, or printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentPlate();
        ArrayList<PlateStore.Plate> entries = plateStore.plates();
        if (entries.isEmpty()) {
            saveCurrentPlate();
            entries = plateStore.plates();
        }
        String[] labels = new String[entries.size()];
        int checked = -1;
        for (int index = 0; index < entries.size(); index++) {
            PlateStore.Plate plate = entries.get(index);
            labels[index] = plate.summary();
            if (plate.index == activePlateIndex) checked = index;
        }
        final ArrayList<PlateStore.Plate> choices = entries;
        final boolean batchReady = lastBatch != null && !lastBatch.plates.isEmpty();
        final int populatedPlates = populatedPlateCount(entries);
        String batchAction = batchReady ? "Export batch" : "Slice all";
        new AlertDialog.Builder(this)
                .setTitle("Print plates")
                .setMessage("Keep separate model sets for boxes, lids, parts, and future assemblies. Alloy stores up to " + PlateStore.MAX_PLATES
                        + " local plate snapshots. " + populatedPlates + " plate" + (populatedPlates == 1 ? " is" : "s are")
                        + " populated.")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    dialog.dismiss();
                    switchPlate(choices.get(which).index);
                })
                .setPositiveButton(batchAction, (dialog, which) -> {
                    if (batchReady) exportBatchArchive();
                    else beginBatchSlice();
                })
                .setNeutralButton("New plate", (dialog, which) -> createPlate())
                .setNegativeButton("Close", null)
                .show();
    }

    private static int populatedPlateCount(ArrayList<PlateStore.Plate> plates) {
        int count = 0;
        if (plates == null) return count;
        for (PlateStore.Plate plate : plates) {
            if (plate != null && plate.uris != null && !plate.uris.isEmpty()) count++;
        }
        return count;
    }

    private void beginBatchSlice() {
        requestNotificationPermissionIfNeeded(this::beginBatchSliceAfterPermission);
    }

    private void beginBatchSliceAfterPermission() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentPlate();
        final ArrayList<PlateStore.Plate> plates = plateStore.plates();
        if (populatedPlateCount(plates) == 0) {
            Toast.makeText(this, "Add a model to at least one plate first", Toast.LENGTH_SHORT).show();
            return;
        }
        lastBatch = null;
        final String jobId = BatchSliceRequestStore.newJobId();
        final Slicer.Config recipe = config.copy();
        final String displayName = currentPlateLabel();
        final int populated = populatedPlateCount(plates);
        batchSlicing = true;
        activeBatchSliceJobId = jobId;
        status.setText("Batch  ·  saving an offline request…");
        refreshActions();
        activeBatchSnapshot = importExecutor.submit(() -> {
            try {
                BatchSliceRequestStore.write(getFilesDir(), getContentResolver(), jobId, plates, recipe, displayName);
                batchSliceJobStore.clear();
                batchSliceJobStore.begin(jobId, displayName, populated);
                mainHandler.post(() -> {
                    if (!jobId.equals(activeBatchSliceJobId) || isFinishing()) return;
                    activeBatchSnapshot = null;
                    try {
                        BatchSliceJobService.start(this, jobId);
                    } catch (Exception error) {
                        batchSliceJobStore.fail(jobId, "Could not start batch slice: " + error.getMessage());
                        activeBatchSliceJobId = null;
                        batchSlicing = false;
                        status.setText(model == null ? "Import a model to begin" : "Prepare  ·  batch slice could not start");
                        refreshActions();
                        Toast.makeText(this, "Batch slice could not start: " + error.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (!jobId.equals(activeBatchSliceJobId) || isFinishing()) return;
                    activeBatchSnapshot = null;
                    activeBatchSliceJobId = null;
                    batchSlicing = false;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  batch request could not be saved");
                    refreshActions();
                    Toast.makeText(this, "Batch request could not be saved: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void cancelBatchSlice() {
        if (!batchSlicing) return;
        String jobId = activeBatchSliceJobId;
        if (jobId != null && BatchSliceJobService.ownsJob(jobId)) {
            try {
                BatchSliceJobService.cancel(this, jobId);
                status.setText("Batch  ·  cancelling…");
                refreshActions();
            } catch (Exception error) {
                Toast.makeText(this, "Batch cancellation failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (activeBatchSnapshot != null) activeBatchSnapshot.cancel(true);
        if (jobId != null && batchSliceJobStore != null) batchSliceJobStore.cancel(jobId, "Batch slice cancelled");
        activeBatchSnapshot = null;
        activeBatchSliceJobId = null;
        batchSlicing = false;
        lastBatch = null;
        status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
        refreshActions();
    }

    private void showBatchSummary(BatchSliceJobController.BatchResult result) {
        if (result == null || result.plates.isEmpty()) return;
        StringBuilder message = new StringBuilder("Each plate is an independent validated artifact. Choose one plate in Print plates before sending it to a printer.\n\n");
        for (BatchSliceJobController.PlateResult plate : result.plates) {
            Slicer.Result slice = plate.slice;
            message.append("Plate ").append(plate.plate.index + 1).append("  ·  ").append(plate.plate.name)
                    .append("\n").append(slice.layers.size()).append(" layers  ·  ")
                    .append(String.format(Locale.US, "%.0f mm", slice.filamentMm));
            if (slice.printTimeSeconds >= 0f) message.append("  ·  ").append(formatDuration(slice.printTimeSeconds));
            message.append("\n");
        }
        new AlertDialog.Builder(this)
                .setTitle("All plates sliced")
                .setMessage(message.toString().trim())
                .setNegativeButton("Done", null)
                .setPositiveButton("Export batch", (dialog, which) -> exportBatchArchive())
                .show();
    }

    private void exportBatchArchive() {
        if (lastBatch == null || lastBatch.plates.isEmpty()) {
            Toast.makeText(this, "Slice the populated plates first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(BatchArtifactArchive.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, safeName(currentPlateLabel()) + "-plates.alloy-batch.zip");
        startActivityForResult(intent, REQUEST_BATCH_EXPORT);
    }

    private void writeBatchArchive(Uri destination) {
        if (destination == null || batchTransferring || lastBatch == null) return;
        final BatchSliceJobController.BatchResult batch = lastBatch;
        final long transferId = batchTransferIds.incrementAndGet();
        activeBatchTransferId = transferId;
        batchTransferring = true;
        status.setText("Batch  ·  validating portable archive…");
        refreshActions();
        activeBatchTransfer = importExecutor.submit(() -> {
            java.io.File temporary = new java.io.File(getCacheDir(), ".alloy-batch-" + transferId + ".part");
            try {
                try (java.io.OutputStream output = new java.io.FileOutputStream(temporary)) {
                    BatchArtifactArchive.write(output, batch);
                    output.flush();
                }
                BatchArtifactArchive.validate(temporary);
                try (java.io.OutputStream output = getContentResolver().openOutputStream(destination);
                     java.io.InputStream input = new java.io.FileInputStream(temporary)) {
                    if (output == null) throw new IOException("The batch destination could not be opened");
                    byte[] buffer = new byte[32 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) if (read > 0) output.write(buffer, 0, read);
                    output.flush();
                }
                mainHandler.post(() -> {
                    if (transferId != activeBatchTransferId || isFinishing()) return;
                    batchTransferring = false;
                    activeBatchTransfer = null;
                    status.setText("Batch  ·  portable archive saved");
                    refreshActions();
                    Toast.makeText(this, "Exported all plate artifacts", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (transferId != activeBatchTransferId || isFinishing()) return;
                    batchTransferring = false;
                    activeBatchTransfer = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Batch export failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            } finally {
                if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            }
        });
    }

    private void createPlate() {
        if (printerBusy || importing || slicing || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish the current import, slice, or printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            saveCurrentPlate();
            PlateStore.Plate created = plateStore.createNext();
            switchPlate(created.index);
        } catch (Exception error) {
            Toast.makeText(this, "New plate could not be created: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void switchPlate(int index) {
        if (index < 0 || index >= PlateStore.MAX_PLATES || printerBusy || importing || slicing || batchSlicing || batchTransferring) return;
        if (index == activePlateIndex && model != null) return;
        saveCurrentPlate();
        PlateStore.Plate target = plateStore.plateAt(index);
        activePlateIndex = index;
        plateStore.setActiveIndex(index);
        slice = null;
        stagedArtifact = null;
        if (viewport != null) viewport.setResult(null);
        updatePlateMarker();
        if (target.uris.isEmpty()) {
            plateImportInFlight = false;
            clearLoadedModelForPlate();
            if (modelHistoryStore != null) {
                modelHistoryStore.ensureCurrent(activePlateIndex, currentPlateSnapshot(), "New plate");
                pruneModelCache();
            }
        } else {
            plateImportInFlight = true;
            restorePlateCheckpoint(target);
            loadUris(target.uris, target.names, true);
        }
    }

    private void restorePlateCheckpoint(PlateStore.Plate plate) {
        if (plate == null || plate.uris.isEmpty()) {
            projectStore.clearModel();
            return;
        }
        projectStore.saveModels(plate.uris, plate.names);
        projectStore.saveTransform(plate.scale, plate.rotationDegrees, plate.tiltXDegrees, plate.tiltYDegrees);
        projectStore.saveSelectedPart(plate.selectedPart);
        projectStore.savePartTransforms(new ArrayList<>(plate.partTransforms));
        projectStore.saveGeometryRepair(plate.geometryRepairEnabled);
    }

    private void saveCurrentPlate() {
        if (plateStore == null || plateImportInFlight) return;
        ArrayList<String> names = new ArrayList<>();
        for (int index = 0; index < modelUris.size(); index++) {
            names.add(index < modelNames.size() ? modelNames.get(index) : "model.stl");
        }
        int selectedPart = viewport == null ? -1 : viewport.getSelectedPart();
        plateStore.save(new PlateStore.Plate(activePlateIndex, currentPlateName(),
                new ArrayList<>(modelUris), names, modelScale, modelRotationDegrees,
                modelTiltXDegrees, modelTiltYDegrees, selectedPart, new ArrayList<>(partTransforms),
                geometryRepairEnabled));
        plateStore.setActiveIndex(activePlateIndex);
    }

    private PlateStore.Plate currentPlateSnapshot() {
        ArrayList<String> names = new ArrayList<>();
        for (int index = 0; index < modelUris.size(); index++) {
            names.add(index < modelNames.size() ? modelNames.get(index) : "model.stl");
        }
        int selectedPart = viewport == null ? -1 : viewport.getSelectedPart();
        return new PlateStore.Plate(activePlateIndex, currentPlateName(),
                new ArrayList<>(modelUris), names, modelScale, modelRotationDegrees,
                modelTiltXDegrees, modelTiltYDegrees, selectedPart,
                new ArrayList<>(partTransforms), geometryRepairEnabled);
    }

    private void pruneModelCache() {
        if (plateStore == null) return;
        ArrayList<PlateStore.Plate> history = modelHistoryStore == null
                ? null : modelHistoryStore.referencedPlates();
        ArrayList<Uri> recent = new ArrayList<>();
        if (importedModelStore != null) {
            for (ImportedModelStore.Entry entry : importedModelStore.entries(getFilesDir())) {
                if (entry != null && entry.uri != null) recent.add(entry.uri);
            }
        }
        ModelStore.prune(getFilesDir(), plateStore.plates(), history, recent);
    }

    private void prepareModelMutation(String label) {
        if (modelHistoryStore == null || plateStore == null || plateImportInFlight) return;
        try {
            modelHistoryStore.ensureCurrent(activePlateIndex, currentPlateSnapshot(), "Current model");
            pruneModelCache();
        } catch (RuntimeException error) {
            // The edit remains local and visible, but the failure is explicit;
            // never pretend an edit is undoable when its snapshot did not save.
            Toast.makeText(this, "Edit history unavailable: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void finishModelMutation(String label) {
        if (modelHistoryStore == null || plateStore == null || plateImportInFlight) return;
        try {
            saveCurrentPlate();
            modelHistoryStore.append(activePlateIndex, currentPlateSnapshot(), label);
            pruneModelCache();
            updateHistoryActions();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Edit completed, but history could not be saved: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void resetModelHistory(String label) {
        if (modelHistoryStore == null || plateStore == null || plateImportInFlight) return;
        try {
            modelHistoryStore.resetDocument(activePlateIndex, currentPlateSnapshot(), label);
            pruneModelCache();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Model history could not be initialized: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateHistoryActions() {
        if (actions != null) refreshActions();
    }

    private void undoModel() {
        if (!canMutateModel()) return;
        PlateStore.Plate target;
        try { target = modelHistoryStore.undo(activePlateIndex); }
        catch (RuntimeException error) {
            Toast.makeText(this, "Undo history is invalid: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (target == null) {
            Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show();
            return;
        }
        restoreHistorySnapshot(target, "Undo");
    }

    private void redoModel() {
        if (!canMutateModel()) return;
        PlateStore.Plate target;
        try { target = modelHistoryStore.redo(activePlateIndex); }
        catch (RuntimeException error) {
            Toast.makeText(this, "Redo history is invalid: " + error.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if (target == null) {
            Toast.makeText(this, "Nothing to redo", Toast.LENGTH_SHORT).show();
            return;
        }
        restoreHistorySnapshot(target, "Redo");
    }

    private boolean canMutateModel() {
        if (modelHistoryStore == null || model == null || printerBusy || importing || slicing || batchSlicing
                || projectTransferring || batchTransferring || visualizing || modeling || repairingGeometry) {
            Toast.makeText(this, "Finish the current operation before changing model history", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void restoreHistorySnapshot(PlateStore.Plate target, String action) {
        if (target == null || target.index != activePlateIndex) return;
        saveCurrentPlate();
        slice = null;
        stagedArtifact = null;
        lastBatch = null;
        if (viewport != null) viewport.setResult(null);
        plateStore.save(target);
        restorePlateCheckpoint(target);
        if (target.uris.isEmpty()) {
            clearLoadedModelForPlate();
            plateStore.save(target);
            modelHistoryStore.ensureCurrent(activePlateIndex, target, action + " state");
            status.setText(currentPlateLabel() + "  ·  " + action.toLowerCase(Locale.US) + " complete");
            pruneModelCache();
            return;
        }
        plateImportInFlight = true;
        status.setText(action + "  ·  restoring model…");
        refreshActions();
        loadUris(target.uris, target.names, true);
    }

    private void showModelHistory() {
        if (modelHistoryStore == null) return;
        ArrayList<String> labels = modelHistoryStore.labels(activePlateIndex);
        if (labels.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Model edit history")
                    .setMessage("Edits to this plate will appear here. Undo and redo retain bounded model snapshots locally on this phone.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        CharSequence[] entries = labels.toArray(new CharSequence[0]);
        new AlertDialog.Builder(this).setTitle("Model edit history")
                .setMessage(currentPlateLabel() + "  ·  " + modelHistoryStore.summary(activePlateIndex))
                .setItems(entries, null)
                .setPositiveButton("Done", null)
                .show();
    }

    private void clearLoadedModelForPlate() {
        cancelImport();
        cancelGeometryRepair();
        if (sliceJobs != null) sliceJobs.cancel();
        if (batchSliceJobs != null) batchSliceJobs.cancel();
        batchSlicing = false;
        slicing = false;
        model = null;
        sourceModel = null;
        unmodifiedSourceModel = null;
        geometryRepairEnabled = false;
        partTransforms.clear();
        modelUris.clear();
        modelNames.clear();
        modelScale = 1f;
        modelRotationDegrees = 0f;
        modelTiltXDegrees = 0f;
        modelTiltYDegrees = 0f;
        slice = null;
        stagedArtifact = null;
        lastBatch = null;
        if (batchSliceJobStore != null) batchSliceJobStore.clear();
        activeBatchSliceJobId = null;
        plateImportInFlight = false;
        projectStore.clearModel();
        plateStore.save(PlateStore.Plate.empty(activePlateIndex));
        pruneModelCache();
        if (viewport != null) {
            viewport.setModel(null);
            viewport.setResult(null);
        }
        modelMeta.setText("NEW PLATE\n" + profileShortLabel());
        status.setText(currentPlateLabel() + "  ·  Import a model to begin");
        details.setText("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume");
        updatePlateMarker();
        refreshActions();
    }

    private void saveProjectArchive() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        saveCurrentPlate();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(ProjectArchive.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_TITLE, safeName(currentPlateLabel()) + ".alloy.zip");
        startActivityForResult(intent, REQUEST_PROJECT_EXPORT);
    }

    private void openProjectArchive() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(ProjectArchive.MIME_TYPE);
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{ProjectArchive.MIME_TYPE, "application/octet-stream"});
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PROJECT_OPEN);
    }

    private void writeProjectArchive(Uri destination) {
        if (destination == null || projectTransferring || batchTransferring) return;
        saveCurrentPlate();
        final ArrayList<PlateStore.Plate> plates = plateStore.plates();
        final Slicer.Config recipe = config.copy();
        final int active = activePlateIndex;
        final String historyName = model == null ? currentPlateLabel() : model.displayName;
        final int historyModelCount = projectModelCount(plates);
        final int historyPlateCount = plates.size();
        final byte[] thumbnail = viewport == null ? null : viewport.thumbnailPng(512);
        final ArrayList<ModelHistoryStore.Timeline> modelHistory = modelHistoryStore == null
                ? new ArrayList<>() : modelHistoryStore.exportTimelines();
        final long transferId = projectTransferIds.incrementAndGet();
        activeProjectTransferId = transferId;
        projectTransferring = true;
        status.setText("Project  ·  saving portable archive…");
        refreshActions();
        activeProjectTransfer = importExecutor.submit(() -> {
            try (java.io.OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new IllegalArgumentException("The project destination could not be opened");
                ProjectArchive.write(output, active, plates, recipe,
                        uri -> getContentResolver().openInputStream(uri), inventoryStore.snapshot(), thumbnail,
                        modelHistory);
                output.flush();
                mainHandler.post(() -> {
                    if (transferId != activeProjectTransferId || isFinishing()) return;
                    projectTransferring = false;
                    activeProjectTransfer = null;
                    try {
                        projectHistoryStore.record(historyName, destination, historyModelCount, historyPlateCount);
                    } catch (Exception ignored) {
                        // History is an enhancement; a successfully written archive remains usable.
                    }
                    status.setText("Project  ·  portable archive saved");
                    refreshActions();
                    Toast.makeText(this, "Saved .alloy.zip project", Toast.LENGTH_LONG).show();
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (transferId != activeProjectTransferId || isFinishing()) return;
                    projectTransferring = false;
                    activeProjectTransfer = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Project save failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void readProjectArchive(Uri source) {
        if (source == null || printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            if (printerBusy || importing || slicing || batchSlicing || batchTransferring)
                Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        final String historyName = displayName(source);
        final long transferId = projectTransferIds.incrementAndGet();
        activeProjectTransferId = transferId;
        projectTransferring = true;
        status.setText("Project  ·  opening portable archive…");
        refreshActions();
        activeProjectTransfer = importExecutor.submit(() -> {
            try (InputStream input = getContentResolver().openInputStream(source)) {
                if (input == null) throw new IllegalArgumentException("The project archive could not be opened");
                ProjectArchive.ImportedProject imported = ProjectArchive.read(input, new java.io.File(getFilesDir(), "projects"));
                mainHandler.post(() -> {
                    if (transferId != activeProjectTransferId || isFinishing()) return;
                    projectTransferring = false;
                    activeProjectTransfer = null;
                    try {
                        applyImportedProject(imported);
                        ProjectArchive.pruneStoredProjects(new java.io.File(getFilesDir(), "projects"), imported.plates);
                        projectHistoryStore.record(historyName, source, projectModelCount(imported.plates), imported.plates.size());
                        Toast.makeText(this, "Opened portable Alloy project", Toast.LENGTH_LONG).show();
                    } catch (Exception error) {
                        Toast.makeText(this, "Project could not be opened: " + error.getMessage(), Toast.LENGTH_LONG).show();
                        refreshActions();
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> {
                    if (transferId != activeProjectTransferId || isFinishing()) return;
                    projectTransferring = false;
                    activeProjectTransfer = null;
                    status.setText(model == null ? "Import a model to begin" : "Prepare  ·  " + model.displayName);
                    refreshActions();
                    Toast.makeText(this, "Project open failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void applyImportedProject(ProjectArchive.ImportedProject imported) throws Exception {
        if (imported == null || imported.plates == null || imported.plates.isEmpty())
            throw new IllegalArgumentException("The project contains no plates");
        if (imported.inventorySnapshot != null) inventoryStore.restoreSnapshot(imported.inventorySnapshot);
        cancelImport();
        cancelGeometryRepair();
        cancelNativeBoolean();
        sliceJobs.cancel();
        batchSliceJobs.cancel();
        batchSlicing = false;
        slicing = false;
        model = null;
        sourceModel = null;
        unmodifiedSourceModel = null;
        geometryRepairEnabled = false;
        partTransforms.clear();
        modelUris.clear();
        modelNames.clear();
        modelScale = 1f;
        modelRotationDegrees = 0f;
        modelTiltXDegrees = 0f;
        modelTiltYDegrees = 0f;
        slice = null;
        stagedArtifact = null;
        lastBatch = null;
        if (batchSliceJobStore != null) batchSliceJobStore.clear();
        activeBatchSliceJobId = null;
        plateImportInFlight = false;
        projectStore.clearModel();
        if (modelHistoryStore != null) modelHistoryStore.clearAll();
        plateStore.clear();
        for (PlateStore.Plate plate : imported.plates) plateStore.save(plate);
        if (modelHistoryStore != null && imported.history != null && !imported.history.isEmpty())
            modelHistoryStore.importTimelines(imported.history);
        activePlateIndex = imported.activePlateIndex;
        plateStore.setActiveIndex(activePlateIndex);
        copyConfig(imported.config, config);
        projectStore.saveRecipe(config);
        updateInventorySummary();
        viewport.setModel(null);
        viewport.setResult(null);
        updateRecipeMarkers();
        updatePlateMarker();
        PlateStore.Plate target = plateStore.activePlate();
        if (target.uris.isEmpty()) {
            modelMeta.setText("NEW PLATE\n" + profileShortLabel());
            status.setText(currentPlateLabel() + "  ·  Import a model to begin");
            details.setText("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume");
            refreshActions();
        } else {
            plateImportInFlight = true;
            restorePlateCheckpoint(target);
            loadUris(target.uris, target.names, true);
        }
    }

    private static int projectModelCount(ArrayList<PlateStore.Plate> plates) {
        int count = 0;
        if (plates == null) return count;
        for (PlateStore.Plate plate : plates) if (plate != null && plate.uris != null) count += plate.uris.size();
        return count;
    }

    private static void copyConfig(Slicer.Config source, Slicer.Config target) {
        if (source == null || target == null) return;
        target.layerHeight = source.layerHeight;
        target.firstLayerHeight = source.firstLayerHeight;
        target.nozzle = source.nozzle;
        target.filamentDiameter = source.filamentDiameter;
        target.infill = source.infill;
        target.bedX = source.bedX;
        target.bedY = source.bedY;
        target.bedZ = source.bedZ;
        target.printer = source.printer;
        target.filament = source.filament;
        target.nozzleTemperature = source.nozzleTemperature;
        target.firstLayerNozzleTemperature = source.firstLayerNozzleTemperature;
        target.bedTemperature = source.bedTemperature;
        target.firstLayerBedTemperature = source.firstLayerBedTemperature;
        target.extrusionMultiplier = source.extrusionMultiplier;
        target.maxVolumetricSpeed = source.maxVolumetricSpeed;
        target.travelSpeed = source.travelSpeed;
        target.outerWallSpeed = source.outerWallSpeed;
        target.innerWallSpeed = source.innerWallSpeed;
        target.infillSpeed = source.infillSpeed;
        target.initialLayerSpeed = source.initialLayerSpeed;
        target.fanMinPercent = source.fanMinPercent;
        target.fanMaxPercent = source.fanMaxPercent;
        target.supports = source.supports;
        target.supportThresholdDegrees = source.supportThresholdDegrees;
        target.perimeters = source.perimeters;
        target.topLayers = source.topLayers;
        target.bottomLayers = source.bottomLayers;
        target.nativeSettings.clear();
        target.nativeSettings.putAll(source.nativeSettings);
    }

    private void showProjectMenu() {
        new AlertDialog.Builder(this)
                .setTitle("Project")
                .setItems(new String[]{"New project", "Print plates", "Open model atlas", "A1 Mini 3D study", "Save current 3D view (PNG)", "Review active profile", "Import Bambu profile(s)", "Export active profile", "Reset imported profile", "Save project archive", "Open project archive", "Project history", "Forget saved model"}, (dialog, which) -> {
                    if (which == 0) resetProject();
                    if (which == 1) showPlates();
                    if (which == 2) showModelLibrary();
                    if (which == 3) showPrinterStudy();
                    if (which == 4) openViewExport();
                    if (which == 5) showProfileReview();
                    if (which == 6) openBambuProfile();
                    if (which == 7) openProfileExport();
                    if (which == 8) resetImportedProfile();
                    if (which == 9) saveProjectArchive();
                    if (which == 10) openProjectArchive();
                    if (which == 11) showProjectHistory();
                    if (which == 12) {
                        if (printerBusy || slicing || batchSlicing || batchTransferring) {
                            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (modelHistoryStore != null) modelHistoryStore.clearDocument(activePlateIndex);
                        clearLoadedModelForPlate();
                        Toast.makeText(this, "Saved model forgotten", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showProjectHistory() {
        if (printerBusy || importing || slicing || batchSlicing || projectTransferring || batchTransferring) {
            Toast.makeText(this, "Finish the current operation first", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<ProjectHistoryStore.Record> records = projectHistoryStore.records();
        if (records.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Project history")
                    .setMessage("Saved and opened portable projects will appear here.")
                    .setPositiveButton("Done", null).show();
            return;
        }
        String[] labels = new String[records.size()];
        for (int index = 0; index < records.size(); index++) labels[index] = records.get(index).summary();
        new AlertDialog.Builder(this).setTitle("Project history")
                .setMessage("Recent portable projects · stored locally on this phone")
                .setItems(labels, (dialog, which) -> {
                    ProjectHistoryStore.Record record = records.get(which);
                    if (record.archiveUri.length() == 0) {
                        Toast.makeText(this, "This history record has no portable archive to reopen", Toast.LENGTH_LONG).show();
                        return;
                    }
                    Uri archive = Uri.parse(record.archiveUri);
                    rememberUriPermission(archive, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    readProjectArchive(archive);
                })
                .setNeutralButton("Clear", (dialog, which) -> {
                    projectHistoryStore.clear();
                    Toast.makeText(this, "Project history cleared", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Close", null).show();
    }

    private void resetProject() {
        if (printerBusy || slicing || batchSlicing || batchTransferring) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        cancelImport();
        cancelGeometryRepair();
        cancelNativeBoolean();
        sliceJobs.cancel();
        batchSliceJobs.cancel();
        batchSlicing = false;
        model = null;
        sourceModel = null;
        unmodifiedSourceModel = null;
        geometryRepairEnabled = false;
        partTransforms.clear();
        modelUris.clear();
        modelNames.clear();
        modelScale = 1f;
        modelRotationDegrees = 0f;
        modelTiltXDegrees = 0f;
        modelTiltYDegrees = 0f;
        slice = null;
        stagedArtifact = null;
        lastBatch = null;
        if (batchSliceJobStore != null) batchSliceJobStore.clear();
        activeBatchSliceJobId = null;
        slicing = false;
        plateImportInFlight = false;
        if (modelHistoryStore != null) modelHistoryStore.clearAll();
        projectStore.clearModel();
        plateStore.save(PlateStore.Plate.empty(activePlateIndex));
        pruneModelCache();
        viewport.setModel(null);
        viewport.setResult(null);
        modelMeta.setText("NEW PROJECT\n" + profileShortLabel());
        status.setText(currentPlateLabel() + "  ·  Import a model to begin");
        details.setText("STL, OBJ, 3MF and STEP  ·  " + profileBuildVolumeLabel() + " build volume");
        updatePlateMarker();
        refreshActions();
    }

    private EditText textField(String value, String hint) {
        EditText field = new EditText(this);
        field.setText(value);
        field.setHint(hint);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        return field;
    }

    private TextView sectionLabel(String text) {
        TextView view = label(text, 10, GOLD);
        view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setLetterSpacing(0.12f);
        view.setPadding(0, 18, 0, 2);
        return view;
    }

    private EditText field(String value, String hint) { return field(value, hint, false); }
    private EditText field(String value, String hint, boolean signed) { EditText field = new EditText(this); field.setText(value); field.setHint(hint); field.setTextColor(TEXT); field.setHintTextColor(MUTED); field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL | (signed ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0)); return field; }
    private static String nativeSetting(Slicer.Config config, String key, String fallback) {
        if (config == null || key == null) return fallback;
        String value = config.nativeSettings.get(key);
        return value == null || value.trim().length() == 0 ? fallback : value;
    }
    private static String plateTemperatureKey(String plate, boolean initialLayer) {
        String value = plate == null ? "" : plate.toLowerCase(Locale.US);
        String suffix = initialLayer ? "_initial_layer" : "";
        if (value.contains("cool")) return "cool_plate_temp" + suffix;
        if (value.contains("engineering") || value.startsWith("eng")) return "eng_plate_temp" + suffix;
        if (value.contains("supertack") || value.contains("super tack")) return "supertack_plate_temp" + suffix;
        if (value.contains("textured")) return "textured_plate_temp" + suffix;
        return "hot_plate_temp" + suffix;
    }
    private static float selectedPlateTemperature(Slicer.Config config, String plate, boolean initialLayer, float fallback) {
        String raw = nativeSetting(config, plateTemperatureKey(plate, initialLayer), "").trim();
        if (raw.length() == 0) return fallback;
        int comma = raw.indexOf(',');
        if (comma >= 0) raw = raw.substring(0, comma).trim();
        try {
            float value = Float.parseFloat(raw);
            return finite(value) && value >= 0f && value <= 150f ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
    private RadioGroup choiceGroup(String[][] options, String selected) {
        RadioGroup group = new RadioGroup(this);
        int selectedIndex = 0;
        for (int index = 0; index < options.length; index++) {
            RadioButton option = new RadioButton(this);
            option.setId(View.generateViewId());
            option.setText(options[index][0]);
            option.setTextColor(TEXT);
            option.setTag(options[index][1]);
            group.addView(option);
            if (options[index][1].equalsIgnoreCase(selected)) selectedIndex = index;
        }
        if (group.getChildCount() > 0) group.check(group.getChildAt(selectedIndex).getId());
        return group;
    }
    private static String selectedChoice(RadioGroup group, String fallback) {
        if (group == null) return fallback;
        View selected = group.findViewById(group.getCheckedRadioButtonId());
        Object tag = selected == null ? null : selected.getTag();
        return tag == null ? fallback : String.valueOf(tag);
    }
    private static boolean nativeBoolean(Slicer.Config config, String key, boolean fallback) {
        String value = nativeSetting(config, key, fallback ? "1" : "0").trim();
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }
    private static String number(float value) { return String.format(Locale.US, "%.5f", value); }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static String safeName(String name) {
        String value = name == null ? "alloy-job" : name.replaceAll("[^A-Za-z0-9._-]", "_").replaceAll("(?i)(\\.(stl|3mf|gcode))+$", "");
        if (value.length() == 0) return "alloy-job";
        return value.length() > 150 ? value.substring(0, 150) : value;
    }

    @Override public void onBackPressed() {
        if (importing) {
            cancelImport();
            return;
        }
        if (repairingGeometry) {
            cancelGeometryRepair();
            return;
        }
        if (projectTransferring || batchTransferring) {
            Toast.makeText(this, projectTransferring ? "Project file transfer is still running" : "Batch export is still running", Toast.LENGTH_SHORT).show();
            return;
        }
        if (batchSlicing) {
            cancelBatchSlice();
            return;
        }
        if (slicing) {
            cancelSlice();
            return;
        }
        if (printerBusy) {
            Toast.makeText(this, "Finish or cancel the active printer job first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (slice != null) { slice = null; viewport.setResult(null); refreshActions(); status.setText("Prepare  ·  " + model.displayName); return; }
        super.onBackPressed();
    }

    @Override protected void onPause() {
        saveCurrentPlate();
        if (viewport != null) viewport.onHostPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (viewport != null) viewport.onHostResume();
        refreshPrinterUi();
        refreshSliceUi();
        refreshBatchSliceUi();
    }

    @Override protected void onDestroy() {
        if (printerEventReceiver != null) {
            try { unregisterReceiver(printerEventReceiver); } catch (Exception ignored) { }
            printerEventReceiver = null;
        }
        if (sliceEventReceiver != null) {
            try { unregisterReceiver(sliceEventReceiver); } catch (Exception ignored) { }
            sliceEventReceiver = null;
        }
        if (batchSliceEventReceiver != null) {
            try { unregisterReceiver(batchSliceEventReceiver); } catch (Exception ignored) { }
            batchSliceEventReceiver = null;
        }
        cancelImport();
        cancelGeometryRepair();
        cancelVisualization();
        cancelNativeBoolean();
        activeCertificateInspectionId = certificateInspectionIds.incrementAndGet();
        if (activeCertificateInspection != null) activeCertificateInspection.cancel(true);
        activeProjectTransferId = projectTransferIds.incrementAndGet();
        if (activeProjectTransfer != null) activeProjectTransfer.cancel(true);
        activeBatchTransferId = batchTransferIds.incrementAndGet();
        if (activeBatchTransfer != null) activeBatchTransfer.cancel(true);
        if (activeDiscovery != null) {
            activeDiscovery.close();
            activeDiscovery = null;
        }
        if (activeBatchSnapshot != null && !BatchSliceJobService.ownsAnyJob()) activeBatchSnapshot.cancel(true);
        if (activeProfileImport != null) activeProfileImport.cancel(true);
        importExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        if (sliceJobs != null) sliceJobs.close();
        if (batchSliceJobs != null) batchSliceJobs.close();
        if (printerTransport != null) printerTransport.close();
        super.onDestroy();
    }
}
