#!/usr/bin/env python3
"""Open the real release scanner and camera; cancel without changing pairing."""

import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--adb", default="adb")
parser.add_argument("--serial", required=True)
args = parser.parse_args()
adb = [args.adb, "-s", args.serial]
xml = "/sdcard/syncclip-scanner-test.xml"


def run(*arguments):
    return subprocess.check_output(
        adb + list(arguments), stderr=subprocess.STDOUT
    ).decode()


def tree():
    run("shell", f"uiautomator dump {xml} >/dev/null")
    return ET.fromstring(run("exec-out", "cat", xml))


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


try:
    run("shell", "am", "start", "-n", "dev.syncclip/.MainActivity", "-f", "0x24000000")
    time.sleep(2)
    package = run("shell", "dumpsys", "package", "dev.syncclip")
    flags = next(line for line in package.splitlines() if "pkgFlags=" in line)
    assert "DEBUGGABLE" not in flags, "Install the signed release APK first"
    for attempt in range(2):
        button = next(
            (n for n in tree().iter("node") if n.get("text") == "Scan pairing code"),
            None,
        )
        assert button is not None, (
            "Use the unpaired English pairing screen; this test never disconnects devices"
        )
        pid = run("shell", "pidof", "dev.syncclip").strip()
        tap(button)
        time.sleep(1)
        for node in tree().iter("node"):
            if node.get("resource-id", "").endswith(
                "/permission_allow_foreground_only_button"
            ):
                tap(node)
                break
        active = False
        for _ in range(20):
            resumed = run("shell", "dumpsys", "activity", "activities")
            camera = run("shell", "dumpsys", "media.camera")
            active = any(
                "ResumedActivity" in line and "CaptureActivity" in line
                for line in resumed.splitlines()
            )
            clients = camera.split("Active Camera Clients:", 1)[-1].split(
                "Allowed user IDs:", 1
            )[0]
            if active and "dev.syncclip" in clients:
                break
            time.sleep(0.25)
        assert active and "dev.syncclip" in clients, (
            "Scanner did not open an active camera"
        )
        assert run("shell", "pidof", "dev.syncclip").strip() == pid, (
            "Scanner restarted/crashed the app"
        )
        run("shell", "input", "keyevent", "KEYCODE_BACK")
        time.sleep(0.7)
        assert run("shell", "pidof", "dev.syncclip").strip() == pid
    print(
        "PASS: signed release opens the scanner and active camera twice; cancellation returns without crashing or changing pairing"
    )
finally:
    run("shell", "rm", "-f", xml)
