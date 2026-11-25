package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.mimocodes.mimorag.ingestion.application.OcrProperties;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrConfigurationException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrEngineException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrPageRejectedException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrResult;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrUnavailableException;
import com.mimocodes.mimorag.ingestion.application.OcrResponseValidator.InvalidOcrResponseException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The HTTP client side of the OCR contract (docs/adr/0014-ocr-service.md): real responses captured from the service
 * image parse and validate, and every status maps to the documented failure class.
 */
class HttpOcrProviderTest {

	private static final String TOKEN = "unit-test-token-0123456789abcdefghijklmnop";

	private static final Path FIXTURES = Path.of("src/test/resources/ocr");

	/** All captured fixtures were rendered at 300 DPI from an A4 page. */
	private static final int WIDTH = 2482;

	private static final int HEIGHT = 3508;

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n' };

	private HttpServer server;

	private final Map<String, String> received = new ConcurrentHashMap<>();

	private volatile Consumer<HttpExchange> responder;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			try (exchange) {
				exchange.getRequestHeaders().forEach((name, values) -> received.put(name.toLowerCase(), values.get(0)));
				received.put(":path", exchange.getRequestURI().getPath());
				received.put(":method", exchange.getRequestMethod());
				exchange.getRequestBody().readAllBytes();
				responder.accept(exchange);
			}
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@ParameterizedTest
	@ValueSource(strings = { "smoke-ar_policy_amiri__flip180", "smoke-ar_sick_naskh__clean300",
			"smoke-mixed_ar_en_naskh__clean300" })
	void realServiceResponsesParseAndValidate(String fixture) throws IOException {
		byte[] body = Files.readAllBytes(FIXTURES.resolve(fixture + ".response.json"));
		responder = exchange -> send(exchange, 200, "application/json", body);

		OcrResult result = provider(DataSize.ofMegabytes(5), Duration.ofSeconds(5)).recognize(PNG, WIDTH, HEIGHT,
				"req-12345678-p1");

		assertThat(result.image().width()).isEqualTo(WIDTH);
		assertThat(result.lines()).isNotEmpty();
		assertThat(result.lines()).allSatisfy(line -> assertThat(line.originalText()).isNotBlank());
		assertThat(result.engine()).containsKeys("pipeline", "paddleocr", "recognition");
		if (fixture.contains("flip180")) {
			assertThat(result.orientation().applied()).isEqualTo(180);
			assertThat(result.warnings()).contains("ORIENTATION_CORRECTED");
		}
		else {
			assertThat(result.lines()).anySatisfy(line -> assertThat(line.recoveredTokens()).isNotEmpty());
		}
		assertThat(received).containsEntry(":path", "/v1/ocr")
			.containsEntry(":method", "POST")
			.containsEntry("x-ocr-token", TOKEN)
			.containsEntry("content-type", "image/png")
			.containsEntry("x-request-id", "req-12345678-p1")
			.containsEntry("x-ocr-profile", "ar-en")
			.containsEntry("x-ocr-orientation", "auto");
	}

	@Test
	void responseForAnotherImageSizeIsRejected() throws IOException {
		byte[] body = Files.readAllBytes(FIXTURES.resolve("smoke-ar_sick_naskh__clean300.response.json"));
		responder = exchange -> send(exchange, 200, "application/json", body);

		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH - 1, HEIGHT, "req-12345678-p1"))
			.isInstanceOf(InvalidOcrResponseException.class)
			.hasMessageContaining("image size");
	}

	@Test
	void unknownContractVersionIsRejected() {
		responder = exchange -> send(exchange, 200, "application/json",
				"{\"contractVersion\":\"2\",\"lines\":[]}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r")).isInstanceOf(
				InvalidOcrResponseException.class);
	}

	@Test
	void oversizedResponseIsRejectedWithoutReadingItAll() {
		byte[] huge = new byte[200_000];
		java.util.Arrays.fill(huge, (byte) ' ');
		responder = exchange -> send(exchange, 200, "application/json", huge);

		assertThatThrownBy(() -> provider(DataSize.ofKilobytes(64), Duration.ofSeconds(5)).recognize(PNG, WIDTH, HEIGHT,
				"r"))
			.isInstanceOf(InvalidOcrResponseException.class)
			.hasMessageContaining("larger than");
	}

	@Test
	void statusCodesMapToTheDocumentedFailures() {
		responder = problem(413, "IMAGE_TOO_LARGE");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOfSatisfying(OcrPageRejectedException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("IMAGE_TOO_LARGE"));

		responder = problem(400, "invalid code with spaces");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.as("codes from the service are only kept when well-formed")
			.isInstanceOfSatisfying(OcrPageRejectedException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("OCR_IMAGE_REJECTED"));

		responder = problem(401, "AUTHENTICATION_REQUIRED");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOf(OcrConfigurationException.class);

		responder = problem(415, "UNSUPPORTED_MEDIA_TYPE");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOf(OcrConfigurationException.class);

		responder = problem(500, "OCR_ENGINE_ERROR");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r")).isInstanceOfSatisfying(
				OcrEngineException.class, ex -> assertThat(ex.getCode()).isEqualTo("OCR_ENGINE_ERROR"));

		responder = problem(503, "OCR_TIMEOUT");
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.as("a page over the service's time limit fails that page, not the job")
			.isInstanceOfSatisfying(OcrEngineException.class, ex -> assertThat(ex.getCode()).isEqualTo("OCR_TIMEOUT"));

		responder = exchange -> {
			exchange.getResponseHeaders().add("Retry-After", "5");
			problem(503, "SERVICE_BUSY").accept(exchange);
		};
		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOfSatisfying(OcrUnavailableException.class, ex -> {
				assertThat(ex.getCode()).isEqualTo("SERVICE_BUSY");
				assertThat(ex.getRetryAfter()).isEqualTo(Duration.ofSeconds(5));
			});
	}

	@Test
	void redirectsAreNotFollowed() {
		responder = exchange -> {
			exchange.getResponseHeaders().add("Location", "http://example.invalid/steal");
			send(exchange, 302, "text/plain", new byte[0]);
		};

		assertThatThrownBy(() -> provider().recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOfSatisfying(OcrUnavailableException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("OCR_UNEXPECTED_STATUS_302"));
	}

	@Test
	void slowServiceTimesOut() {
		responder = exchange -> {
			try {
				Thread.sleep(3000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		};

		assertThatThrownBy(() -> provider(DataSize.ofMegabytes(5), Duration.ofMillis(300)).recognize(PNG, WIDTH, HEIGHT,
				"r"))
			.isInstanceOfSatisfying(OcrUnavailableException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("OCR_TIMEOUT"));
	}

	@Test
	void unreachableServiceIsUnavailable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		HttpOcrProvider provider = new HttpOcrProvider(properties(URI.create("http://127.0.0.1:" + closedPort),
				DataSize.ofMegabytes(5), Duration.ofSeconds(2)), JsonMapper.builder().build());

		assertThatThrownBy(() -> provider.recognize(PNG, WIDTH, HEIGHT, "r"))
			.isInstanceOfSatisfying(OcrUnavailableException.class,
					ex -> assertThat(ex.getCode()).isEqualTo("OCR_SERVICE_UNAVAILABLE"));
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private HttpOcrProvider provider() {
		return provider(DataSize.ofMegabytes(5), Duration.ofSeconds(5));
	}

	private HttpOcrProvider provider(DataSize maxResponse, Duration timeout) {
		URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		return new HttpOcrProvider(properties(base, maxResponse, timeout), JsonMapper.builder().build());
	}

	private static OcrProperties properties(URI base, DataSize maxResponse, Duration timeout) {
		return new OcrProperties(true, base, TOKEN, Duration.ofSeconds(2), timeout, maxResponse, 2000, 5000, 64, 300,
				40_000_000L, DataSize.ofMegabytes(30), 4, 32, "auto", 3);
	}

	private static Consumer<HttpExchange> problem(int status, String code) {
		return exchange -> send(exchange, status, "application/problem+json",
				("{\"status\":" + status + ",\"errorCode\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8));
	}

	private static void send(HttpExchange exchange, int status, String contentType, byte[] body) {
		try {
			exchange.getResponseHeaders().add("Content-Type", contentType);
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(body);
				}
			}
		}
		catch (IOException ex) {
			// the client may have given up (size limit, timeout)
		}
	}

}
