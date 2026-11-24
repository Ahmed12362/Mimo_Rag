package com.mimocodes.mimorag.ingestion.application;

import java.util.List;

/**
 * What the PDF library found on one page, before any interpretation. Coordinates are PDF points with the origin at
 * the top-left of the (rotation-adjusted) page, as a reader sees it.
 *
 * @param glyphs every text glyph in content-stream order (capped; see {@code truncated})
 * @param images placement boxes of images drawn on the page
 * @param filledPaths number of filled vector paths (many and no text suggests outlined text)
 * @param truncated whether the glyph cap was reached
 */
public record NativePage(int pageNumber, double width, double height, int rotation, List<Glyph> glyphs,
		List<Region> images, int filledPaths, boolean truncated) {

	public NativePage {
		glyphs = List.copyOf(glyphs);
		images = List.copyOf(images);
	}

	/**
	 * One positioned text glyph.
	 *
	 * @param text the Unicode text the PDF maps the glyph to (may be several code points, e.g. a ligature, or empty)
	 * @param x left edge, {@code y} baseline, {@code width} advance, all in points
	 * @param streamIndex position in the content stream; marks are attached to base letters by this order
	 * @param invisible drawn with text render mode 3 (neither filled nor stroked)
	 * @param white fill colour is (near) white
	 * @param offPage the glyph lies outside the visible page area
	 */
	public record Glyph(String text, double x, double y, double width, double fontSize, int streamIndex,
			boolean invisible, boolean white, boolean offPage) {

		public Glyph {
			text = text == null ? "" : text;
		}

	}

	/** A rectangle in page points (top-left origin). */
	public record Region(double x, double y, double width, double height) {

		public double area() {
			return Math.max(0, width) * Math.max(0, height);
		}

	}

}
