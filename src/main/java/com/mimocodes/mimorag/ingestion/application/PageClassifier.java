package com.mimocodes.mimorag.ingestion.application;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mimocodes.mimorag.ingestion.application.LineBuilder.TextLine;
import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.PageAnalysis.LineBox;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;

/**
 * Decides what a page's native text can be used for, and what a reviewer should be told about it. Thresholds were set
 * on the synthetic corpus and are named so they can be revisited with real documents.
 */
public final class PageClassifier {

	/** Images covering at least this share of a page without (enough) text: a scan. */
	static final double SCAN_IMAGE_COVERAGE = 0.25;

	/** Images covering at least this share of a page with text: OCR the image regions too (MIXED). */
	static final double MIXED_IMAGE_COVERAGE = 0.10;

	/** This many filled shapes without text suggest text converted to outlines. */
	static final int VECTOR_TEXT_PATHS = 50;

	/** Fonts smaller than this (points) count as tiny text. */
	static final double TINY_FONT_SIZE = 1.0;

	/** Longest native text stored per page (characters). */
	public static final int MAX_TEXT_CHARS = 200_000;

	private PageClassifier() {
	}

	public static PageAnalysis classify(NativePage page) {
		LineBuilder.Result built = LineBuilder.build(page.glyphs());
		EnumSet<PageWarning> warnings = EnumSet.noneOf(PageWarning.class);

		StringBuilder text = new StringBuilder();
		List<LineBox> lines = new ArrayList<>();
		for (TextLine line : built.lines()) {
			if (text.length() + line.text().length() + 1 > MAX_TEXT_CHARS) {
				warnings.add(PageWarning.TEXT_TRUNCATED);
				break;
			}
			if (!text.isEmpty()) {
				text.append('\n');
			}
			int start = text.length();
			text.append(line.text());
			lines.add(new LineBox(line.box(), line.direction(), start, text.length(), line.mixedDirection()));
			if (line.mixedDirection()) {
				warnings.add(PageWarning.MIXED_DIRECTION_LINE);
			}
		}
		if (page.truncated()) {
			warnings.add(PageWarning.TEXT_TRUNCATED);
		}
		String nativeText = text.toString();
		TextQuality quality = TextQuality.of(nativeText, page.glyphs().size(), built.skippedGlyphs());
		hiddenTextWarnings(page.glyphs(), warnings);
		if (quality.bidiControls() > 0) {
			warnings.add(PageWarning.BIDI_CONTROL_CHARACTERS);
		}
		if (quality.zeroWidth() > 0) {
			warnings.add(PageWarning.ZERO_WIDTH_CHARACTERS);
		}

		double coverage = imageCoverage(page);
		List<String> unreliable = quality.unreliableReasons();
		PageClass pageClass;
		if (!unreliable.isEmpty()) {
			warnings.add(PageWarning.NATIVE_TEXT_UNRELIABLE);
			pageClass = PageClass.OCR_REQUIRED;
		}
		else if (quality.nonSpaceChars() < TextQuality.MIN_TEXT_CHARS) {
			if (coverage >= SCAN_IMAGE_COVERAGE) {
				pageClass = PageClass.OCR_REQUIRED;
			}
			else if (page.filledPaths() >= VECTOR_TEXT_PATHS) {
				warnings.add(PageWarning.VECTOR_ONLY_CONTENT);
				pageClass = PageClass.OCR_REQUIRED;
			}
			else {
				pageClass = quality.nonSpaceChars() == 0 ? PageClass.EMPTY : PageClass.NATIVE_TEXT;
			}
		}
		else {
			pageClass = coverage >= MIXED_IMAGE_COVERAGE ? PageClass.MIXED : PageClass.NATIVE_TEXT;
		}

		boolean trusted = unreliable.isEmpty() && (pageClass == PageClass.NATIVE_TEXT || pageClass == PageClass.MIXED);
		return new PageAnalysis(page.pageNumber(), pageClass, nativeText, trusted, trusted ? nativeText : null,
				scripts(quality), warnings, quality, unreliable, lines, page.images(), coverage, page.width(),
				page.height(), page.rotation(), 0);
	}

	/** A page that could not be read at all. */
	public static PageAnalysis failed(int pageNumber, PageWarning reason, long durationMillis) {
		return new PageAnalysis(pageNumber, PageClass.FAILED, "", false, null, Set.of(), Set.of(reason),
				TextQuality.of("", 0, 0), List.of(), List.of(), List.of(), 0, 0, 0, 0, durationMillis);
	}

	private static void hiddenTextWarnings(List<Glyph> glyphs, Set<PageWarning> warnings) {
		for (Glyph glyph : glyphs) {
			if (glyph.text().isBlank()) {
				continue;
			}
			if (glyph.invisible()) {
				warnings.add(PageWarning.INVISIBLE_TEXT);
			}
			if (glyph.white()) {
				warnings.add(PageWarning.WHITE_TEXT);
			}
			if (glyph.fontSize() < TINY_FONT_SIZE) {
				warnings.add(PageWarning.TINY_TEXT);
			}
			if (glyph.offPage()) {
				warnings.add(PageWarning.OFF_PAGE_TEXT);
			}
		}
	}

	private static double imageCoverage(NativePage page) {
		double pageArea = page.width() * page.height();
		if (pageArea <= 0) {
			return 0;
		}
		double covered = page.images().stream().mapToDouble(Region::area).sum();
		return Math.min(1.0, covered / pageArea);
	}

	private static Set<String> scripts(TextQuality quality) {
		Set<String> scripts = new LinkedHashSet<>();
		if (quality.arabicLetters() > 0) {
			scripts.add("ARABIC");
		}
		if (quality.latinLetters() > 0) {
			scripts.add("LATIN");
		}
		if (quality.otherLetters() > 0) {
			scripts.add("OTHER");
		}
		return scripts;
	}

}
