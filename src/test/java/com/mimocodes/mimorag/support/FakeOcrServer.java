package com.mimocodes.mimorag.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for the Python OCR service in the normal test suite. It speaks contract version 1 (same paths, headers,
 * status codes and JSON shape; see {@code src/test/resources/ocr/*.response.json} for real responses) but recognises
 * nothing: it answers with scripted lines whose boxes are fractions of the submitted image.
 * <p>
 * Scenarios are keyed by a prefix of {@code X-Request-Id}, which the application builds from the upload's correlation
 * id, so tests running against the shared server never consume each other's scripts. Requests without a script get a
 * single default Arabic line.
 */
public final class FakeOcrServer {

	/** Service-to-service token the test application is configured with (never used outside tests). */
	public static final String TOKEN = "fake-ocr-token-for-tests-only-0123456789abcdef";

	public enum Outcome {

		OK, ENGINE_ERROR, TIMEOUT, BUSY, REJECTED, UNAUTHORIZED, ALTERS_ARABIC, WRONG_SIZE, NOT_JSON,

		/** Blocks until {@link FakeOcrServer#release} for the prefix (at most 30 s), then answers 503. */
		HOLD

	}

	/** A line to return; the box is given as fractions of the submitted image's width and height. */
	public record Line(double fx, double fy, double fw, double fh, String originalText, String proposedText,
			List<String> recovered) {

		public static Line arabic(double fx, double fy, double fw, double fh) {
			return new Line(fx, fy, fw, fh, "سياسة الإجازات", "سياسة الإجازات 2026", List.of("2026"));
		}

	}

	/** What the server received. */
	public record Request(String requestId, boolean tokenValid, String contentType, String orientation,
			String profile, int width, int height, int bytes) {
	}

	private static final FakeOcrServer INSTANCE = new FakeOcrServer();

	private static final List<Line> DEFAULT_LINES = List.of(Line.arabic(0.1, 0.1, 0.8, 0.05));

	private record Script(List<Line> lines, Deque<Outcome> outcomes) {
	}

	private final HttpServer server;

	private final JsonMapper json = JsonMapper.builder().build();

	private final Map<String, Script> scripts = new ConcurrentHashMap<>();

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final Map<String, java.util.concurrent.CountDownLatch> holds = new ConcurrentHashMap<>();

	private FakeOcrServer() {
		try {
			server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		server.createContext("/v1/ocr", this::ocr);
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
	}

	public static FakeOcrServer instance() {
		return INSTANCE;
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Requests whose id starts with {@code prefix} get {@code lines}, after the outcomes in order (then OK). */
	public void script(String prefix, List<Line> lines, Outcome... outcomes) {
		scripts.put(prefix, new Script(List.copyOf(lines), new ArrayDeque<>(List.of(outcomes))));
	}

	public void script(String prefix, Outcome... outcomes) {
		script(prefix, DEFAULT_LINES, outcomes);
	}

	/** Lets requests held by {@link Outcome#HOLD} for this prefix continue. */
	public void release(String prefix) {
		holds.computeIfAbsent(prefix, key -> new java.util.concurrent.CountDownLatch(1)).countDown();
	}

	public List<Request> requests(String prefix) {
		return requests.stream().filter(r -> r.requestId() != null && r.requestId().startsWith(prefix)).toList();
	}

	// ---- protocol -------------------------------------------------------------------------------------------------

	private void ocr(HttpExchange exchange) throws IOException {
		try (exchange) {
			byte[] body;
			try (InputStream in = exchange.getRequestBody()) {
				body = in.readAllBytes();
			}
			String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
			boolean tokenValid = TOKEN.equals(exchange.getRequestHeaders().getFirst("X-OCR-Token"));
			int width = body.length >= 24 ? ByteBuffer.wrap(body, 16, 4).getInt() : -1;
			int height = body.length >= 24 ? ByteBuffer.wrap(body, 20, 4).getInt() : -1;
			requests.add(new Request(requestId, tokenValid, exchange.getRequestHeaders().getFirst("Content-Type"),
					exchange.getRequestHeaders().getFirst("X-OCR-Orientation"),
					exchange.getRequestHeaders().getFirst("X-OCR-Profile"), width, height, body.length));
			if (!"POST".equals(exchange.getRequestMethod())) {
				problem(exchange, 405, "METHOD_NOT_ALLOWED", requestId);
				return;
			}
			if (!tokenValid) {
				problem(exchange, 401, "AUTHENTICATION_REQUIRED", requestId);
				return;
			}
			if (body.length < 24 || body[1] != 'P' || body[2] != 'N' || body[3] != 'G') {
				problem(exchange, 415, "UNSUPPORTED_MEDIA_TYPE", requestId);
				return;
			}
			Script script = scriptFor(requestId);
			Outcome outcome = next(script);
			if (outcome == Outcome.HOLD) {
				String prefix = scripts.entrySet().stream().filter(e -> e.getValue() == script).findFirst()
					.map(Map.Entry::getKey).orElse("");
				try {
					holds.computeIfAbsent(prefix, key -> new java.util.concurrent.CountDownLatch(1))
						.await(30, java.util.concurrent.TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				outcome = Outcome.BUSY;
			}
			switch (outcome) {
				case ENGINE_ERROR -> problem(exchange, 500, "OCR_ENGINE_ERROR", requestId);
				case TIMEOUT -> problem(exchange, 503, "OCR_TIMEOUT", requestId);
				case BUSY -> {
					exchange.getResponseHeaders().add("Retry-After", "1");
					problem(exchange, 503, "SERVICE_BUSY", requestId);
				}
				case REJECTED -> problem(exchange, 413, "IMAGE_TOO_LARGE", requestId);
				case UNAUTHORIZED -> problem(exchange, 401, "AUTHENTICATION_REQUIRED", requestId);
				case NOT_JSON -> send(exchange, 200, "text/html", "<html>proxy error</html>".getBytes(StandardCharsets.UTF_8));
				default -> send(exchange, 200, "application/json",
						json.writeValueAsBytes(result(requestId, width, height, script.lines(), outcome)));
			}
		}
	}

	private Script scriptFor(String requestId) {
		if (requestId != null) {
			for (Map.Entry<String, Script> entry : scripts.entrySet()) {
				if (requestId.startsWith(entry.getKey())) {
					return entry.getValue();
				}
			}
		}
		return new Script(DEFAULT_LINES, new ArrayDeque<>());
	}

	private static Outcome next(Script script) {
		synchronized (script.outcomes()) {
			return script.outcomes().isEmpty() ? Outcome.OK : script.outcomes().poll();
		}
	}

	private Map<String, Object> result(String requestId, int width, int height, List<Line> lines, Outcome outcome) {
		List<Map<String, Object>> out = new ArrayList<>();
		int row = 0;
		int recovered = 0;
		for (Line line : lines) {
			Map<String, Object> l = new LinkedHashMap<>();
			l.put("box", Map.of("x", (int) Math.floor(line.fx() * width), "y", (int) Math.floor(line.fy() * height),
					"width", (int) Math.floor(line.fw() * width), "height", (int) Math.floor(line.fh() * height)));
			l.put("direction", "RTL");
			l.put("confidence", 0.91);
			l.put("originalText", line.originalText());
			l.put("proposedText", outcome == Outcome.ALTERS_ARABIC ? "سياسة مختلفة" : line.proposedText());
			l.put("recoveredTokens", line.recovered()
				.stream()
				.map(token -> Map.of("token", token, "offset", line.originalText().length(), "sources",
						List.of("tesseract", "paddle-en"), "anchor", "END"))
				.toList());
			l.put("unplacedTokens", List.of());
			l.put("possiblyMissingTokens", List.of());
			l.put("flags", line.recovered().isEmpty() ? List.of() : List.of("TOKEN_RECOVERED"));
			l.put("row", row++);
			recovered += line.recovered().size();
			out.add(l);
		}
		Map<String, Object> orientation = new LinkedHashMap<>();
		orientation.put("checked", false);
		orientation.put("applied", 0);
		orientation.put("classifierConfidence", null);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("contractVersion", "1");
		result.put("requestId", requestId);
		result.put("engine", Map.of("pipeline", "fake/1"));
		result.put("image", Map.of("width", outcome == Outcome.WRONG_SIZE ? width + 1 : width, "height", height));
		result.put("orientation", orientation);
		result.put("lines", out);
		result.put("summary", Map.of("lines", out.size(), "characters", 13 * out.size(), "meanConfidence",
				out.isEmpty() ? 0.0 : 0.91, "lowConfidenceLines", 0, "recoveredTokens", recovered, "unplacedTokens", 0,
				"possiblyMissingTokens", 0));
		result.put("warnings", recovered > 0 ? List.of("TOKEN_RECOVERED") : List.of());
		result.put("timings", Map.of("ocrMs", 1, "totalMs", 1));
		return result;
	}

	private void problem(HttpExchange exchange, int status, String code, String requestId) throws IOException {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "about:blank");
		body.put("title", code);
		body.put("status", status);
		body.put("errorCode", code);
		body.put("requestId", requestId);
		send(exchange, status, "application/problem+json", json.writeValueAsBytes(body));
	}

	private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
		exchange.getResponseHeaders().add("Content-Type", contentType);
		exchange.sendResponseHeaders(status, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

}
