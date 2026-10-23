#!/usr/bin/env bash
set -euo pipefail
task_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
install_root="${XDG_DATA_HOME:-$HOME/.local/share}/syncclip"
python3 -c 'import gi; gi.require_version("Gtk", "3.0")' || {
    echo 'Install dependencies: sudo apt install python3-gi gir1.2-gtk-3.0 python3-venv gir1.2-ayatanaappindicator3-0.1 qrencode'
    exit 1
}
mkdir -p "$install_root" "$HOME/.local/bin" "${XDG_DATA_HOME:-$HOME/.local/share}/applications" "${XDG_CONFIG_HOME:-$HOME/.config}/autostart"
cp -R "$task_root/linux/syncclip" "$install_root/"
# Keep security-patched crypto local to Syncclip; never replace distribution Python packages.
python3 -m venv --system-site-packages "$install_root/runtime" || {
    echo 'Install python3-venv, then rerun this installer.'
    exit 1
}
cp "$task_root/linux/requirements.txt" "$install_root/requirements.txt"
if [[ -d "$task_root/wheels" ]] && python3 -c 'import sys, platform; sys.exit(not (sys.version_info[:2] == (3, 12) and platform.machine() == "x86_64"))'; then
    python3 - "$task_root/wheels" <<'VERIFY'
import hashlib, sys
from pathlib import Path
root = Path(sys.argv[1])
for line in (root / 'SHA256SUMS').read_text().splitlines():
    digest, name = line.split('  ', 1)
    if Path(name).name != name or hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        raise SystemExit('Bundled dependency checksum mismatch')
VERIFY
    "$install_root/runtime/bin/python" -m pip install --disable-pip-version-check --no-index --no-deps --ignore-installed --find-links "$task_root/wheels" -r "$install_root/requirements.txt"
else
    "$install_root/runtime/bin/python" -m pip install --disable-pip-version-check -r "$install_root/requirements.txt"
fi
mkdir -p "$install_root/legal"
cp "$task_root/LICENSE" "$task_root/THIRD_PARTY_NOTICES.md" "$install_root/legal/"
cp -R "$task_root/licenses" "$install_root/legal/"
cp "$task_root/VERSION" "$install_root/installed-version"
"$install_root/runtime/bin/python" -c 'import gi, cryptography, websockets'
cat > "$HOME/.local/bin/syncclip" <<'LAUNCHER'
#!/usr/bin/env bash
# Let the system package refresh this per-user install after future apt upgrades.
packaged_version=/usr/share/syncclip/VERSION
installed_version="${XDG_DATA_HOME:-$HOME/.local/share}/syncclip/installed-version"
if [[ -f "$packaged_version" && -x /usr/bin/syncclip ]] && command -v dpkg >/dev/null; then
    if dpkg --compare-versions "$(cat "$packaged_version")" gt "$(cat "$installed_version" 2>/dev/null || echo 0)"; then
        exec /usr/bin/syncclip "$@"
    fi
fi
export PYTHONPATH="${XDG_DATA_HOME:-$HOME/.local/share}/syncclip${PYTHONPATH:+:$PYTHONPATH}"
exec "${XDG_DATA_HOME:-$HOME/.local/share}/syncclip/runtime/bin/python" -m syncclip.app "$@"
LAUNCHER
chmod +x "$HOME/.local/bin/syncclip"
cat > "${XDG_DATA_HOME:-$HOME/.local/share}/applications/dev.syncclip.Linux.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Syncclip
Comment=Clipboard sharing between your devices
Exec=$HOME/.local/bin/syncclip
Icon=$install_root/syncclip/assets/syncclip.svg
Terminal=false
Categories=Utility;
StartupNotify=true
DESKTOP
cat > "${XDG_CONFIG_HOME:-$HOME/.config}/autostart/syncclip.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=Syncclip
Exec=$HOME/.local/bin/syncclip --background
Icon=$install_root/syncclip/assets/syncclip.svg
Terminal=false
X-GNOME-Autostart-enabled=true
DESKTOP
echo 'Installed Syncclip in the application menu, with login autostart.'
