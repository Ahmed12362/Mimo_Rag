package com.mimocodes.mimorag.ingestion.domain;

/**
 * Processing state of one uploaded file (see docs/workflows/document-review.md). Review states follow in M3.
 */
public enum DocumentVersionStatus {

	/** Stored; the processing job is queued. */
	UPLOADED,
	/** A worker is checking the PDF structure. */
	VALIDATING,
	/** Structurally valid PDF with a known page count. */
	VALIDATED,
	/** Rejected or failed during validation; {@code failureCode} says why. */
	VALIDATION_FAILED,
	/** Native text is being extracted page by page. */
	EXTRACTING,
	/** Every page has usable native text (or is empty): nothing to OCR. */
	EXTRACTED,
	/** Native extraction is done and some pages (or image regions) need OCR (slice 2d). */
	AWAITING_OCR,
	/** Extraction failed for the whole document; {@code failureCode} says why. */
	EXTRACTION_FAILED

}
