#!/usr/bin/env python3
"""Test an external Linux image clipboard and the Android native Share flow."""

import asyncio
from io import BytesIO
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk
from PIL import Image
import websockets

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "linux"))
from syncclip.core import Store, SyncClient


async def main():
    serial = sys.argv[1]
    adb = ["/home/hal/Android/Sdk/platform-tools/adb", "-s", serial]
    config = json.loads((root / "private/pairing.json").read_text())
    actual_config = Path.home() / ".config/syncclip/config.json"
    was_auto = json.loads(actual_config.read_text()).get("auto", True)
    clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
    original_image = clipboard.wait_for_image()
    original_text = clipboard.wait_for_text() if original_image is None else None
    fixture = (root / "android/app/src/androidTest/assets/screenshot.png").read_bytes()
    expected = Image.open(BytesIO(fixture)).convert("RGB")
    result = {"linux_to_android": False, "android_to_linux": False, "ids": []}
    with tempfile.TemporaryDirectory(prefix="syncclip-image-peer-") as temporary:
        peer = SyncClient(
            config,
            Store(Path(temporary) / "state.json"),
            lambda *a: None,
            lambda *a: None,
            lambda: None,
        )
        url = config["url"].replace("https://", "wss://") + "/v1/ws"
        async with websockets.connect(
            url,
            extra_headers={"Authorization": peer.crypto.authorization},
            max_size=32 * 1024 * 1024,
        ) as ws:
            hello = json.loads(await ws.recv())
            epoch = hello.get("epoch", 0)
            log_path = root / "artifacts/android-image-instrumentation.log"
            with log_path.open("w") as output:
                process = subprocess.Popen(
                    adb
                    + [
                        "shell",
                        "am",
                        "instrument",
                        "-w",
                        "-e",
                        "live_images",
                        "true",
                        "dev.syncclip.test/androidx.test.runner.AndroidJUnitRunner",
                    ],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
                try:
                    deadline = time.monotonic() + 40
                    while "IMAGE_TEST_READY" not in log_path.read_text():
                        if process.poll() is not None or time.monotonic() > deadline:
                            raise RuntimeError(
                                "Android did not become ready; inspect instrumentation output"
                            )
                        await asyncio.sleep(0.15)
                    subprocess.run(
                        ["gapplication", "action", "dev.syncclip.Linux", "resume"],
                        check=True,
                    )
                    for _ in range(30):
                        if json.loads(actual_config.read_text()).get("auto"):
                            break
                        await asyncio.sleep(0.05)
                    started = time.monotonic()
                    subprocess.run(
                        ["xclip", "-selection", "clipboard", "-target", "image/png"],
                        input=fixture,
                        check=True,
                    )
                    print(
                        "External program placed a synthetic screenshot on Linux clipboard",
                        flush=True,
                    )
                    async with asyncio.timeout(75):
                        while len(result["ids"]) < 2:
                            message = json.loads(await ws.recv())
                            if message.get("type") != "clip" or not message["clip"].get(
                                "blob"
                            ):
                                continue
                            clip = message["clip"]
                            data = await asyncio.to_thread(peer.load_image, clip)
                            actual = Image.open(BytesIO(data)).convert("RGB")
                            if (
                                actual.size != expected.size
                                or actual.tobytes() != expected.tobytes()
                            ):
                                continue
                            result["ids"].append(clip["id"])
                            print(
                                "Verified exact screenshot pixels from",
                                peer.crypto.decrypt(clip)["device"],
                                flush=True,
                            )
                            if len(result["ids"]) == 1:
                                result["linux_observer_verified_ms"] = round(
                                    (time.monotonic() - started) * 1000
                                )
                    deadline = time.monotonic() + 20
                    while process.poll() is None and time.monotonic() < deadline:
                        await asyncio.sleep(0.1)
                    log = log_path.read_text()
                    if "OK (" not in log or "ANDROID_IMAGE_PIXELS_VERIFIED" not in log:
                        raise RuntimeError(
                            "Android clipboard/Share test did not pass; inspect instrumentation output"
                        )
                    deadline = time.monotonic() + 15
                    while time.monotonic() < deadline:
                        # Read from a fresh external process: this async harness has no
                        # GLib loop to keep GTK's cached clipboard targets current.
                        capture = await asyncio.to_thread(
                            subprocess.run,
                            [
                                "xclip",
                                "-selection",
                                "clipboard",
                                "-target",
                                "image/png",
                                "-o",
                            ],
                            capture_output=True,
                            timeout=5,
                        )
                        if capture.returncode == 0 and capture.stdout:
                            actual = Image.open(BytesIO(capture.stdout)).convert("RGB")
                            if (
                                actual.size == expected.size
                                and actual.tobytes() == expected.tobytes()
                            ):
                                break
                        await asyncio.sleep(0.1)
                    else:
                        raise RuntimeError(
                            "Returned image did not reach the Linux clipboard"
                        )
                    result["linux_to_android"] = True
                    result["android_to_linux"] = True
                    print(
                        "Linux clipboard → VPS → Android clipboard → native Share → VPS → Linux clipboard: PASS",
                        flush=True,
                    )
                    for clip_id in result["ids"]:
                        await ws.send(
                            json.dumps(
                                {"type": "delete", "id": clip_id, "epoch": epoch}
                            )
                        )
                    deleted = set()
                    async with asyncio.timeout(20):
                        while len(deleted) < len(result["ids"]):
                            message = json.loads(await ws.recv())
                            if (
                                message.get("type") == "deleted"
                                and message.get("id") in result["ids"]
                            ):
                                deleted.add(message["id"])
                    result["test_clips_removed"] = True
                finally:
                    if process.poll() is None:
                        process.terminate()
                    # Pause before restoration so the test cannot re-send the user's prior clipboard.
                    subprocess.run(
                        ["gapplication", "action", "dev.syncclip.Linux", "pause"],
                        check=True,
                    )
                    for _ in range(40):
                        if not json.loads(actual_config.read_text()).get("auto", True):
                            break
                        await asyncio.sleep(0.05)
                    if original_image is not None:
                        ok, data = original_image.save_to_bufferv("png", [], [])
                        subprocess.run(
                            [
                                "xclip",
                                "-selection",
                                "clipboard",
                                "-target",
                                "image/png",
                            ],
                            input=data,
                            check=True,
                        )
                    elif original_text is not None:
                        subprocess.run(
                            ["xclip", "-selection", "clipboard"],
                            input=original_text.encode(),
                            check=True,
                        )
                    if was_auto:
                        subprocess.run(
                            ["gapplication", "action", "dev.syncclip.Linux", "resume"],
                            check=True,
                        )
    (root / "artifacts/image-live-result.json").write_text(json.dumps(result, indent=2))


asyncio.run(main())
