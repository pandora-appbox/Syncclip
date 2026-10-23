import hashlib
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from syncclip.core import Crypto, parse_pairing

ROOT = Path(__file__).resolve().parents[1]


class ServerSetupTests(unittest.TestCase):
    def test_stdlib_provisioning_matches_clients_and_preserves_pairing(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "private"
            command = [
                sys.executable,
                str(ROOT / "scripts/provision.py"),
                "https://sync.example.com/syncclip",
                "--output",
                str(output),
            ]
            subprocess.run(command, check=True, capture_output=True)
            config = parse_pairing((output / "pairing-link.txt").read_text())
            expected = hashlib.sha256(
                ("Bearer " + config["invite"]).encode()
            ).hexdigest()
            self.assertEqual(
                (output / "relay.env").read_text(),
                "SYNC_TOKEN_SHA256=" + expected + "\n",
            )
            self.assertNotEqual(
                "Bearer " + config["invite"], Crypto(config["key"]).authorization
            )
            snapshot = (output / "pairing.json").read_bytes()
            subprocess.run(command, check=True, capture_output=True)
            self.assertEqual((output / "pairing.json").read_bytes(), snapshot)
            for p in output.iterdir():
                self.assertEqual(p.stat().st_mode & 0o777, 0o600)
            self.assertEqual(output.stat().st_mode & 0o777, 0o700)

    def test_configure_rejects_unsafe_addresses_before_writing(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / "scripts").mkdir()
            shutil.copy2(ROOT / "scripts/configure-server.py", root / "scripts")
            shutil.copy2(ROOT / "scripts/provision.py", root / "scripts")
            for url in [
                "http://sync.example.com/syncclip",
                "https://user:pass@sync.example.com/syncclip",
                "https://sync.example.com/syncclip?key=value",
                "https://sync.example.com:8443/syncclip",
                "https://sync.example.com/other",
            ]:
                result = subprocess.run(
                    [sys.executable, str(root / "scripts/configure-server.py"), url],
                    capture_output=True,
                )
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((root / "private").exists())
            subprocess.run(
                [
                    sys.executable,
                    str(root / "scripts/configure-server.py"),
                    "https://sync.example.com/syncclip",
                ],
                check=True,
                capture_output=True,
            )
            self.assertEqual(
                (root / ".env").read_text(), "SYNC_DOMAIN=sync.example.com\n"
            )
            self.assertEqual((root / ".env").stat().st_mode & 0o777, 0o600)
