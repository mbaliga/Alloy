#!/usr/bin/env python3
"""Static guardrails for the Bambu LAN transport boundary."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(text: str, token: str, path: Path) -> None:
    if token not in text:
        raise SystemExit(f"{path}: missing required transport wiring: {token}")


def main() -> None:
    transport_path = ROOT / "app/src/main/java/com/mbaliga/alloy/BambuLanTransport.java"
    artifact_path = ROOT / "app/src/main/java/com/mbaliga/alloy/ArtifactStore.java"
    package_path = ROOT / "app/src/main/java/com/mbaliga/alloy/GcodePackageValidator.java"
    interface_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterTransport.java"
    safety_path = ROOT / "app/src/main/java/com/mbaliga/alloy/GcodeSafetyValidator.java"
    activity_path = ROOT / "app/src/main/java/com/mbaliga/alloy/MainActivity.java"
    manifest_path = ROOT / "app/src/main/AndroidManifest.xml"
    docs_path = ROOT / "docs/BAMBU_LAN_TRANSPORT.md"
    probe_path = ROOT / "tools/bambu_lan_probe.py"
    build_path = ROOT / "app/build.gradle"
    transport = transport_path.read_text()
    artifact = artifact_path.read_text()
    package = package_path.read_text()
    interface_text = interface_path.read_text()
    safety = safety_path.read_text()
    activity = activity_path.read_text()
    manifest = manifest_path.read_text()
    docs = docs_path.read_text()
    probe = probe_path.read_text()
    build = build_path.read_text()

    for token in (
        "implements PrinterTransport",
        "FTPS_PORT = 990",
        "MQTT_PORT = 8883",
        "SSLSocketFactory",
        "PASV",
        "sha256",
        "project_file",
        "reportTopic",
        "Telemetry.parse",
        "telemetryMentionsJob",
        "gcode_file",
        "bed_levelling",
        "bed_type",
        "CANCEL_REQUESTED",
        "public synchronized void cancel(PrinterTarget target, Callback callback)",
        "certificate fingerprint mismatch",
        "lengthBytes > 4",
        "Malformed MQTT publish QoS",
        "Malformed MQTT packet flags",
        "MAX_FTP_REPLY_BYTES",
        "out-of-range FTP passive address",
        "SIZE /",
        "remote file verification",
        "readMqttPacket",
        "parsePassiveAddress",
        "parseRemoteSize",
        "normalizeFingerprint",
        "stopPayload",
        "readStatus",
        "STATUS_TIMEOUT_MS",
        "Telemetry · state",
        "telemetrySummary",
        "mc_percent",
        "nozzle_temper",
        "bed_temper",
        "BouncyCastleJsseProvider",
        "BCSSLSocket",
        "setBCSessionToResume",
        "getBCSession",
        "TLSv1.2",
        "FTPS data TLS session was not resumed",
    ):
        require(transport, token, transport_path)
    for token in ("GcodePackageWriter.write", "thumbnailPng", "getFD().sync", "SHA-256", "MAX_STAGED_ARTIFACTS", "recover(File storageDir", "GcodePackageValidator.validate", "recoverForA1MiniNoSupportPilot", "Artifact storage root must not be a symbolic link", "isSymbolicLink(target)"):
        require(artifact, token, artifact_path)
    package_writer = (ROOT / "app/src/main/java/com/mbaliga/alloy/GcodePackageWriter.java").read_text()
    for token in ("writePackageModelXml", "writeObjectModelXml", "OutputStreamWriter", "mesh_health", "nozzle_temperature_c", "first_layer_height_mm", "Metadata/plate_1.png", "Metadata/plate_1_small.png", "Metadata/bbl_thumbnail.png", "Metadata/plate_1.gcode.md5", "Metadata/cut_information.xml", "Metadata/filament_sequence.json", "printProfileConfig", "projectSettingsConfig", "modelSettingsConfig", "sliceInfoConfig", "rootRelationships", "modelRelationships", "image/png"):
        require(package_writer, token, ROOT / "app/src/main/java/com/mbaliga/alloy/GcodePackageWriter.java")
    require(transport, "GcodePackageValidator.validate", transport_path)
    for token in ("ZipInputStream", "MAX_ENTRY_BYTES", "MAX_ENTRY_COUNT", "MAX_VALIDATION_TEXT_BYTES", "MAX_THUMBNAIL_BYTES", "isPng",
                  "GcodeSafetyValidator.Stream", "gcodeScanner.finish()", "unsafe or duplicate", "validateContentTypes", "validateRootRelationships",
                  "validateModelRelationships", "validatePackageModel", "validateObjectModel", "validateMetadata", "validateConfig",
                  "validateModelSettings", "validateCutInformation", "validateFilamentSequence", "validateProjectSettings", "validatePrintProfile",
                  "gcodeDigest", "actualGcodeMd5", "validateForA1MiniNoSupportPilot", "Pilot packages must remain explicitly unverified"):
        require(package, token, package_path)
    for token in ("MAX_GCODE_CHARS", "M104", "hasStopTemperature", "hasPrintableMotion",
                  "removeCommentsAndChecksum", "StandardCharsets.UTF_8"):
        require(safety, token, safety_path)
    for token in ("sourceFile", "CANCEL_REQUESTED", "PAUSE_REQUESTED", "PAUSED", "RESUME_REQUESTED", "RECOVERY_REQUIRED", "SHA256_PATTERN", "artifact digest must be SHA-256",
                  "artifact size does not match its source file"):
        require(interface_text, token, interface_path)
    job_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterJobStore.java"
    job = job_path.read_text()
    for token in ("recoverAfterRestart", "printer job checkpoint", "requiresRecovery", "MAX_ARTIFACT_BYTES", "updateIfMatches",
                  "claim", "heartbeat", "release", "WORKER_LEASE_TIMEOUT_MS"):
        require(job, token, job_path)
    service_path = ROOT / "app/src/main/java/com/mbaliga/alloy/PrinterJobService.java"
    service = service_path.read_text()
    for token in ("extends Service", "startForegroundCompat", "ACTION_UPLOAD", "ACTION_START", "ACTION_PAUSE", "ACTION_RESUME", "ACTION_CANCEL",
                  "ACTION_STATUS", "sendBroadcast", "ArtifactStore.recover", "START_NOT_STICKY"):
        require(service, token, service_path)
    require(manifest, 'android:allowBackup="false"', manifest_path)
    for token in ("cancelPrint()", "pausePrint()", "resumePrint()", "Cancel print", "Pause print", "Resume print", "activePrinterTarget", "printerBusy",
                  "PrinterJobService.startUpload", "PrinterJobService.startPrint", "PrinterJobService.pausePrint", "PrinterJobService.resumePrint", "PrinterJobService.cancelPrint",
                  "refreshPrinterStatus", "Refresh telemetry", "readStatus", "STATUS_UNCONFIRMED",
                  "activePrinterTarget = null", "Upload controlled pilot package?", "A1MiniNoSupportPilot.evaluate",
                  "printerBusy = false"):
        require(activity, token, activity_path)
    for token in (
        "upload-only",
        "telemetry",
        "session reuse",
        "must not treat an MQTT publish as proof",
    ):
        require(docs, token, docs_path)
    for token in ("build_curl_config", "validate_remote", "validate_host", "validate_access_code", "build_start_payload", "telemetry_matches_job", "client.on_message", "accepted.wait", "--telemetry-timeout", "--config", "paho-mqtt"):
        require(probe, token, probe_path)
    for token in ("org.bouncycastle:bcprov-jdk18on:1.85.2", "org.bouncycastle:bctls-jdk18on:1.85"):
        require(build, token, build_path)
    data_start = transport.find("SSLSocket data = null;")
    if data_start < 0:
        raise SystemExit(f"{transport_path}: missing FTPS data socket block")
    data_block = transport[data_start:]
    resume_at = data_block.find("setBCSessionToResume(controlSession)")
    handshake_at = data_block.find("data.startHandshake()")
    if resume_at < 0 or handshake_at < 0 or resume_at > handshake_at:
        raise SystemExit(f"{transport_path}: FTPS data session must be selected before handshake")
    if "TrustAll" in transport or "ALLOW_ALL" in transport:
        raise SystemExit(f"{transport_path}: insecure trust-all TLS shortcut detected")
    print("validated Bambu LAN transport wiring")


if __name__ == "__main__":
    main()
