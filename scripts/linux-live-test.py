#!/usr/bin/env python3
"""Exercise actual GTK clipboard events through the VPS, preserving prior text."""

import asyncio
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import subprocess

root = Path(__file__).resolve().parents[1]
temporary = tempfile.TemporaryDirectory(prefix="syncclip-gtk-test-")
os.environ["XDG_CONFIG_HOME"] = temporary.name
sys.path.insert(0, str(root / "linux"))
from live_config import load_config, authorization
from syncclip.core import Crypto, atomic_json
from syncclip.app import App
from gi.repository import Gdk, GLib, Gtk
import websockets

config = dict(
    load_config(),
    device="Linux desktop test",
    auto=True,
)
atomic_json(Path(temporary.name) / "syncclip/config.json", config)
clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
original = clipboard.wait_for_text()
ready = threading.Event()
replied = threading.Event()
errors = []
send_text = "Syncclip GTK clipboard test ✓"
reply_text = "Ready to paste on Linux 🐧"


async def peer():
    crypto = Crypto(config["key"])
    try:
        async with websockets.connect(
            config["url"].replace("https://", "wss://") + "/v1/ws",
            extra_headers={"Authorization": authorization(config)},
            max_size=32 * 1024 * 1024,
        ) as ws:
            await ws.recv()
            ready.set()
            async with asyncio.timeout(40):
                async for raw in ws:
                    m = json.loads(raw)
                    if (
                        m.get("type") == "clip"
                        and crypto.decrypt(m["clip"]).get("text") == send_text
                    ):
                        await ws.send(
                            json.dumps(
                                {
                                    "type": "publish",
                                    "clip": crypto.encrypt(
                                        reply_text, "Android test peer"
                                    ),
                                }
                            )
                        )
                        replied.set()
                        return
    except Exception as error:
        errors.append(type(error).__name__)


threading.Thread(target=lambda: asyncio.run(peer()), daemon=True).start()
app = App()
began = time.monotonic()
sent = False
success = False
last_status = ""


def tick():
    global sent, success, last_status
    if app.status_text != last_status:
        last_status = app.status_text
        print("GTK status:", last_status, flush=True)
    if errors or time.monotonic() - began > 45:
        print("GTK live test failed:", errors or "timeout", flush=True)
        finish()
        return False
    if ready.is_set() and app.status_text == "Connected" and not sent:
        sent = True
        subprocess.run(
            ["xclip", "-selection", "clipboard"],
            input=send_text.encode(),
            check=True,
            timeout=3,
        )
    if (
        replied.is_set()
        and app.last_text == reply_text
        and clipboard.wait_for_text() == reply_text
    ):
        success = True
        pix = Gdk.pixbuf_get_from_window(
            app.window.get_window(),
            0,
            0,
            app.window.get_allocated_width(),
            app.window.get_allocated_height(),
        )
        pix.savev(str(root / "artifacts/linux-connected.png"), "png", [], [])
        print(
            "GTK clipboard → VPS → peer and peer → VPS → GTK clipboard: passed",
            flush=True,
        )
        finish()
        return False
    return True


def finish():
    app.config["auto"] = False
    if original is not None:
        clipboard.set_text(original, -1)
        clipboard.store()
    else:
        clipboard.clear()
    app.finish()


GLib.timeout_add(200, tick)
app.run(["syncclip-gtk-test"])
temporary.cleanup()
sys.exit(0 if success else 1)
