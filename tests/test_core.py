import base64
import json
from pathlib import Path
import tempfile
import time
import unittest

from syncclip.core import Crypto, Store, SyncClient, parse_pairing, pairing_link

KEY = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


class CoreTests(unittest.TestCase):
    def test_crypto_unicode_and_tamper(self):
        crypto = Crypto(KEY)
        clip = crypto.encrypt("Hello Android ↔ Linux 🐧\nSecond line", "Test computer")
        self.assertEqual(
            crypto.decrypt(clip)["text"], "Hello Android ↔ Linux 🐧\nSecond line"
        )
        damaged = dict(clip, id="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        with self.assertRaises(Exception):
            crypto.decrypt(damaged)
        self.assertNotIn("Hello", json.dumps(clip))

    def test_limits(self):
        crypto = Crypto(KEY)
        for text in ("", "x" * 65537, "😀" * 16385):
            with self.assertRaises(ValueError):
                crypto.encrypt(text, "Test")
        self.assertEqual(
            crypto.decrypt(crypto.encrypt("x" * 65536, "Test"))["text"], "x" * 65536
        )

    def test_pairing(self):
        config = {"url": "https://example.com/syncclip", "key": KEY}
        self.assertEqual(parse_pairing(pairing_link(config)), config)
        for url in (
            "http://example.com",
            "https://user:pass@example.com",
            "https://example.com/?key=bad",
        ):
            with self.assertRaises(ValueError):
                parse_pairing(pairing_link(dict(config, url=url)))

    def test_queue_survives_restart_ack_and_expiry(self):
        with tempfile.TemporaryDirectory() as directory:
            file = Path(directory) / "state.json"
            store = Store(file)
            clip = Crypto(KEY).encrypt("Offline clip", "Test")
            store.queue(clip)
            self.assertEqual(len(Store(file).snapshot()["pending"]), 1)
            self.assertEqual(file.stat().st_mode & 0o777, 0o600)
            self.assertNotIn("Offline clip", file.read_text())
            store.acknowledge(clip["id"])
            self.assertEqual(store.snapshot()["pending"], [])
            store.queue(clip)
            store.data["pending"][0]["created"] = 1
            store.prune()
            self.assertEqual(store.snapshot()["pending"], [])

    def test_reconnect_uses_latest_without_echo_or_overwriting_pending(self):
        with tempfile.TemporaryDirectory() as directory:
            store = Store(Path(directory) / "state.json")
            received = []
            client = SyncClient(
                {"url": "https://example.com", "key": KEY},
                store,
                lambda c, p: received.append(p["text"]),
                lambda s: None,
                lambda: None,
            )

            def clip(text, seq):
                return dict(
                    client.crypto.encrypt(text, "Peer"),
                    seq=seq,
                    created=int(time.time() * 1000),
                )

            first, second = clip("First", 1), clip("Latest", 2)
            client.receive({"type": "hello", "clips": [first, second]})
            self.assertEqual(received, ["Latest"])
            client.receive({"type": "hello", "clips": [first, second]})
            self.assertEqual(received, ["Latest"])
            own = clip("Local", 3)
            store.queue(own)
            client.receive({"type": "clip", "clip": own})
            self.assertEqual(received, ["Latest"])
            newer = clip("Reconnect snapshot", 4)
            client.receive({"type": "hello", "clips": [newer]})
            self.assertEqual(received, ["Latest"])


if __name__ == "__main__":
    unittest.main()
