"""Exercise actual Linux executable replacement while a candidate process is running."""

import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class DeployRollbackTests(unittest.TestCase):
    @unittest.skipUnless(
        shutil.which("sleep") and shutil.which("bash"), "Requires Linux shell tools"
    )
    def test_failure_atomically_restores_running_binary_and_configuration(self):
        source = (
            Path(__file__).resolve().parents[1] / "scripts/deploy-vps.sh"
        ).read_text()
        function = (
            "rollback() {"
            + source.split("rollback() {", 1)[1].split("\ntrap rollback ERR", 1)[0]
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            backup = root / "backup"
            backup.mkdir()
            binary, config, added = [root / n for n in ["relay", "config", "new-file"]]
            old = Path(shutil.which("sleep")).read_bytes() + b"old release"
            binary.write_bytes(old + b"new release")
            binary.chmod(0o755)
            (backup / "0").write_bytes(old)
            (backup / "0").chmod(0o755)
            (backup / "1").write_text("old config")
            config.write_text("new config")
            added.write_text("created during failed deployment")
            process = subprocess.Popen([str(binary), "30"])
            try:
                body = "\n".join(
                    [
                        "set -Eeuo pipefail",
                        "backup=" + shlex.quote(str(backup)),
                        "targets=("
                        + " ".join(shlex.quote(str(p)) for p in [binary, config, added])
                        + ")",
                        "was_active=1; service_touched=1; nginx_touched=1",
                        'systemctl() { echo "$*" >> "$TEST_SERVICE_LOG"; }',
                        "nginx() { return 0; }",
                        function,
                        "trap rollback ERR",
                        "false",
                    ]
                )
                log = root / "services.log"
                result = subprocess.run(
                    ["bash", "-c", body],
                    capture_output=True,
                    text=True,
                    env=dict(os.environ, TEST_SERVICE_LOG=str(log)),
                )
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(binary.read_bytes(), old, result.stderr)
                self.assertEqual(config.read_text(), "old config")
                self.assertFalse(added.exists())
                self.assertIsNone(
                    process.poll(), "Rollback must not overwrite the executing inode"
                )
                self.assertIn("restart syncclip.service", log.read_text())
                self.assertIn("reload nginx", log.read_text())
                self.assertNotIn("incomplete", result.stderr)
            finally:
                process.terminate()
                process.wait(timeout=5)
