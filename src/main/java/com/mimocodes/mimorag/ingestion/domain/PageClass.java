package com.mimocodes.mimorag.ingestion.domain;

/** What a page's native text layer can be used for. */
public enum PageClass {

	/** Usable native text; no OCR needed. */
	NATIVE_TEXT,
	/**
	 * OCR needed: the page is a scan or drawn as vector shapes, or its native text is unreliable (kept internally, never
	 * offered as the page result).
	 */
	OCR_REQUIRED,
	/** Usable native text plus image regions that may contain more text (OCR only those regions). */
	MIXED,
	/** Nothing to read: no text, no images, no significant drawing. */
	EMPTY,
	/** The page could not be read (timeout or parser error); other pages are unaffected. */
	FAILED

}
