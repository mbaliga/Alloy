#!/usr/bin/env python3
"""Minimal Bambu LAN Developer Mode upload + print-start probe.

This is intentionally independent of Alloy's Android transport implementation.
It exists to prove the printer protocol before that behavior is embedded in the app.

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
import ftplib
import hashlib
import json
import os
import socket
import ssl
import sys
from pathlib import Path

import paho.mqtt.client as mqtt


class ImplicitFTP_TLS(ftplib.FTP_TLS):
    """FTP_TLS variant for implicit TLS (Bambu FTPS port 990)."""

    def connect(self, host="", port=0, timeout=-999, source_address=None):
        if host:
            self.host = host
        if port > 0:
            self.port = port
        if timeout != -999:
            self.timeout = timeout
        if source_address is not None:
            self.source_address = source_address

        raw_sock = socket.create_connection(
            (self.host, self.port), self.timeout, source_address=self.source_address
        )
        self.af = raw_sock.family
        self.sock = self.context.wrap_socket(raw_sock, server_hostname=self.host)
        self.file = self.sock.makefile("r", encoding=self.encoding)
        self.welcome = self.getresp()
        return self.welcome



def upload_ftps(host: str, access_code: str, local: Path, remote: str) -> None:
    context = ssl.create_default_context()
    # Current Bambu LAN endpoints commonly present a self-signed certificate.
    # Developer Mode authentication still occurs with the local access code.
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE

    ftp = ImplicitFTP_TLS(context=context, timeout=20)
    try:
        ftp.connect(host, 990)
        ftp.login("bblp", access_code)
        ftp.prot_p()
        with local.open("rb") as handle:
            ftp.storbinary(f"STOR {remote}", handle)
    finally:
        try:
            ftp.quit()
        except Exception:
            ftp.close()



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
            # Firmware/tools in the ecosystem use both spellings. Sending both is
            # deliberate until the A1 Mini hardware fixture settles the contract.
            "bed_leveling": True,
            "bed_levelling": True,
            "flow_cali": False,
            "vibration_cali": True,
            "layer_inspect": False,
            "use_ams": False,
            "ams_mapping": [-1],
        }
    }

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=f"alloy-{os.getpid()}")
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
    if args.file.suffix.lower() != ".3mf":
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
    print("Start command published. Confirm acceptance on the printer/status channel.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
