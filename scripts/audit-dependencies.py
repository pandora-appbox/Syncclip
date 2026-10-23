#!/usr/bin/env python3
"""Query OSV for exact pinned Python and resolved Maven versions; send metadata only."""

import argparse
import json
from pathlib import Path
import re
import sys
from urllib.request import Request, urlopen

parser = argparse.ArgumentParser()
parser.add_argument("--requirements", type=Path)
parser.add_argument("--gradle-report", type=Path, action="append", default=[])
args = parser.parse_args()
packages = set()
if args.requirements:
    for line in args.requirements.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)==([A-Za-z0-9_.+-]+)", line)
        if not match:
            parser.error("Requirements must use exact package==version pins")
        packages.add(("PyPI", *match.groups()))
for path in args.gradle_report:
    report = path.read_text()
    if "FAILED" in report or "BUILD SUCCESSFUL" not in report:
        parser.error("Gradle report is incomplete or contains unresolved dependencies")
    for line in report.splitlines():
        match = re.search(r"(?:\+---|\\---)\s+(.+)$", line)
        if not match:
            continue
        entry = match.group(1)
        if entry.startswith("project "):
            continue  # Local project dependency, not a published Maven package.
        requested, arrow, selected = entry.partition(" -> ")
        coordinates = requested.split()[0].split(":")
        if arrow:
            resolved = selected.split()[0].split(":")
            if len(resolved) == 3:
                coordinates = resolved
            elif len(resolved) == 1 and len(coordinates) >= 2:
                coordinates = coordinates[:2] + resolved
            else:
                parser.error("Cannot extract resolved Maven coordinates")
        if len(coordinates) != 3 or not all(coordinates):
            parser.error("Dependency entry has no resolved group:artifact:version")
        group, name, version = coordinates
        if not re.fullmatch(r"[A-Za-z0-9_.+-]+", version):
            parser.error("Maven dependency version is not an exact resolved version")
        packages.add(("Maven", group + ":" + name, version))

if not packages:
    parser.error("No dependency versions were found")
ordered = sorted(packages)
queries = [
    {"package": {"ecosystem": e, "name": n}, "version": v} for e, n, v in ordered
]
try:
    request = Request(
        "https://api.osv.dev/v1/querybatch",
        data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urlopen(request, timeout=60) as response:
        results = json.load(response)["results"]
    if len(results) != len(ordered) or any(r.get("next_page_token") for r in results):
        raise ValueError("Incomplete advisory response")
except Exception as error:
    print("Advisory check failed:", error, file=sys.stderr)
    sys.exit(2)
found = 0
for package, result in zip(ordered, results):
    ids = [v["id"] for v in result.get("vulns", [])]
    if ids:
        print(*package, ", ".join(ids))
        found += 1
if found:
    print(f"{found} dependency versions have published advisories")
    sys.exit(1)
print(f"No OSV advisories found for {len(ordered)} exact dependency versions")
