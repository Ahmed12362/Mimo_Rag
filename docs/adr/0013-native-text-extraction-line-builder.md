# ADR-0013: Native PDF text through our own right-to-left-aware line builder

- **Status:** Accepted (M2 slice 2c, decisions a–e of the slice 2c plan)
- **Date:** 2026-11-24

## Context

PDFs store positioned glyphs, not sentences. Libraries rebuild text from them, but for right-to-left scripts the
glyphs are drawn in visual order, and on the synthetic corpus PDFBox's own text (`PDFTextStripper`) had these
character error rates (measured before this decision, same metric as the 2a OCR benchmark):

| Page | PDFBox text |
|---|---|
| English, Arabic Naskh, Arabic Sans 10 pt | 0% |
| Mixed: Latin inside Arabic lines | 14.0% |
| Mixed: alternating Arabic/English lines | 7.8% |
| Arabic Amiri (ligatures) | 22.3% |
| Right-to-left table | 53.2% |
| Fully diacritised Arabic | 72.4% |

## Decision

Build lines ourselves from glyph positions (`LineBuilder`, pure Java, no PDF library):

1. Separate base glyphs, combining marks and glyphs without text (skipped, counted as a quality signal).
2. Group base glyphs into lines by baseline.
3. Attach each mark to its base by **content-stream order** (shapers emit a right-to-left cluster as mark then base;
   logical-order streams put marks after). Geometry is not used: marks are often drawn over the neighbouring letter.
4. Order each line visually by x; gaps become spaces, gaps wider than 2.5 font sizes become tabs (table cells).
5. Decide each line's paragraph direction by **alignment** (right-aligned lines on a right-to-left page are
   right-to-left paragraphs), falling back to the line's own letters and then the page; then convert visual to
   logical order: reverse right-to-left lines and restore left-to-right runs (Latin words, numbers, e-mail addresses,
   with the spaces between them).

The PDF library only collects facts (`GlyphCollector`: glyph, render mode, fill colour, on-page; `ImageRegionCollector`:
image boxes, filled shapes). `PageClassifier` decides the page class and warnings; untrusted native text is stored but
never offered as the page result.

## Result (measured, same corpus and metric)

| Page | PDFBox | Line builder | Target |
|---|---|---|---|
| English, Arabic Naskh, Sans, Amiri, table, Latin-inside-Arabic | 0–53% | **0.00%** | 0–10% |
| Mixed: alternating lines | 7.8% | **1.04%** | ≤ 2% |
| Fully diacritised | 72.4% | **1.38%** | reported |

The remaining 1.04% is one English line that is the widest on an Arabic page: it touches both margins, so its
paragraph direction cannot be told from alignment, and its final period lands at the start. A punctuation heuristic
was rejected because it would break ordinary lines that begin with `(`.

## Consequences

- Extraction quality is now ours to maintain; `NativeExtractionAccuracyTest` guards it on the committed corpus.
- Producers that store right-to-left text in logical order are handled by sorting by position; producers with broken
  Unicode mappings are caught by the quality checks (`NATIVE_TEXT_UNRELIABLE` → OCR).
- Multi-column layouts on one baseline are read as one line with tab-separated parts (reading across columns).
  Column detection is out of scope until real documents need it.
- The thresholds (line tolerance, word and cell gaps, classification limits) were tuned on synthetic pages and must be
  re-checked on authorised real documents before production.
