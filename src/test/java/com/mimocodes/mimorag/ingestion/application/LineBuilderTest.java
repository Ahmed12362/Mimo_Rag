package com.mimocodes.mimorag.ingestion.application;

import java.util.ArrayList;
import java.util.List;

import com.mimocodes.mimorag.ingestion.application.LineBuilder.Direction;
import com.mimocodes.mimorag.ingestion.application.LineBuilder.Result;
import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules of the line builder on hand-made glyphs. Glyphs are placed the way a shaper draws them: one glyph per
 * character, left to right on the page (visual order), 6 pt wide, 12 pt font.
 */
class LineBuilderTest {

	private static final double FONT = 12;

	private static final double ADVANCE = 6;

	private int stream;

	@Test
	void arabicLineDrawnVisuallyIsReturnedInLogicalOrder() {
		// Logical "مرحبا بكم" is drawn right to left, so its glyphs appear on the page as "مكب ابحرم".
		Result result = build(visualLine("مكب ابحرم", 400, 100));

		assertThat(result.text()).isEqualTo("مرحبا بكم");
		assertThat(result.lines().get(0).direction()).isEqualTo(Direction.RTL);
	}

	@Test
	void latinAndDigitRunsInsideArabicKeepTheirOrder() {
		// Logical "رقم POL-2026 للعام 1500" displayed right to left: Latin/digit runs stay left-to-right inside.
		Result result = build(visualLine("1500 ماعلل POL-2026 مقر", 300, 100));

		assertThat(result.text()).isEqualTo("رقم POL-2026 للعام 1500");
		assertThat(result.lines().get(0).mixedDirection()).isTrue();
	}

	@Test
	void spacesBetweenLatinWordsStayInsideTheLatinRun() {
		Result result = build(visualLine("ةميقب Grade C لودج", 300, 100));

		assertThat(result.text()).isEqualTo("جدول Grade C بقيمة");
	}

	@Test
	void trailingPunctuationOfAnArabicSentenceEndsUpAtTheEnd() {
		// In a right-to-left paragraph the final period is drawn at the far left.
		Result result = build(visualLine(".رهشلا", 300, 100));

		assertThat(result.text()).isEqualTo("الشهر.");
	}

	@Test
	void englishLinesStayLeftToRight() {
		Result result = build(visualLine("Annual leave is 21 days.", 72, 100));

		assertThat(result.text()).isEqualTo("Annual leave is 21 days.");
		assertThat(result.lines().get(0).direction()).isEqualTo(Direction.LTR);
	}

	@Test
	void arabicPhraseInsideAnEnglishLineIsReversedInPlace() {
		Result result = build(visualLine("Policy مقر 3 applies", 72, 100));

		assertThat(result.text()).isEqualTo("Policy رقم 3 applies");
	}

	@Test
	void linesAreOrderedTopToBottomAndSeparated() {
		List<Glyph> glyphs = new ArrayList<>(visualLine("ىلوأ", 400, 100));
		glyphs.addAll(visualLine("ةيناث", 400, 130));

		assertThat(build(glyphs).text()).isEqualTo("أولى\nثانية");
	}

	@Test
	void wideGapsBecomeCellBreaksAndRightToLeftCellsAreReadRightToLeft() {
		// A table row: three cells far apart on one baseline, right cell first in reading order.
		List<Glyph> glyphs = new ArrayList<>(visualLine("ةظحالم", 100, 100));
		glyphs.addAll(visualLine("ةدم", 250, 100));
		glyphs.addAll(visualLine("دنب", 400, 100));

		String text = build(glyphs).text();
		assertThat(text).as(text.codePoints().mapToObj(c -> String.format("%04X", c)).toList().toString())
			.isEqualTo("بند\tمدة\tملاحظة");
	}

	@Test
	void combiningMarksDrawnBeforeTheirBaseAreAttachedToIt() {
		// A shaper emits a right-to-left cluster as [mark, base]: "كَتَبَ" drawn visually is "بَتَكَ" with marks first.
		List<Glyph> glyphs = new ArrayList<>();
		double x = 400;
		for (String cluster : List.of("ب", "ت", "ك")) {
			glyphs.add(glyph("َ", x + 2, 100, 0));
			glyphs.add(glyph(cluster, x, 100, ADVANCE));
			x += ADVANCE;
		}

		assertThat(build(glyphs).text()).isEqualTo("كَتَبَ");
	}

	@Test
	void marksAttachByStreamOrderEvenWhenDrawnOverTheNeighbouringLetter() {
		// The fatha of "ج" is drawn inside the box of the preceding "ا" (seen in the Amiri corpus page).
		List<Glyph> glyphs = new ArrayList<>();
		glyphs.add(glyph("ا", 100, 100, 5));
		glyphs.add(glyph("َ", 103, 100, 0));
		glyphs.add(glyph("ج", 105, 100, 10));

		assertThat(build(glyphs).text()).isEqualTo("جَا");
	}

	@Test
	void glyphsWithoutTextAreSkippedAndCounted() {
		List<Glyph> glyphs = new ArrayList<>(visualLine("abc", 72, 100));
		glyphs.add(glyph("\u0000", 100, 100, 5));
		glyphs.add(glyph("", 110, 100, 5));
		glyphs.add(glyph("\u0000", 120, 100, 0));

		Result result = build(glyphs);

		assertThat(result.text()).isEqualTo("abc");
		assertThat(result.skippedGlyphs()).isEqualTo(2);
	}

	@Test
	void englishLineRightAlignedOnARightToLeftPageIsARightToLeftParagraph() {
		// On an Arabic page an English sentence is a right-to-left paragraph: its period is drawn at the far left.
		// All three lines end at x = 420 (right-aligned); 6 pt per glyph.
		List<Glyph> glyphs = new ArrayList<>(visualLine("ةيبرع ةلمج يهو ىلوأ", 420 - 19 * ADVANCE, 100));
		glyphs.addAll(visualLine("ةيبرع ةيناث ةلمج", 420 - 16 * ADVANCE, 130));
		glyphs.addAll(visualLine(".Remote work", 420 - 12 * ADVANCE, 160));

		Result result = build(glyphs);

		assertThat(result.pageDirection()).isEqualTo(Direction.RTL);
		assertThat(result.lines().get(2).text()).isEqualTo("Remote work.");
	}

	@Test
	void rightAlignedEnglishLineOnAnEnglishPageStaysLeftToRight() {
		List<Glyph> glyphs = new ArrayList<>(visualLine("First line of the page", 72, 100));
		glyphs.addAll(visualLine("Second line of it", 72, 130));
		glyphs.addAll(visualLine("Page 3.", 162, 160));

		assertThat(build(glyphs).lines().get(2).text()).isEqualTo("Page 3.");
	}

	@Test
	void glyphsStoredInLogicalOrderAreStillOrderedByPosition() {
		// Some producers write right-to-left text in reading order; positions still decide the visual order.
		List<Glyph> logicalStream = new ArrayList<>(visualLine("مكب", 400, 100));
		java.util.Collections.reverse(logicalStream);
		List<Glyph> restamped = new ArrayList<>();
		for (Glyph glyph : logicalStream) {
			restamped.add(glyph(glyph.text(), glyph.x(), glyph.y(), glyph.width()));
		}

		assertThat(build(restamped).text()).isEqualTo("بكم");
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private Result build(List<Glyph> glyphs) {
		return LineBuilder.build(glyphs);
	}

	/** Glyphs for {@code visual} placed left to right starting at {@code x} on baseline {@code y}. */
	private List<Glyph> visualLine(String visual, double x, double y) {
		List<Glyph> glyphs = new ArrayList<>();
		double position = x;
		for (int i = 0; i < visual.length();) {
			int cp = visual.codePointAt(i);
			glyphs.add(glyph(new String(Character.toChars(cp)), position, y, ADVANCE));
			position += ADVANCE;
			i += Character.charCount(cp);
		}
		return glyphs;
	}

	private Glyph glyph(String text, double x, double y, double width) {
		return new Glyph(text, x, y, width, FONT, stream++, false, false, false);
	}

}
