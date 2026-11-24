package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mimocodes.mimorag.ingestion.application.NativePage;
import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import com.mimocodes.mimorag.ingestion.application.NativeTextExtractor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import org.springframework.stereotype.Component;

/**
 * PDFBox implementation of {@link NativeTextExtractor}.
 * <ul>
 * <li>The file is read from disk with a temp-file-only stream cache, and PDFBox's resource cache is switched off:
 * by default it keeps fonts and images of every visited page, which grows without bound on large documents.</li>
 * <li>Each page runs on a dedicated thread with a time limit. After a timeout the document is reopened, so a stuck
 * parse cannot affect the following pages.</li>
 * </ul>
 */
@Component
class PdfBoxNativeTextExtractor implements NativeTextExtractor {

	@Override
	public NativePdf open(Path pdf) {
		return new PdfBoxPdf(pdf);
	}

	@Override
	public String engine() {
		return "pdfbox+linebuilder";
	}

	@Override
	public String engineVersion() {
		String pdfbox = org.apache.pdfbox.util.Version.getVersion();
		return (pdfbox == null ? "unknown" : pdfbox) + "/1";
	}

	private static final class PdfBoxPdf implements NativePdf {

		private final Path file;

		private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "pdf-extract");
			thread.setDaemon(true);
			return thread;
		});

		private PDDocument document;

		private int pageCount;

		PdfBoxPdf(Path file) {
			this.file = file;
			this.document = load();
			this.pageCount = document.getNumberOfPages();
		}

		@Override
		public int pageCount() {
			return pageCount;
		}

		@Override
		public NativePage page(int pageNumber, Duration timeout) {
			if (pageNumber < 1 || pageNumber > pageCount) {
				throw new IllegalArgumentException("No page " + pageNumber);
			}
			PDDocument current = document;
			Future<NativePage> result = worker.submit(() -> read(current, pageNumber));
			try {
				return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			}
			catch (TimeoutException ex) {
				result.cancel(true);
				reopen();
				throw new PageTimeoutException(pageNumber);
			}
			catch (InterruptedException ex) {
				result.cancel(true);
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while reading a PDF page", ex);
			}
			catch (ExecutionException ex) {
				throw new PageReadException(pageNumber, ex.getCause());
			}
		}

		private static NativePage read(PDDocument document, int pageNumber) throws IOException {
			PDPage page = document.getPage(pageNumber - 1);
			GlyphCollector glyphs = new GlyphCollector();
			List<Glyph> collected = glyphs.collect(document, pageNumber);
			ImageRegionCollector drawing = new ImageRegionCollector(page);
			drawing.processPage(page);
			PDRectangle crop = page.getCropBox();
			int rotation = ((page.getRotation() % 360) + 360) % 360;
			boolean sideways = rotation == 90 || rotation == 270;
			return new NativePage(pageNumber, sideways ? crop.getHeight() : crop.getWidth(),
					sideways ? crop.getWidth() : crop.getHeight(), rotation, collected, drawing.images(),
					drawing.filledPaths(), glyphs.truncated());
		}

		private PDDocument load() {
			try {
				PDDocument loaded = Loader.loadPDF(new RandomAccessReadBufferedFile(file.toFile()), "", null, null,
						IOUtils.createTempFileOnlyStreamCache());
				loaded.setResourceCache(null);
				return loaded;
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		private void reopen() {
			closeQuietly(document);
			document = load();
		}

		@Override
		public void close() {
			worker.shutdownNow();
			closeQuietly(document);
		}

		private static void closeQuietly(PDDocument document) {
			try {
				document.close();
			}
			catch (IOException | RuntimeException ignored) {
				// Closing a document that a cancelled parse may still touch; nothing more to do.
			}
		}

	}

}
