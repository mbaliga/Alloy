Warning: truncated output (original token count: 100366)
Total output lines: 6913

package com.mbaliga.alloy;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private static final String BUNDLED_PROFILE_ID = "bundled_profile_id";
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
            android.content.SharedPreferences profilePreferences = getPreferences(MODE_PRIVATE);
            String bundledPrinterId = profilePreferences.getString(BUNDLED_PROFILE_PRINTER_ID, "bambu.a1-mini");
            String bundledProfileId = profilePreferences.getString(BUNDLED_PROFILE_ID, "");
            profile = bundledProfileId.isEmpty()
                    ? ProfileCatalog.loadInitialByPrinterId(getAssets(), bundledPrinterId)
                    : ProfileCatalog.loadPlanningById(getAssets(), bundledProfileId);
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
        if (current == null || jobId == null…88366 tokens truncated…egory(Intent.CATEGORY_OPENABLE);
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
