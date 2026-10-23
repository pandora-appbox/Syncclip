#!/usr/bin/env python3
"""Configure Compose with a validated HTTPS URL without exposing pairing keys."""

import argparse
import os
from pathlib import Path
import subprocess
from urllib.parse import urlsplit

parser = argparse.ArgumentParser()
parser.add_argument("url")
args = parser.parse_args()
url = urlsplit(args.url)
if (
    url.scheme != "https"
    or not url.hostname
    or url.username
    or url.password
    or url.query
    or url.fragment
    or url.path.rstrip("/") != "/syncclip"
    or url.port not in (None, 443)
):
    parser.error("Use https://YOUR_DOMAIN/syncclip (standard HTTPS port 443)")
# Restrict dotenv syntax: no newlines, interpolation, config injection or shell fragments.
if any(
    c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.-"
    for c in url.hostname
):
    parser.error("Use a DNS hostname or IPv4 address")
root = Path(__file__).resolve().parents[1]
subprocess.run(
    ["python3", str(root / "scripts/provision.py"), args.url.rstrip("/")], check=True
)
path = root / ".env"
content = "SYNC_DOMAIN=" + url.hostname + "\n"
if path.exists() and path.read_text() != content:
    parser.error("Existing .env differs; preserve custom settings and edit it manually")
if not path.exists():
    with os.fdopen(
        os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w"
    ) as f:
        f.write(content)
print("Server configuration ready; pairing credentials remain in private/")
