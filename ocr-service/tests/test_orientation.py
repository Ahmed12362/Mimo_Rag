import numpy as np
import pytest

from app.orientation import better, candidate_rotations, needs_orientation_check, rotate, unrotate_box


def summary(characters, confidence):
    return {"characters": characters, "meanConfidence": confidence}


def test_good_pages_are_not_checked():
    assert not needs_orientation_check(summary(300, 0.93))


def test_empty_or_low_confidence_pages_are_checked():
    assert needs_orientation_check(summary(3, 0.95))
    assert needs_orientation_check(summary(300, 0.42))


def test_rotated_reading_must_be_clearly_better():
    assert better(summary(320, 0.91), summary(280, 0.45))
    assert not better(summary(320, 0.47), summary(280, 0.45)), "within the confidence margin"
    assert not better(summary(40, 0.95), summary(280, 0.45)), "reads much less text"
    assert not better(summary(0, 0.0), summary(280, 0.45))


@pytest.mark.parametrize("quarter_turns", [0, 1, 2, 3])
def test_boxes_found_in_a_rotated_image_map_back_to_the_submitted_image(quarter_turns):
    height, width = 60, 100
    original = np.zeros((height, width), dtype=np.uint8)
    original[10:20, 30:70] = 1  # a 40 x 10 box at x=30, y=10
    rotated = rotate(original, quarter_turns)
    ys, xs = np.nonzero(rotated)
    found = {"x": int(xs.min()), "y": int(ys.min()), "width": int(xs.max() - xs.min() + 1),
             "height": int(ys.max() - ys.min() + 1)}

    assert unrotate_box(found, quarter_turns, width, height) == {"x": 30, "y": 10, "width": 40, "height": 10}


def test_classifier_proposal_is_tried_first():
    assert candidate_rotations(90, summary(300, 0.93)) == [90]
    assert candidate_rotations(180, summary(3, 0.40)) == [180]


def test_poorly_read_page_is_also_tried_upside_down_when_the_classifier_says_upright():
    assert candidate_rotations(0, summary(80, 0.44)) == [180]
    assert candidate_rotations(270, summary(80, 0.44)) == [270, 180]


def test_well_read_upright_page_is_not_rotated():
    assert candidate_rotations(0, summary(300, 0.93)) == []
