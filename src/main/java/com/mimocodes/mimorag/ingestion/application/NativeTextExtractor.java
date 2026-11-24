package com.mimocodes.mimorag.ingestion.application;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Reads the text layer and drawing facts of a PDF page by page (PDF library confined behind this interface). One
 * open document is processed page by page, so memory stays bounded however many pages the file has.
 */
public interface NativeTextExtractor {

	/** Opens an untrusted PDF for page-by-page reading; the caller closes it. */
	NativePdf open(Path pdf);

	/** Engine name and version, recorded with every extraction run. */
	String engine();

	String engineVersion();

	interface NativePdf extends AutoCloseable {

		int pageCount();

		/**
		 * @param pageNumber 1-based
		 * @throws PageTimeoutException if the page takes longer than {@code timeout} (the document stays usable)
		 */
		NativePage page(int pageNumber, Duration timeout);

		@Override
		void close();

	}

	/** One page took too long; other pages can still be read. */
	class PageTimeoutException extends RuntimeException {

		public PageTimeoutException(int pageNumber) {
			super("Page " + pageNumber + " timed out");
		}

	}

	/** One page could not be read (the rest of the document may still be fine). */
	class PageReadException extends RuntimeException {

		public PageReadException(int pageNumber, Throwable cause) {
			super("Page " + pageNumber + " could not be read", cause);
		}

	}

}
