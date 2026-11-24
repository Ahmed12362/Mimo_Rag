package com.mimocodes.mimorag.ingestion.domain;

/**
 * Processing state of one uploaded file. Slice 2b covers upload and structural validation; later slices add
 * extraction and review states (see docs/workflows/document-review.md).
 */
public enum DocumentVersionStatus {

	/** Stored; the extraction job is queued. */
	UPLOADED,
	/** A worker is checking the PDF structure. */
	VALIDATING,
	/** Structurally valid PDF with a known page count. */
	VALIDATED,
	/** Rejected or failed; {@code failureCode} says why. */
	VALIDATION_FAILED

}
