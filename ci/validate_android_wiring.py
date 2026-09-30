#!/usr/bin/env python3
"""Static guardrails for the phone-first Android shell and import lifecycle."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def require(text: str, token: str, path: Path) -> None:
    if token not in text:
        raise SystemExit(f"{path}: missing Android wiring: {token}")


def main() -> None:
    activity_path = ROOT / "app/src/main/java/com/mbaliga/alloy/MainActivity.java"
    viewport_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ViewportView.java"
    gles_viewport_path = ROOT / "app/src/main/java/com/mbaliga/alloy/GlesViewportSurface.java"
    project_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ProjectStore.java"
    plate_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PlateStore.java"
    archive_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ProjectArchive.java"
    preparation_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PreparationValidator.java"
    inventory_path = ROOT / "app/src/main/java/com/mbaliga/alloy/InventoryStore.java"
    pipeline_test_path = ROOT / "app/src/androidTest/java/com/mbaliga/alloy/AndroidPipelineTest.java"
    native_smoke_test_path = ROOT / "app/src/androidTest/java/com/mbaliga/alloy/NativeEngineSmokeTest.java"
    catalog_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ModelCatalog.java"
    community_catalog_path = ROOT / "app/src/main/java/com/mbaliga/alloy/CommunityModelCatalog.java"
    model_store_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ModelStore.java"
    model_history_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ModelHistoryStore.java"
    studio_preview_path = ROOT / "app/src/main/java/com/mbaliga/alloy/StudioPreviewRenderer.java"
    g4_scope_validator_path = ROOT / "ci/validate_g4_scope.py"
    g4_scope_path = ROOT / "parity/a1mini_g4_scope.json"
    job_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterJobStore.java"
    service_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterJobService.java"
    reconciliation_path = ROOT / "app/src/main/java/com/mbaliga/alloy/InventoryReconciliationStore.java"
    batch_slice_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchSliceJobController.java"
    batch_archive_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchArtifactArchive.java"
    slice_job_path = ROOT / "app/src/main/java/com/mbaliga/alloy/SliceJobStore.java"
    slice_service_path = ROOT / "app/src/main/java/com/mbaliga/alloy/SliceJobService.java"
    slice_request_path = ROOT / "app/src/main/java/com/mbaliga/alloy/SliceRequestStore.java"
    slice_result_path = ROOT / "app/src/main/java/com/mbaliga/alloy/SliceResultStore.java"
    batch_slice_job_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchSliceJobStore.java"
    batch_slice_service_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchSliceJobService.java"
    batch_slice_request_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchSliceRequestStore.java"
    batch_slice_result_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BatchSliceResultStore.java"
    safety_path = ROOT / "app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java"
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    splash_path = ROOT / "app/src/main/res/drawable/splash_screen.xml"
    logo_path = ROOT / "app/src/main/res/drawable-nodpi/alloy_logo.png"
    activity = activity_path.read_text()
    viewport = viewport_path.read_text()
    gles_viewport = gles_viewport_path.read_text()
    project = project_path.read_text()
    plate = plate_path.read_text()
    archive = archive_path.read_text()
    preparation = preparation_path.read_text()
    inventory = inventory_path.read_text()
    pipeline_test = pipeline_test_path.read_text()
    native_smoke_test = native_smoke_test_path.read_text()
    catalog = catalog_path.read_text()
    community_catalog = community_catalog_path.read_text()
    model_store = model_store_path.read_text()
    model_history = model_history_path.read_text()
    studio_preview = studio_preview_path.read_text()
    g4_scope_validator = g4_scope_validator_path.read_text()
    g4_scope = g4_scope_path.read_text()
    job = job_path.read_text()
    service = service_path.read_text()
    reconciliation = reconciliation_path.read_text()
    batch_slice = batch_slice_path.read_text()
    batch_archive = batch_archive_path.read_text()
    slice_job = slice_job_path.read_text()
    slice_service = slice_service_path.read_text()
    slice_request = slice_request_path.read_text()
    slice_result = slice_result_path.read_text()
    batch_slice_job = batch_slice_job_path.read_text()
    batch_slice_service = batch_slice_service_path.read_text()
    batch_slice_request = batch_slice_request_path.read_text()
    batch_slice_result = batch_slice_result_path.read_text()
    safety = safety_path.read_text()
    manifest = manifest_path.read_text()
    splash = splash_path.read_text()

    for token in (
        "ExecutorService importExecutor",
        "activeImportId",
        "Thread.currentThread().isInterrupted()",
        "cancelImport()",
        "loadShowcaseModel()",
        "ModelWorkbench.createShowcaseBoxAssembly",
        "ACTION_OPEN_DOCUMENT",
        "setType(\"*/*\")",
        "EXTRA_ALLOW_MULTIPLE",
        "FLAG_GRANT_PERSISTABLE_URI_PERMISSION",
        "model/obj",
        "firstLayerNozzleTemperature",
        "maxVolumetricSpeed",
        "updateRecipeMarkers()",
        "inventoryStatusDot",
        "setContentDescription(summary)", "setBuildVolume(config.bedX, config.bedY, config.bedZ)",
        "setOnApplyWindowInsetsListener",
        "WindowInsets.Type.systemBars()",
        "requestApplyInsets()",
        "sectionLabel(\"QUALITY\")",
        "PrinterJobStore printerJobStore",
        "recoverAfterRestart(PrinterJobService.ownsAnyJob())",
        "recoveredArtifact",
        "hasPrinterRecovery()",
        "printerJobStore.begin(target, artifact, filament, filamentMm, filamentDiameterMm)",
        "ArtifactStore.recover(getFilesDir()",
        "ArtifactStore.stage(getFilesDir()",
        "Export destination received an incomplete artifact",
        "Export source changed while copying",
        "PrinterTransport.State.RECOVERY_REQUIRED",
        "Review printer job",
        "partTransforms",
        "showPartTransform",
        "autoArrangeParts",
        "rebuildModel",
        "PreparationValidator.validate",
        "beginSlice()",
        "PlateStore plateStore",
        "showPlates()",
        "saveCurrentPlate()",
        "restorePlateCheckpoint",
        "onPause()",
        "POST_NOTIFICATIONS",
        "REQUEST_NOTIFICATIONS",
        "requestNotificationPermissionIfNeeded",
        "onRequestPermissionsResult",
        "REQUEST_PROJECT_EXPORT",
        "REQUEST_PROJECT_OPEN",
        "saveProjectArchive()",
        "openProjectArchive()",
        "ProjectArchive.write",
        "ProjectArchive.read",
        "ProjectArchive.pruneStoredProjects",
        "ProjectHistoryStore",
        "showProjectHistory",
        "projectModelCount",
        "applyImportedProject",
        "modelUriFromIntent",
        "ModelStore.materialize",
        "ModelStore.materializeAsset",
        "ModelStore.prune",
        "ModelHistoryStore",
        "modelHistoryStore",
        "prepareModelMutation",
        "finishModelMutation",
        "undoModel",
        "redoModel",
        "showModelHistory",
        "referencedPlates",
        "showCommunityModelSources()",
        "repairGeometry()",
        "restoreOriginalGeometry()",
        "cancelGeometryRepair()",
        "geometryRepairEnabled",
        "openArcContext()",
        "showMoreActions()",
        "showActiveArcOperation()",
        "onContext() { openArcContext(); }",
        "onMore() { showMoreActions(); }",
    ):
        require(activity, token, activity_path)
    for token in ("REQUEST_BATCH_EXPORT", "beginBatchSlice()", "BatchSliceJobController", "exportBatchArchive()",
                  "writeBatchArchive", "batchSlicing", "batchTransferring", "Export all plates"):
        require(activity, token, activity_path)
    for token in ("registerSliceEvents", "handleSliceEvent", "SliceJobService.start", "SliceJobService.cancel",
                  "restoreForegroundSliceIfMatching", "refreshSliceUi", "activeSliceJobId", "sameConfig"):
        require(activity, token, activity_path)
    for token in (
        "FIRST_LAYER_HEIGHT",
        "NOZZLE_TEMPERATURE",
        "EXTRUSION_MULTIPLIER",
        "MAX_SAVED_MODELS",
        "MAX_SAVED_URI_LENGTH",
        "MODEL_TILT_X",
        "MODEL_TILT_Y",
        "savedTiltX",
        "savedTiltY",
        "PART_TRANSFORMS",
        "savePartTransforms",
        "savedPartTransforms",
        "validateUri",
        "boundedFloat",
        "saveRecipe", "NATIVE_SETTINGS", "NativeSettings.encode", "NativeSettings.parse", "putString(NATIVE_SETTINGS",
        "GEOMETRY_REPAIR", "savedGeometryRepair", "saveGeometryRepair",
    ):
        require(project, token, project_path)
    mesh_path = ROOT / "app/src/main/java/com/mbaliga/alloy/MeshModel.java"
    mesh = mesh_path.read_text()
    for token in ("if (isZip(data)) return read3mf(name + \".3mf\", data)", "if (isLikelyObj(data)) return readObj(name + \".obj\", data)", "return readStl(name + \".stl\", data)", "if (lower.endsWith(\".obj\")) return readObj(name, data)", "private static MeshModel readObj", "MAX_3MF_RELATIONSHIPS_BYTES", "readEntryBytes", "root relationships do not target", "MAX_STL_LINE_CHARS", "MAX_OBJ_LINE_CHARS", "public RepairResult repair", "removedDegenerateTriangles", "normalizeClosedWinding", "RepairVertexKey", "RepairTriangleKey"):
        require(mesh, token, mesh_path)
    for token in ("public static Report validate", "isReady", "build volume", "profile.verified", "config.supports"):
        require(preparation, token, preparation_path)
    for token in ("MAX_PLATES", "activePlate()", "createNext()", "tilt_x", "tilt_y", "part_transforms", "geometry_repair", "geometryRepairEnabled", "validUri"):
        require(plate, token, plate_path)
    for token in ("alloy-project", "VERSION = 3", "MAX_ARCHIVE_BYTES", "MAX_MODEL_BYTES", "MAX_THUMBNAIL_BYTES", "MAX_STORED_PROJECTS", "MAX_STORED_PROJECT_BYTES", "thumbnail.png", "tilt_x", "tilt_y", "geometry_repair", "history", "ModelHistoryStore.Timeline", "encodePlate", "parsePlate", "MessageDigest", "sha256", "bytes", "BoundedInputStream", "unreferenced model entry", "SourceProvider", "ZipInputStream", "safePath", "validModelPath", "obj", "parseRecipe", "NativeSettings.encode", "NativeSettings.parseField", "native_settings", "isPng", "pruneStoredProjects", "isSymbolicLink", "Project storage root must not be a symbolic link"):
        require(archive, token, archive_path)
    credentials_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterCredentialStore.java"
    credentials = credentials_path.read_text()
    for token in ("AndroidKeyStore", "AES/GCM/NoPadding", "safeField", "topicToken", "Character.isISOControl", "certificate_sha256", "hasCertificatePin", "Could not persist encrypted printer credentials", "commit()"):
        require(credentials, token, credentials_path)
    for token in ("lastServicedKey", "nextServiceKey", "serviceIntervalDays", "markServiced", "serviceLabel", "serviceSoon", "serviceOverdue", "needsServiceAttention", "addCustom", "delete", "CUSTOM_ITEMS", "isCustom", "snapshot", "restoreSnapshot", "validateSnapshot", "recordCompletedPrint", "filamentUsageMm", "consumedJobs", "MAX_CONSUMED_JOBS"):
        require(inventory, token, inventory_path)
    history_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ProjectHistoryStore.java"
    history = history_path.read_text()
    for token in ("MAX_RECORDS", "records()", "record(", "clear()", "normalizeUri", "SharedPreferences"):
        require(history, token, history_path)
    for token in ("MAX_ENTRIES", "resetDocument", "ensureCurrent", "append", "undo", "redo",
                  "canUndo", "canRedo", "referencedPlates", "PlateStore.decodeSnapshot", "MAX_STORAGE_BYTES"):
        require(model_history, token, model_history_path)
    for token in ("Finish", "Environment", "MATTE_BLACK", "WORKSHOP", "MAX_PIXELS", "finishMatrix", "drawEnvironment", "concept renderer"):
        require(studio_preview, token, studio_preview_path)
    for token in ("setEGLContextClientVersion(2)", "RENDERMODE_WHEN_DIRTY", "setPreserveEGLContextOnPause",
                  "MESH_VERTEX_SHADER", "MESH_FRAGMENT_SHADER", "MAX_TOOLPATH_SEGMENTS", "outsideBuildVolume",
                  "uSelectedPart", "uOutOfBounds", "thumbnailPng", "capturePng", "PixelCopy", "setBuildVolume", "hitTest"):
        require(gles_viewport, token, gles_viewport_path)
    for token in ("slicebeam_commit", "required", "excluded", "rationales", "unreviewed unsupported field", "status_by_key"):
        require(g4_scope_validator, token, g4_scope_validator_path)
    for token in ("schema_version", "engine_commit", "multi_material_or_toolchange_not_targeted", "machine_maintenance_or_telemetry_not_targeted", "optional_process_feature_not_targeted"):
        require(g4_scope, token, g4_scope_path)
    for token in ("bundledModelSlicesAndStagesValidatedPackage", "modelImportSniffsStlWhenDocumentNameHasNoExtension", "modelImportRejectsUnsafe3mfZipEntry", "modelImportReadsValid3mfUnitComponentAndMetadataEntries", "modelImportRejectsMismatched3mfRootRelationship", "modelImportRejectsOversizedAsciiStlLine", "modelImportPreservesNamedObjPartsAndNegativeIndices", "meshRepairRemovesDegenerateDuplicatesWeldsVerticesAndNormalizesClosedWinding", "bambuTelemetryRequiresStructuredMatching", "bambuTelemetrySummaryIsBoundedAndStructured", "bambuPayloadUsesConservativeLocalPrintFields", "bambuStopPayloadIsAConservativeCommand", "bambuTelemetryDoesNotAcceptNearMatchJobNames", "bambuCancellationRequiresPriorTargetIdentity", "bambuFtpParsersValidatePassiveAddressAndRemoteSize", "bambuMqttParserHandlesQosAndRejectsMalformedPackets", "bambuCertificateFingerprintNormalizationIsStrict", "persistedNativeSettingsRejectUnknownProjection", "durableArtifactCanBeRecoveredAfterCheckpointReload", "projectAndInventoryCheckpointsSurviveReload", "printerJobCheckpointFailsClosedAfterReload", "partTransformChangesOnlySelectedPartAndSurvivesReload", "offlineFallbackGeneratesConservativeSupportsForDownwardFace", "preparationPreflightBlocksModelOutsideBuildVolume", "gcodeSafetyIgnoresCommentTokens", "gcodeSafetyStrictlyBoundsRawMovesAndRequiresSafeShutdown", "gcodeSafetyStreamHandlesChunkBoundaries", "nativePreviewPreservesPerimeterSupportAndNeutralToolpaths", "nativePreviewHonorsCancelledSliceThread", "customInventoryItemsPersistWithReorderAndService", "inventoryFlagsServiceSoonAndOverdue", "projectHistoryIsBoundedAndReloadable", "plateSnapshotsSurviveReload", "projectArchiveEmbedsModelsPlatesAndRecipe", "projectArchivePreservesObjSourceExtension", "projectArchiveRejectsTamperedEmbeddedModel", "extractedProjectStoragePrunesUnreferencedCopies", "projectRetentionDoesNotFollowSymlinks", "printerCredentialsRejectProtocolControlCharacters", "artifactStoreRejectsSymlinkedSource", "artifactStoreRejectsSymlinkedTarget", "createSymbolicLink", "thumbnailPng", "tiltXDegrees", "tiltYDegrees", "ModelCatalog.load", "ModelCatalog.verify", "GcodePackageValidator.validate"):
        require(pipeline_test, token, pipeline_test_path)
    require(pipeline_test, "localStudioPreviewAppliesBoundedFinishAndEnvironment", pipeline_test_path)
    require(Path(ROOT / "parity/test_g4_scope.py").read_text(), "test_rejects_unreviewed_field", ROOT / "parity/test_g4_scope.py")
    for token in ("unreadablePrinterCheckpointStillBlocksSending", "stalePrinterCallbackCannotOverwriteNewArtifactCheckpoint",
                  "importedModelSourcesMaterializeForOfflineUse", "bundledExampleMaterializesForPortableProjects",
                  "misleadingDocumentNameUsesDetectedModelParser", "nativePreviewRejectsAnOversizedOutputLine",
                  "legacyCachedModelRechecksItsContentFormat", "completedPrintConsumesFilamentOnceAndTriggersReorder",
                  "printerCheckpointPersistsMaterialEstimateForCompletionAccounting", "printerCheckpointAllowsOnlyConfirmedPauseResumeTransitions", "physicalPrinterCredentialsRequireLeafCertificatePin",
                  "printerWorkerLeaseSurvivesActivityRecreationButNotProcessRestart",
                  "batchArtifactsAreIndividuallyValidatedAndPortable",
                  "foregroundBatchSnapshotAndPerPlateResultsSurviveActivityRecreation",
                  "foregroundBatchCheckpointFailsClosedAfterProcessLoss",
                  "foregroundSliceSnapshotAndResultSurviveActivityRecreation",
        "foregroundSliceCheckpointFailsClosedAfterProcessLoss", "modelHistoryPersistsUndoRedoAndDropsRedoBranch",
        "historicalModelSnapshotsSurviveCachePrune"):
        require(pipeline_test, token, pipeline_test_path)
    require(pipeline_test, "liveGlesCaptureReturnsBoundedPngForA1Study", pipeline_test_path)
    require(activity, "viewport.capturePng(1_024", activity_path)
    for token in ("typedRecipeOwnsOverlappingNativeSettings",):
        require(native_smoke_test, token, native_smoke_test_path)
    for token in ("MAX_GCODE_CHARS", "class Stream", "hasPrintableMotion", "removeCommentsAndChecksum"):
        require(safety, token, safety_path)
    for token in ("normalizeAssetPath", "verify(AssetManager assets, Entry entry)", "sha256", "MAX_CATALOG_BYTES"):
        require(catalog, token, catalog_path)
    for token in ("Collections.unmodifiableList", "https://pinshape.com/items/33841-3d-printed-recurve-bow",
                  "https://cults3d.com/en/3d-model/game/100-3d-printed-compound-bow",
                  "https://makerworld.com/es/models/1140708-compound-bow", "licenseNote", "safetyNote",
                  "browserIntent()"):
        require(community_catalog, token, community_catalog_path)
    for token in ("MAX_MODEL_BYTES", "MAX_TOTAL_BYTES", "ATOMIC_MOVE", "content-addressed", "getFD().sync()", ".part",
                  "isInside", "SAFE_NAME", "detectExtension", "parserName", "extension", "Model source must not be a symbolic link"):
        require(model_store, token, model_store_path)
    for token in ("recoverAfterRestart", "RECOVERY_REQUIRED", "MAX_ARTIFACT_BYTES", "commit()", "requiresRecovery", "isUnreadable", "checkpoint is unreadable", "JOB_ID", "canTransition", "jobId", "claim", "heartbeat", "release", "WORKER_LEASE_TIMEOUT_MS"):
        require(job, token, job_path)
    for token in ("extends Service", "ACTION_UPLOAD", "ACTION_START", "ACTION_PAUSE", "ACTION_RESUME", "ACTION_CANCEL", "ACTION_STATUS",
                  "startForegroundCompat", "FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE", "ArtifactStore.recover",
                  "InventoryReconciliationStore", "PrinterJobStore", "interruptedJob", "recoverAfterRestart(false)",
                  "sendBroadcast", "START_NOT_STICKY", "canRequestCancel", "not in a cancellable state",
                  "CANCEL_REQUESTED"):
        require(service, token, service_path)
    for token in ("class InventoryReconciliationStore", "enqueue", "drain", "pendingCount", "MAX_PENDING", "recordCompletedPrint", "commit()"):
        require(reconciliation, token, reconciliation_path)
    for forbidden in ("if (pause && job.state", "if (!pause && job.state"):
        if forbidden in service:
            raise SystemExit(f"{service_path}: cancellation path contains a pause/resume-only guard: {forbidden}")
    for token in ("class BatchSliceJobController", "PlateStore.MAX_PLATES", "ArtifactStore.stage", "ModelStore.materialize",
                  "engine.slice", "BatchResult", "CancellationException", "alloy-batch-slice", "PlateCallback", "static BatchResult run"):
        require(batch_slice, token, batch_slice_path)
    for token in ("class BatchArtifactArchive", "alloy-batch.json", "GcodePackageValidator.validate",
                  "validateManifest", "unreferenced artifact", "BoundedOutputStream", "MAX_ARCHIVE_BYTES"):
        require(batch_archive, token, batch_archive_path)
    for token in ("enum State", "RECOVERY_REQUIRED", "recoverAfterRestart", "commit()", "artifactSha256",
                  "isTerminal", "modelSha256"):
        require(slice_job, token, slice_job_path)
    for token in ("extends Service", "ACTION_START", "ACTION_CANCEL", "ACTION_STATUS", "startForegroundCompat",
                  "FOREGROUND_SERVICE_TYPE_DATA_SYNC", "SliceRequestStore.read", "SliceResultStore.write",
                  "ArtifactStore.stage", "START_NOT_STICKY", "recoverAfterRestart"):
        require(slice_service, token, slice_service_path)
    for token in ("class SliceRequestStore", "writeBinaryStl", "fingerprint", "configFingerprint", "Slicer.validate", "model_sha256", "recipe_sha256",
                  "ATOMIC_MOVE", "MAX_REQUEST_BYTES", "isSymbolicLink", "NativeSettings.encode", "NativeSettings.parse",
                  "native_settings"):
        require(slice_request, token, slice_request_path)
    for token in ("class SliceResultStore", "GcodeSafetyValidator.requireSafe", "result.gcode", "engine_verified",
                  "NativeSlicerEngine.parseGcode", "ATOMIC_MOVE", "MAX_GCODE_BYTES"):
        require(slice_result, token, slice_result_path)
    for token in ("enum State", "RECOVERY_REQUIRED", "recoverAfterRestart", "completedPlates", "commit()", "isTerminal"):
        require(batch_slice_job, token, batch_slice_job_path)
    for token in ("extends Service", "ACTION_START", "ACTION_CANCEL", "ACTION_STATUS", "startForegroundCompat",
                  "FOREGROUND_SERVICE_TYPE_DATA_SYNC", "BatchSliceRequestStore.read", "BatchSliceResultStore.record",
                  "BatchSliceResultStore.read", "START_NOT_STICKY", "recoverAfterRestart"):
        require(batch_slice_service, token, batch_slice_service_path)
    for token in ("class BatchSliceRequestStore", "materialize", "fingerprint", "project_sha256", "ATOMIC_MOVE",
                  "MAX_REQUEST_BYTES", "isSymbolicLink", "NativeSettings.encode", "NativeSettings.parse",
                  "native_settings", "new TreeMap<>(config.nativeSettings)"):
        require(batch_slice_request, token, batch_slice_request_path)
    for token in ("class BatchSliceResultStore", "manifest.json", "SliceResultStore.write", "ArtifactStore.recover",
                  "GcodePackageValidator.validate", "completed", "result_dir"):
        require(batch_slice_result, token, batch_slice_result_path)
    for token in ("public final class ViewportView", "GlesViewportSurface", "MAX_DRAW_TRIANGLES",
                  "setModel", "setResult", "setToolpathOnly", "setSelectedLayer", "setSelectedPart",
                  "thumbnailPng", "onHostPause", "onHostResume"):
        require(viewport, token, viewport_path)
    if gles_viewport.count("panX += focusX - lastFocusX;") != 1:
        raise SystemExit(f"{gles_viewport_path}: two-finger horizontal pan must apply exactly once")
    for token in (
        'android:allowBackup="false"',
        'android:icon="@drawable/ic_alloy_launcher"',
        'android:label="@string/app_name"',
        'android:roundIcon="@drawable/ic_alloy_launcher"',
        'android:usesCleartextTraffic="false"',
        'android:exported="true"',
        'android:name=".PrinterJobService"',
        'android:exported="false"',
        'android:foregroundServiceType="dataSync|connectedDevice"',
        'android:name=".SliceJobService"',
        'android:foregroundServiceType="dataSync"',
        'android:name=".BatchSliceJobService"',
        'android.permission.FOREGROUND_SERVICE',
        'android.permission.FOREGROUND_SERVICE_DATA_SYNC',
        'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE',
        'android.permission.CHANGE_NETWORK_STATE',
        'android.permission.POST_NOTIFICATIONS',
        'android.intent.action.VIEW',
        'android.intent.action.SEND',
        'android:mimeType="model/stl"',
        'android:mimeType="model/obj"',
        'android:mimeType="model/3mf"',
        'android:mimeType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"',
    ):
        require(manifest, token, manifest_path)
    for token in ('@drawable/alloy_logo', '@color/splash_black'):
        require(splash, token, splash_path)
    if not logo_path.is_file() or logo_path.stat().st_size == 0:
        raise SystemExit(f"missing or empty Alloy logo asset: {logo_path}")
    require(activity, "onNewIntent(Intent intent)", activity_path)
    require(pipeline_test, "sharedModelIntentCarriesStreamUri", pipeline_test_path)
    for asset in (
        "app/src/main/assets/models/box-and-lid.stl",
        "app/src/main/assets/models/box-20mm.stl",
        "app/src/main/assets/models/mounting-block.stl",
        "app/src/main/assets/models/catalog.json",
    ):
        if not (ROOT / asset).is_file():
            raise SystemExit(f"missing bundled Android model asset: {asset}")
    print("validated Android shell, import lifecycle, manifest and bundled model wiring")


if __name__ == "__main__":
    main()
