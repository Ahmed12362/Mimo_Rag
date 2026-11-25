package com.mimocodes.mimorag.ingestion.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OCR of one page image (contract v1, docs/adr/0014-ocr-service.md). The implementation calls the separate OCR service;
 * results are validated before they are returned. Failures are typed by how the job must react (retry policy).
 */
public interface OcrProvider {

	/**
	 * @param png the rendered page or region, PNG-encoded
	 * @param requestId correlation id passed to the service and its logs
	 */
	OcrResult recognize(byte[] png, int width, int height, String requestId);

	record OcrResult(Map<String, Object> engine, Image image, Orientation orientation, List<OcrLine> lines,
			Summary summary, List<String> warnings, Map<String, Integer> timings) {
	}

	/** Size of the image the service decoded; must equal the submitted one. */
	record Image(int width, int height) {
	}

	record Orientation(boolean checked, int applied, Double classifierConfidence) {
	}

	record Box(double x, double y, double width, double height) {
	}

	record RecoveredToken(String token, int offset, List<String> sources, String anchor) {
	}

	/** One recognised line. Boxes are in pixels of the submitted image. */
	record OcrLine(Box box, String direction, double confidence, String originalText, String proposedText,
			List<RecoveredToken> recoveredTokens, List<String> unplacedTokens, List<String> possiblyMissingTokens,
			List<String> flags, int row) {
	}

	record Summary(int lines, int characters, double meanConfidence, int lowConfidenceLines, int recoveredTokens,
			int unplacedTokens, int possiblyMissingTokens) {
	}

	/** The service cannot answer now (unreachable, busy, timeout, models loading): retry the job later. */
	class OcrUnavailableException extends RuntimeException {

		private final String code;

		private final Duration retryAfter;

		public OcrUnavailableException(String code, Duration retryAfter, Throwable cause) {
			super(code, cause);
			this.code = code;
			this.retryAfter = retryAfter;
		}

		public String getCode() {
			return code;
		}

		public Duration getRetryAfter() {
			return retryAfter;
		}

	}

	/** The service refused this image (invalid, too large): this page fails, the others continue. */
	class OcrPageRejectedException extends RuntimeException {

		private final String code;

		public OcrPageRejectedException(String code) {
			super(code);
			this.code = code;
		}

		public String getCode() {
			return code;
		}

	}

	/** The engine failed on this image (HTTP 500): retried a few times for this page, then the page fails. */
	class OcrEngineException extends RuntimeException {

		private final String code;

		/** @param code {@code OCR_ENGINE_ERROR} or {@code OCR_TIMEOUT} (the page exceeded the service's time limit) */
		public OcrEngineException(String code) {
			super(code);
			this.code = code;
		}

		public String getCode() {
			return code;
		}

	}

	/** Token or contract mismatch (401, 415): fixing configuration is needed; the job fails permanently. */
	class OcrConfigurationException extends RuntimeException {

		public OcrConfigurationException(String message) {
			super(message);
		}

	}

}
