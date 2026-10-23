import base64
from pathlib import Path
import tempfile
import time
import unittest

from syncclip.core import Crypto, MAX_FILE, Store, SyncClient, safe_filename

KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


class FileAndClearingTests(unittest.TestCase):
    def test_arbitrary_and_empty_file_authenticated_with_private_filename(self):
        crypto = Crypto(KEY)
        for data in (b"", b"%PDF-test\0\xff\n" + bytes(range(256))):
            clip, encrypted = crypto.encrypt_file(data, "document.pdf", "Test")
            self.assertEqual(crypto.decrypt_attachment(clip, encrypted), data)
            self.assertEqual(crypto.decrypt(clip)["name"], "document.pdf")
            self.assertNotIn("document.pdf", str(clip))
            self.assertNotIn("application/pdf", str(clip))
            with self.assertRaises(Exception):
                crypto.decrypt_attachment(
                    clip, encrypted[:-1] + bytes([encrypted[-1] ^ 1])
                )
        with self.assertRaises(ValueError):
            crypto.encrypt_file(bytes(MAX_FILE + 1), "large.zip", "Test")

    def test_untrusted_filenames_never_become_paths(self):
        for name in ("../../private.txt", "C:\\secret\\notes.txt", "..", "\0\n"):
            sanitized = safe_filename(name)
            self.assertNotIn("/", sanitized)
            self.assertNotIn("\\", sanitized)
            self.assertNotIn(sanitized, ("", ".", ".."))
            self.assertTrue(sanitized.isprintable())
        self.assertEqual(safe_filename("Screenshot 🐧.png"), "Screenshot 🐧.png")

    def test_delete_removes_outbox_ciphertext_and_export_and_survives_restart(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "state.json")
            clip, encrypted = Crypto(KEY).encrypt_file(b"private", "notes.txt", "Test")
            store.put_blob(clip["id"], encrypted)
            store.queue(clip)
            export = Path(folder) / "exports" / clip["id"] / "notes.txt"
            export.parent.mkdir(parents=True)
            export.write_bytes(b"private")
            store.remove(clip["id"])
            self.assertFalse(export.exists())
            self.assertFalse(store.blob_path(clip["id"]).exists())
            self.assertFalse(store.snapshot()["pending"])
            restarted = Store(store.path)
            with self.assertRaises(ValueError):
                restarted.queue(clip)
            with self.assertRaises(ValueError):
                restarted.put_blob(clip["id"], encrypted)

    def test_clear_reconciles_offline_pending_and_does_not_reapply_deleted_clip(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "state.json")
            received = []
            client = SyncClient(
                {"url": "https://example.com", "key": KEY},
                store,
                lambda c, p: received.append(c["id"]),
                lambda *a: None,
                lambda: None,
            )
            old = client.crypto.encrypt("Old queued text", "Test")
            store.queue(old)
            client.receive({"type": "hello", "epoch": 1, "clips": [], "deleted": []})
            self.assertFalse(store.snapshot()["pending"])
            self.assertEqual(store.snapshot()["epoch"], 1)
            old.update(created=int(time.time() * 1000), seq=1)
            client.receive({"type": "deleted", "id": old["id"]})
            client.receive({"type": "clip", "clip": old})
            self.assertFalse(store.snapshot()["history"])
            self.assertEqual(received, [])
            fresh = client.crypto.encrypt("New copy", "Test")
            store.queue(fresh)
            client.receive(
                {"type": "hello", "epoch": 1, "clips": [], "deleted": [old["id"]]}
            )
            self.assertEqual(len(store.snapshot()["pending"]), 1)
