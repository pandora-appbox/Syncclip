import base64
from pathlib import Path
import tempfile
import unittest
from syncclip.core import Crypto, Store, SyncClient, MAX_IMAGE

KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+afooAAAAASUVORK5CYII="
)


class ImageTests(unittest.TestCase):
    def test_image_download_is_retried_until_applied(self):
        import time

        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "state.json")
            received = []
            client = SyncClient(
                {"url": "https://example.com", "key": KEY},
                store,
                lambda c, p: received.append(c["id"]),
                lambda s: None,
                lambda: None,
            )
            clip, _ = client.crypto.encrypt_image(PNG, "Test")
            clip.update(seq=1, created=int(time.time() * 1000))
            client.receive({"type": "hello", "clips": [clip]})
            client.receive({"type": "hello", "clips": [clip]})
            self.assertEqual(received, [clip["id"], clip["id"]])
            store.mark_applied(clip["id"])
            client.receive({"type": "hello", "clips": [clip]})
            self.assertEqual(len(received), 2)

    def test_image_encrypted_separately_and_authenticated(self):
        crypto = Crypto(KEY)
        clip, encrypted = crypto.encrypt_image(PNG, "Screenshot test")
        self.assertNotIn("image/png", str(clip))
        self.assertEqual(crypto.decrypt(clip)["mime"], "image/png")
        self.assertEqual(crypto.decrypt_image(clip, encrypted), PNG)
        with self.assertRaises(Exception):
            crypto.decrypt_image(clip, encrypted[:-1] + bytes([encrypted[-1] ^ 1]))
        with self.assertRaises(Exception):
            crypto.decrypt(dict(clip, blob="other"))

    def test_image_size_and_format_limits(self):
        crypto = Crypto(KEY)
        for data in (b"text is not an image", PNG + bytes(MAX_IMAGE)):
            with self.assertRaises(ValueError):
                crypto.encrypt_image(data, "Test")

    def test_encrypted_image_survives_offline_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "state.json")
            crypto = Crypto(KEY)
            clip, encrypted = crypto.encrypt_image(PNG, "Test")
            store.put_blob(clip["id"], encrypted)
            store.queue(clip)
            restarted = Store(store.path)
            cached = restarted.blob_path(clip["id"]).read_bytes()
            self.assertEqual(cached, encrypted)
            self.assertNotEqual(cached, PNG)
            self.assertEqual(
                crypto.decrypt_image(restarted.snapshot()["pending"][0], cached), PNG
            )
            self.assertEqual(
                restarted.blob_path(clip["id"]).stat().st_mode & 0o777, 0o600
            )
            with self.assertRaises(ValueError):
                store.blob_path("../../config.json")


class ImageUploadTests(unittest.IsolatedAsyncioTestCase):
    async def test_text_is_delivered_while_image_upload_waits(self):
        import asyncio
        import json
        import threading

        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "state.json")
            client = SyncClient(
                {"url": "https://example.com", "key": KEY},
                store,
                lambda *a: None,
                lambda *a: None,
                lambda: None,
            )
            client.loop = asyncio.get_running_loop()
            client.wake = asyncio.Event()
            client.send_image(PNG)
            client.send("Text copied after screenshot")
            release = threading.Event()
            client.upload_image = lambda clip: release.wait(5)
            sent = asyncio.Queue()

            class Socket:
                async def send(self, raw):
                    clip = json.loads(raw)["clip"]
                    store.acknowledge(clip["id"])
                    await sent.put(clip)

            sender = asyncio.create_task(client.transmit(Socket()))
            try:
                first = await asyncio.wait_for(sent.get(), 2)
                self.assertNotIn("blob", first)
                self.assertEqual(
                    client.crypto.decrypt(first)["text"], "Text copied after screenshot"
                )
                release.set()
                second = await asyncio.wait_for(sent.get(), 2)
                self.assertIn("blob", second)
                self.assertEqual(store.snapshot()["pending"], [])
            finally:
                release.set()
                sender.cancel()
                await asyncio.gather(sender, return_exceptions=True)
