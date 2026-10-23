"""Decode untrusted attachment bytes without invoking GTK's system image loaders."""

from io import BytesIO

from PIL import Image

from .core import MAX_IMAGE, image_type


def webp_size(data):
    # Pillow constructs the WebP animation decoder at open(), which allocates its
    # canvas. Read the format's fixed canvas header before entering native code.
    kind = data[12:16]
    if kind == b"VP8X" and len(data) >= 30:
        return int.from_bytes(data[24:27], "little") + 1, int.from_bytes(
            data[27:30], "little"
        ) + 1
    if kind == b"VP8 " and len(data) >= 30 and data[23:26] == b"\x9d\x01\x2a":
        return int.from_bytes(data[26:28], "little") & 0x3FFF, int.from_bytes(
            data[28:30], "little"
        ) & 0x3FFF
    if kind == b"VP8L" and len(data) >= 25 and data[20] == 0x2F:
        bits = int.from_bytes(data[21:25], "little")
        return (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1
    raise ValueError("Invalid WebP header")


def check_size(width, height):
    if width <= 0 or height <= 0 or width * height > 24_000_000:
        raise ValueError("Images can be up to 24 megapixels")


def decode_rgba(data):
    if not data or len(data) > MAX_IMAGE:
        raise ValueError("Images can be up to 8 MB")
    mime = image_type(data)
    if mime == "image/webp":
        check_size(*webp_size(data))
    with Image.open(BytesIO(data), formats=["PNG", "JPEG", "WEBP"]) as image:
        if {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp"}.get(
            image.format
        ) != mime:
            raise ValueError("Image format does not match its description")
        width, height = image.size
        check_size(width, height)
        # Never run color-profile conversion on untrusted ICC data.
        return image.convert("RGBA").tobytes(), width, height
