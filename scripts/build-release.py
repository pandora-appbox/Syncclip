#!/usr/bin/env python3
"""Build a signed APK, retaining its private update-signing key locally."""

import json
import argparse
import os
import re
from pathlib import Path
import secrets
import shutil
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument(
    "--with-tests",
    action="store_true",
    help="Also build instrumentation APKs with the same signing key",
)
args = parser.parse_args()
private = root / "private"
private.mkdir(mode=0o700, exist_ok=True)
password_file = private / "android-signing.json"
keystore = private / "android-release.keystore"
if not password_file.exists():
    fd = os.open(password_file, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(fd, "w") as file:
        json.dump({"password": secrets.token_urlsafe(32)}, file)
password = json.loads(password_file.read_text())["password"]
env = dict(
    os.environ, SYNCCLIP_KEYSTORE=str(keystore), SYNCCLIP_STORE_PASSWORD=password
)
if not keystore.exists():
    subprocess.run(
        [
            "keytool",
            "-genkeypair",
            "-keystore",
            str(keystore),
            "-storepass:env",
            "SYNCCLIP_STORE_PASSWORD",
            "-keypass:env",
            "SYNCCLIP_STORE_PASSWORD",
            "-alias",
            "syncclip",
            "-keyalg",
            "RSA",
            "-keysize",
            "3072",
            "-validity",
            "10000",
            "-dname",
            "CN=Syncclip Local",
        ],
        env=env,
        check=True,
    )
    keystore.chmod(0o600)
gradle = os.environ.get("SYNCCLIP_GRADLE", str(root / "android/gradlew"))
subprocess.run(
    [gradle, "--no-daemon", "--max-workers", "2", "assembleRelease"]
    + (
        ["assembleDebug", "assembleDebugAndroidTest", "lintDebug"]
        if args.with_tests
        else []
    ),
    cwd=root / "android",
    env=env,
    check=True,
)
version = re.search(r"versionName '([^']+)'", (root / "android/app/build.gradle").read_text()).group(1)
target = root / f"artifacts/Syncclip-{version}.apk"
shutil.copy2(root / "android/app/build/outputs/apk/release/app-release.apk", target)
print("Signed Android app:", target)
