#!/usr/bin/env python3
"""Test the real file clipboard, native Android sharing, quiet mode and deletion."""

import asyncio
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from urllib.parse import urlparse, unquote
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gtk, Gdk
import websockets

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "linux"))
from syncclip.core import Store, SyncClient


async def main():
    adb = ["/home/hal/Android/Sdk/platform-tools/adb", "-s", sys.argv[1]]
    config = json.loads((root / "private/pairing.json").read_text())
    actual_config = Path.home() / ".config/syncclip/config.json"
    was_auto = json.loads(actual_config.read_text()).get("auto", True)
    clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
    original_uris = clipboard.wait_for_uris()
    original_image = clipboard.wait_for_image() if not original_uris else None
    original_text = (
        clipboard.wait_for_text()
        if not original_uris and original_image is None
        else None
    )
    fixture = root / "android/app/src/androidTest/assets/sample.zip"
    expected = fixture.read_bytes()
    result = {
        "linux_to_android": False,
        "android_to_linux": False,
        "quiet_mode": False,
        "deletion": False,
        "ids": [],
    }
    with tempfile.TemporaryDirectory(prefix="syncclip-file-peer-") as temporary:
        peer = SyncClient(
            config,
            Store(Path(temporary) / "state.json"),
            lambda *a: None,
            lambda *a: None,
            lambda: None,
        )
        async with websockets.connect(
            config["url"].replace("https://", "wss://") + "/v1/ws",
            extra_headers={"Authorization": peer.crypto.authorization},
            max_size=32 * 1024 * 1024,
            open_timeout=20,
        ) as ws:
            hello = json.loads(await ws.recv())
            epoch = hello.get("epoch", 0)
            peer.store.reconcile(epoch, hello.get("deleted", []))
            log_path = root / "artifacts/upgrade-android-instrumentation.log"
            with log_path.open("w") as output:
                process = subprocess.Popen(
                    adb
                    + [
                        "shell",
                        "am",
                        "instrument",
                        "-w",
                        "-e",
                        "live_files",
                        "true",
                        "-e",
                        "class",
                        "dev.syncclip.CryptoTest,dev.syncclip.FileAndSettingsTest,dev.syncclip.LiveFileTest",
                        "dev.syncclip.test/androidx.test.runner.AndroidJUnitRunner",
                    ],
                    stdout=output,
                    stderr=subprocess.STDOUT,
                )
                try:
                    deadline = time.monotonic() + 60
                    while "FILE_TEST_READY" not in log_path.read_text():
                        if process.poll() is not None or time.monotonic() > deadline:
                            raise RuntimeError(
                                "Android was not ready; inspect test output"
                            )
                        await asyncio.sleep(0.1)
                    subprocess.run(
                        ["gapplication", "action", "dev.syncclip.Linux", "resume"],
                        check=True,
                    )
                    await asyncio.sleep(0.25)
                    subprocess.run(
                        [
                            "xclip",
                            "-selection",
                            "clipboard",
                            "-target",
                            "text/uri-list",
                        ],
                        input=(fixture.as_uri() + "\r\n").encode(),
                        check=True,
                    )
                    print(
                        "External file manager clipboard → encrypted VPS relay",
                        flush=True,
                    )
                    async with asyncio.timeout(160):
                        while len(result["ids"]) < 2:
                            message = json.loads(await ws.recv())
                            if message.get("type") != "clip":
                                continue
                            clip = message["clip"]
                            content = peer.crypto.decrypt(clip)
                            if (
                                content.get("kind") != "file"
                                or content.get("name") != "sample.zip"
                                or clip["id"] in result["ids"]
                            ):
                                continue
                            data = await asyncio.to_thread(peer.load_image, clip)
                            if data != expected:
                                raise RuntimeError("Transferred ZIP bytes differ")
                            result["ids"].append(clip["id"])
                            print(
                                "Verified exact ZIP bytes from",
                                content["device"],
                                flush=True,
                            )
                    deadline = time.monotonic() + 40
                    while time.monotonic() < deadline:
                        capture = await asyncio.to_thread(
                            subprocess.run,
                            [
                                "xclip",
                                "-selection",
                                "clipboard",
                                "-target",
                                "text/uri-list",
                                "-o",
                            ],
                            capture_output=True,
                            timeout=5,
                        )
                        if capture.returncode == 0:
                            uris = capture.stdout.decode().strip().splitlines()
                            if uris:
                                path = Path(unquote(urlparse(uris[0]).path))
                                if (
                                    path.parent.name == result["ids"][1]
                                    and path.is_file()
                                    and path.read_bytes() == expected
                                ):
                                    break
                        await asyncio.sleep(0.1)
                    else:
                        raise RuntimeError(
                            "Returned file was not placed on the Linux file clipboard"
                        )
                    result["linux_to_android"] = result["android_to_linux"] = True
                    for clip_id in result["ids"]:
                        await ws.send(
                            json.dumps(
                                {"type": "delete", "id": clip_id, "epoch": epoch}
                            )
                        )
                    deleted = set()
                    async with asyncio.timeout(30):
                        while len(deleted) < 2:
                            message = json.loads(await ws.recv())
                            if (
                                message.get("type") == "deleted"
                                and message.get("id") in result["ids"]
                            ):
                                deleted.add(message["id"])
                    deadline = time.monotonic() + 30
                    while process.poll() is None and time.monotonic() < deadline:
                        await asyncio.sleep(0.1)
                    log = log_path.read_text()
                    if (
                        "OK (" not in log
                        or "ANDROID_FILE_BYTES_AND_QUIET_MODE_VERIFIED" not in log
                        or "ANDROID_FILE_DELETION_VERIFIED" not in log
                    ):
                        raise RuntimeError(
                            "Android assertions failed; inspect instrumentation output"
                        )
                    result["quiet_mode"] = result["deletion"] = True
                    for clip_id in result["ids"]:
                        request = Request(
                            config["url"] + "/v1/blobs/" + clip_id,
                            headers={"Authorization": peer.crypto.authorization},
                        )
                        try:
                            await asyncio.to_thread(
                                urlopen,
                                request,
                                timeout=15,
                            )
                            raise RuntimeError("Deleted attachment still downloadable")
                        except HTTPError as error:
                            if error.code != 404:
                                raise
                    print(
                        "Linux file clipboard → Android URI/Share → Linux file clipboard: PASS",
                        flush=True,
                    )
                    print(
                        "Disabled clip notifications and deletion on both devices: PASS",
                        flush=True,
                    )
                finally:
                    if process.poll() is None:
                        process.terminate()
                    subprocess.run(
                        ["gapplication", "action", "dev.syncclip.Linux", "pause"],
                        check=True,
                    )
                    for _ in range(40):
                        if not json.loads(actual_config.read_text()).get("auto", True):
                            break
                        await asyncio.sleep(0.05)
                    if original_uris:
                        target, data = (
                            "text/uri-list",
                            ("\r\n".join(original_uris) + "\r\n").encode(),
                        )
                    elif original_image is not None:
                        ok, data = original_image.save_to_bufferv("png", [], [])
                        target = "image/png"
                    elif original_text is not None:
                        target, data = "UTF8_STRING", original_text.encode()
                    else:
                        target, data = "UTF8_STRING", b""
                    subprocess.run(
                        ["xclip", "-selection", "clipboard", "-target", target],
                        input=data,
                        check=True,
                    )
                    await asyncio.sleep(0.5)
                    if was_auto:
                        subprocess.run(
                            ["gapplication", "action", "dev.syncclip.Linux", "resume"],
                            check=True,
                        )
    (root / "artifacts/upgrade-file-live-result.json").write_text(
        json.dumps(result, indent=2)
    )


asyncio.run(main())
