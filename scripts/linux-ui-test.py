#!/usr/bin/env python3
"""Exercise hidden login startup and reopening, without reading the real clipboard."""

import base64
from pathlib import Path
import tempfile
import time
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / "linux"))
import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GLib, Gtk
import syncclip.app as module
from syncclip.core import Crypto, atomic_json, pairing_link

with tempfile.TemporaryDirectory(prefix="syncclip-ui-regression-") as directory:
    module.ROOT = Path(directory)
    key = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")
    config = {"url": "https://example.invalid/syncclip", "key": key, "auto": False}
    atomic_json(module.ROOT / "config.json", config)
    atomic_json(module.ROOT / "settings.json", {"language": "en"})
    crypto = Crypto(key)
    clips = [
        crypto.encrypt("Settings", "Desktop test"),
        crypto.encrypt_file(b"zip bytes", "archive.zip", "Phone test")[0],
    ]
    image_clip, blob = crypto.encrypt_image(
        (root / "android/app/src/androidTest/assets/screenshot.png").read_bytes(),
        "Screenshot test",
    )
    clips.append(image_clip)
    (module.ROOT / "images").mkdir()
    (module.ROOT / "images" / image_clip["id"]).write_bytes(blob)
    for i, c in enumerate(clips):
        c.update(seq=i + 1, created=int(time.time() * 1000))
    atomic_json(
        module.ROOT / "state.json", {"history": clips, "pending": [], "last_id": ""}
    )
    app = module.App(background=True)
    app.set_application_id("dev.syncclip.UiRegression")

    class Client:
        socket = None

        def __init__(self):
            self.crypto = crypto

        def stop(self):
            pass

        def invite(self):
            return pairing_link(dict(config, invite="a" * 64))

        def devices(self):
            return {
                "self": "desktop",
                "devices": [
                    {"id": "desktop", "name": "Linux desktop", "online": True},
                    {"id": "phone", "name": "Android phone", "online": False},
                ],
            }

        def load_image(self, clip):
            return crypto.decrypt_attachment(
                clip, (module.ROOT / "images" / clip["id"]).read_bytes()
            )

    app.connect_client = lambda: setattr(app, "client", Client())
    app.make_tray = lambda: None
    app.register(None)
    app.activate()
    assert not app.window.get_visible(), "Login startup should stay hidden"
    assert app.window.get_child().get_visible(), (
        "Background startup left widgets hidden"
    )
    app.refresh_history()
    app.activate()
    context = GLib.MainContext.default()
    while context.pending():
        context.iteration(False)

    def widgets(widget):
        yield widget
        if isinstance(widget, Gtk.Container):
            for child in widget.get_children():
                yield from widgets(child)

    buttons = [w for w in widgets(app.window) if isinstance(w, Gtk.Button)]
    for name in (
        "Settings",
        "Send files",
        "Clear history",
        "Copy file",
        "Save as…",
    ):
        assert any(
            w.get_label() == name and w.get_visible() and w.get_mapped()
            for w in buttons
        ), name + " missing or hidden"
    assert any(w.get_tooltip_text() == "Delete clip" for w in buttons), (
        "Trash icons are missing"
    )
    assert not any(
        isinstance(w, Gtk.Label) and w.get_text() == "Copy here. Pick it up there."
        for w in widgets(app.window)
    )
    app.hide()
    app.activate()
    while context.pending():
        context.iteration(False)
    assert app.window.get_child().get_mapped(), (
        "Reopening still produces a blank window"
    )
    deadline = time.monotonic() + 0.4
    while time.monotonic() < deadline:
        while context.pending():
            context.iteration(False)
        time.sleep(0.01)
    previews = [
        w
        for w in widgets(app.history)
        if isinstance(w, Gtk.Image) and w.get_pixbuf() is not None
    ]
    assert any(
        w.get_pixbuf().get_width() == app.history.get_allocated_width()
        for w in previews
    ), "Image preview does not use full width"
    assert all(
        w.get_allocated_height() == w.get_pixbuf().get_height() for w in previews
    ), "Image preview was vertically cropped"
    module.set_language("fr")
    app.build()
    assert any(
        isinstance(w, Gtk.Button) and w.get_label() == "Réglages"
        for w in widgets(app.window)
    ), "French settings label missing"
    assert any(
        isinstance(w, Gtk.Label) and w.get_text() == "Settings"
        for w in widgets(app.history)
    ), "Clipboard text was translated"
    module.set_language("en")
    app.build()
    deadline = time.monotonic() + 0.3
    while time.monotonic() < deadline:
        while context.pending():
            context.iteration(False)
        time.sleep(0.01)
    previews = [
        w
        for w in widgets(app.history)
        if isinstance(w, Gtk.Image) and w.get_pixbuf() is not None
    ]
    assert all(
        w.get_allocated_height() == w.get_pixbuf().get_height() for w in previews
    ), [
        (w.get_allocated_height(), w.get_pixbuf().get_height(), w.get_size_request())
        for w in previews
    ]
    image = Gdk.pixbuf_get_from_window(
        app.window.get_window(),
        0,
        0,
        app.window.get_allocated_width(),
        app.window.get_allocated_height(),
    )
    image.savev(str(root / "artifacts/linux-upgrade-ui.png"), "png", [], [])
    assert len(set(image.get_pixels())) > 8, "Window did not paint its controls"

    def settings_frame():
        dialog = next(
            w for w in Gtk.Window.list_toplevels() if w.get_title() == "Settings"
        )
        assert any(
            isinstance(w, Gtk.Label)
            and w.get_text() == config["url"]
            and w.get_mapped()
            for w in widgets(dialog)
        )
        frame = Gdk.pixbuf_get_from_window(
            dialog.get_window(),
            0,
            0,
            dialog.get_allocated_width(),
            dialog.get_allocated_height(),
        )
        frame.savev(str(root / "artifacts/linux-settings.png"), "png", [], [])
        dialog.response(Gtk.ResponseType.CLOSE)
        return False

    GLib.timeout_add(400, settings_frame)
    app.show_settings()
    # Use synthetic pairing data, never the real group or the real clipboard.
    from unittest import mock

    def check_dialog(title, verify, filename):
        errors = []
        deadline = time.monotonic() + 8

        def probe():
            dialog = next(
                (w for w in Gtk.Window.list_toplevels() if w.get_title() == title), None
            )
            if dialog is None:
                return True
            if not verify(dialog) and time.monotonic() < deadline:
                return True
            if not verify(dialog):
                errors.append(title + " did not render")
            frame = Gdk.pixbuf_get_from_window(
                dialog.get_window(),
                0,
                0,
                dialog.get_allocated_width(),
                dialog.get_allocated_height(),
            )
            frame.savev(str(root / "artifacts" / filename), "png", [], [])
            dialog.response(Gtk.ResponseType.CLOSE)
            return False

        GLib.timeout_add(100, probe)
        return errors

    errors = check_dialog(
        "Pair your phone",
        lambda d: any(
            isinstance(w, Gtk.Image) and w.get_pixbuf() is not None for w in widgets(d)
        ),
        "linux-pairing-qr.png",
    )
    app.show_pairing()
    assert not errors, errors
    with mock.patch("subprocess.check_output", side_effect=FileNotFoundError()):
        errors = check_dialog(
            "Pair your phone",
            lambda d: any(
                isinstance(w, Gtk.Label)
                and w.get_text().startswith("QR code unavailable.")
                for w in widgets(d)
            ),
            "linux-pairing-fallback.png",
        )
        app.show_pairing()
        assert not errors, errors
    errors = check_dialog(
        "Connected devices",
        lambda d: any(
            isinstance(w, Gtk.Label) and w.get_text().startswith("Android phone")
            for w in widgets(d)
        ),
        "linux-connected-devices.png",
    )
    app.show_devices()
    assert not errors, errors
    errors = check_dialog(
        "Support development",
        lambda d: any(
            isinstance(w, Gtk.Label) and w.get_text() == "pandora.appbox@cake.cash"
            for w in widgets(d)
        ),
        "linux-support.png",
    )
    app.show_support()
    assert not errors, errors
    selection = Gdk.Atom.intern("SYNCCLIP_PRIVATE_TEST_SELECTION", False)
    files = module.FileClipboard(selection)
    files.set_files([Path("/tmp/Syncclip archive.zip")])
    clipboard = Gtk.Clipboard.get(selection)
    assert clipboard.wait_for_uris() == ["file:///tmp/Syncclip%20archive.zip"]
    copied = clipboard.wait_for_contents(
        Gdk.Atom.intern("x-special/gnome-copied-files", False)
    )
    assert copied.get_data() == b"copy\nfile:///tmp/Syncclip%20archive.zip\n"
    assert clipboard.wait_for_text() == "file:///tmp/Syncclip%20archive.zip"
    files.widget.destroy()
    app.window.destroy()
    app.image_workers.shutdown(wait=False, cancel_futures=True)
    app.release()
    app.quit()
    print(
        "Hidden startup → open → hide → reopen: PASS; Settings and file/history actions visible"
    )
