"""PNG checks done on the raw bytes, before any decoding (a small PNG can declare a huge canvas)."""
import struct
from dataclasses import dataclass

SIGNATURE = b"\x89PNG\r\n\x1a\n"


class ImageRejected(Exception):
    """The image cannot be accepted. status/error_code go straight into the problem response."""

    def __init__(self, status: int, error_code: str, detail: str):
        super().__init__(detail)
        self.status = status
        self.error_code = error_code
        self.detail = detail


@dataclass(frozen=True)
class PngHeader:
    width: int
    height: int


def read_header(data: bytes) -> PngHeader:
    """Validates the PNG signature and reads width/height from the IHDR chunk."""
    if not data.startswith(SIGNATURE):
        raise ImageRejected(415, "UNSUPPORTED_MEDIA_TYPE", "Only PNG images are accepted.")
    if len(data) < 33:
        raise ImageRejected(400, "INVALID_IMAGE", "The PNG is truncated.")
    length, chunk_type = struct.unpack(">I4s", data[8:16])
    if chunk_type != b"IHDR" or length != 13:
        raise ImageRejected(400, "INVALID_IMAGE", "The PNG has no valid header chunk.")
    width, height = struct.unpack(">II", data[16:24])
    return PngHeader(width, height)


def check_limits(header: PngHeader, *, min_side: int, max_side: int, max_pixels: int) -> None:
    if header.width < min_side or header.height < min_side:
        raise ImageRejected(400, "INVALID_IMAGE", f"The image must be at least {min_side}x{min_side} pixels.")
    if header.width > max_side or header.height > max_side:
        raise ImageRejected(413, "IMAGE_TOO_LARGE", f"Each side must be at most {max_side} pixels.")
    if header.width * header.height > max_pixels:
        raise ImageRejected(413, "IMAGE_TOO_LARGE", f"The image must have at most {max_pixels} pixels.")
