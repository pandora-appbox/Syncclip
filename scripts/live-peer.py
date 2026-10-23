#!/usr/bin/env python3
"""Synthetic peer for the opt-in Android live instrumentation test."""

import asyncio
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "linux"))
from live_config import load_config, authorization
from syncclip.core import Crypto
import websockets


async def main():
    config = load_config()
    crypto = Crypto(config["key"])
    async with websockets.connect(
        config["url"].replace("https://", "wss://") + "/v1/ws",
        extra_headers={"Authorization": authorization(config)},
        max_size=32 * 1024 * 1024,
    ) as ws:
        await ws.recv()
        print("Linux test peer connected", flush=True)
        async with asyncio.timeout(120):
            async for raw in ws:
                message = json.loads(raw)
                if message.get("type") != "clip":
                    continue
                content = crypto.decrypt(message["clip"])
                if content.get("text") == "Syncclip Android live test 🐧":
                    print("Android → VPS → Linux: received and decrypted", flush=True)
                    start = time.monotonic()
                    reply = crypto.encrypt("Syncclip Linux reply ✓", "Linux test")
                    await ws.send(json.dumps({"type": "publish", "clip": reply}))
                elif content.get("text") == "Syncclip Linux reply ✓":
                    print(
                        f"VPS round trip: {(time.monotonic() - start) * 1000:.0f} ms",
                        flush=True,
                    )
                    return


asyncio.run(main())
