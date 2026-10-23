#!/usr/bin/env python3
"""Create a private pairing bundle; the relay receives only an auth hash."""

import argparse
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
from urllib.parse import urlparse, urlencode


def pairing_link(config):
    return "syncclip://pair?" + urlencode(config)


def validate(config):
    url = urlparse(config["url"])
    if (
        url.scheme != "https"
        or not url.hostname
        or url.username
        or url.password
        or url.query
        or url.fragment
    ):
        raise ValueError("The server must be an HTTPS address")
    raw = base64.urlsafe_b64decode(config["key"] + "=" * (-len(config["key"]) % 4))
    if len(raw) != 32:
        raise ValueError("Invalid pairing key")
    return raw


def atomic_json(path, value):
    temporary = path.with_suffix(".tmp")
    with os.fdopen(
        os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w"
    ) as f:
        json.dump(value, f)
    os.replace(temporary, path)


parser = argparse.ArgumentParser()
parser.add_argument("url")
parser.add_argument(
    "--output", type=Path, default=Path(__file__).resolve().parents[1] / "private"
)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True, mode=0o700)
path = args.output / "pairing.json"
if path.exists():
    config = json.loads(path.read_text())
    if config["url"] != args.url.rstrip("/"):
        raise SystemExit(
            "Existing pairing uses another URL; choose a different output folder."
        )
else:
    config = {
        "url": args.url.rstrip("/"),
        "key": base64.urlsafe_b64encode(os.urandom(32)).decode().rstrip("="),
    }
    validate(config)
    atomic_json(path, config)
raw = validate(config)
authorization = (
    "Bearer " + hmac.new(raw, b"syncclip-auth-v1", hashlib.sha256).hexdigest()
)
for name, content in {
    "relay.env": "SYNC_TOKEN_SHA256="
    + hashlib.sha256(authorization.encode()).hexdigest()
    + "\n",
    "pairing-link.txt": pairing_link(config) + "\n",
}.items():
    with os.fdopen(
        os.open(args.output / name, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w"
    ) as file:
        file.write(content)
print("Private pairing bundle ready in", args.output)
