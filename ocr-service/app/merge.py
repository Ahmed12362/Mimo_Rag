"""Gated hybrid token recovery: pure functions, no OCR engines (moved from benchmarks/ocr/hybrid_rec.py).

The Arabic text of the PaddleOCR line is never changed. A Latin/digit token is inserted only when Tesseract has it,
the English PaddleOCR recogniser also sees it (the gate), the Arabic parts of both lines agree, and an Arabic anchor
word next to it matches exactly one word of the PaddleOCR line. Everything else becomes a review flag.
Measured in slice 2a: 0 false insertions, 0 duplicates, Arabic unchanged on 35/35 page images.
"""
import re
from dataclasses import dataclass, field

TOKEN = re.compile(r"[A-Za-z0-9٠-٩](?:[A-Za-z0-9٠-٩@._\-/:]*[A-Za-z0-9٠-٩])?")
ARABIC = re.compile(r"[؀-ۿ]")
NON_ARABIC = re.compile(r"[^؀-ۿ ]")
DIRECTION_MARKS = re.compile(r"[‎‏؜]")
WORD_STRIP = re.compile(r"[^؀-ۿ]")
DIACRITIC_MARKS = re.compile(r"[ً-ٰٟـ]")
EASTERN_DIGITS = str.maketrans("٠١٢٣٤٥٦٧٨٩", "0123456789")

AGREEMENT_THRESHOLD = 0.5
MIN_HINT_LENGTH = 2


def tokens(text: str) -> set[str]:
    """Latin/digit tokens; Eastern Arabic digits count as the same token as Western ones."""
    return {t.translate(EASTERN_DIGITS) for t in TOKEN.findall(text)}


def arabic_only(text: str) -> str:
    return re.sub(r"\s+", " ", NON_ARABIC.sub(" ", text)).strip()


def has_arabic(text: str) -> bool:
    return bool(ARABIC.search(text))


def strip_direction_marks(text: str) -> str:
    """Tesseract inserts LRM/RLM/ALM: engine artefacts, not document content."""
    return DIRECTION_MARKS.sub("", text)


def _anchor_key(word: str) -> str:
    return WORD_STRIP.sub("", DIACRITIC_MARKS.sub("", word))


def _levenshtein(a: str, b: str) -> int:
    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        current = [i]
        for j, cb in enumerate(b, 1):
            current.append(min(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + (ca != cb)))
        previous = current
    return previous[-1]


def similarity(a: str, b: str) -> float:
    if not a and not b:
        return 1.0
    return 1.0 - _levenshtein(a, b) / max(len(a), len(b))


def _unique_match(words: list[str], anchor: str) -> int | None:
    key = _anchor_key(anchor)
    if len(key) < 2:
        return None
    exact = [i for i, w in enumerate(words) if _anchor_key(w) == key]
    if len(exact) == 1:
        return exact[0]
    if exact or len(key) < 4:
        return None
    near = [i for i, w in enumerate(words) if len(_anchor_key(w)) >= 3 and _levenshtein(_anchor_key(w), key) <= 1]
    return near[0] if len(near) == 1 else None


@dataclass
class RecoveredToken:
    token: str
    offset: int
    sources: list[str]
    anchor: str


@dataclass
class MergeResult:
    proposed_text: str
    recovered: list[RecoveredToken] = field(default_factory=list)
    unplaced: list[str] = field(default_factory=list)
    possibly_missing: list[str] = field(default_factory=list)


def hinted_tokens(arabic_line: str, latin_line: str) -> set[str]:
    """Tokens the English gate sees that the Arabic line lacks (the trigger for a Tesseract read)."""
    return {t for t in tokens(latin_line) - tokens(arabic_line) if len(t) >= MIN_HINT_LENGTH}


def merge_line(arabic_line: str, latin_line: str | None, tesseract_line: str | None) -> MergeResult:
    """Combines the three reads of one Arabic line. Never alters the Arabic line's characters."""
    hints = hinted_tokens(arabic_line, latin_line or "")
    if not hints or tesseract_line is None:
        return MergeResult(arabic_line, possibly_missing=sorted(hints))
    tess = strip_direction_marks(tesseract_line)
    gained = (tokens(tess) - tokens(arabic_line)) & tokens(latin_line or "")
    agreement = similarity(arabic_only(arabic_line), arabic_only(tess))
    if not gained or agreement < AGREEMENT_THRESHOLD:
        return MergeResult(arabic_line, possibly_missing=sorted(hints))
    result = _insert(arabic_line, tess, gained)
    result.possibly_missing = sorted(hints - tokens(result.proposed_text) - set(result.unplaced))
    return result


def _insert(paddle_text: str, tess_text: str, recovered: set[str]) -> MergeResult:
    words = paddle_text.split()
    tess_words = tess_text.split()
    inserted: list[tuple[str, str]] = []
    unplaced: list[str] = []
    for token in sorted(recovered):
        if token in tokens(" ".join(words)):
            continue  # already present: never inserted twice
        position = next((i for i, w in enumerate(tess_words) if token in tokens(w)), None)
        if position is None:
            unplaced.append(token)
            continue
        source_word = tess_words[position]
        before = next((w for w in reversed(tess_words[:position]) if has_arabic(w)), None)
        after = next((w for w in tess_words[position + 1:] if has_arabic(w)), None)
        target, anchor = None, None
        if before is not None and (j := _unique_match(words, before)) is not None:
            target, anchor = j + 1, before
        elif after is not None and (j := _unique_match(words, after)) is not None:
            target, anchor = j, after
        if target is None:
            unplaced.append(token)
            continue
        # Insert the token itself, never Tesseract's Arabic letters around it.
        piece = token if has_arabic(source_word) else source_word
        words.insert(target, piece)
        inserted.append((token, anchor))
    merged = " ".join(words)
    if arabic_only(merged) != arabic_only(paddle_text) or not {t for t, _ in inserted} <= tokens(merged):
        # Safety net: the merge must never change Arabic content or lose a token. Fall back to the original.
        return MergeResult(paddle_text, unplaced=sorted({t for t, _ in inserted} | set(unplaced)))
    recovered_tokens = []
    for token, anchor in inserted:
        offset = _token_offset(merged, token)
        recovered_tokens.append(RecoveredToken(token, offset, ["tesseract", "paddle-en"], anchor))
    return MergeResult(merged, recovered_tokens, sorted(unplaced))


def _token_offset(text: str, token: str) -> int:
    for match in TOKEN.finditer(text):
        if match.group(0).translate(EASTERN_DIGITS) == token:
            return match.start()
    return -1
