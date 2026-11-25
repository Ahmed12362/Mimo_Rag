import pytest

from app.png import ImageRejected, check_limits, read_header
from tests.fake_worker import png

LIMITS = dict(min_side=16, max_side=10_000, max_pixels=40_000_000)


def test_reads_dimensions_from_the_header():
    header = read_header(png(2480, 3508))
    assert (header.width, header.height) == (2480, 3508)


def test_rejects_non_png_bytes():
    with pytest.raises(ImageRejected) as rejected:
        read_header(b"%PDF-1.7 not an image")
    assert rejected.value.status == 415


def test_rejects_truncated_png():
    with pytest.raises(ImageRejected) as rejected:
        read_header(b"\x89PNG\r\n\x1a\n\x00\x00")
    assert rejected.value.error_code == "INVALID_IMAGE"


def test_decompression_bomb_is_rejected_from_the_header_before_decoding():
    # A few dozen bytes declaring a 50,000 x 50,000 canvas (2.5 gigapixels).
    with pytest.raises(ImageRejected) as rejected:
        check_limits(read_header(png(50_000, 50_000)), **LIMITS)
    assert rejected.value.status == 413


def test_side_and_pixel_limits():
    check_limits(read_header(png(2480, 3508)), **LIMITS)  # A4 at 300 DPI is fine
    for width, height in [(10_001, 100), (8_000, 8_000), (15, 100)]:
        with pytest.raises(ImageRejected):
            check_limits(read_header(png(width, height)), **LIMITS)
