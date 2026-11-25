package com.mimocodes.mimorag.ingestion.application;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * OCR settings ({@code mimorag.ocr.*}). OCR is off unless enabled; then a service token of at least 32 characters is
 * required. Defaults follow the slice 2d plan (§5).
 *
 * @param requestTimeout whole-request limit; above the service's own 60 s budget, so the service answers first
 * @param maxPageEngineAttempts attempts per page when the engine fails (HTTP 500) before the page is marked failed
 */
@ConfigurationProperties("mimorag.ocr")
public record OcrProperties(@DefaultValue("false") boolean enabled,
		@DefaultValue("http://127.0.0.1:8090") URI baseUrl, @DefaultValue("") String token,
		@DefaultValue("PT2S") Duration connectTimeout, @DefaultValue("PT90S") Duration requestTimeout,
		@DefaultValue("5MB") DataSize maxResponseSize, @DefaultValue("2000") int maxLines,
		@DefaultValue("5000") int maxLineChars, @DefaultValue("64") int maxTokensPerLine,
		@DefaultValue("300") int renderDpi, @DefaultValue("40000000") long maxRenderPixels,
		@DefaultValue("30MB") DataSize maxImageSize, @DefaultValue("4") double regionMarginPoints,
		@DefaultValue("32") int minRegionPixels, @DefaultValue("auto") String orientation,
		@DefaultValue("3") int maxPageEngineAttempts) {

	public OcrProperties {
		if (enabled && (token == null || token.length() < 32)) {
			throw new IllegalArgumentException(
					"mimorag.ocr.token (MIMORAG_OCR_TOKEN) must be at least 32 characters when OCR is enabled");
		}
		if (!"auto".equals(orientation) && !"off".equals(orientation)) {
			throw new IllegalArgumentException("mimorag.ocr.orientation must be auto or off");
		}
		if (renderDpi < 72 || renderDpi > 600) {
			throw new IllegalArgumentException("mimorag.ocr.render-dpi must be between 72 and 600");
		}
	}

	public OcrResponseValidator.Limits limits() {
		return new OcrResponseValidator.Limits(maxLines, maxLineChars, maxTokensPerLine);
	}

	@Override
	public String toString() {
		return "OcrProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", token=<hidden>]";
	}

}
