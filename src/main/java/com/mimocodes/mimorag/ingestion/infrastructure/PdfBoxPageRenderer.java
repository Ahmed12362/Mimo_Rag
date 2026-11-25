package com.mimocodes.mimorag.ingestion.infrastructure;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Map;

import javax.imageio.ImageIO;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.OcrProperties;
import com.mimocodes.mimorag.ingestion.application.PageRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.PageDrawer;
import org.apache.pdfbox.rendering.PageDrawerParameters;

import org.springframework.stereotype.Component;

/**
 * Renders pages for OCR with PDFBox: RGB at the configured resolution (300 DPI), lowered when a page would exceed
 * the pixel cap. One page in memory at a time; resource cache off, temp-file stream cache (as in extraction).
 * Coordinates follow the displayed (rotated) page, like the 2c glyph and image positions.
 * <p>
 * Images are always scaled smoothly and bilinearly ({@link SmoothImageRenderer}): scans are typically 150–200 DPI
 * images enlarged to 300 DPI. PDFBox's default nearest-neighbour enlargement lowered OCR accuracy on noisy scans, and
 * bicubic on low-resolution scans; bilinear matched the 2a baseline on every profile (slice 2d gate, measured).
 */
@Component
class PdfBoxPageRenderer implements PageRenderer {

	private final OcrProperties properties;

	PdfBoxPageRenderer(OcrProperties properties) {
		this.properties = properties;
	}

	@Override
	public RenderSession open(Path pdf) {
		try {
			PDDocument document = Loader.loadPDF(new RandomAccessReadBufferedFile(pdf.toFile()), "", null, null,
					IOUtils.createTempFileOnlyStreamCache());
			document.setResourceCache(null);
			return new Session(document);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private final class Session implements RenderSession {

		private final PDDocument document;

		private final PDFRenderer renderer;

		Session(PDDocument document) {
			this.document = document;
			this.renderer = new SmoothImageRenderer(document);
		}

		@Override
		public RenderedImage page(int pageNumber) {
			int dpi = dpiFor(pageNumber);
			BufferedImage image = render(pageNumber, dpi);
			return encode(image, dpi, 0, 0, dpi < properties.renderDpi());
		}

		@Override
		public RenderedImage region(int pageNumber, Region region, double marginPoints) {
			int dpi = dpiFor(pageNumber);
			double scale = dpi / 72.0;
			BufferedImage page = render(pageNumber, dpi);
			int x0 = clamp((int) Math.floor((region.x() - marginPoints) * scale), page.getWidth());
			int y0 = clamp((int) Math.floor((region.y() - marginPoints) * scale), page.getHeight());
			int x1 = clamp((int) Math.ceil((region.x() + region.width() + marginPoints) * scale), page.getWidth());
			int y1 = clamp((int) Math.ceil((region.y() + region.height() + marginPoints) * scale), page.getHeight());
			BufferedImage crop = page.getSubimage(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0));
			return encode(crop, dpi, x0 / scale, y0 / scale, dpi < properties.renderDpi());
		}

		/** The configured DPI, lowered so the page stays under the pixel cap. */
		private int dpiFor(int pageNumber) {
			PDPage page = document.getPage(pageNumber - 1);
			double widthPoints = page.getCropBox().getWidth();
			double heightPoints = page.getCropBox().getHeight();
			double pixels = (widthPoints / 72 * properties.renderDpi()) * (heightPoints / 72 * properties.renderDpi());
			if (pixels <= properties.maxRenderPixels()) {
				return properties.renderDpi();
			}
			return (int) Math.max(72, Math.floor(properties.renderDpi()
					* Math.sqrt(properties.maxRenderPixels() / pixels)));
		}

		private BufferedImage render(int pageNumber, int dpi) {
			try {
				return renderer.renderImageWithDPI(pageNumber - 1, dpi, ImageType.RGB);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		private RenderedImage encode(BufferedImage image, int dpi, double offsetX, double offsetY, boolean reduced) {
			ByteArrayOutputStream png = new ByteArrayOutputStream();
			try {
				ImageIO.write(image, "png", png);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return new RenderedImage(png.toByteArray(), image.getWidth(), image.getHeight(), dpi, dpi / 72.0, offsetX,
					offsetY, reduced);
		}

		@Override
		public void close() {
			try {
				document.close();
			}
			catch (IOException ignored) {
				// Nothing more to release.
			}
		}

	}

	/**
	 * PDFBox enlarges images whose {@code /Interpolate} flag is false with nearest-neighbour sampling (blocky pixels);
	 * scanners rarely set the flag. Marking each image as interpolated, only in the in-memory document that is never
	 * saved, makes PDFBox use the renderer's interpolation hint (bilinear) instead.
	 */
	static final class SmoothImageRenderer extends PDFRenderer {

		/** Bilinear image scaling; antialiasing and quality rendering as in PDFBox's defaults. */
		private static final RenderingHints HINTS = new RenderingHints(Map.of(RenderingHints.KEY_INTERPOLATION,
				RenderingHints.VALUE_INTERPOLATION_BILINEAR, RenderingHints.KEY_RENDERING,
				RenderingHints.VALUE_RENDER_QUALITY, RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON));

		SmoothImageRenderer(PDDocument document) {
			super(document);
			setRenderingHints(HINTS);
		}

		@Override
		protected PageDrawer createPageDrawer(PageDrawerParameters parameters) throws IOException {
			return new PageDrawer(parameters) {

				@Override
				public void drawImage(PDImage image) throws IOException {
					if (image instanceof PDImageXObject xobject && !image.isStencil()) {
						xobject.getCOSObject().setBoolean(COSName.INTERPOLATE, true);
					}
					super.drawImage(image);
				}

			};
		}

	}

	private static int clamp(int value, int limit) {
		return Math.max(0, Math.min(limit, value));
	}

}
