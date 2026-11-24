package com.mimocodes.mimorag.ingestion.application;

import java.util.ArrayList;
import java.util.List;

import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageClassifierTest {

	private static final double WIDTH = 612;

	private static final double HEIGHT = 792;

	private static final String ENGLISH = "Employees are entitled to paid annual leave after six months of service.";

	private int stream;

	@Test
	void pageWithEnoughTextIsNativeTextAndTrusted() {
		PageAnalysis page = classify(line(ENGLISH, 72, 100), List.of(), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.NATIVE_TEXT);
		assertThat(page.nativeTextTrusted()).isTrue();
		assertThat(page.resultText()).isEqualTo(ENGLISH);
		assertThat(page.scripts()).containsExactly("LATIN");
		assertThat(page.warnings()).isEmpty();
	}

	@Test
	void imageOnlyPageNeedsOcr() {
		PageAnalysis page = classify(List.of(), List.of(new Region(0, 0, WIDTH, HEIGHT)), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
		assertThat(page.resultText()).isNull();
		assertThat(page.imageCoverage()).isEqualTo(1.0);
	}

	@Test
	void textPlusLargeImageIsMixedAndKeepsBothSeparately() {
		Region photo = new Region(72, 300, 400, 300);
		PageAnalysis page = classify(line(ENGLISH, 72, 100), List.of(photo), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.MIXED);
		assertThat(page.resultText()).isEqualTo(ENGLISH);
		assertThat(page.images()).containsExactly(photo);
		assertThat(page.lines()).hasSize(1);
		assertThat(page.lines().get(0).end()).isEqualTo(ENGLISH.length());
	}

	@Test
	void smallLogoDoesNotMakeAPageMixed() {
		PageAnalysis page = classify(line(ENGLISH, 72, 100), List.of(new Region(500, 20, 60, 40)), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.NATIVE_TEXT);
	}

	@Test
	void pageDrawnOnlyAsVectorShapesNeedsOcrAndIsFlagged() {
		PageAnalysis page = classify(List.of(), List.of(), 120);

		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
		assertThat(page.warnings()).contains(PageWarning.VECTOR_ONLY_CONTENT);
	}

	@Test
	void blankPageIsEmpty() {
		PageAnalysis page = classify(List.of(), List.of(), 3);

		assertThat(page.pageClass()).isEqualTo(PageClass.EMPTY);
		assertThat(page.resultText()).isNull();
	}

	@Test
	void shortTextWithoutImagesIsStillNativeText() {
		PageAnalysis page = classify(line("Annex A", 72, 100), List.of(), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.NATIVE_TEXT);
		assertThat(page.resultText()).isEqualTo("Annex A");
	}

	@Test
	void brokenCharacterMappingIsKeptButNotTrusted() {
		String broken = "  ";
		PageAnalysis page = classify(line(broken + " abc", 72, 100), List.of(), 0);

		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
		assertThat(page.warnings()).contains(PageWarning.NATIVE_TEXT_UNRELIABLE);
		assertThat(page.nativeTextTrusted()).isFalse();
		assertThat(page.resultText()).as("never offered as the page result").isNull();
		assertThat(page.nativeText()).as("but kept internally").contains("");
		assertThat(page.unreliableReasons()).contains("BROKEN_CHARACTERS");
	}

	@Test
	void arabicPresentationFormsAreNotTrusted() {
		// Positional glyph codes (U+FExx) instead of letters: a text layer that cannot be searched or embedded.
		String presentation = "ﻟﺎﻣﻮﻟﺎﻣ ﺳﺎﺎﻣﻮﻣﻣ";
		PageAnalysis page = classify(line(presentation, 72, 100), List.of(), 0);

		assertThat(page.unreliableReasons()).contains("PRESENTATION_FORMS");
		assertThat(page.pageClass()).isEqualTo(PageClass.OCR_REQUIRED);
	}

	@Test
	void hiddenTextIsFlaggedAndKept() {
		List<Glyph> glyphs = new ArrayList<>(line(ENGLISH, 72, 100));
		glyphs.add(new Glyph("I", 72, 200, 3, 12, stream++, true, false, false));
		glyphs.add(new Glyph("W", 80, 200, 3, 12, stream++, false, true, false));
		glyphs.add(new Glyph("T", 90, 200, 0.2, 0.5, stream++, false, false, false));
		glyphs.add(new Glyph("O", 700, 200, 3, 12, stream++, false, false, true));

		PageAnalysis page = classify(glyphs, List.of(), 0);

		assertThat(page.warnings()).contains(PageWarning.INVISIBLE_TEXT, PageWarning.WHITE_TEXT,
				PageWarning.TINY_TEXT, PageWarning.OFF_PAGE_TEXT);
		assertThat(page.nativeText()).contains("I", "W", "T", "O");
		assertThat(page.pageClass()).as("hidden text is a warning, not a reason to distrust the page")
			.isEqualTo(PageClass.NATIVE_TEXT);
	}

	@Test
	void bidiControlAndZeroWidthCharactersAreFlaggedAndKept() {
		PageAnalysis page = classify(line(ENGLISH + " ‮fdp.exe and in​visible", 72, 100), List.of(), 0);

		assertThat(page.warnings()).contains(PageWarning.BIDI_CONTROL_CHARACTERS, PageWarning.ZERO_WIDTH_CHARACTERS);
		assertThat(page.resultText()).contains("‮", "​");
	}

	@Test
	void mixedDirectionLinesAreFlaggedForReview() {
		PageAnalysis page = classify(line("1500 ماعلل POL-2026 مقر ةسايسلا لودج يف ةدراولا", 72, 100), List.of(), 0);

		assertThat(page.warnings()).contains(PageWarning.MIXED_DIRECTION_LINE);
		assertThat(page.scripts()).containsExactly("ARABIC", "LATIN");
	}

	@Test
	void failedPageCarriesItsReason() {
		PageAnalysis page = PageClassifier.failed(7, PageWarning.PAGE_TIMEOUT, 30_000);

		assertThat(page.pageClass()).isEqualTo(PageClass.FAILED);
		assertThat(page.warnings()).containsExactly(PageWarning.PAGE_TIMEOUT);
		assertThat(page.resultText()).isNull();
	}

	@Test
	void reversedArabicIsDetected() {
		// "الموظف" stored backwards is "فظوملا": many words end in "لا", none start with "ال".
		String reversed = "فظوملا قحتسي ةزاجا ةيونس ىلع تاونسلا قحلملا ةمدخلا ةكرشلا رودصلا ريدملا ءاضقلا";
		TextQuality quality = TextQuality.of(reversed, reversed.length(), 0);

		assertThat(quality.reversedArabic()).isTrue();
		assertThat(TextQuality.of("الموظف يستحق الإجازة السنوية من الشركة بعد الخدمة في الإدارة والقسم والمدير",
				80, 0)
			.reversedArabic()).isFalse();
	}

	@Test
	void numericPagesAreNotMistakenForBrokenText() {
		String figures = "2026 2025 1500.00 230.50 1,200 4,750.25 99.9% 12/03/2026 15:30";
		assertThat(TextQuality.of(figures, figures.length(), 0).unreliableReasons()).isEmpty();
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private PageAnalysis classify(List<Glyph> glyphs, List<Region> images, int filledPaths) {
		return PageClassifier.classify(new NativePage(1, WIDTH, HEIGHT, 0, glyphs, images, filledPaths, false));
	}

	private List<Glyph> line(String visual, double x, double y) {
		List<Glyph> glyphs = new ArrayList<>();
		double position = x;
		for (int i = 0; i < visual.length();) {
			int cp = visual.codePointAt(i);
			glyphs.add(new Glyph(new String(Character.toChars(cp)), position, y, 6, 12, stream++, false, false,
					false));
			position += 6;
			i += Character.charCount(cp);
		}
		return glyphs;
	}

}
