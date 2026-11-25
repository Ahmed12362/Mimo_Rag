package com.mimocodes.mimorag.ingestion.application;

import java.nio.file.Path;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;

/** Renders PDF pages to PNG for OCR (PDF library confined behind this interface; the OCR service never sees PDFs). */
public interface PageRenderer {

	RenderSession open(Path pdf);

	interface RenderSession extends AutoCloseable {

		/** The whole page at the configured resolution (lowered if it would exceed the pixel cap). */
		RenderedImage page(int pageNumber);

		/** One region of the page (page points, top-left origin), expanded by {@code marginPoints}. */
		RenderedImage region(int pageNumber, Region region, double marginPoints);

		@Override
		void close();

	}

	/**
	 * A rendered image and how to map its pixels back to page points: {@code pageX = offsetX + pixelX / scale}.
	 *
	 * @param scale pixels per point (dpi / 72)
	 * @param reducedDpi the page was too large for the configured resolution and was rendered at a lower one
	 */
	record RenderedImage(byte[] png, int width, int height, int dpi, double scale, double offsetX, double offsetY,
			boolean reducedDpi) {
	}

}
