package com.mimocodes.mimorag.ingestion.application;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mimocodes.mimorag.ingestion.application.LineBuilder.Direction;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;

/**
 * Everything decided about one page. {@code nativeText} is always kept exactly as extracted (in logical order);
 * {@code resultText} is the trusted page text, {@code null} when the page needs OCR or has no text.
 *
 * @param lines line boxes with their direction and offsets into {@code nativeText}, so slice 2d can drop OCR lines
 * that overlap native text and the review UI can highlight lines
 * @param images image regions (top-left page coordinates) that slice 2d OCRs on {@code MIXED} pages
 * @param imageCoverage share of the page covered by images (overlaps counted once per image, capped at 1)
 */
public record PageAnalysis(int pageNumber, PageClass pageClass, String nativeText, boolean nativeTextTrusted,
		String resultText, Set<String> scripts, Set<PageWarning> warnings, TextQuality quality,
		List<String> unreliableReasons, List<LineBox> lines, List<Region> images, double imageCoverage,
		double width, double height, int rotation, long durationMillis) {

	public PageAnalysis {
		// Stable order (ARABIC, LATIN, OTHER) so API responses do not change from call to call.
		scripts = Collections.unmodifiableSet(new LinkedHashSet<>(scripts));
		warnings = Set.copyOf(warnings);
		unreliableReasons = List.copyOf(unreliableReasons);
		lines = List.copyOf(lines);
		images = List.copyOf(images);
	}

	/** A line's position, direction and where its text sits in {@code nativeText} ([start, end) in chars). */
	public record LineBox(Region box, Direction direction, int start, int end, boolean mixedDirection) {
	}

	public PageAnalysis withDuration(long millis) {
		return new PageAnalysis(pageNumber, pageClass, nativeText, nativeTextTrusted, resultText, scripts, warnings,
				quality, unreliableReasons, lines, images, imageCoverage, width, height, rotation, millis);
	}

}
