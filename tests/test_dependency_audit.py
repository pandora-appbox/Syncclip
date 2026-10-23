"""A version supplied by a platform must not escape the advisory query."""

import contextlib
import io
import json
from pathlib import Path
import runpy
import tempfile
import unittest
from unittest.mock import patch


class DependencyAuditTests(unittest.TestCase):
    def test_versionless_request_is_audited_and_known_advisory_fails(self):
        with tempfile.TemporaryDirectory() as folder:
            report = Path(folder) / "dependencies.txt"
            report.write_text(
                "+--- safe:library:1.0\n\\--- dangerous:library -> 2.3.4\nBUILD SUCCESSFUL\n"
            )
            captured = []

            def response(request, **kwargs):
                queries = json.loads(request.data)["queries"]
                captured.extend(queries)
                results = [
                    {"vulns": [{"id": "TEST-KNOWN-ADVISORY"}]}
                    if q["package"]["name"] == "dangerous:library"
                    else {}
                    for q in queries
                ]
                return io.BytesIO(json.dumps({"results": results}).encode())

            script = (
                Path(__file__).resolve().parents[1] / "scripts/audit-dependencies.py"
            )
            with (
                patch("sys.argv", [str(script), "--gradle-report", str(report)]),
                patch("urllib.request.urlopen", response),
                contextlib.redirect_stdout(io.StringIO()),
            ):
                with self.assertRaises(SystemExit) as exit_result:
                    runpy.run_path(str(script), run_name="__main__")
            self.assertEqual(exit_result.exception.code, 1)
            self.assertIn(
                {
                    "package": {"ecosystem": "Maven", "name": "dangerous:library"},
                    "version": "2.3.4",
                },
                captured,
            )
