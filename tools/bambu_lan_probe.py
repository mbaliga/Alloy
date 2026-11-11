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
import ssl
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote

import paho.mqtt.client as mqtt


def upload_ftps(host: str, access_code: str, local: Path, remote: str) -> None:
    """Upload through curl because Bambu FTPS may require TLS session reuse."""
    remote_url = f"ftps://{host}:990/{quote(remote, safe='/')}"
    # Feed curl configuration on stdin so the access code is not exposed in the
    # process command line. -k/insecure is required for the printer's current
    # self-signed LAN certificate.
    curl_config = "\n".join(
        [
            "silent",
            "show-error",
            "fail",
            "insecure",
            "ftp-pasv",
            "ssl-reqd",
            f'user = "bblp:{access_code}"',
            f'upload-file = "{local}"',
            f'url = "{remote_url}"',
            "",
        ]
    )
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
) -> None:
    topic = f"device/{serial}/request"
    payload = {
        "print": {
            "sequence_id": "0",
            "command": "project_file",
            "param": f"Metadata/plate_{plate}.gcode",
            "project_id": "0",
            "profile_id": "0",
            "task_id": "0",
            "subtask_id": "0",
            "subtask_name": name,
            "file": remote,
            "url": f"file:///sdcard/{remote}",
            "md5": "",
            "timelapse": False,
            "bed_type": "auto",
            # Current ecosystem implementations expose both spellings across
            # firmware generations. Keep both until the A1 Mini hardware fixture
            # settles the exact accepted contract.
            "bed_leveling": True,
            "bed_levelling": True,
            "flow_cali": False,
            "vibration_cali": True,
            "layer_inspect": False,
            "use_ams": False,
            "ams_mapping": [-1],
        }
    }

    client = mqtt.Client(
        mqtt.CallbackAPIVersion.VERSION2,
        client_id=f"alloy-{os.getpid()}",
    )
    client.username_pw_set("bblp", access_code)
    client.tls_set(cert_reqs=ssl.CERT_NONE)
    client.tls_insecure_set(True)
    client.connect(host, 8883, keepalive=20)
    client.loop_start()
    try:
        info = client.publish(topic, json.dumps(payload), qos=0)
        info.wait_for_publish(timeout=10)
        if info.rc != mqtt.MQTT_ERR_SUCCESS:
            raise RuntimeError(f"MQTT publish failed: rc={info.rc}")
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
    args = parser.parse_args()

    if not args.file.is_file():
        parser.error(f"not a file: {args.file}")
    if not args.file.name.lower().endswith(".gcode.3mf"):
        print("warning: expected a .gcode.3mf project", file=sys.stderr)

    remote = args.remote or f"cache/{args.file.name}"
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
    )
    print("Start command published. Confirm acceptance on printer/status telemetry.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
