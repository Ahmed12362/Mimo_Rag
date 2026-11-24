package com.mimocodes.mimorag.ingestion.application;

import java.util.ArrayList;
import java.util.List;

/**
 * Signals that tell whether a native text layer can be trusted. Computed from the extracted text and glyph counts
 * only; nothing is changed.
 *
 * @param skippedGlyphs glyphs with a width but no Unicode mapping
 * @param reversedArabic Arabic words look stored backwards (many words end with "لا", few start with "ال")
 */
public record TextQuality(int glyphs, int skippedGlyphs, int nonSpaceChars, int letters, int digits,
		int arabicLetters, int latinLetters, int otherLetters, int privateUse, int replacement, int presentationForms,
		int bidiControls, int zeroWidth, int arabicWords, int wordsStartingWithAl, int wordsEndingWithLa,
		boolean reversedArabic) {

	/** Below this many non-space characters a page has too little text to judge or use on its own. */
	static final int MIN_TEXT_CHARS = 20;

	static final int MIN_CHARS_FOR_BROKEN_CHECK = 5;

	static final double MAX_BROKEN_CHAR_RATIO = 0.05;

	static final double MAX_SKIPPED_GLYPH_RATIO = 0.10;

	static final double MAX_PRESENTATION_FORM_RATIO = 0.30;

	static final double MIN_LETTER_OR_DIGIT_RATIO = 0.30;

	static TextQuality of(String text, int glyphs, int skippedGlyphs) {
		int nonSpace = 0, letters = 0, digits = 0, arabic = 0, latin = 0, other = 0, privateUse = 0, replacement = 0,
				presentation = 0, bidi = 0, zeroWidth = 0;
		for (int i = 0; i < text.length();) {
			int cp = text.codePointAt(i);
			i += Character.charCount(cp);
			if (Scripts.isBidiControl(cp)) {
				bidi++;
			}
			if (Scripts.isZeroWidth(cp)) {
				zeroWidth++;
			}
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				continue;
			}
			nonSpace++;
			if (Character.getType(cp) == Character.PRIVATE_USE) {
				privateUse++;
			}
			if (cp == 0xFFFD) {
				replacement++;
			}
			if (Scripts.isPresentationForm(cp)) {
				presentation++;
			}
			if (Character.isDigit(cp)) {
				digits++;
			}
			else if (Character.isLetter(cp)) {
				letters++;
				if (Scripts.isArabicLetter(cp)) {
					arabic++;
				}
				else if (Scripts.isLatinLetter(cp)) {
					latin++;
				}
				else {
					other++;
				}
			}
		}
		int arabicWords = 0, startsAl = 0, endsLa = 0;
		for (String word : text.split("[\\s\\p{Punct}]+")) {
			if (word.codePoints().anyMatch(Scripts::isArabicLetter)) {
				arabicWords++;
				startsAl += word.startsWith("ال") && word.length() > 2 ? 1 : 0;
				endsLa += word.endsWith("لا") && word.length() > 2 ? 1 : 0;
			}
		}
		boolean reversed = arabicWords >= 10 && startsAl < 0.05 * arabicWords && endsLa > 0.15 * arabicWords;
		return new TextQuality(glyphs, skippedGlyphs, nonSpace, letters, digits, arabic, latin, other, privateUse,
				replacement, presentation, bidi, zeroWidth, arabicWords, startsAl, endsLa, reversed);
	}

	/** Reasons the native text cannot be trusted; empty when it can. */
	List<String> unreliableReasons() {
		List<String> reasons = new ArrayList<>();
		// Broken characters are unambiguous, so even a short page (a title page) is judged on them.
		if (nonSpaceChars >= MIN_CHARS_FOR_BROKEN_CHECK
				&& privateUse + replacement > MAX_BROKEN_CHAR_RATIO * nonSpaceChars) {
			reasons.add("BROKEN_CHARACTERS");
		}
		if (glyphs >= MIN_TEXT_CHARS && skippedGlyphs > MAX_SKIPPED_GLYPH_RATIO * glyphs) {
			reasons.add("UNMAPPED_GLYPHS");
		}
		if (arabicLetters + presentationForms >= 10
				&& presentationForms > MAX_PRESENTATION_FORM_RATIO * (arabicLetters + presentationForms)) {
			reasons.add("PRESENTATION_FORMS");
		}
		if (reversedArabic) {
			reasons.add("REVERSED_ARABIC");
		}
		if (nonSpaceChars >= MIN_TEXT_CHARS && letters + digits < MIN_LETTER_OR_DIGIT_RATIO * nonSpaceChars) {
			reasons.add("FEW_LETTERS");
		}
		return reasons;
	}

}
