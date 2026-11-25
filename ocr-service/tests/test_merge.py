from app.merge import arabic_only, hinted_tokens, merge_line, tokens


def test_recovered_token_is_inserted_next_to_its_anchor_and_arabic_is_unchanged():
    paddle = "يجب تسجيل الطلب في نظام قبل نهاية الشهر"
    result = merge_line(paddle, "1 HRIS", "يجب تسجيل الطلب في نظام HRIS قبل نهاية الشهر")

    assert result.proposed_text == "يجب تسجيل الطلب في نظام HRIS قبل نهاية الشهر"
    assert arabic_only(result.proposed_text) == arabic_only(paddle)
    assert [r.token for r in result.recovered] == ["HRIS"]
    assert result.recovered[0].anchor == "نظام"


def test_token_seen_only_by_tesseract_is_not_inserted():
    paddle = "رقم الهاتف لقسم الموارد البشرية"
    result = merge_line(paddle, "abc", "رقم الهاتف لقسم الموارد البشرية: 0225551234")

    assert result.proposed_text == paddle
    assert result.recovered == []


def test_token_already_present_is_never_inserted_twice():
    paddle = "رقم السياسة POL-2026 للعام"
    result = merge_line(paddle, "POL-2026 2026", "رقم السياسة POL-2026 للعام 2026")

    assert result.proposed_text.count("POL-2026") == 1


def test_ambiguous_anchor_leaves_the_token_unplaced():
    # Both neighbouring words ("في" and "المكتب") occur twice, so the position cannot be decided safely.
    paddle = "في المكتب في المكتب"
    result = merge_line(paddle, "1500", "في 1500 المكتب في المكتب")

    assert result.proposed_text == paddle
    assert result.unplaced == ["1500"]
    assert result.possibly_missing == []


def test_disagreeing_arabic_reads_are_not_merged():
    paddle = "يستحق الموظف إجازة سنوية"
    result = merge_line(paddle, "2026", "كلام مختلف تماما 2026 ليس له علاقة")

    assert result.proposed_text == paddle
    assert result.possibly_missing == ["2026"]


def test_hint_without_tesseract_read_is_reported_as_possibly_missing():
    result = merge_line("رقم الهاتف", "0225551234", None)

    assert result.proposed_text == "رقم الهاتف"
    assert result.possibly_missing == ["0225551234"]


def test_tesseract_direction_marks_are_ignored():
    paddle = "يجب تسجيل الطلب في نظام قبل نهاية الشهر"
    result = merge_line(paddle, "HRIS", "‏يجب تسجيل الطلب في نظام ‎HRIS‏ قبل نهاية الشهر")

    assert "‎" not in result.proposed_text and "‏" not in result.proposed_text
    assert "HRIS" in result.proposed_text


def test_eastern_and_western_digits_are_the_same_token():
    assert tokens("١٢٠") == tokens("120") == {"120"}
    assert hinted_tokens("الحد الأقصى ١٢٠ يوما", "120") == set()
