#!/usr/bin/env python3
"""Export an F-Droid submission recipe pinned to committed source; never submit it."""

import argparse
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--ref", default="HEAD")
parser.add_argument("--output-dir", type=Path, default=root / "artifacts/fdroiddata")
args = parser.parse_args()
commit = subprocess.check_output(
    ["git", "rev-parse", args.ref + "^{commit}"], cwd=root, text=True
).strip()
gradle = subprocess.check_output(
    ["git", "show", commit + ":android/app/build.gradle"], cwd=root, text=True
)
version = re.search(r"versionName '([0-9]+\.[0-9]+\.[0-9]+)'", gradle).group(1)
code = re.search(r"versionCode (\d+)", gradle).group(1)
text = subprocess.check_output(
    ["git", "show", commit + ":fdroid/dev.syncclip.yml.in"], cwd=root, text=True
)
for key, value in {"COMMIT": commit, "VERSION": version, "VERCODE": code}.items():
    text = text.replace("@" + key + "@", value)
assert not re.search(r"@[A-Z]+@", text)
target = args.output_dir / "metadata/dev.syncclip.yml"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(text)
print("Prepared", target.resolve(), "from", commit)
print(
    "Keep the repositories private until publication is approved. No F-Droid request was sent."
)
