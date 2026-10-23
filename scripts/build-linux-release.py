#!/usr/bin/env python3
"""Package the GTK client as a Debian package and a user-installable tarball."""

import argparse
import hashlib
from pathlib import Path
import re
import shutil
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument(
    "--wheels", type=Path, help="Prebuilt Python 3.12 x86-64 wheel directory"
)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
version = (root / "VERSION").read_text().strip()
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
    raise SystemExit("Invalid VERSION")
artifacts = root / "artifacts"
artifacts.mkdir(exist_ok=True)
wheels = args.wheels or artifacts / "wheels"
if args.wheels is None:
    subprocess.run(
        [
            "python3",
            "-m",
            "pip",
            "wheel",
            "--wheel-dir",
            str(wheels),
            "-r",
            str(root / "linux/requirements.txt"),
        ],
        check=True,
    )
if len(list(wheels.glob("*.whl"))) != 5:
    raise SystemExit("Expected five pinned runtime wheels")


def ignore(directory, names):
    return [n for n in names if n == "__pycache__" or n.endswith(".pyc")]


with tempfile.TemporaryDirectory(prefix="syncclip-package-") as temporary:
    stage = Path(temporary)
    bundle = stage / f"Syncclip-{version}-linux"
    bundle.mkdir()
    for name in ["linux", "licenses", "docs"]:
        shutil.copytree(root / name, bundle / name, ignore=ignore)
    (bundle / "scripts").mkdir()
    shutil.copy2(root / "scripts/install-linux.sh", bundle / "scripts/install-linux.sh")
    for name in ["README.md", "LICENSE", "THIRD_PARTY_NOTICES.md", "VERSION"]:
        shutil.copy2(root / name, bundle / name)
    shutil.copytree(wheels, bundle / "wheels")
    hashes = "".join(
        hashlib.sha256(p.read_bytes()).hexdigest() + "  " + p.name + "\n"
        for p in sorted((bundle / "wheels").glob("*.whl"))
    )
    (bundle / "wheels/SHA256SUMS").write_text(hashes)
    with tarfile.open(
        artifacts / f"Syncclip-{version}-linux.tar.gz", "w:gz"
    ) as archive:
        archive.add(bundle, arcname=bundle.name)
    package = stage / "deb"
    app = package / "usr/share/syncclip"
    shutil.copytree(bundle, app)
    (package / "DEBIAN").mkdir()
    (package / "DEBIAN/control").write_text(f"""Package: syncclip
Version: {version}
Section: utils
Priority: optional
Architecture: amd64
Maintainer: Syncclip contributors <pandora.appbox@proton.me>
Depends: python3 (>= 3.12), python3 (<< 3.13), python3-venv, python3-gi, gir1.2-gtk-3.0, gir1.2-ayatanaappindicator3-0.1
Recommends: qrencode
Description: Encrypted clipboard sharing between Linux and Android
 Native GTK tray application for text, screenshots and files.
 Bundled pinned Python wheels are installed per user on first launch.
""")
    launcher = package / "usr/bin/syncclip"
    launcher.parent.mkdir(parents=True)
    launcher.write_text(f'''#!/usr/bin/env bash
set -euo pipefail
app_root="${{XDG_DATA_HOME:-$HOME/.local/share}}/syncclip"
if [[ ! -f "$app_root/installed-version" ]] || [[ "$(cat "$app_root/installed-version")" != "{version}" ]]; then
    /usr/share/syncclip/scripts/install-linux.sh
fi
export PYTHONPATH="$app_root${{PYTHONPATH:+:$PYTHONPATH}}"
exec "$app_root/runtime/bin/python" -m syncclip.app "$@"
''')
    launcher.chmod(0o755)
    desktop = package / "usr/share/applications/dev.syncclip.Linux.desktop"
    desktop.parent.mkdir(parents=True)
    desktop.write_text("""[Desktop Entry]
Type=Application
Name=Syncclip
Comment=Clipboard sharing between your devices
Exec=/usr/bin/syncclip
Icon=syncclip
Terminal=false
Categories=Utility;
StartupNotify=true
""")
    icon = package / "usr/share/icons/hicolor/scalable/apps/syncclip.svg"
    icon.parent.mkdir(parents=True)
    shutil.copy2(root / "linux/syncclip/assets/syncclip.svg", icon)
    docs = package / "usr/share/doc/syncclip"
    docs.mkdir(parents=True)
    shutil.copy2(root / "LICENSE", docs / "copyright")
    shutil.copy2(root / "THIRD_PARTY_NOTICES.md", docs / "THIRD_PARTY_NOTICES.md")
    shutil.copytree(root / "licenses", docs / "licenses")
    subprocess.run(
        [
            "dpkg-deb",
            "--root-owner-group",
            "--build",
            str(package),
            str(artifacts / f"syncclip_{version}_amd64.deb"),
        ],
        check=True,
    )
print("Linux release artifacts ready in", artifacts)
