package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import com.mimocodes.mimorag.ingestion.application.OcrProperties;
import com.mimocodes.mimorag.ingestion.application.OcrProvider;
import com.mimocodes.mimorag.ingestion.application.OcrResponseValidator;
import com.mimocodes.mimorag.ingestion.application.OcrResponseValidator.InvalidOcrResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Client of the OCR service (contract v1). Never follows redirects, bounds every wait and every response, and maps
 * each outcome to the retry policy (docs/adr/0014-ocr-service.md):
 * <ul>
 * <li>connection failure, timeout, 503 → {@link OcrUnavailableException} (the job retries later);</li>
 * <li>400, 413 → {@link OcrPageRejectedException} (this page fails);</li>
 * <li>500, 503 {@code OCR_TIMEOUT} → {@link OcrEngineException} (retried for the page, then the page fails);</li>
 * <li>401, 415 → {@link OcrConfigurationException} (the job fails until configuration is fixed);</li>
 * <li>an invalid 200 body → {@link InvalidOcrResponseException} (this page fails).</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "mimorag.ocr.enabled", havingValue = "true")
class HttpOcrProvider implements OcrProvider {

	private static final Logger log = LoggerFactory.getLogger(HttpOcrProvider.class);

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final HttpClient http;

	private final URI endpoint;

	private final OcrProperties properties;

	private final JsonMapper json;

	HttpOcrProvider(OcrProperties properties, JsonMapper json) {
		this.properties = properties;
		this.json = json;
		this.endpoint = properties.baseUrl().resolve("/v1/ocr");
		this.http = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			// Plain HTTP/1.1: no h2c upgrade attempt on every request (the service does not speak HTTP/2).
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public OcrResult recognize(byte[] png, int width, int height, String requestId) {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(properties.requestTimeout())
			.header("Content-Type", "image/png")
			.header("X-OCR-Token", properties.token())
			.header("X-Request-Id", requestId)
			.header("X-OCR-Orientation", properties.orientation())
			.header("X-OCR-Profile", "ar-en")
			.POST(HttpRequest.BodyPublishers.ofByteArray(png))
			.build();
		HttpResponse<InputStream> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (HttpTimeoutException ex) {
			throw new OcrUnavailableException("OCR_TIMEOUT", null, ex);
		}
		catch (ConnectException ex) {
			throw new OcrUnavailableException("OCR_SERVICE_UNAVAILABLE", null, ex);
		}
		catch (IOException ex) {
			throw new OcrUnavailableException("OCR_SERVICE_UNAVAILABLE", null, ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new OcrUnavailableException("OCR_INTERRUPTED", null, ex);
		}
		byte[] body = readLimited(response);
		int status = response.statusCode();
		if (status == 200) {
			OcrResult result = parse(body);
			OcrResponseValidator.validate(result, width, height, properties.limits());
			return result;
		}
		String code = errorCode(body);
		log.warn("OCR service answered {} {} for request {}", status, code, requestId);
		switch (status) {
			case 400, 413 -> throw new OcrPageRejectedException(code == null ? "OCR_IMAGE_REJECTED" : code);
			case 401, 415 -> throw new OcrConfigurationException("OCR service rejected the request: " + status);
			case 500 -> throw new OcrEngineException("OCR_ENGINE_ERROR");
			case 503 -> {
				if ("OCR_TIMEOUT".equals(code)) {
					// The page itself exceeded the service's time limit: retried for the page, then only it fails.
					throw new OcrEngineException("OCR_TIMEOUT");
				}
				throw new OcrUnavailableException(code == null ? "OCR_SERVICE_UNAVAILABLE" : code, retryAfter(response),
						null);
			}
			default -> throw new OcrUnavailableException("OCR_UNEXPECTED_STATUS_" + status, null, null);
		}
	}

	private byte[] readLimited(HttpResponse<InputStream> response) {
		long limit = properties.maxResponseSize().toBytes();
		try (InputStream in = response.body()) {
			byte[] body = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limit + 1));
			if (body.length > limit) {
				throw new InvalidOcrResponseException("response larger than " + limit + " bytes");
			}
			return body;
		}
		catch (IOException ex) {
			throw new OcrUnavailableException("OCR_SERVICE_UNAVAILABLE", null, ex);
		}
	}

	private OcrResult parse(byte[] body) {
		try {
			Map<String, Object> raw = json.readValue(body, MAP);
			if (!"1".equals(raw.get("contractVersion"))) {
				throw new InvalidOcrResponseException("unsupported contract version");
			}
			// Fields added within contract version 1 are ignored; removed or renamed ones fail validation.
			return json.readerFor(OcrResult.class)
				.without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(body);
		}
		catch (JacksonException | IllegalArgumentException ex) {
			throw new InvalidOcrResponseException("response is not valid contract JSON");
		}
	}

	private String errorCode(byte[] body) {
		try {
			Object code = json.readValue(body, MAP).get("errorCode");
			return code instanceof String text && text.matches("[A-Z][A-Z0-9_]{1,59}") ? text : null;
		}
		catch (JacksonException | IllegalArgumentException ex) {
			return null;
		}
	}

	private static Duration retryAfter(HttpResponse<?> response) {
		return response.headers()
			.firstValue("Retry-After")
			.filter(value -> value.matches("\\d{1,4}"))
			.map(value -> Duration.ofSeconds(Long.parseLong(value)))
			.orElse(null);
	}

}
