package com.mimocodes.mimorag.ingestion.application;

import java.util.Set;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.ingestion.application.OcrProvider.Box;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrLine;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrResult;

/**
 * Checks every OCR response before anything is stored (requirement: validate responses). The Java side re-checks the
 * service's most important promise itself: the Arabic text of a line is never changed by token recovery.
 */
public final class OcrResponseValidator {

	static final Set<String> LINE_FLAGS = Set.of("TOKEN_RECOVERED", "TOKEN_MISSING_UNPLACED", "TOKEN_POSSIBLY_MISSING",
			"LOW_CONFIDENCE");

	static final Set<String> PAGE_WARNINGS = Set.of("ORIENTATION_CORRECTED", "ORIENTATION_UNCERTAIN", "NO_TEXT_FOUND",
			"TOKEN_RECOVERED", "TOKEN_MISSING_UNPLACED", "TOKEN_POSSIBLY_MISSING", "LOW_CONFIDENCE");

	private static final Pattern NON_ARABIC = Pattern.compile("[^\\u0600-\\u06FF ]");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	private static final int MAX_METADATA_ENTRIES = 32;

	private static boolean isSmallScalar(Object value) {
		return value == null || value instanceof Number || value instanceof Boolean
				|| (value instanceof String text && text.length() <= 200);
	}

	/** Limits a response must respect. */
	public record Limits(int maxLines, int maxLineChars, int maxTokensPerLine) {
	}

	/** The response cannot be trusted; this page fails with {@code OCR_RESPONSE_INVALID}. */
	public static class InvalidOcrResponseException extends RuntimeException {

		public InvalidOcrResponseException(String reason) {
			super(reason);
		}

	}

	private OcrResponseValidator() {
	}

	public static void validate(OcrResult result, int sentWidth, int sentHeight, Limits limits) {
		require(result.image() != null && result.image().width() == sentWidth && result.image().height() == sentHeight,
				"image size differs from the request");
		require(result.lines() != null && result.lines().size() <= limits.maxLines(), "too many lines");
		require(result.summary() != null && result.orientation() != null && result.engine() != null,
				"missing summary, orientation or engine");
		require(Set.of(0, 90, 180, 270).contains(result.orientation().applied()), "invalid orientation");
		require(result.warnings() != null && PAGE_WARNINGS.containsAll(result.warnings()), "unknown page warning");
		// Stored verbatim as metadata: small maps of short keys and scalar values only.
		require(result.engine().size() <= MAX_METADATA_ENTRIES && result.engine().entrySet().stream()
			.allMatch(e -> e.getKey().length() <= 64 && isSmallScalar(e.getValue())), "invalid engine metadata");
		require(result.timings() != null && result.timings().size() <= MAX_METADATA_ENTRIES
				&& result.timings().entrySet().stream()
					.allMatch(e -> e.getKey().length() <= 64 && e.getValue() != null && e.getValue() >= 0),
				"invalid timings");
		for (OcrLine line : result.lines()) {
			validateLine(line, sentWidth, sentHeight, limits);
		}
	}

	private static void validateLine(OcrLine line, int width, int height, Limits limits) {
		require(line.originalText() != null && line.proposedText() != null, "line text missing");
		require(line.originalText().length() <= limits.maxLineChars()
				&& line.proposedText().length() <= limits.maxLineChars(), "line text too long");
		require(Double.isFinite(line.confidence()) && line.confidence() >= 0 && line.confidence() <= 1,
				"confidence outside [0, 1]");
		require("RTL".equals(line.direction()) || "LTR".equals(line.direction()), "invalid direction");
		require(line.flags() != null && LINE_FLAGS.containsAll(line.flags()), "unknown line flag");
		require(line.recoveredTokens() != null && line.recoveredTokens().size() <= limits.maxTokensPerLine(),
				"too many recovered tokens");
		require(line.unplacedTokens() != null && line.possiblyMissingTokens() != null, "token lists missing");
		require(line.row() >= 0, "invalid row");
		Box box = line.box();
		require(box != null && finite(box) && box.x() >= 0 && box.y() >= 0 && box.width() >= 0 && box.height() >= 0
				&& box.x() + box.width() <= width + 1 && box.y() + box.height() <= height + 1, "box outside the image");
		require(arabicOnly(line.proposedText()).equals(arabicOnly(line.originalText())),
				"proposed text changes the Arabic text");
	}

	static String arabicOnly(String text) {
		return SPACES.matcher(NON_ARABIC.matcher(text).replaceAll(" ")).replaceAll(" ").strip();
	}

	private static boolean finite(Box box) {
		return Double.isFinite(box.x()) && Double.isFinite(box.y()) && Double.isFinite(box.width())
				&& Double.isFinite(box.height());
	}

	private static void require(boolean condition, String reason) {
		if (!condition) {
			throw new InvalidOcrResponseException(reason);
		}
	}

}
