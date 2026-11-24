package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorN;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingColorSpace;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceCMYKColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceGrayColor;
import org.apache.pdfbox.contentstream.operator.color.SetNonStrokingDeviceRGBColor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/**
 * Collects positioned glyphs of one page with the facts needed to detect hidden text: render mode, fill colour and
 * whether the glyph lies on the visible page. PDFBox's own text assembly is not used (see {@code LineBuilder}).
 */
final class GlyphCollector extends PDFTextStripper {

	/** Above this many glyphs on one page the rest is ignored and the page is marked truncated. */
	static final int MAX_GLYPHS_PER_PAGE = 300_000;

	/** RGB components above this (0-255) count as white. */
	private static final int WHITE_THRESHOLD = 242;

	private final List<Glyph> glyphs = new ArrayList<>();

	private boolean truncated;

	private int streamIndex;

	GlyphCollector() {
		setSortByPosition(false);
		setSuppressDuplicateOverlappingText(false);
		// PDFTextStripper only tracks text operators; without these the fill colour stays black and white text
		// (a classic way to hide instructions) would go unnoticed.
		addOperator(new SetNonStrokingColorSpace(this));
		addOperator(new SetNonStrokingColor(this));
		addOperator(new SetNonStrokingColorN(this));
		addOperator(new SetNonStrokingDeviceGrayColor(this));
		addOperator(new SetNonStrokingDeviceRGBColor(this));
		addOperator(new SetNonStrokingDeviceCMYKColor(this));
	}

	List<Glyph> collect(PDDocument document, int pageNumber) throws IOException {
		glyphs.clear();
		truncated = false;
		streamIndex = 0;
		setStartPage(pageNumber);
		setEndPage(pageNumber);
		writeText(document, new StringWriter());
		return List.copyOf(glyphs);
	}

	boolean truncated() {
		return truncated;
	}

	@Override
	protected void processTextPosition(TextPosition text) {
		if (glyphs.size() >= MAX_GLYPHS_PER_PAGE) {
			truncated = true;
			return;
		}
		RenderingMode mode = getGraphicsState().getTextState().getRenderingMode();
		boolean invisible = mode == RenderingMode.NEITHER || mode == RenderingMode.NEITHER_CLIP;
		double x = text.getXDirAdj();
		double y = text.getYDirAdj();
		boolean offPage = x + text.getWidthDirAdj() < 0 || y < 0 || x > text.getPageWidth()
				|| y - text.getHeightDir() > text.getPageHeight();
		glyphs.add(new Glyph(text.getUnicode(), x, y, text.getWidthDirAdj(), text.getFontSizeInPt(), streamIndex++,
				invisible, isWhite(getGraphicsState().getNonStrokingColor()), offPage));
	}

	private static boolean isWhite(PDColor color) {
		if (color == null) {
			return false;
		}
		try {
			int rgb = color.toRGB();
			return ((rgb >> 16) & 0xFF) > WHITE_THRESHOLD && ((rgb >> 8) & 0xFF) > WHITE_THRESHOLD
					&& (rgb & 0xFF) > WHITE_THRESHOLD;
		}
		catch (IOException | RuntimeException unsupportedColourSpace) {
			return false;
		}
	}

}
