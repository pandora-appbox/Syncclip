"""Shared UTF-8 UI catalogs; content and protocol fields are never translated."""

import json
import locale
import os
from pathlib import Path

LANGUAGES = {
    "": "System default",
    "en": "English",
    "fr": "Français",
    "es": "Español",
    "ru": "Русский",
}
_catalog = {}
_language = "en"


def set_language(tag=""):
    global _catalog, _language
    system = (
        os.environ.get("LANGUAGE", "").split(":")[0] or locale.getlocale()[0] or "en"
    )
    _language = (tag or system).split("_")[0].split("-")[0].lower()
    if _language not in LANGUAGES:
        _language = "en"
    path = Path(__file__).parent / "locales" / (_language + ".json")
    _catalog = json.loads(path.read_text()) if path.exists() else {}


def tr(source):
    return _catalog.get(source, source)


def fmt(source, *values):
    return tr(source) % values


def size(value):
    if value < 1024:
        return fmt("%s B", value)
    if value < 1024 * 1024:
        return fmt("%s KB", f"{value / 1024:.0f}")
    return fmt("%s MB", f"{value / (1024 * 1024):.1f}")
