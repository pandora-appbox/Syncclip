#!/usr/bin/env python3
"""Rebuild a committed Android release without the signing key and verify its signature."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("apk", type=Path, help="Existing upstream-signed release APK")
parser.add_argument("--ref", default="HEAD", help="Exact committed source to rebuild")
parser.add_argument(
    "--gradle",
    type=Path,
    help="Trusted Gradle 8.14.6 executable; otherwise use wrapper",
)
parser.add_argument(
    "--sdk",
    type=Path,
    default=Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")),
)
parser.add_argument(
    "--output-dir", type=Path, default=root / "artifacts/reproducibility"
)
args = parser.parse_args()
args.apk = args.apk.resolve()
args.sdk = args.sdk.resolve()
args.output_dir.mkdir(parents=True, exist_ok=True)
args.output_dir = args.output_dir.resolve()
import apksigcopier

ref = subprocess.check_output(
    ["git", "rev-parse", args.ref + "^{commit}"], cwd=root, text=True
).strip()
epoch = subprocess.check_output(
    ["git", "show", "-s", "--format=%ct", ref], cwd=root, text=True
).strip()
env = {
    k: v
    for k, v in os.environ.items()
    if k not in {"SYNCCLIP_KEYSTORE", "SYNCCLIP_STORE_PASSWORD"}
}
env.update(
    ANDROID_HOME=str(args.sdk),
    ANDROID_SDK_ROOT=str(args.sdk),
    SOURCE_DATE_EPOCH=epoch,
    TZ="UTC",
    LC_ALL="C.UTF-8",
)
with tempfile.TemporaryDirectory(prefix="syncclip-repro-") as temporary:
    source = Path(temporary) / "source"
    subprocess.run(
        [
            "git",
            "clone",
            "--quiet",
            "--no-local",
            "--no-checkout",
            str(root),
            str(source),
        ],
        check=True,
    )
    subprocess.run(
        ["git", "checkout", "--quiet", "--detach", ref], cwd=source, check=True
    )
    assert not (source / "private").exists()
    # Match fdroiddata's release-only preparation: this directory contains a test ZIP,
    # not release inputs. No source-scan exclusions are required.
    shutil.rmtree(source / "android/app/src/androidTest", ignore_errors=True)
    gradle = (
        str(args.gradle.resolve()) if args.gradle else str(source / "android/gradlew")
    )
    with (args.output_dir / "build.log").open("w") as log:
        subprocess.run(
            [
                gradle,
                "--no-daemon",
                "--max-workers",
                "2",
                "--no-build-cache",
                "--no-configuration-cache",
                "--rerun-tasks",
                "clean",
                "assembleRelease",
            ],
            cwd=source / "android",
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            check=True,
        )
    unsigned = args.output_dir / "rebuilt-unsigned.apk"
    shutil.copy2(
        source / "android/app/build/outputs/apk/release/app-release-unsigned.apk",
        unsigned,
    )
rebuilt = args.output_dir / "rebuilt-signed.apk"
apksigcopier.do_copy(str(args.apk), str(unsigned), str(rebuilt))
expected = hashlib.sha256(args.apk.read_bytes()).hexdigest()
actual = hashlib.sha256(rebuilt.read_bytes()).hexdigest()
if expected != actual:
    with zipfile.ZipFile(args.apk) as a, zipfile.ZipFile(rebuilt) as b:
        changed = [
            n
            for n in set(a.namelist()) | set(b.namelist())
            if n not in a.namelist() or n not in b.namelist() or a.read(n) != b.read(n)
        ]
    raise SystemExit("APK differs; changed ZIP entries: " + ", ".join(sorted(changed)))
signer = args.sdk / "build-tools/36.0.0/apksigner"
verified = subprocess.check_output(
    [str(signer), "verify", "--print-certs", str(rebuilt)], text=True
)
fingerprint = "aab4ccf9ccc71f938e22c0c2d58a84a977d415a085040d02f6c24718e18cc0bb"
if "certificate SHA-256 digest: " + fingerprint not in verified:
    raise SystemExit("APK signing certificate is not Syncclip's expected update key")
report = {
    "source_commit": ref,
    "signed_apk_sha256": expected,
    "rebuilt_apk_sha256": actual,
    "signer_sha256": fingerprint,
    "byte_identical": True,
    "signature_verified": True,
    "private_key_used_for_rebuild": False,
    "scope": "local clean Git checkout; F-Droid build-server acceptance remains external",
}
(args.output_dir / "result.json").write_text(json.dumps(report, indent=2) + "\n")
print(json.dumps(report, indent=2))
