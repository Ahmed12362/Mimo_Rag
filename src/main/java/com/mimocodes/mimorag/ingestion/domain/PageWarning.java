package com.mimocodes.mimorag.ingestion.domain;

/**
 * Things a reviewer should know about a page. Warnings never change or remove text: hidden or suspicious content is
 * kept exactly as found and only flagged (decision D: preserve, then flag).
 */
public enum PageWarning {

	/** Text drawn invisibly (render mode 3), a common way to hide instructions or keywords. */
	INVISIBLE_TEXT,
	/** Text drawn in (near) white. */
	WHITE_TEXT,
	/** Text smaller than 1 pt. */
	TINY_TEXT,
	/** Text outside the visible page area. */
	OFF_PAGE_TEXT,
	/** Bidi embedding, override or isolate characters (text may display differently than it is stored). */
	BIDI_CONTROL_CHARACTERS,
	/** Zero-width or soft-hyphen characters that can hide or split words. */
	ZERO_WIDTH_CHARACTERS,
	/** The native text layer looks broken (unmapped glyphs, private-use characters, presentation forms, reversed). */
	NATIVE_TEXT_UNRELIABLE,
	/** A line mixes right-to-left and left-to-right text; check the order of numbers, codes and Latin words. */
	MIXED_DIRECTION_LINE,
	/** No text, but many filled vector shapes: possibly text converted to outlines. */
	VECTOR_ONLY_CONTENT,
	/** The page has more text than is stored; the rest was cut off. */
	TEXT_TRUNCATED,
	/** Reading the page took too long. */
	PAGE_TIMEOUT,
	/** The page could not be parsed. */
	PAGE_READ_ERROR

}
