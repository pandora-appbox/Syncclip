from io import BytesIO
import struct
import unittest
from unittest.mock import patch
import zlib

from PIL import Image
from syncclip.images import decode_rgba


class ImageDecoderTests(unittest.TestCase):
    def test_supported_formats_alpha_and_ignored_bad_profile(self):
        for fmt in ["PNG", "JPEG", "WEBP"]:
            image = Image.new("RGB", (13, 9), (30, 40, 50))
            output = BytesIO()
            image.save(output, fmt, icc_profile=b"not a valid color profile")
            raw, width, height = decode_rgba(output.getvalue())
            self.assertEqual((width, height, len(raw)), (13, 9, 13 * 9 * 4))
            self.assertEqual(raw[3::4], b"\xff" * (13 * 9))
        output = BytesIO()
        Image.new("RGBA", (3, 2), (4, 5, 6, 7)).save(output, "PNG")
        self.assertEqual(decode_rgba(output.getvalue())[0], bytes([4, 5, 6, 7]) * 6)

    def test_pixel_limit_before_decode_allocation(self):
        def chunk(kind, value):
            return (
                struct.pack(">I", len(value))
                + kind
                + value
                + struct.pack(">I", zlib.crc32(kind + value))
            )

        data = b"\x89PNG\r\n\x1a\n"
        data += chunk(b"IHDR", struct.pack(">IIBBBBB", 24_000_001, 1, 8, 2, 0, 0, 0))
        data += chunk(b"IDAT", b"") + chunk(b"IEND", b"")
        with self.assertRaisesRegex(ValueError, "24 megapixels"):
            decode_rgba(data)

    def test_non_images_unsupported_formats_and_truncation_rejected(self):
        for value in [b"", b"not an image", b"\xff\xd8\xffbad"]:
            with self.assertRaises((ValueError, OSError)):
                decode_rgba(value)
        output = BytesIO()
        Image.new("RGB", (16, 16)).save(output, "ICO")
        with self.assertRaises(ValueError):
            decode_rgba(output.getvalue())

    def test_webp_canvas_limit_precedes_native_decoder_creation(self):
        # VP8X width/height are 24-bit little endian values minus one.
        data = b"RIFF" + struct.pack("<I", 22) + b"WEBPVP8X" + struct.pack("<I", 10)
        data += bytes(4) + (5000).to_bytes(3, "little") + (4999).to_bytes(3, "little")
        with patch("syncclip.images.Image.open") as decoder:
            with self.assertRaisesRegex(ValueError, "24 megapixels"):
                decode_rgba(data)
            decoder.assert_not_called()
        for lossless in [False, True]:
            output = BytesIO()
            Image.new("RGBA", (4, 3), (20, 30, 40, 50)).save(
                output, "WEBP", lossless=lossless
            )
            raw, width, height = decode_rgba(output.getvalue())
            self.assertEqual((width, height, len(raw)), (4, 3, 48))
            self.assertEqual(raw[3::4], bytes([50]) * 12)
