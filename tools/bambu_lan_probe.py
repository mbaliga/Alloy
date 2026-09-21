#!/usr/bin/env python3
"""Minimal Bambu LAN Developer Mode upload + print-start probe.

This is intentionally independent of Alloy's Android transport implementation.
It exists to prove the printer protocol before that behavior is embedded in the app.

Requirements:
- curl built with TLS support
- paho-mqtt (see tools/requirements.txt)

Usage example:
  python tools/bambu_lan_probe.py \
    --host 192.168.1.50 \
    --serial 01S... \
    --access-code 12345678 \
    --file ./fixture.gcode.3mf \
    --upload-only

Remove --upload-only only when the uploaded artifact is known-good and you
intend to start a physical print.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import ssl
import subprocess
import sys
import threading
import time
from pathlib import Path
from urllib.parse import quote

try:
    import paho.mqtt.client as mqtt
except ImportError:  # Keep offline payload/config tests usable without paho.
    mqtt = None


SAFE_REMOTE_PART = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,180}\Z")
SAFE_TOPIC_TOKEN = re.compile(r"[A-Za-z0-9._:-]{1,128}\Z")


def validate_remote(remote: str) -> str:
    """Accept only a relative, printable artifact path with safe components."""
    if not remote or len(remote) > 240 or remote.startswith("/"):
        raise ValueError("remote artifact path must be a bounded relative path")
    parts = remote.split("/")
    if not parts or any(not SAFE_REMOTE_PART.fullmatch(part) for part in parts):
        raise ValueError("remote artifact path contains an unsafe component")
    if not parts[-1].lower().endswith(".gcode.3mf"):
        raise ValueError("remote artifact must end in .gcode.3mf")
    return remote


def validate_serial(serial: str) -> str:
    if not serial or not SAFE_TOPIC_TOKEN.fullmatch(serial):
        raise ValueError("printer serial is not a safe MQTT topic token")
    return serial


def validate_host(host: str) -> str:
    if not host or len(host) > 253 or any(character.isspace() or ord(character) < 0x20 for character in host):
        raise ValueError("printer host must be a bounded printable token")
    return host


def validate_access_code(access_code: str) -> str:
    if not access_code or len(access_code) > 128 or any(ord(character) < 0x20 or ord(character) == 0x7F for character in access_code):
        raise ValueError("access code must be a bounded protocol-safe line")
    return access_code


def curl_config_value(value: object) -> str:
    """Quote a value for curl's config-file syntax without shell evaluation."""
    text = str(value).replace("\\", "\\\\").replace('"', '\\"')
    text = text.replace("\r", "\\r").replace("\n", "\\n")
    return f'"{text}"'


def build_curl_config(host: str, access_code: str, local: Path, remote: str) -> str:
    validate_host(host)
    validate_access_code(access_code)
    remote = validate_remote(remote)
    remote_url = f"ftps://{host}:990/{quote(remote, safe='/')}"
    return "\n".join(
        [
            "silent",
            "show-error",
            "fail",
            "insecure",
            "ftp-pasv",
            "ssl-reqd",
            f"user = {curl_config_value('bblp:' + access_code)}",
            f"upload-file = {curl_config_value(local)}",
            f"url = {curl_config_value(remote_url)}",
            "",
        ]
    )


def build_start_payload(serial: str, remote: str, name: str, plate: int) -> dict:
    """Build the deliberately conservative A1 no-AMS project_file payload."""
    validate_serial(serial)
    remote = validate_remote(remote)
    if plate < 1 or plate > 99:
        raise ValueError("plate number is out of range")
    safe_name = Path(name).name
    if not safe_name or len(safe_name) > 180 or any(ord(character) < 0x20 or ord(character) == 0x7F for character in safe_name):
        raise ValueError("job name is unsafe")
    if safe_name.lower().endswith(".gcode.3mf"):
        safe_name = safe_name[: -len(".gcode.3mf")]
    if not safe_name:
        raise ValueError("job name is unsafe")
    return {
        "print": {
            "sequence_id": "0",
            "command": "project_file",
            "param": f"Metadata/plate_{plate}.gcode",
            "project_id": "0",
            "profile_id": "0",
            "task_id": "0",
            "subtask_id": "0",
            "subtask_name": safe_name,
            # URL is the only path authority for an FTP-uploaded local job;
            # leave the optional file field empty until firmware-specific
            # semantics are validated on hardware.
            "file": "",
            "url": f"ftp:///{remote}",
            "md5": "",
            "timelapse": False,
            "bed_type": "auto",
            # Keep both spellings until the target firmware is exercised.
            "bed_leveling": True,
            "bed_levelling": True,
            "flow_cali": False,
            "vibration_cali": True,
            "layer_inspect": False,
            "use_ams": False,
        }
    }


def telemetry_matches_job(payload: str | bytes, remote: str) -> tuple[bool, str]:
    """Return whether a structured report identifies *remote* and its state."""
    remote = validate_remote(remote)
    try:
        encoded = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else payload
        root = json.loads(encoded)
        print_state = root.get("print")
        if not isinstance(print_state, dict):
            return False, ""
    except (TypeError, ValueError):
        return False, ""

    expected_remote = remote.lower()
    expected_file = Path(remote).name.lower()
    expected_name = expected_file[: -len(".gcode.3mf")]
    for key in ("subtask_name", "file", "url", "gcode_file"):
        value = str(print_state.get(key, "")).strip().lower()
        if value and (value in (expected_remote, expected_file, expected_name)
                      or value.endswith("/" + expected_remote)
                      or value.endswith("/" + expected_file)
                      or value.endswith("/" + expected_name)):
            return True, str(print_state.get("gcode_state", "")).strip().upper()
    return False, ""


def upload_ftps(host: str, access_code: str, local: Path, remote: str) -> None:
    """Upload through curl because Bambu FTPS may require TLS session reuse."""
    # Feed curl configuration on stdin so the access code is not exposed in the
    # process command line. -k/insecure is required for the printer's current
    # self-signed LAN certificate.
    curl_config = build_curl_config(host, access_code, local, remote)
    try:
        subprocess.run(
            ["curl", "--config", "-"],
            input=curl_config,
            text=True,
            check=True,
        )
    except FileNotFoundError as exc:
        raise RuntimeError("curl is required for the FTPS transport probe") from exc


def start_project(
    host: str,
    serial: str,
    access_code: str,
    remote: str,
    name: str,
    plate: int,
    telemetry_timeout: float = 30.0,
) -> None:
    if mqtt is None:
        raise RuntimeError("paho-mqtt is required for the MQTT transport probe")
    validate_host(host)
    validate_access_code(access_code)
    topic = f"device/{validate_serial(serial)}/request"
    payload = build_start_payload(serial, remote, name, plate)

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"alloy-{os.getpid()}",
    )
    client.username_pw_set("bblp", access_code)
    client.tls_set(cert_reqs=ssl.CERT_NONE)
    client.tls_insecure_set(True)
    client.connect(host, 8883, keepalive=20)
    client.loop_start()
    accepted = threading.Event()
    telemetry_state: dict[str, str] = {"value": ""}

    def on_message(_client: object, _userdata: object, message: object) -> None:
        matches, state = telemetry_matches_job(getattr(message, "payload", b""), remote)
        if matches and state in {"PREPARE", "RUNNING"}:
            telemetry_state["value"] = state
            accepted.set()

    client.on_message = on_message
    try:
        subscribe_result = client.subscribe(f"device/{validate_serial(serial)}/report", qos=0)
        subscribe_rc = subscribe_result[0] if isinstance(subscribe_result, tuple) else subscribe_result
        if subscribe_rc != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError(f"MQTT telemetry subscription failed: rc={subscribe_rc}")
        info = client.publish(topic, json.dumps(payload), qos=0)
        info.wait_for_publish(timeout=10)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError(f"MQTT publish failed: rc={info.rc}")
        deadline = time.monotonic() + max(1.0, min(float(telemetry_timeout), 300.0))
        if not accepted.wait(max(0.0, deadline - time.monotonic())):
            raise RuntimeError("printer did not confirm PREPARE/RUNNING telemetry for this job")
        print(f"Printer telemetry: {telemetry_state['value']} ({remote})")
    finally:
        client.loop_stop()
        client.disconnect()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True, help="A1 Mini LAN IP")
    parser.add_argument("--serial", required=True, help="Printer serial number")
    parser.add_argument("--access-code", required=True, help="Developer/LAN access code")
    parser.add_argument("--file", required=True, type=Path, help="Known-good .gcode.3mf")
    parser.add_argument("--remote", help="Remote SD path; defaults to cache/<filename>")
    parser.add_argument("--plate", type=int, default=1, help="Plate number inside the 3MF")
    parser.add_argument(
        "--upload-only",
        action="store_true",
        help="Upload but do not send the command that starts a physical print",
    )
    parser.add_argument(
        "--telemetry-timeout",
        type=float,
        default=30.0,
        help="Seconds to wait for matching PREPARE/RUNNING telemetry after start (default: 30)",
    )
    args = parser.parse_args()

    if not args.file.is_file():
        parser.error(f"not a file: {args.file}")
    if not args.file.name.lower().endswith(".gcode.3mf"):
        print("warning: expected a .gcode.3mf project", file=sys.stderr)

    remote = validate_remote(args.remote or args.file.name)
    digest = hashlib.sha256(args.file.read_bytes()).hexdigest()
    print(f"Local artifact: {args.file}")
    print(f"SHA-256:       {digest}")
    print(f"Remote path:   {remote}")
    print(f"Printer:       {args.host} / {args.serial}")

    print("Uploading over implicit FTPS...")
    upload_ftps(args.host, args.access_code, args.file, remote)
    print("Upload completed.")

    if args.upload_only:
        print("Upload-only requested; no print-start command sent.")
        return 0

    confirmation = input(
        "This will command the physical printer to start the uploaded job. Type PRINT: "
    )
    if confirmation != "PRINT":
        print("Print start cancelled.")
        return 2

    print("Publishing MQTT project_file command...")
    start_project(
        args.host,
        args.serial,
        args.access_code,
        remote,
        args.file.name,
        args.plate,
        args.telemetry_timeout,
    )
    print("Start command accepted by matching printer telemetry.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
