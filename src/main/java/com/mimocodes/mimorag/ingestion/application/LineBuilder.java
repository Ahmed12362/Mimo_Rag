package com.mimocodes.mimorag.ingestion.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mimocodes.mimorag.ingestion.application.NativePage.Glyph;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.Scripts.Strength;

/**
 * Rebuilds lines of text in logical (reading) order from positioned glyphs. PDF text extraction libraries return
 * glyphs as they are drawn; for right-to-left scripts that order is visual, and mixed Arabic/Latin lines, tables and
 * diacritics then come out scrambled. Measured in slice 2c: PDFBox's own text had 8-73% character error on Arabic,
 * mixed and table pages of the synthetic corpus.
 * <ol>
 * <li>Glyphs are split into base glyphs, combining marks, and glyphs without text (skipped, counted).</li>
 * <li>Base glyphs are grouped into lines by baseline.</li>
 * <li>Each mark is attached to its base letter by content-stream order (shapers emit a right-to-left cluster with the
 * mark before its base; logical-order streams put it after). Geometry is not used: marks are often drawn over the
 * neighbouring letter.</li>
 * <li>Within a line, glyphs are put in visual order (left to right); gaps become spaces, large gaps become tabs (table
 * cells, columns).</li>
 * <li>The visual line is converted to logical order for its base direction: right-to-left lines are reversed, then
 * left-to-right runs (Latin words, numbers, e-mail addresses, including spaces between them) are restored.</li>
 * </ol>
 */
public final class LineBuilder {

	/** Baselines closer than this fraction of the font size belong to the same line. */
	static final double SAME_LINE_TOLERANCE = 0.35;

	/** A gap wider than this fraction of the font size without a space glyph becomes a space. */
	static final double WORD_GAP = 0.25;

	/** A gap wider than this many font sizes becomes a tab (table cell or column boundary). */
	static final double CELL_GAP = 2.5;

	public enum Direction {

		LTR, RTL

	}

	/**
	 * One line in logical order.
	 *
	 * @param box bounding box of the line's glyphs (top-left origin)
	 * @param mixedDirection the line contains both right-to-left and left-to-right letters
	 */
	public record TextLine(String text, Direction direction, Region box, boolean mixedDirection) {
	}

	/**
	 * @param skippedGlyphs glyphs with a width but no text (no Unicode mapping): a quality signal
	 */
	public record Result(List<TextLine> lines, Direction pageDirection, int skippedGlyphs) {

		public String text() {
			return String.join("\n", lines.stream().map(TextLine::text).toList());
		}

	}

	private LineBuilder() {
	}

	public static Result build(List<Glyph> glyphs) {
		List<Glyph> bases = new ArrayList<>();
		List<Glyph> marks = new ArrayList<>();
		int skipped = 0;
		for (Glyph glyph : glyphs) {
			String text = glyph.text().replace("\u0000", "");
			if (text.isEmpty()) {
				if (glyph.width() > 0.5) {
					skipped++;
				}
			}
			else if (Scripts.isAllMarks(text)) {
				marks.add(glyph);
			}
			else {
				bases.add(glyph);
			}
		}
		List<List<Glyph>> lines = groupIntoLines(bases);
		Map<Glyph, List<Glyph>> attached = attachMarks(lines, marks);

		List<VisualLine> visualLines = new ArrayList<>();
		for (List<Glyph> line : lines) {
			VisualLine visual = toVisualLine(line, attached);
			if (visual.strongUnits() > 0 || visual.units().stream().anyMatch(u -> !u.space())) {
				visualLines.add(visual);
			}
		}
		Alignment alignment = Alignment.of(visualLines);
		Direction pageDirection = alignment.pageDirection(dominantDirection(bases));

		List<TextLine> result = new ArrayList<>();
		for (VisualLine visual : visualLines) {
			Direction direction = baseDirection(visual, alignment, pageDirection);
			String text = render(toLogicalOrder(visual.units(), direction));
			if (!text.isBlank()) {
				result.add(new TextLine(text, direction, visual.box(), visual.rtl() > 0 && visual.ltr() > 0));
			}
		}
		return new Result(result, pageDirection, skipped);
	}

	/**
	 * The paragraph direction of a line cannot be read from its letters alone: "HRIS portal." looks the same inside an
	 * Arabic and an English paragraph, except for where the final period is drawn. Alignment decides it: right-aligned
	 * lines on a right-to-left page are right-to-left paragraphs, left-aligned lines on a left-to-right page are
	 * left-to-right. Lines aligned to both edges (justified, tables) or to neither (centred) use their own letters, and
	 * mixed lines then the page direction.
	 */
	private static Direction baseDirection(VisualLine line, Alignment alignment, Direction pageDirection) {
		boolean rightOnly = alignment.rightAligned(line) && !alignment.leftAligned(line);
		boolean leftOnly = alignment.leftAligned(line) && !alignment.rightAligned(line);
		if (line.rtl() == 0) {
			return pageDirection == Direction.RTL && rightOnly ? Direction.RTL : Direction.LTR;
		}
		if (line.ltr() == 0) {
			return pageDirection == Direction.LTR && leftOnly ? Direction.LTR : Direction.RTL;
		}
		if (rightOnly) {
			return Direction.RTL;
		}
		if (leftOnly) {
			return Direction.LTR;
		}
		return pageDirection;
	}

	/** Text column edges of a page and which lines touch them. */
	private record Alignment(double left, double right, double tolerance, int rightOnly, int leftOnly) {

		static Alignment of(List<VisualLine> lines) {
			double left = Double.MAX_VALUE;
			double right = -Double.MAX_VALUE;
			double size = 0;
			for (VisualLine line : lines) {
				left = Math.min(left, line.box().x());
				right = Math.max(right, line.box().x() + line.box().width());
				size = Math.max(size, line.fontSize());
			}
			Alignment edges = new Alignment(left, right, Math.max(2.0, 0.5 * size), 0, 0);
			int rightOnly = 0;
			int leftOnly = 0;
			for (VisualLine line : lines) {
				boolean r = edges.rightAligned(line);
				boolean l = edges.leftAligned(line);
				rightOnly += r && !l ? 1 : 0;
				leftOnly += l && !r ? 1 : 0;
			}
			return new Alignment(left, right, edges.tolerance(), rightOnly, leftOnly);
		}

		boolean rightAligned(VisualLine line) {
			return right - (line.box().x() + line.box().width()) <= tolerance;
		}

		boolean leftAligned(VisualLine line) {
			return line.box().x() - left <= tolerance;
		}

		/** Majority of one-sided alignment; letter counts only when alignment says nothing. */
		Direction pageDirection(Direction byLetters) {
			if (rightOnly > leftOnly) {
				return Direction.RTL;
			}
			if (leftOnly > rightOnly) {
				return Direction.LTR;
			}
			return byLetters;
		}

	}

	// ---- lines ----------------------------------------------------------------------------------------------------

	private static List<List<Glyph>> groupIntoLines(List<Glyph> bases) {
		List<Glyph> byBaseline = new ArrayList<>(bases);
		byBaseline.sort(Comparator.comparingDouble(Glyph::y).thenComparingDouble(Glyph::x));
		List<List<Glyph>> lines = new ArrayList<>();
		List<Glyph> current = null;
		double baseline = 0;
		double fontSize = 0;
		for (Glyph glyph : byBaseline) {
			double size = Math.max(1, Math.min(fontSize == 0 ? glyph.fontSize() : fontSize, glyph.fontSize()));
			if (current != null && Math.abs(glyph.y() - baseline) <= SAME_LINE_TOLERANCE * size) {
				current.add(glyph);
				continue;
			}
			current = new ArrayList<>();
			current.add(glyph);
			lines.add(current);
			baseline = glyph.y();
			fontSize = glyph.fontSize();
		}
		return lines;
	}

	/** Attaches each mark to a base glyph by stream order; marks without any base are dropped from the text. */
	private static Map<Glyph, List<Glyph>> attachMarks(List<List<Glyph>> lines, List<Glyph> marks) {
		Map<Glyph, List<Glyph>> attached = new HashMap<>();
		if (marks.isEmpty()) {
			return attached;
		}
		TreeMap<Integer, Glyph> byStream = new TreeMap<>();
		Map<Glyph, Boolean> visualStream = new HashMap<>();
		for (List<Glyph> line : lines) {
			boolean visual = isVisualStreamOrder(line);
			for (Glyph glyph : line) {
				byStream.put(glyph.streamIndex(), glyph);
				visualStream.put(glyph, visual);
			}
		}
		for (Glyph mark : marks) {
			Map.Entry<Integer, Glyph> next = byStream.higherEntry(mark.streamIndex());
			Map.Entry<Integer, Glyph> previous = byStream.lowerEntry(mark.streamIndex());
			Glyph base;
			if (next != null && isRtlBase(next.getValue()) && visualStream.get(next.getValue())) {
				base = next.getValue();
			}
			else if (previous != null) {
				base = previous.getValue();
			}
			else {
				base = next == null ? null : next.getValue();
			}
			if (base != null) {
				attached.computeIfAbsent(base, key -> new ArrayList<>()).add(mark);
			}
		}
		return attached;
	}

	/** Whether the line's glyphs were drawn mostly left to right (visual order), as text shapers emit them. */
	private static boolean isVisualStreamOrder(List<Glyph> line) {
		List<Glyph> inStream = new ArrayList<>(line);
		inStream.sort(Comparator.comparingInt(Glyph::streamIndex));
		int forward = 0;
		int backward = 0;
		for (int i = 1; i < inStream.size(); i++) {
			double step = inStream.get(i).x() - inStream.get(i - 1).x();
			if (step > 0.01) {
				forward++;
			}
			else if (step < -0.01) {
				backward++;
			}
		}
		return forward >= backward;
	}

	private static boolean isRtlBase(Glyph glyph) {
		return Scripts.strength(glyph.text()) == Strength.RTL;
	}

	// ---- one line ---------------------------------------------------------------------------------------------------

	/** A unit of the visual line: a base glyph with its marks, a space, or a cell break. */
	private record Unit(String text, Strength strength, boolean space, boolean cellBreak) {

		static final Unit SPACE = new Unit(" ", Strength.NEUTRAL, true, false);

		static final Unit TAB = new Unit("\t", Strength.NEUTRAL, true, true);

	}

	/** A line in visual (left-to-right) order, before its paragraph direction is known. */
	private record VisualLine(List<Unit> units, int rtl, int ltr, Region box, double fontSize) {

		int strongUnits() {
			return rtl + ltr;
		}

	}

	private static VisualLine toVisualLine(List<Glyph> line, Map<Glyph, List<Glyph>> attached) {
		List<Glyph> visual = new ArrayList<>(line);
		visual.sort(Comparator.comparingDouble(Glyph::x).thenComparingInt(Glyph::streamIndex));

		List<Unit> units = new ArrayList<>();
		Glyph previous = null;
		int rtl = 0;
		int ltr = 0;
		double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, top = Double.MAX_VALUE, bottom = -Double.MAX_VALUE;
		for (Glyph glyph : visual) {
			String text = glyph.text().replace("\u0000", "");
			boolean isSpace = text.isBlank();
			if (previous != null) {
				double gap = glyph.x() - (previous.x() + previous.width());
				double size = Math.max(1, Math.min(glyph.fontSize(), previous.fontSize()));
				if (gap > CELL_GAP * size) {
					appendBreak(units, Unit.TAB);
				}
				else if (!isSpace && gap > WORD_GAP * size) {
					appendBreak(units, Unit.SPACE);
				}
			}
			if (isSpace) {
				appendBreak(units, Unit.SPACE);
			}
			else {
				StringBuilder cluster = new StringBuilder(text);
				for (Glyph mark : attached.getOrDefault(glyph, List.of())) {
					cluster.append(mark.text().replace("\u0000", ""));
				}
				Strength strength = Scripts.strength(cluster.toString());
				if (strength == Strength.RTL) {
					rtl++;
				}
				else if (strength == Strength.LTR) {
					ltr++;
				}
				units.add(new Unit(cluster.toString(), strength, false, false));
				x0 = Math.min(x0, glyph.x());
				x1 = Math.max(x1, glyph.x() + glyph.width());
				top = Math.min(top, glyph.y() - glyph.fontSize() * 0.8);
				bottom = Math.max(bottom, glyph.y() + glyph.fontSize() * 0.2);
			}
			previous = glyph;
		}

		Region box = x0 == Double.MAX_VALUE ? new Region(0, 0, 0, 0) : new Region(x0, top, x1 - x0, bottom - top);
		double fontSize = line.stream().mapToDouble(Glyph::fontSize).max().orElse(0);
		return new VisualLine(units, rtl, ltr, box, fontSize);
	}

	private static void appendBreak(List<Unit> units, Unit unit) {
		if (units.isEmpty()) {
			return;
		}
		Unit last = units.get(units.size() - 1);
		if (last.space()) {
			if (unit.cellBreak() && !last.cellBreak()) {
				units.set(units.size() - 1, unit);
			}
			return;
		}
		units.add(unit);
	}

	/**
	 * Visual to logical order. Right-to-left lines: reverse, then restore left-to-right runs. Left-to-right lines:
	 * reverse right-to-left runs only. A run starts and ends with a strong unit of its direction and may contain
	 * neutrals and spaces, but no strong unit of the other direction and no cell break.
	 */
	static List<Unit> toLogicalOrder(List<Unit> visual, Direction direction) {
		List<Unit> units = new ArrayList<>(visual);
		if (direction == Direction.RTL) {
			Collections.reverse(units);
			reverseRuns(units, true);
		}
		else {
			reverseRuns(units, false);
		}
		return units;
	}

	/** Reverses maximal runs of left-to-right ({@code ltrRuns}) or right-to-left units in place. */
	private static void reverseRuns(List<Unit> units, boolean ltrRuns) {
		int i = 0;
		while (i < units.size()) {
			if (!belongsToRun(units.get(i), ltrRuns)) {
				i++;
				continue;
			}
			int start = i;
			int lastStrong = i;
			int j = i + 1;
			while (j < units.size() && !opposes(units.get(j), ltrRuns) && !units.get(j).cellBreak()) {
				if (belongsToRun(units.get(j), ltrRuns)) {
					lastStrong = j;
				}
				j++;
			}
			Collections.reverse(units.subList(start, lastStrong + 1));
			i = lastStrong + 1;
		}
	}

	private static boolean belongsToRun(Unit unit, boolean ltrRuns) {
		return ltrRuns ? unit.strength() == Strength.LTR || unit.strength() == Strength.NUMBER
				: unit.strength() == Strength.RTL;
	}

	private static boolean opposes(Unit unit, boolean ltrRuns) {
		return ltrRuns ? unit.strength() == Strength.RTL
				: unit.strength() == Strength.LTR || unit.strength() == Strength.NUMBER;
	}

	private static String render(List<Unit> units) {
		StringBuilder text = new StringBuilder();
		for (Unit unit : units) {
			text.append(unit.text());
		}
		return text.toString().replaceAll(" *\t *", "\t").strip();
	}

	private static Direction dominantDirection(List<Glyph> bases) {
		int rtl = 0;
		int ltr = 0;
		for (Glyph glyph : bases) {
			Strength strength = Scripts.strength(glyph.text());
			if (strength == Strength.RTL) {
				rtl++;
			}
			else if (strength == Strength.LTR) {
				ltr++;
			}
		}
		return rtl >= ltr && rtl > 0 ? Direction.RTL : Direction.LTR;
	}

}
