"""Orientation (decision D-32, revised by B1 in slice 2d): pure decisions and geometry, unit-tested without models.

The classifier runs on every page in ``auto`` mode but only proposes a rotation (its confidence was 0.62-0.87 even on clear cases in slice 2d
checks). A rotated reading is kept only if its OCR result is clearly better, so an upright page can never be made worse.
"""
import numpy as np

MIN_CHARACTERS = 20
MIN_MEAN_CONFIDENCE = 0.60
CONFIDENCE_MARGIN = 0.05
CHARACTER_FACTOR = 1.2


def needs_orientation_check(summary: dict) -> bool:
    """A page is suspicious when almost nothing was read, or read with low confidence."""
    return summary["characters"] < MIN_CHARACTERS or summary["meanConfidence"] < MIN_MEAN_CONFIDENCE


def candidate_rotations(classifier_label: int, summary: dict) -> list[int]:
    """Rotations to try, in order: the classifier's proposal, then 180 degrees for a page that reads poorly."""
    candidates = [classifier_label] if classifier_label != 0 else []
    if 180 not in candidates and needs_orientation_check(summary):
        candidates.append(180)
    return candidates


def better(candidate: dict, current: dict) -> bool:
    """The rotated reading must be clearly more confident and read at least as much text."""
    if candidate["characters"] == 0:
        return False
    if current["characters"] == 0:
        return candidate["meanConfidence"] >= MIN_MEAN_CONFIDENCE
    return (candidate["meanConfidence"] >= current["meanConfidence"] + CONFIDENCE_MARGIN
            and candidate["characters"] >= current["characters"] / CHARACTER_FACTOR)


def rotate(image: np.ndarray, quarter_turns: int) -> np.ndarray:
    """Counter-clockwise quarter turns (numpy convention)."""
    return np.ascontiguousarray(np.rot90(image, quarter_turns % 4))


def unrotate_box(box: dict, quarter_turns: int, width: int, height: int) -> dict:
    """Maps a box found in the rotated image back to the submitted image of size width x height.

    For R = rot90(O, k) (counter-clockwise): k=1 maps O(x, y) to R(y, W-x); k=2 to R(W-x, H-y); k=3 to R(H-y, x).
    """
    k = quarter_turns % 4
    u0, v0 = box["x"], box["y"]
    u1, v1 = u0 + box["width"], v0 + box["height"]
    if k == 0:
        x0, y0, x1, y1 = u0, v0, u1, v1
    elif k == 1:
        x0, x1, y0, y1 = width - v1, width - v0, u0, u1
    elif k == 2:
        x0, x1, y0, y1 = width - u1, width - u0, height - v1, height - v0
    else:
        x0, x1, y0, y1 = v0, v1, height - u1, height - u0
    return {"x": int(x0), "y": int(y0), "width": int(x1 - x0), "height": int(y1 - y0)}
