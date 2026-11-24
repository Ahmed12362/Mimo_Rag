package com.mimocodes.mimorag.ingestion.application;

/** Character classification used for reading direction and quality checks. */
final class Scripts {

	enum Strength {

		/** Right-to-left letter (Arabic, Hebrew, Syriac, Thaana, NKo and their presentation forms). */
		RTL,
		/** Left-to-right letter. */
		LTR,
		/** Digit (European or Arabic-Indic): always laid out left to right. */
		NUMBER,
		/** Everything else (spaces, punctuation, symbols, combining marks). */
		NEUTRAL

	}

	private Scripts() {
	}

	static Strength strength(int codePoint) {
		if (Character.isDigit(codePoint)) {
			return Strength.NUMBER;
		}
		if (isRtlLetter(codePoint)) {
			return Strength.RTL;
		}
		if (Character.isLetter(codePoint)) {
			return Strength.LTR;
		}
		return Strength.NEUTRAL;
	}

	/** Strength of a piece of text: its first strong character (numbers count as strong). */
	static Strength strength(String text) {
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			Strength s = strength(cp);
			if (s != Strength.NEUTRAL) {
				return s;
			}
			i += Character.charCount(cp);
		}
		return Strength.NEUTRAL;
	}

	static boolean isRtlLetter(int cp) {
		if (!Character.isLetter(cp)) {
			return false;
		}
		Character.UnicodeScript script = Character.UnicodeScript.of(cp);
		return script == Character.UnicodeScript.ARABIC || script == Character.UnicodeScript.HEBREW
				|| script == Character.UnicodeScript.SYRIAC || script == Character.UnicodeScript.THAANA
				|| script == Character.UnicodeScript.NKO;
	}

	static boolean isArabicLetter(int cp) {
		return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.ARABIC;
	}

	static boolean isLatinLetter(int cp) {
		return Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.LATIN;
	}

	/** Combining marks (e.g. Arabic harakat, shadda); they belong to a base letter. */
	static boolean isMark(int cp) {
		int type = Character.getType(cp);
		return type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK
				|| type == Character.COMBINING_SPACING_MARK;
	}

	/** Text made only of combining marks (a mark glyph). */
	static boolean isAllMarks(String text) {
		if (text.isEmpty()) {
			return false;
		}
		return text.codePoints().allMatch(Scripts::isMark);
	}

	/** Arabic presentation forms (positional glyph codes instead of letters: a sign of a poor text layer). */
	static boolean isPresentationForm(int cp) {
		return (cp >= 0xFB50 && cp <= 0xFDFF) || (cp >= 0xFE70 && cp <= 0xFEFF && cp != 0xFEFF);
	}

	/** Explicit bidi embeddings, overrides and isolates (can make text display differently than stored). */
	static boolean isBidiControl(int cp) {
		return (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069);
	}

	/** Zero-width and invisible formatting characters that can hide or split words. */
	static boolean isZeroWidth(int cp) {
		return cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD;
	}

}
