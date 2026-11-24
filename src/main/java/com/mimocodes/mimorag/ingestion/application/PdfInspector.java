package com.mimocodes.mimorag.ingestion.application;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Structural check of an untrusted PDF, run only by background workers (never on request threads). The PDF library
 * stays behind this interface (architecture rule: PDFBox only in {@code ingestion.infrastructure}).
 */
public interface PdfInspector {

	/**
	 * @return the page count of a readable, unencrypted PDF with 1 to {@code maxPages} pages
	 * @throws PdfRejectedException with a stable code when the file must be rejected
	 */
	int countPages(Path pdf, int maxPages, Duration timeout);

	/** The PDF cannot be accepted. Codes are stable and shown to API callers. */
	class PdfRejectedException extends RuntimeException {

		public static final String ENCRYPTED = "PDF_ENCRYPTED";

		public static final String CORRUPT = "PDF_CORRUPT";

		public static final String EMPTY = "PDF_EMPTY";

		public static final String TOO_MANY_PAGES = "PDF_TOO_MANY_PAGES";

		public static final String TIMEOUT = "PDF_VALIDATION_TIMEOUT";

		private final String code;

		public PdfRejectedException(String code) {
			super(code);
			this.code = code;
		}

		public String getCode() {
			return code;
		}

	}

}
