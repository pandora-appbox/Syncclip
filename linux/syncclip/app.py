import argparse
import hashlib
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
from urllib.parse import urlparse, unquote
import sys

import gi

gi.require_version("Gtk", "3.0")
gi.require_version("Gdk", "3.0")
from gi.repository import Gdk, GdkPixbuf, Gio, GLib, Gtk

from .images import decode_rgba
from .i18n import tr as _, fmt, size, set_language, LANGUAGES

from .core import (
    MAX_TEXT,
    Store,
    SyncClient,
    atomic_json,
    pairing_link,
    parse_pairing,
    safe_filename,
)

ROOT = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "syncclip"
CSS = b"""
window, dialog { background: #111018; color: #f3effa; font-size: 14px; }
label { color: #f3effa; }
.muted { color: #b1aabd; }
.title { font-size: 30px; font-weight: bold; }
.card { background: #1b1826; border-radius: 0; padding: 0; }
.icon-button { background: transparent; color: #b69cff; padding: 4px; min-width: 24px; min-height: 24px; }
.icon-button:hover { background: #45375c; }
entry, textview, textview text { background: #272132; color: #f3effa; border-radius: 8px; }
button { background-image: none; background: #30283f; color: #f3effa; border: 0; border-radius: 8px; padding: 9px 15px; }
button:hover { background: #45375c; }
button.suggested-action { background: #b69cff; color: #1b1032; }
button.suggested-action label { color: #1b1032; }
switch { background: #30283f; border-radius: 14px; padding: 2px; min-width: 44px; }
switch:checked { background: #8e68d7; }
switch slider { background: #f3effa; border: 0; border-radius: 12px; min-width: 20px; min-height: 20px; }
scrolledwindow { border: 0; }
"""


def label(text, style=None):
    widget = Gtk.Label(label=text, xalign=0)
    widget.set_line_wrap(True)
    if style:
        widget.get_style_context().add_class(style)
    return widget


def inset(widget, horizontal=16):
    widget.set_margin_start(horizontal)
    widget.set_margin_end(horizontal)
    return widget


class FileClipboard:
    """Offer the native URI list and the file-copy format used by Nemo/Nautilus."""

    def __init__(self, selection=Gdk.SELECTION_CLIPBOARD):
        self.selection = selection
        self.uris = []
        self.widget = Gtk.Invisible()
        self.widget.realize()
        for index, target in enumerate(
            ("text/uri-list", "x-special/gnome-copied-files", "UTF8_STRING")
        ):
            Gtk.selection_add_target(
                self.widget, selection, Gdk.Atom.intern(target, False), index
            )
        self.widget.connect("selection-get", self.provide)

    def set_files(self, paths):
        self.uris = [Path(path).as_uri() for path in paths]
        if not Gtk.selection_owner_set(self.widget, self.selection, Gdk.CURRENT_TIME):
            raise ValueError("Could not copy this file")

    def provide(self, widget, data, info, timestamp):
        if info == 0:
            data.set_uris(self.uris)
        elif info == 1:
            data.set(
                data.get_target(), 8, ("copy\n" + "\n".join(self.uris) + "\n").encode()
            )
        else:
            data.set_text("\n".join(self.uris), -1)


class App(Gtk.Application):
    def __init__(self, background=False):
        super().__init__(
            application_id="dev.syncclip.Linux", flags=Gio.ApplicationFlags.FLAGS_NONE
        )
        self.background = background
        self.window = None
        self.client = None
        self.client_generation = 0
        self.status_text = "Not paired"
        self.last_text = None
        self.suppress_text = None
        self.clipboard_generation = 0
        self.image_workers = ThreadPoolExecutor(
            max_workers=2, thread_name_prefix="syncclip-image"
        )
        self.last_image = None
        self.last_files = None
        self.image_previews = {}
        self.history_generation = 0
        self.receive_generation = 0
        self.config = (
            json.loads((ROOT / "config.json").read_text())
            if (ROOT / "config.json").exists()
            else {}
        )
        try:
            self.settings = json.loads((ROOT / "settings.json").read_text())
        except (OSError, ValueError):
            self.settings = {}
        set_language(self.settings.get("language", ""))
        self.store = Store(ROOT / "state.json")
        self.connect("activate", self.activate)
        for name, enabled in (("pause", False), ("resume", True)):
            action = Gio.SimpleAction.new(name, None)
            action.connect(
                "activate",
                lambda _, param, enabled=enabled: self.auto.set_active(enabled)
                if self.config and hasattr(self, "auto")
                else None,
            )
            self.add_action(action)

    def activate(self, *_args):
        if self.window:
            self.open_window()
            return
        self.hold()
        provider = Gtk.CssProvider()
        provider.load_from_data(CSS)
        Gtk.StyleContext.add_provider_for_screen(
            Gdk.Screen.get_default(), provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )
        self.window = Gtk.ApplicationWindow(application=self, title="Syncclip")
        self.window.set_default_size(520, 680)
        self.window.set_icon_from_file(
            str(Path(__file__).parent / "assets" / "syncclip.svg")
        )
        self.window.connect("delete-event", lambda *_: self.hide())
        self.clipboard = Gtk.Clipboard.get(Gdk.SELECTION_CLIPBOARD)
        self.file_clipboard = FileClipboard()
        self.clipboard.connect("owner-change", self.clipboard_changed)
        self.build()
        self.make_tray()
        if self.config.get("key"):
            self.connect_client()
        # Children must be visible even when login autostart hides the window.
        # present() only shows the toplevel and cannot reveal hidden descendants.
        self.window.get_child().show_all()
        if not self.background or not self.config:
            self.open_window()
        GLib.timeout_add_seconds(60, self.expire)

    def open_window(self):
        self.window.show_all()
        self.window.present()

    def hide(self):
        self.window.hide()
        return True

    def make_tray(self):
        menu = Gtk.Menu()
        for title, callback in [
            ("Open Syncclip", lambda *_: self.open_window()),
            ("Send clipboard", lambda *_: self.send_clipboard()),
            ("Pause / resume", lambda *_: self.toggle_pause()),
            ("Quit", lambda *_: self.finish()),
        ]:
            item = Gtk.MenuItem(label=_(title))
            item.connect("activate", callback)
            menu.append(item)
        menu.show_all()
        try:
            gi.require_version("AyatanaAppIndicator3", "0.1")
            from gi.repository import AyatanaAppIndicator3 as Indicator

            self.tray = Indicator.Indicator.new(
                "syncclip",
                str(Path(__file__).parent / "assets" / "syncclip.svg"),
                Indicator.IndicatorCategory.APPLICATION_STATUS,
            )
            self.tray.set_status(Indicator.IndicatorStatus.ACTIVE)
            self.tray.set_menu(menu)
        except (ValueError, ImportError):
            self.tray = Gtk.StatusIcon.new_from_file(
                str(Path(__file__).parent / "assets" / "syncclip.svg")
            )
            self.tray.set_tooltip_text("Syncclip")
            self.tray.connect("activate", lambda *_: self.open_window())
            self.tray.connect(
                "popup-menu",
                lambda icon, button, time: menu.popup(
                    None, None, Gtk.StatusIcon.position_menu, icon, button, time
                ),
            )
        self.menu = menu

    def build(self):
        old = self.window.get_child()
        if old:
            self.window.remove(old)
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=14, margin=0)
        self.window.add(outer)
        heading = inset(Gtk.Box(spacing=12))
        heading.set_margin_top(16)
        heading.pack_start(label("Syncclip", "title"), True, True, 0)
        settings = Gtk.Button(label=_("Settings"))
        settings.connect("clicked", self.show_settings)
        heading.pack_end(settings, False, False, 0)
        outer.pack_start(heading, False, False, 0)
        self.status_label = inset(label(_(self.status_text), "muted"))
        outer.pack_start(self.status_label, False, False, 0)
        if not self.config:
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
            card.get_style_context().add_class("card")
            card.set_border_width(16)
            card.pack_start(label(_("Connect your devices")), False, False, 0)
            card.pack_start(
                label(
                    _("Paste your private pairing link to connect to your server."),
                    "muted",
                ),
                False,
                False,
                0,
            )
            self.pair_entry = Gtk.Entry(
                placeholder_text="syncclip://pair?…", visibility=False
            )
            card.pack_start(self.pair_entry, False, False, 0)
            pair = Gtk.Button(label=_("Connect"))
            pair.get_style_context().add_class("suggested-action")
            pair.connect("clicked", self.pair)
            card.pack_start(pair, False, False, 0)
            outer.pack_start(card, False, False, 0)
            outer.show_all()
            return
        row = inset(Gtk.Box(spacing=12))
        row.pack_start(label(_("Automatic clipboard sharing")), True, True, 0)
        self.auto = Gtk.Switch(active=self.config.get("auto", True))
        self.auto.connect("notify::active", self.auto_changed)
        row.pack_end(self.auto, False, False, 0)
        outer.pack_start(row, False, False, 0)
        outer.pack_start(
            inset(
                label(
                    _("Text, images and files · encrypted between your devices"),
                    "muted",
                )
            ),
            False,
            False,
            0,
        )
        actions = inset(Gtk.Box(spacing=10))
        send = Gtk.Button(label=_("Send clipboard"))
        send.get_style_context().add_class("suggested-action")
        send.connect("clicked", lambda *_: self.send_clipboard())
        actions.pack_start(send, True, True, 0)
        files = Gtk.Button(label=_("Send files"))
        files.connect("clicked", self.choose_files)
        actions.pack_start(files, False, False, 0)
        outer.pack_start(actions, False, False, 0)
        self.pending_label = inset(label("", "muted"))
        outer.pack_start(self.pending_label, False, False, 0)
        history_heading = inset(Gtk.Box(spacing=10))
        history_heading.pack_start(label(_("RECENT CLIPS"), "muted"), True, True, 0)
        clear = Gtk.Button(label=_("Clear history"))
        clear.connect("clicked", lambda *_: self.confirm_clear())
        history_heading.pack_end(clear, False, False, 0)
        outer.pack_start(history_heading, False, False, 0)
        scroll = Gtk.ScrolledWindow()
        scroll.set_policy(Gtk.PolicyType.NEVER, Gtk.PolicyType.AUTOMATIC)
        self.history = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        scroll.add(self.history)
        outer.pack_start(scroll, True, True, 0)
        outer.pack_start(
            inset(label(_("50 clips · 24 hours · Files up to 32 MB"), "muted")),
            False,
            False,
            0,
        )
        self.refresh_history()
        outer.show_all()

    def show_settings(self, *_args):
        dialog = Gtk.Dialog(title=_("Settings"), transient_for=self.window, modal=True)
        dialog.add_button(_("Done"), Gtk.ResponseType.CLOSE)
        dialog.set_default_size(440, 280)
        box = dialog.get_content_area()
        box.set_spacing(14)
        box.set_border_width(22)
        box.pack_start(label(_("Language")), False, False, 0)
        languages = Gtk.ComboBoxText()
        for tag, name in LANGUAGES.items():
            languages.append(tag, _(name))
        languages.set_active_id(self.settings.get("language", ""))

        def changed_language(widget):
            tag = widget.get_active_id()
            if tag == self.settings.get("language", ""):
                return
            self.settings["language"] = tag
            atomic_json(ROOT / "settings.json", self.settings)
            set_language(tag)
            dialog.response(Gtk.ResponseType.APPLY)

        languages.connect("changed", changed_language)
        box.pack_start(languages, False, False, 0)
        box.pack_start(label(_("Connection"), "title"), False, False, 0)
        server = label(self.config.get("url") or _("Not paired"), "muted")
        server.set_selectable(True)
        box.pack_start(server, False, False, 0)
        if self.config:
            row = Gtk.Box(spacing=12)
            row.pack_start(label(_("Automatic clipboard sharing")), True, True, 0)
            switch = Gtk.Switch(active=self.config.get("auto", True))
            switch.connect(
                "notify::active",
                lambda widget, _: self.auto.set_active(widget.get_active()),
            )
            row.pack_end(switch, False, False, 0)
            box.pack_start(row, False, False, 0)
            pair = Gtk.Button(label=_("Pair another device"))
            pair.connect("clicked", lambda *_: self.show_pairing())
            box.pack_start(pair, False, False, 0)
            clear = Gtk.Button(label=_("Clear shared history"))
            clear.connect("clicked", lambda *_: self.confirm_clear(parent=dialog))
            box.pack_start(clear, False, False, 0)
            disconnect = Gtk.Button(label=_("Disconnect this computer"))
            disconnect.connect("clicked", lambda *_: (dialog.destroy(), self.unpair()))
            box.pack_start(disconnect, False, False, 0)
        clipboard = Gtk.Button(label=_("Clear clipboard on this computer"))
        clipboard.connect("clicked", lambda *_: self.clear_clipboard())
        box.pack_start(clipboard, False, False, 0)
        box.pack_start(
            label(_("Syncclip 0.4.0 · End-to-end encrypted"), "muted"), False, False, 0
        )
        dialog.show_all()
        response = dialog.run()
        dialog.destroy()
        if response == Gtk.ResponseType.APPLY:
            self.build()
            GLib.idle_add(self.show_settings)

    def clear_clipboard(self):
        self.receive_generation += 1
        self.last_text = self.last_image = self.last_files = None
        self.clipboard.set_text("", -1)
        self.clipboard.store()
        self.set_status("Clipboard cleared")

    def confirm_clear(self, clip_id=None, parent=None):
        dialog = Gtk.MessageDialog(
            transient_for=parent or self.window,
            modal=True,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.OK_CANCEL,
            text=_("Delete this clip?") if clip_id else _("Clear shared history?"),
        )
        dialog.format_secondary_text(
            _(
                "Removes this clip from the server and paired devices. Saved files are kept."
            )
            if clip_id
            else _(
                "Removes all shared clips and queued transfers from the server and paired devices. Saved files are kept."
            )
        )
        result = dialog.run()
        dialog.destroy()
        if result == Gtk.ResponseType.OK:
            try:
                self.client.clear_history(clip_id)
                self.receive_generation += 1
                self.image_previews.clear()
            except Exception as error:
                self.set_status(str(error))

    def choose_files(self, *_args):
        dialog = Gtk.FileChooserDialog(
            title=_("Send files"),
            transient_for=self.window,
            action=Gtk.FileChooserAction.OPEN,
        )
        dialog.add_buttons(
            _("Cancel"), Gtk.ResponseType.CANCEL, _("Send"), Gtk.ResponseType.OK
        )
        dialog.set_select_multiple(True)
        if dialog.run() == Gtk.ResponseType.OK:
            self.send_files(dialog.get_filenames(), manual=True)
        dialog.destroy()

    def send_files(self, paths, manual=False):
        if not self.client:
            return
        if len(paths) > 20:
            self.set_status("Choose up to 20 files at a time")
            return
        try:
            fingerprint = tuple(
                (str(path), Path(path).stat().st_mtime_ns, Path(path).stat().st_size)
                for path in paths
            )
        except (OSError, ValueError):
            self.set_status("Could not read the copied files")
            return
        if not manual and fingerprint == self.last_files:
            return
        self.last_files = fingerprint
        self.last_text = self.last_image = None
        client, generation = self.client, self.client_generation
        clipboard_generation = self.clipboard_generation

        def send():
            try:
                for path in paths:
                    if generation != self.client_generation or (
                        not manual and clipboard_generation != self.clipboard_generation
                    ):
                        return
                    client.send_file(path)
            except Exception as error:
                self.last_files = None
                GLib.idle_add(self.set_status, str(error))

        self.image_workers.submit(send)

    def save_attachment(self, clip):
        content = self.client.crypto.decrypt(clip)
        name = content.get(
            "name",
            "Screenshot."
            + {"image/jpeg": "jpg", "image/webp": "webp"}.get(content["mime"], "png"),
        )
        dialog = Gtk.FileChooserDialog(
            title=_("Save file"),
            transient_for=self.window,
            action=Gtk.FileChooserAction.SAVE,
        )
        dialog.add_buttons(
            _("Cancel"), Gtk.ResponseType.CANCEL, _("Save"), Gtk.ResponseType.OK
        )
        dialog.set_do_overwrite_confirmation(True)
        dialog.set_current_name(safe_filename(name))
        path = dialog.get_filename() if dialog.run() == Gtk.ResponseType.OK else None
        dialog.destroy()
        if not path:
            return
        client = self.client

        def save():
            try:
                data = client.load_image(clip)
                Path(path).write_bytes(data)
                GLib.idle_add(self.set_status, "File saved")
            except Exception:
                GLib.idle_add(self.set_status, "Could not save this file. Try again.")

        self.image_workers.submit(save)

    def copy_file(self, clip, automatic=False):
        self.receive_generation += 1
        current = self.receive_generation
        client, generation = self.client, self.client_generation
        clipboard_generation = self.clipboard_generation
        self.set_status("Receiving file…")

        def work():
            try:
                data = client.load_image(clip)
                content = client.crypto.decrypt(clip)
                path = self.store.export_file(clip["id"], content["name"], data)

                def apply():
                    if (
                        generation != self.client_generation
                        or current != self.receive_generation
                        or not self.store.contains(clip["id"])
                    ):
                        return False
                    if automatic and (
                        not self.config.get("auto", True)
                        or clipboard_generation != self.clipboard_generation
                    ):
                        self.set_status("File received · available in history")
                        return False
                    try:
                        self.last_files = (
                            (str(path), path.stat().st_mtime_ns, path.stat().st_size),
                        )
                        self.last_text = self.last_image = None
                        self.file_clipboard.set_files([path])
                        if automatic:
                            self.store.mark_applied(clip["id"])
                        self.set_status("File copied · paste into your file manager")
                    except Exception:
                        self.set_status("Could not copy file. Use Save instead.")
                    return False

                GLib.idle_add(apply)
            except Exception:
                GLib.idle_add(
                    self.set_status,
                    "Could not receive file. Use Copy or Save to retry.",
                )

        self.image_workers.submit(work)

    def pair(self, *_args):
        try:
            self.config = dict(
                parse_pairing(self.pair_entry.get_text()),
                device=socket.gethostname(),
                auto=True,
            )
            atomic_json(ROOT / "config.json", self.config)
            self.connect_client()
            self.build()
            self.window.show_all()
        except Exception as error:
            self.set_status(str(error))

    def connect_client(self):
        if self.client:
            self.client.stop()
        self.client_generation += 1
        generation = self.client_generation

        def dispatch(callback, *args):
            GLib.idle_add(
                lambda: callback(*args)
                if generation == self.client_generation
                else False
            )

        self.client = SyncClient(
            self.config,
            self.store,
            lambda c, p: dispatch(self.receive, c, p),
            lambda s: dispatch(self.set_status, s),
            lambda: dispatch(self.refresh_history),
        )
        self.client.start()

    def set_status(self, text):
        self.status_text = text
        self.status_label.set_text(_(text))
        return False

    def auto_changed(self, switch, *_):
        self.config["auto"] = switch.get_active()
        atomic_json(ROOT / "config.json", self.config)

    def toggle_pause(self):
        if self.config:
            self.auto.set_active(not self.auto.get_active())

    def clipboard_changed(self, *_args):
        self.clipboard_generation += 1
        generation = self.clipboard_generation
        if self.client and self.config.get("auto", True):
            self.read_clipboard(generation)

    def read_clipboard(self, generation, manual=False):
        def image_ready(cb, pixbuf, _):
            if generation != self.clipboard_generation:
                return
            if pixbuf is not None:
                self.send_pixbuf(pixbuf, manual)
            else:
                self.clipboard.request_text(
                    lambda cb, text, _: self.send_text(text)
                    if manual
                    else self.local_text(text, generation),
                    None,
                )

        def uris_ready(cb, uris, _):
            if generation != self.clipboard_generation:
                return
            if uris:
                paths = []
                for uri in uris:
                    parsed = urlparse(uri)
                    if parsed.scheme != "file" or parsed.netloc not in (
                        "",
                        "localhost",
                    ):
                        self.set_status("Only local files can be shared")
                        return
                    paths.append(unquote(parsed.path))
                self.send_files(paths, manual)
            else:
                self.clipboard.request_image(image_ready, None)

        self.clipboard.request_uris(uris_ready, None)

    def local_text(self, text, generation):
        if generation != self.clipboard_generation or not text:
            return
        self.last_image = self.last_files = None
        if text == self.suppress_text:
            self.suppress_text = None
            self.last_text = text
            return
        self.suppress_text = None
        if text == self.last_text or len(text.encode()) > MAX_TEXT:
            return
        # Never share the pairing secret itself through the clipboard relay.
        if text.strip().startswith("syncclip://pair?"):
            return
        self.send_text(text)

    def send_clipboard(self):
        self.read_clipboard(self.clipboard_generation, True)

    @staticmethod
    def image_fingerprint(pixbuf):
        rgba = pixbuf if pixbuf.get_has_alpha() else pixbuf.add_alpha(False, 0, 0, 0)
        digest = hashlib.sha256()
        digest.update(f"{rgba.get_width()}x{rgba.get_height()}".encode())
        data = rgba.get_pixels()
        for row in range(rgba.get_height()):
            start = row * rgba.get_rowstride()
            digest.update(data[start : start + rgba.get_width() * 4])
        return digest.hexdigest()

    def send_pixbuf(self, pixbuf, manual=False):
        if not self.client:
            self.window.present()
            return
        if pixbuf.get_width() * pixbuf.get_height() > 24_000_000:
            self.set_status("Images can be up to 24 megapixels")
            return
        client = self.client
        generation = self.client_generation
        clipboard_generation = self.clipboard_generation

        def prepare():
            try:
                fingerprint = self.image_fingerprint(pixbuf)
                if not manual and fingerprint == self.last_image:
                    return
                self.last_image = fingerprint
                self.last_text = self.last_files = None
                success, data = pixbuf.save_to_bufferv("png", [], [])
                if not success:
                    raise ValueError("Could not read the clipboard image")
                if generation == self.client_generation and (
                    manual or clipboard_generation == self.clipboard_generation
                ):
                    client.send_image(data)
            except Exception as error:
                self.last_image = None
                GLib.idle_add(self.set_status, str(error))

        self.image_workers.submit(prepare)

    def send_text(self, text):
        if not self.client:
            self.window.present()
            return
        if text and text.strip().startswith("syncclip://pair?"):
            self.set_status("Pairing links are private and are never synced.")
            return
        try:
            self.client.send(text or "")
            self.last_text = text
        except Exception as error:
            self.set_status(str(error))

    def receive(self, clip, content):
        if self.config.get("auto", True):
            if content.get("kind") == "file":
                self.copy_file(clip, automatic=True)
            elif content.get("kind") == "image":
                self.copy_image(clip, automatic=True)
            else:
                self.copy_text(content["text"])
        return False

    @staticmethod
    def decode_image(data):
        raw, width, height = decode_rgba(data)
        return GdkPixbuf.Pixbuf.new_from_bytes(
            GLib.Bytes.new(raw), GdkPixbuf.Colorspace.RGB, True, 8,
            width, height, width * 4,
        )

    def copy_image(self, clip, automatic=False):
        self.receive_generation += 1
        receive_generation = self.receive_generation
        client = self.client
        generation = self.client_generation
        clipboard_generation = self.clipboard_generation
        self.set_status("Receiving image…")

        def work():
            try:
                pixbuf = self.decode_image(client.load_image(clip))
                fingerprint = self.image_fingerprint(pixbuf)

                def apply():
                    if (
                        generation != self.client_generation
                        or receive_generation != self.receive_generation
                        or not self.store.contains(clip["id"])
                    ):
                        return False
                    # A screenshot downloading in the background must not overwrite a newer copy.
                    if automatic and (
                        not self.config.get("auto", True)
                        or clipboard_generation != self.clipboard_generation
                    ):
                        self.set_status("Image received · available in history")
                        return False
                    self.last_image = fingerprint
                    self.last_text = self.last_files = None
                    self.suppress_text = None
                    self.clipboard.set_image(pixbuf)
                    self.clipboard.store()
                    if automatic:
                        self.store.mark_applied(clip["id"])
                    self.set_status(
                        "Connected" if client.socket else "Offline · image copied"
                    )
                    self.refresh_history()
                    return False

                GLib.idle_add(apply)
            except Exception:
                GLib.idle_add(
                    self.set_status, "Could not receive image. Use Copy to retry."
                )

        self.image_workers.submit(work)

    def copy_text(self, text):
        self.receive_generation += 1
        self.last_image = self.last_files = None
        self.suppress_text = text
        self.last_text = text
        self.clipboard.set_text(text, -1)
        self.clipboard.store()

    def fit_preview(self, widget, clip_id, width):
        pix = self.image_previews.get(clip_id)
        if pix is None or width < 2:
            return
        height = max(1, round(pix.get_height() * width / pix.get_width()))
        if widget.get_size_request() != (1, height):
            widget.set_size_request(1, height)
        current = widget.get_pixbuf()
        if (
            current is None
            or current.get_width() != width
            or current.get_height() != height
        ):
            widget.set_from_pixbuf(
                pix.scale_simple(width, height, GdkPixbuf.InterpType.BILINEAR)
            )

    def refresh_history(self):
        if not hasattr(self, "history") or not self.client:
            return False
        self.image_previews = {
            key: preview
            for key, preview in self.image_previews.items()
            if self.store.contains(key)
        }
        self.history_generation += 1
        history_generation = self.history_generation
        for child in self.history.get_children():
            self.history.remove(child)
        state = self.store.snapshot()
        count = len(state["pending"])
        self.pending_label.set_text(
            fmt("%s waiting to send", count) if count else _("Everything sent")
        )
        if not state["history"]:
            self.history.pack_start(
                label(_("Your shared clips will appear here."), "muted"),
                False,
                False,
                12,
            )
        for clip in reversed(state["history"]):
            try:
                content = self.client.crypto.decrypt(clip)
            except Exception:
                continue
            card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            card.get_style_context().add_class("card")
            from datetime import datetime

            header = inset(Gtk.Box(spacing=10))
            header.set_margin_top(8)
            header.pack_start(
                label(
                    content["device"]
                    + "  ·  "
                    + datetime.fromtimestamp(clip["created"] / 1000).strftime("%H:%M"),
                    "muted",
                ),
                True,
                True,
                0,
            )
            delete = Gtk.Button.new_from_icon_name(
                "user-trash-symbolic", Gtk.IconSize.SMALL_TOOLBAR
            )
            delete.get_style_context().add_class("icon-button")
            delete.set_tooltip_text(_("Delete clip"))
            delete.get_accessible().set_name(_("Delete clip"))
            delete.connect("clicked", lambda _, c=clip: self.confirm_clear(c["id"]))
            header.pack_end(delete, False, False, 0)
            card.pack_start(header, False, False, 0)
            if content.get("kind") == "file":
                name = inset(label(content["name"]))
                name.set_max_width_chars(40)
                card.pack_start(name, False, False, 0)
                card.pack_start(
                    inset(label(fmt("File · %s", size(content["bytes"])), "muted")),
                    False,
                    False,
                    0,
                )
                buttons = inset(Gtk.Box(spacing=10))
                buttons.set_margin_bottom(12)
                copy = Gtk.Button(label=_("Copy file"))
                copy.connect("clicked", lambda _, c=clip: self.copy_file(c))
                buttons.pack_start(copy, False, False, 0)
                save = Gtk.Button(label=_("Save as…"))
                save.connect("clicked", lambda _, c=clip: self.save_attachment(c))
                buttons.pack_start(save, False, False, 0)
                card.pack_start(buttons, False, False, 0)
                self.history.pack_start(card, False, False, 0)
                continue
            if content.get("kind") == "image":
                preview = Gtk.Image.new_from_icon_name(
                    "image-x-generic", Gtk.IconSize.DIALOG
                )
                preview.set_size_request(1, -1)
                preview.connect(
                    "size-allocate",
                    lambda widget, rect, key=clip["id"]: GLib.idle_add(
                        self.fit_preview, widget, key, rect.width
                    ),
                )
                card.pack_start(preview, False, False, 0)
                card.pack_start(
                    inset(label(fmt("Image · %s", size(content["bytes"])), "muted")),
                    False,
                    False,
                    0,
                )
                cached = self.image_previews.get(clip["id"])
                if cached:
                    self.fit_preview(
                        preview, clip["id"], max(1, self.history.get_allocated_width())
                    )
                elif self.store.blob_path(clip["id"]).exists():
                    client = self.client

                    def thumbnail(clip=clip, widget=preview, client=client):
                        try:
                            pix = self.decode_image(client.load_image(clip))
                            ratio = min(
                                1600 / pix.get_width(), 1200 / pix.get_height(), 1
                            )
                            pix = pix.scale_simple(
                                max(1, int(pix.get_width() * ratio)),
                                max(1, int(pix.get_height() * ratio)),
                                GdkPixbuf.InterpType.BILINEAR,
                            )

                            def show():
                                if not self.store.contains(clip["id"]):
                                    return False
                                self.image_previews[clip["id"]] = pix
                                if len(self.image_previews) > 50:
                                    self.image_previews.pop(
                                        next(iter(self.image_previews))
                                    )
                                if history_generation == self.history_generation:
                                    self.fit_preview(
                                        widget,
                                        clip["id"],
                                        max(1, self.history.get_allocated_width()),
                                    )
                                return False

                            GLib.idle_add(show)
                        except Exception:
                            pass

                    self.image_workers.submit(thumbnail)
                button = Gtk.Button(label=_("Copy image"))
                button.set_halign(Gtk.Align.START)
                button.connect("clicked", lambda _, c=clip: self.copy_image(c))
                buttons = inset(Gtk.Box(spacing=10))
                buttons.set_margin_bottom(12)
                buttons.pack_start(button, False, False, 0)
                save = Gtk.Button(label=_("Save as…"))
                save.connect("clicked", lambda _, c=clip: self.save_attachment(c))
                buttons.pack_start(save, False, False, 0)
                card.pack_start(buttons, False, False, 0)
                self.history.pack_start(card, False, False, 0)
                continue
            preview = label(
                content["text"][:220] + ("…" if len(content["text"]) > 220 else "")
            )
            inset(preview)
            preview.set_max_width_chars(45)
            card.pack_start(preview, False, False, 0)
            button = Gtk.Button(label=_("Copy"))
            button.set_halign(Gtk.Align.START)
            button.connect(
                "clicked", lambda _, text=content["text"]: self.copy_text(text)
            )
            inset(button)
            button.set_margin_bottom(12)
            card.pack_start(button, False, False, 0)
            self.history.pack_start(card, False, False, 0)
        self.history.show_all()
        return False

    def show_pairing(self, *_args):
        dialog = Gtk.Dialog(
            title=_("Pair your phone"), transient_for=self.window, modal=True
        )
        dialog.add_button(_("Done"), Gtk.ResponseType.CLOSE)
        box = dialog.get_content_area()
        box.set_spacing(12)
        box.set_border_width(20)
        box.pack_start(
            label(
                _("Scan with Syncclip on Android, or paste the pairing link."), "muted"
            ),
            False,
            False,
            0,
        )
        uri = pairing_link(self.config)
        try:
            import subprocess
            from gi.repository import GdkPixbuf

            png = subprocess.check_output(
                ["qrencode", "-t", "PNG", "-s", "6", "-m", "2"], input=uri.encode()
            )
            loader = GdkPixbuf.PixbufLoader.new_with_type("png")
            loader.write(png)
            loader.close()
            box.pack_start(
                Gtk.Image.new_from_pixbuf(loader.get_pixbuf()), False, False, 0
            )
        except Exception:
            pass
        entry = Gtk.Entry(text=uri, visibility=False, editable=False)
        box.pack_start(entry, False, False, 0)
        button = Gtk.Button(label=_("Copy pairing link"))
        button.connect("clicked", lambda *_: self.copy_text(uri))
        box.pack_start(button, False, False, 0)
        box.pack_start(
            label(
                _("Keep this link private. It grants access to your shared clipboard."),
                "muted",
            ),
            False,
            False,
            0,
        )
        dialog.show_all()
        dialog.run()
        dialog.destroy()

    def unpair(self, *_args):
        dialog = Gtk.MessageDialog(
            transient_for=self.window,
            modal=True,
            message_type=Gtk.MessageType.QUESTION,
            buttons=Gtk.ButtonsType.OK_CANCEL,
            text=_("Disconnect this computer?"),
        )
        dialog.format_secondary_text(
            _(
                "Local clips and pairing details will be removed. Your other devices stay connected."
            )
        )
        result = dialog.run()
        dialog.destroy()
        if result != Gtk.ResponseType.OK:
            return
        self.client_generation += 1
        self.client.stop()
        self.client = None
        self.config = {}
        (ROOT / "config.json").unlink(missing_ok=True)
        self.store.data = {
            "history": [],
            "pending": [],
            "last_id": "",
            "epoch": 0,
            "deleted": [],
        }
        self.store.save()
        for file in (ROOT / "images").glob("*"):
            if file.is_file():
                file.unlink()
        import shutil

        shutil.rmtree(ROOT / "exports", ignore_errors=True)
        self.image_previews.clear()
        self.status_text = "Not paired"
        self.build()
        self.window.show_all()

    def expire(self):
        self.store.prune()
        self.refresh_history()
        return True

    def finish(self):
        if self.client:
            self.client.stop()
        self.quit()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--background", action="store_true")
    options = parser.parse_args()
    ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
    App(options.background).run([sys.argv[0]])


if __name__ == "__main__":
    main()
