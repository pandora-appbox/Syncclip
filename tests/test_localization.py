import json
from pathlib import Path
import re
import unittest

from syncclip import i18n


class LocalizationTests(unittest.TestCase):
    def tearDown(self):
        i18n.set_language("en")

    def test_all_languages_cover_the_same_messages_and_placeholders(self):
        folder = Path(i18n.__file__).parent / "locales"
        source = json.loads((folder / "en.json").read_text())
        for tag in ("fr", "es", "ru"):
            translations = json.loads((folder / (tag + ".json")).read_text())
            self.assertEqual(set(source), set(translations))
            for key, value in translations.items():
                self.assertTrue(value.strip(), (tag, key))
                self.assertEqual(
                    re.findall(r"%[sd]", key), re.findall(r"%[sd]", value), (tag, key)
                )

    def test_language_override_and_unsupported_language_fallback(self):
        for tag, expected in [
            ("en", "Settings"),
            ("fr", "Réglages"),
            ("es", "Ajustes"),
            ("ru", "Настройки"),
            ("unknown", "Settings"),
        ]:
            i18n.set_language(tag)
            self.assertEqual(i18n.tr("Settings"), expected)
            self.assertEqual(i18n.tr("personal filename.txt"), "personal filename.txt")
            self.assertIn("3", i18n.fmt("%s waiting to send", 3))
