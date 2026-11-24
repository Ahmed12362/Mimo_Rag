package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import com.mimocodes.mimorag.ingestion.application.PdfInspector;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/**
 * Structural PDF check with Apache PDFBox. The file is read from disk with a temp-file-only stream cache, so large
 * PDFs do not fill the heap; parsing runs on a separate thread with a time limit. Only the page tree is touched:
 * no rendering, no fonts, no embedded files, and PDFBox never executes JavaScript or fetches external resources.
 */
@Component
class PdfBoxInspector implements PdfInspector, DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PdfBoxInspector.class);

	private final ExecutorService executor = Executors.newCachedThreadPool(daemonThreads());

	@Override
	public int countPages(Path pdf, int maxPages, Duration timeout) {
		Future<Integer> pages = executor.submit(() -> inspect(pdf, maxPages));
		try {
			return pages.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException ex) {
			pages.cancel(true);
			throw new PdfRejectedException(PdfRejectedException.TIMEOUT);
		}
		catch (InterruptedException ex) {
			pages.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while inspecting a PDF", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof PdfRejectedException rejected) {
				throw rejected;
			}
			log.info("PDF rejected as corrupt: {}", ex.getCause().getClass().getSimpleName());
			throw new PdfRejectedException(PdfRejectedException.CORRUPT);
		}
	}

	private static int inspect(Path pdf, int maxPages) {
		try (RandomAccessReadBufferedFile file = new RandomAccessReadBufferedFile(pdf.toFile());
				PDDocument document = Loader.loadPDF(file, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
			if (document.isEncrypted()) {
				throw new PdfRejectedException(PdfRejectedException.ENCRYPTED);
			}
			int pages = document.getNumberOfPages();
			if (pages < 1) {
				throw new PdfRejectedException(PdfRejectedException.EMPTY);
			}
			if (pages > maxPages) {
				throw new PdfRejectedException(PdfRejectedException.TOO_MANY_PAGES);
			}
			return pages;
		}
		catch (InvalidPasswordException ex) {
			throw new PdfRejectedException(PdfRejectedException.ENCRYPTED);
		}
		catch (IOException ex) {
			throw new PdfRejectedException(PdfRejectedException.CORRUPT);
		}
	}

	@Override
	public void destroy() {
		executor.shutdownNow();
	}

	private static java.util.concurrent.ThreadFactory daemonThreads() {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, "pdf-inspect-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

}
