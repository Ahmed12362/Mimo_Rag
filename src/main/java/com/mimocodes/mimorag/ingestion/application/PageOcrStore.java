package com.mimocodes.mimorag.ingestion.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;

/**
 * Read-only OCR results per page (insert-only for the runtime role, unique per run and page), plus the inputs OCR
 * needs from native extraction.
 */
public interface PageOcrStore {

	/** A page that native extraction classified as needing OCR, with what 2c stored about it. */
	record OcrCandidate(int pageNumber, boolean mixed, List<Region> images, List<Region> nativeLines) {
	}

	/** Pages of an extraction run classified {@code OCR_REQUIRED} or {@code MIXED}, in page order. */
	List<OcrCandidate> candidates(UUID integrationId, UUID extractionRunId);

	/** Page numbers already stored for an OCR run. */
	Set<Integer> storedPages(UUID integrationId, UUID ocrRunId);

	/** Inserts a page result inside the caller's transaction (mandatory); returns 0 if the page is already stored. */
	int insert(UUID integrationId, UUID ocrRunId, PageOcrResult page, Instant now);

	/** Stored and failed page counts of a run. */
	int[] counts(UUID integrationId, UUID ocrRunId);

	Optional<Map<String, Object>> find(UUID integrationId, UUID ocrRunId, int pageNumber);

	/**
	 * The OCR result of one page. Lines carry boxes in page points (top-left origin).
	 *
	 * @param errorCode set only when {@code completed} is false
	 */
	record PageOcrResult(int pageNumber, boolean completed, String mode, int renderDpi, int imageWidth,
			int imageHeight, int orientationApplied, String originalText, String proposedText,
			List<Map<String, Object>> lines, List<Map<String, Object>> regions, double meanConfidence,
			int lowConfidenceLines, int recoveredTokens, int unplacedTokens, int possiblyMissingTokens,
			int droppedOverlapping, Set<String> flags, Set<String> warnings, String errorCode,
			Map<String, Object> engine, Map<String, Object> timings, long durationMillis) {

		public static PageOcrResult failed(int pageNumber, String mode, int dpi, String errorCode, long durationMillis) {
			return new PageOcrResult(pageNumber, false, mode, dpi, 0, 0, 0, "", "", List.of(), List.of(), 0, 0, 0, 0, 0,
					0, Set.of(), Set.of(), errorCode, Map.of(), Map.of(), durationMillis);
		}

	}

}
