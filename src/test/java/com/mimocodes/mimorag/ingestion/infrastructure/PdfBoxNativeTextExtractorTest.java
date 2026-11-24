package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.mimocodes.mimorag.ingestion.application.NativePage;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.NativeTextExtractor.NativePdf;
import com.mimocodes.mimorag.ingestion.application.PageAnalysis;
import com.mimocodes.mimorag.ingestion.application.PageClassifier;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The PDFBox adapter on generated PDFs: glyph flags, image regions, vector shapes, rotation. */
class PdfBoxNativeTextExtractorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final PdfBoxNativeTextExtractor extractor = new PdfBoxNativeTextExtractor();

	@TempDir
	Path dir;

	@Test
	void hiddenTextIsDetectedAndKeptInTheText() throws IOException {
		PageAnalysis page = analyse(TestPdfs.hiddenText("hidden"));

		assertThat(page.warnings()).contains(PageWarning.INVISIBLE_TEXT, PageWarning.WHITE_TEXT,
				PageWarning.TINY_TEXT, PageWarning.OFF_PAGE_TEXT);
		assertThat(page.nativeText()).contains("Visible policy text", "Invisible instruction",
				"White instruction", "Tiny instruction", "Off page instruction");
		assertThat(page.pageClass()).isEqualTo(PageClass.NATIVE_TEXT);
	}

	@Test
	void imageOnlyPageNeedsOcr() throws IOException {
		PageAnalysis page = analyse(TestPdfs.imageOnly("scan"));

		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
		assertThat(page.imageCoverage()).isGreaterThan(0.99);
		assertThat(page.resultText()).isNull();
	}

	@Test
	void textWithLargeImageIsMixedWithTheImageRegionInPageCoordinates() throws IOException {
		PageAnalysis page = analyse(TestPdfs.textWithLargeImage("mixed"));

		assertThat(page.pageClass()).isEqualTo(PageClass.MIXED);
		assertThat(page.resultText()).contains("Employees must read this notice carefully");
		// Drawn at (72, 200) size 400x300 in PDF space (bottom-left origin) on a 792 pt high page.
		Region image = page.images().get(0);
		assertThat(image.x()).isCloseTo(72, within(0.5));
		assertThat(image.y()).isCloseTo(792 - 200 - 300, within(0.5));
		assertThat(image.width()).isCloseTo(400, within(0.5));
		assertThat(image.height()).isCloseTo(300, within(0.5));
	}

	@Test
	void vectorOnlyPageIsFlagged() throws IOException {
		PageAnalysis page = analyse(TestPdfs.vectorOnly("outlines"));

		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
		assertThat(page.warnings()).contains(PageWarning.VECTOR_ONLY_CONTENT);
	}

	@Test
	void blankPageIsEmpty() throws IOException {
		assertThat(analyse(blankPage()).pageClass()).isEqualTo(PageClass.EMPTY);
	}

	@Test
	void rotatedPageTextIsReadInReadingOrder() throws IOException {
		PageAnalysis page = analyse(TestPdfs.rotated("turned"));

		assertThat(page.resultText()).isEqualTo("Rotated page text turned");
		assertThat(page.rotation()).isEqualTo(90);
	}

	@Test
	void documentIsReadPageByPage() throws IOException {
		Path file = write(TestPdfs.textOnEveryPage(5, "multi"));
		try (NativePdf pdf = extractor.open(file)) {
			assertThat(pdf.pageCount()).isEqualTo(5);
			NativePage third = pdf.page(3, TIMEOUT);
			assertThat(PageClassifier.classify(third).resultText()).startsWith("Page 3 of the employee handbook");
		}
	}

	private PageAnalysis analyse(byte[] pdf) throws IOException {
		try (NativePdf document = extractor.open(write(pdf))) {
			return PageClassifier.classify(document.page(1, TIMEOUT));
		}
	}

	private static byte[] blankPage() {
		try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
			document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			document.save(out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new java.io.UncheckedIOException(ex);
		}
	}

	private Path write(byte[] content) throws IOException {
		Path file = Files.createTempFile(dir, "page-", ".pdf");
		Files.write(file, content);
		return file;
	}

}
