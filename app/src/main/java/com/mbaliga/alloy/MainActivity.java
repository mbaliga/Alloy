Warning: truncated output (original token count: 96435)
Total output lines: 6683

package com.mbaliga.alloy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
    private static final String BUNDLED_PROFILE_PRINTER_ID = "bundled_profile_printer_id";
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
    private static final String FIRST_RUN_ONBOARDING_VERSION = "first_run_onboarding_version";
    private static final int CURRENT_FIRST_RUN_ONBOARDING_VERSION = 1;
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
    private ArcNavigationBar arcNavigation;
    private SeekBar layerSeek;
    private TextView layerInspectorLabel;
    private TextView status, details, modelMeta, inventorySummary, inventoryStatusDot;
    private TextView printerMarkerValue, materialMarkerValue, qualityMarkerValue, supportsMarkerValue, plateMarkerValue;
    private View printerMarker, materialMarker;
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
    // Set only after an untouched bundled fixture finishes importing. User
    // imports, restored projects and all geometry mutations clear this proof.
    private String trustedPilotFixtureAssetPath;
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
    private Dialog sliceProgressDialog;
    private FilamentSweepLoader sliceProgressLoader;
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
                recoveredArtifact = recoverPrinterArtifact(recoveredJob);
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
            String bundledPrinterId = getPreferences(MODE_PRIVATE)
                    .getString(BUNDLED_PROFILE_PRINTER_ID, "bambu.a1-mini");
            profile = ProfileCatalog.loadInitialByPrinterId(getAssets(), bundledPrinterId);
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
                    if (introVersion < CURRENT_IMMERSIVE_INTRO_VERSION && !firstRunOnboardingPending()) {
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
        if (incoming == null) scheduleFirstRunOnboarding();
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
            showSliceProgressLoader(progress, phase);
        } else if (state == SliceJobStore.State.COMPLETED) {
            activeSliceJobId = null;
            slicing = false;
            dismissSliceProgressLoader();
            if (!restoreForegroundSliceIfMatching()) {
                status.setText(model == null ? "Import a model to begin" : "Prepare  ·  slice result needs review");
                Toast.makeText(this, "Slice completed but its local result could not be revalidated", Toast.LENGTH_LONG).show();
            }
        } else if (state == SliceJobStore.State.CANCELLED) {
            activeSliceJobId = null;
            slicing = false;
            dismissSliceProgressLoader();
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  slice cancelled");
        } else if (state == SliceJobStore.State.RECOVERY_REQUIRED) {
            activeSliceJobId = null;
            slicing = false;
            dismissSliceProgressLoader();
            status.setText(model == null ? "Import a model to begin" : "Prepare  ·  previous slice needs review");
        } else if (state == SliceJobStore.State.FAILED) {
            activeSliceJobId = null;
            slicing = false;
            dismissSliceProgressLoader();
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
            showSliceProgressLoader(job.progress, job.phase);
        } else if (job.state == SliceJobStore.State.COMPLETED && model != null) {
            dismissSliceProgressLoader();
            if (restoreForegroundSliceIfMatching()) {
                status.setText("Inspect  ·  " + slice.layers.size() + " layers");
                details.setText(String.format(Locale.US, "%.0f mm filament  ·  %s  ·  %d warning(s)", slice.filamentMm,
                        slice.printTimeSeconds < 0f ? "time pending" : formatDuration(slice.printTimeSeconds), slice.warnings));
            }
        }
        refreshActions();
    }

    /**
     * Slicing happens in a durable foreground service. This surface mirrors
     * its actual progress and offers a visible cancellation route; it never
     * manufactures completion just to make the animation look polished.
     */
    private void showSliceProgressLoader(int progress, String phase) {
        if (isFinishing()) return;
        if (sliceProgressDialog == null) {
            sliceProgressDialog = new Dialog(this);
            sliceProgressDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            sliceProgressDialog.setCancelable(false);
            FrameLayout page = new FrameLayout(this);
            page.setBackgroundColor(Color.rgb(5, 8, 8));
            sliceProgressLoader = new FilamentSweepLoader(this);
            page.addView(sliceProgressLoader, new FrameLayout.LayoutParams(-1, -1));
            TextView cancel = label("Cancel slice", 14, Color.rgb(184, 188, 184));
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(18), dp(12), dp(18), dp(12));
            cancel.setContentDescription("Cancel slicing");
            cancel.setOnClickListener(v -> cancelSlice());
            FrameLayout.LayoutParams cancelLp = new FrameLayout.LayoutParams(-2, -2,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            cancelLp.topMargin = dp(24);
            page.addView(cancel, cancelLp);
            sliceProgressDialog.setContentView(page);
            Window dialogWindow = sliceProgressDialog.getWindow();
            if (dialogWindow != null) {
                dialogWindow.setBackgroundDrawable(new ColorDrawable(Color.rgb(5, 8, 8)));
                dialogWindow.setDimAmount(0f);
            }
        }
        sliceProgressLoader.setProgress(progress, phase);
        if (!sliceProgressDialog.isShowing()) {
            sliceProgressDialog.show();
            Window dialogWindow = sliceProgressDialog.getWindow();
            if (dialogWindow != null) dialogWindow.setLayout(-1, -1);
        }
    }

    private void dismissSliceProgressLoader() {
        if (sliceProgressDialog != null && sliceProgressDialog.isShowing()) sliceProgressDialog.dismiss();
        sliceProgressDialog = null;
        sliceProgressLoader = null;
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
            recoveredArtifact = recoverPrinterArtifact(job);
        } catch (Exception ignored) {
            recoveredArtifact = null;
        }
    }

    private PrinterTransport.Artifact recoverPrinterArtifact(PrinterJobStore.Job job) throws Exception {
        if (BuildConfig.PHYSICAL_PILOT_ENABLED) {
            return ArtifactStore.recoverForA1MiniNoSupportPilot(getFilesDir(), job.artifactName,
                    job.artifactSize, job.artifactSha256);
        }
        return ArtifactStore.recoverForPhysicalPrint(getFilesDir(), job.artifactName,
                job.artifactSize, job.artifactSha256);
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

        // Dynamic task actions stay in this off-screen source of truth. The
        // upper curve triggers one contextual next step; the lower "more"
        // destination exposes the remaining routes. This avoids turning the
        // contextual bar into an unstructured list of every action.
        actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        arcNavigation = new ArcNavigationBar(this);
        arcNavigation.setListener(new ArcNavigationBar.Listener() {
            @Override public void onContext() { openArcContext(); }
            @Override public void onSearch() { showModelLibrary(); }
            @Override public void onAlerts() { showPrinterReadiness(); }
            @Override public void onHome() {
                if (viewport != null) viewport.resetView();
                status.setText(model == null ? "Start  ·  choose a model" : "Prepare  ·  " + model.displayName);
            }
            @Override public void onLibrary() { showModelLibrary(); }
            @Override public void onPrepare() { showPrepare(); }
            @Override public void onHistory() { showModelHistory(); }
            @Override public void onMore() { showMoreActions(); }
        });
        root.addView(arcNavigation, new LinearLayout.LayoutParams(-1, dp(116)));

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

        ImageView logo = alloyLogo();
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        logoLp.topMargin = dp(14);
        stage.addView(logo, logoLp);

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
        stage.addView(sceneTag("▣", "PRINTER", profilePrinterLabel().toUpperCase(Locale.US), Gravity.BOTTOM | Gravity.START, dp(22), dp(62)));
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
        context.setContentDescription("Open Learn: beginner guide, materials and troubleshooting");
        context.setOnClickListener(v -> showLearnHub());

        // The real mark owns the visual centre of the header. It is clipped
        // to a circle, rather than being scaled down inside one, so the black
        // field and luminous lettering retain the intended icon treatment.
        ImageView logo = alloyLogo();
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        logoLp.topMargin = 0;
        header.addView(logo, logoLp);

        LinearLayout leftControls = new LinearLayout(this);
        leftControls.setGravity(Gravity.CENTER_VERTICAL);
        TextView printerStudy = control("3D", "Open model 3D study", v -> {
            if (model == null) showModelLibrary();
            else showImmersiveView();
        });
        TextView machineStudy = control("A1", "Open A1 Mini 3D study", v -> showPrinterStudy());
        leftControls.addView(printerStudy, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams machineStudyLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        machineStudyLp.leftMargin = dp(6);
        leftControls.addView(machineStudy, machineStudyLp);
        FrameLayout.LayoutParams leftControlsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        leftControlsLp.leftMargin = dp(2);
        header.addView(leftControls, leftControlsLp);

        LinearLayout rightControls = new LinearLayout(this);
        rightControls.setGravity(Gravity.CENTER_VERTICAL);
        TextView inventory = control("▦", "Workshop inventory", v -> showInventory());
        TextView menu = control("·", "Project menu", v -> showProjectMenu());
        LinearLayout.LayoutParams printerInventoryLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        rightControls.addView(inventory, printerInventoryLp);
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        menuLp.leftMargin = dp(6);
        rightControls.addView(menu, menuLp);
        FrameLayout.LayoutParams rightControlsLp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        rightControlsLp.rightMargin = dp(2);
        header.addView(rightControls, rightControlsLp);
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
        ImageView logo = alloyLogo();
        FrameLayout.LayoutParams logoLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        logoLp.topMargin = dp(12);
        stage.addView(logo, logoLp);
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
        // The fit button occupies the upper-right control rail. Keep all
        // three configurator callouts below that rail on portrait screens;
        // the previous 78dp row collided with the nozzle pill and fit button
        // on narrow devices, making the first frame feel like a debug HUD.
        // Leave a full control-height plus touch-spacing below the fit button.
        // On tall, dense phones 124dp still landed on that rail after status
        // bar/window insets were applied to the stage.
        int calloutTop = dp(160);
        View sizeTag = compactSceneTag("□", "SIZE", "180 × 180 × 180 MM",
                Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, calloutTop);
        sizeTag.setOnClickListener(v -> {
            scene.fitModel();
            Toast.makeText(this, "A1 Mini volume · 180 × 180 × 180 mm", Toast.LENGTH_SHORT).show();
        });
        stage.addView(sizeTag);
        View materialTag = compactSceneTag("◌", "MATERIAL", "NATURAL PLA",
                Gravity.TOP | Gravity.START, dp(8), calloutTop);
        materialTag.setOnClickListener(v -> {
            int next = (scene.getFinishMode() + 1) % 5;
            scene.setFinishMode(next);
            String[] finishes = {"Natural PLA", "Matte black", "Silk white", "Signal orange", "Steel blue"};
            Toast.makeText(this, "Preview finish · " + finishes[next], Toast.LENGTH_SHORT).show();
        });
        stage.addView(materialTag);
        View nozzleTag = compactSceneTag("⌁", "NOZZLE", "0.4 MM",
                Gravity.TOP | Gravity.END, dp(8), calloutTop);
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

    /** Active dimensions are profile-derived, bounded to the disclosed A1/P1S maximum. */
    private float activeBedX() { return finite(config.bedX) ? clamp(config.bedX, 0.5f, 256f) : 180f; }
    private float activeBedY() { return finite(config.bedY) ? clamp(config.bedY, 0.5f, 256f) : 180f; }
    private float activeBedZ() { return finite(config.bedZ) ? clamp(config.bedZ, 0.5f, 256f) : 180f; }

    private void requireActiveBuildVolume(MeshModel candidate) throws IOException {
        if (candidate == null) throw new IOException("Generated model is empty");
        float width = candidate.maxX - candidate.minX;
        float depth = candidate.maxY - candidate.minY;
        float height = candidate.maxZ - candidate.minZ;
        if (width > activeBedX() + 0.001f || depth > activeBedY() + 0.001f || height > activeBedZ() + 0.001f)
            throw new IOException("Generated model exceeds the " + profileBuildVolumeLabel() + " "
                    + profilePrinterLabel() + " build volume");
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
        if ("Printer".equals(eyebrow)) { printerMarkerValue = main; printerMarker = pill; }
        if ("Material".equals(eyebrow)) { materialMarkerValue = main; materialMarker = pill; }
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
        if (printerMarkerValue != null) printerMarkerValue.setText(profilePrinterLabel());
        if (printerMarker != null) printerMarker.setContentDescription("Printer: " + profilePrinterLabel());
        if (materialMarkerValue != null) materialMarkerValue.setText(profileMaterialLabel());
        if (materialMarker != null) materialMarker.setContentDescription("Material: " + profileMaterialLabel());
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

    /** One true mark everywhere: centred, circle-clipped and never shrunk into a badge. */
    private ImageView alloyLogo() {
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.alloy_logo);
        logo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        logo.setBackground(round(Color.BLACK, Color.rgb(99, 224, 211), 1, 21));
        logo.setClipToOutline(true);
        logo.setContentDescription("Alloy");
        return logo;
    }

    private void showPrinterStatus() {
        PrinterCredentialStore.Credentials credentials = savedCredentials();
        String statusTitle = profile == null ? "Printer status" : profilePrinterLabel();
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
                .setTitle(statusTitle)
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
        EditText name = textField(current == null ? profilePrinterLabel() : current.name, "Printer name");
        EditText host = textField(current == null ? "" : current.host, "LAN IP or hostname");
        EditText serial = textField(current == null ? "" : current.serial, "Printer serial");
        EditText modelCode = textField(current == null ? "" : current.model,
                "Bambu model code (N1 = A1 Mini; required for current pilot)");
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
             …46435 tokens truncated…ndex, current.scale,
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
            // Packing only chooses a reversible 0°/90° XY rotation. It must
            // not be presented as an orientation recommendation: tilt and
            // first-contact decisions can change supports, strength and
            // surface quality and remain visible user choices.
            status.setText("Prepare  ·  parts packed on plate");
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
            showSliceProgressLoader(0, "Preparing the slice");
            SliceJobService.start(this, jobId);
        } catch (Exception error) {
            activeSliceJobId = null;
            slicing = false;
            dismissSliceProgressLoader();
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
            // Preserve the head position while cancellation is acknowledged.
            // Snapping it back to zero would falsely suggest the durable job
            // restarted, even though SliceJobStore retains its last progress.
            SliceJobStore.Job current = sliceJobStore == null ? null : sliceJobStore.load();
            int visibleProgress = current == null ? 0 : current.progress;
            showSliceProgressLoader(visibleProgress, "Cancelling slice");
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

    /**
     * A preflight summary deliberately distinguishes the model toolpath from
     * consumables a phone-side engine cannot measure. In particular, a
     * single-material slice must never claim a purge, prime, or separately
     * weighed support total that has not been emitted by the engine.
     */
    private void showPrintPlan() {
        if (slice == null || slice.layers == null || slice.layers.isEmpty()) {
            Toast.makeText(this, "Slice the model before opening its print plan", Toast.LENGTH_SHORT).show();
            return;
        }
        PrintPlanEstimate estimate = PrintPlanEstimate.from(slice, config);
        String orientation = String.format(Locale.US, "Z rotation %.0f°  ·  X tilt %.0f°  ·  Y tilt %.0f°  ·  scale %.0f%%",
                modelRotationDegrees, modelTiltXDegrees, modelTiltYDegrees, modelScale * 100f);
        String message = String.format(Locale.US,
                "%s\n%.0f mm  ·  about %.1f g of %s\n\nSUPPORT MATERIAL\n%s\n\nPRIME / PURGE / CLEANING\n%s\n\nESTIMATED PRINT TIME\n%s\n\nCURRENT RECIPE\nNozzle %.0f°C (first layer %.0f°C)  ·  plate %.0f°C (first layer %.0f°C)\n%.2f mm layers  ·  %.0f%% infill  ·  %d walls\n\nORIENTATION\n%s\nUse Model → Lay flat or Auto orient, then review the first layer and supports before slicing again.\n\nPRINTER STATUS\nPlanning profile only  ·  no live printer temperature or idle/running telemetry has been verified.",
                estimate.filamentScope, estimate.reportedFilamentMm, estimate.approximateReportedFilamentGrams, config.filament,
                estimate.supportMaterial, estimate.primePurgeCleaning, estimate.time,
                config.nozzleTemperature, config.firstLayerNozzleTemperature,
                config.bedTemperature, config.firstLayerBedTemperature,
                config.layerHeight, config.infill * 100f, config.perimeters, orientation);
        new AlertDialog.Builder(this)
                .setTitle("Print plan")
                .setMessage(message)
                .setNegativeButton("Inspect toolpath", (dialog, which) -> showInspection())
                .setNeutralButton("Orientation", (dialog, which) -> showModelWorkbench())
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

    /** Offer a bounded package to another Android app without implying recipient compatibility. */
    private void sharePackage() {
        sharePackage(false);
    }

    /**
     * Attempt Bambu Handy only when it advertises a suitable Android share
     * target. An available activity is not treated as package/import proof.
     */
    private void shareToBambuHandy() {
        sharePackage(true);
    }

    private void sharePackage(boolean preferBambuHandy) {
        if (importing || model == null || slice == null) {
            Toast.makeText(this, "Slice a model before sharing a package", Toast.LENGTH_SHORT).show();
            return;
        }
        ArtifactValidator.Report report = ArtifactValidator.validate(model, slice, config);
        if (!report.isValid()) {
            Toast.makeText(this, "Sharing blocked: " + report.summary(), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (stagedArtifact == null || stagedArtifact.sourceFile == null
                    || stagedArtifact.sourceFile.length() != stagedArtifact.sizeBytes
                    || !ArtifactStore.sha256(stagedArtifact.sourceFile).equalsIgnoreCase(stagedArtifact.sha256)) {
                stagedArtifact = ArtifactStore.stage(getFilesDir(), model, slice, config,
                        artifactDisplayName(), viewport.thumbnailPng(512));
            }
            Uri uri = ArtifactShareProvider.uriFor(getPackageName(), stagedArtifact.displayName);
            Intent generic = BambuHandyHandoff.genericShare(uri);
            if (preferBambuHandy) {
                Intent targeted = BambuHandyHandoff.targetedShare(uri);
                if (BambuHandyHandoff.canHandle(getPackageManager(), targeted)) {
                    startActivity(targeted);
                    Toast.makeText(this,
                            "Bambu Handy opened. Confirm its import result; recipient compatibility is not yet verified.",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this,
                            "Bambu Handy is unavailable for this package type. Choose a recipient manually.",
                            Toast.LENGTH_LONG).show();
                    startActivity(Intent.createChooser(generic,
                            "Share validated package — confirm recipient compatibility"));
                }
            } else {
                startActivity(Intent.createChooser(generic,
                        "Share validated package — confirm recipient compatibility"));
            }
        } catch (Exception error) {
            Toast.makeText(this, "Package sharing failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        // The ordinary on-device fallback emits only conservative grid
        // supports. Do not expose native tree labels there: selecting one
        // would create a user expectation the fallback cannot meet. The
        // native build keeps the source-backed choices visible, while its
        // Bambu TreeSupport3D parity remains separately gated.
        String[][] supportStyleOptions = BuildConfig.NATIVE_ENGINE_ENABLED
                ? new String[][]{{"Organic tree", "organic"}, {"Slim tree", "tree"}, {"Grid", "grid"}}
                : new String[][]{{"Grid (offline fallback)", "grid"}};
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
        Button materialScope = dialogButton("Review material, feed route & spool fit", v -> showMaterialScopeReview());
        materialScope.setContentDescription("Review material compatibility, feed route and spool fit for the active planning printer");
        fields.addView(materialScope, new LinearLayout.LayoutParams(-1, dp(46)));
        TextView materialScopeNote = label("The active recipe stays profile-locked. This review explains compatible material and spool forms; it never changes temperatures or qualifies a printer send.", 12, MUTED);
        materialScopeNote.setPadding(0, dp(4), 0, dp(8));
        materialScopeNote.setLineSpacing(dp(2), 1f);
        fields.addView(materialScopeNote);
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

    /**
     * Keep Bambu-published material/spool capability beside the actual recipe
     * decision. This deliberately is not a material selector: choosing PETG
     * while retaining a PLA profile would be an unsafe, misleading shortcut.
     */
    private void showMaterialScopeReview() {
        if (profile == null) {
            Toast.makeText(this, "Load a planning profile before reviewing materials", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            PrinterCapabilityCatalog catalog = PrinterCapabilityCatalog.load(getAssets());
            PrinterCapabilityCatalog.Printer printer = catalog.byId(profile.printerId);
            if (printer == null) {
                Toast.makeText(this, "No bounded material catalog is available for this profile", Toast.LENGTH_LONG).show();
                return;
            }
            LinearLayout page = new LinearLayout(this);
            page.setOrientation(LinearLayout.VERTICAL);
            page.setPadding(dp(22), dp(8), dp(22), dp(4));
            TextView active = label("ACTIVE PROFILE\n" + profile.name + "\n" + profile.filamentName
                    + " · " + profile.material + " · " + String.format(Locale.US, "%.2f mm", profile.filamentDiameter), 14, TEXT);
            active.setLineSpacing(dp(2), 1f);
            page.addView(active);
            TextView boundary = label("Compatibility is not a recipe. To change filament, select or import a source-backed material profile, then re-slice and review. Direct send remains " + printer.directSendState + ".", 12, RED);
            boundary.setPadding(0, dp(10), 0, dp(6)); boundary.setLineSpacing(dp(2), 1f);
            page.addView(boundary);
            TextView materials = label("MATERIAL SCOPE", 10, GOLD); materials.setLetterSpacing(0.08f);
            materials.setPadding(0, dp(8), 0, 0); page.addView(materials);
            for (PrinterCapabilityCatalog.Material material : printer.materials) {
                TextView row = label(material.name + "\nBambu: " + material.bambuStatus
                        + " · Alloy: " + material.directSendState + "\n" + material.note, 12, TEXT);
                row.setPadding(0, dp(7), 0, 0); row.setLineSpacing(dp(2), 1f); page.addView(row);
            }
            TextView routes = label("FEED ROUTES", 10, GOLD); routes.setLetterSpacing(0.08f);
            routes.setPadding(0, dp(12), 0, 0); page.addView(routes);
            for (PrinterCapabilityCatalog.FeedRoute route : printer.feedRoutes) {
                TextView row = label(route.title + " · " + route.state + "\n" + route.note, 12, TEXT);
                row.setPadding(0, dp(7), 0, 0); row.setLineSpacing(dp(2), 1f); page.addView(row);
            }
            TextView forms = label("SPOOL FORM & FIT", 10, GOLD); forms.setLetterSpacing(0.08f);
            forms.setPadding(0, dp(12), 0, 0); page.addView(forms);
            for (PrinterCapabilityCatalog.SpoolForm form : printer.spoolForms) {
                TextView row = label(form.title + " · " + form.state + "\n"
                        + "Form: " + form.form + "\nRoutes: " + form.compatibleRoutes
                        + "\nFit: " + form.geometry + "\n" + form.note, 12, TEXT);
                row.setPadding(0, dp(7), 0, 0); row.setLineSpacing(dp(2), 1f); page.addView(row);
            }
            TextView source = label("Source/scope · " + printer.source + " · reviewed " + printer.reviewed, 10, MUTED);
            source.setPadding(0, dp(12), 0, 0); source.setLineSpacing(dp(2), 1f); page.addView(source);
            Button openSource = dialogButton("Open official Bambu source", v -> openCapabilitySource(printer.sourceUrl));
            LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(-1, dp(44)); sourceLp.topMargin = dp(8); page.addView(openSource, sourceLp);
            ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.addView(page);
            new AlertDialog.Builder(this)
                    .setTitle("Material & spool review")
                    .setView(scroll)
                    .setPositiveButton("Done", null)
                    .show();
        } catch (IOException error) {
            Toast.makeText(this, "Material catalog is unavailable: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openCapabilitySource(String sourceUrl) {
        try {
            Uri uri = Uri.parse(sourceUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || !(uri.getHost().equalsIgnoreCase("bambulab.com")
                    || uri.getHost().toLowerCase(Locale.US).endsWith(".bambulab.com")))
                throw new IllegalArgumentException("The capability source is not an official HTTPS URL");
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            Toast.makeText(this, "Could not open the official source: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
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
        trustedPilotFixtureAssetPath = null;
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
        dismissSliceProgressLoader();
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
