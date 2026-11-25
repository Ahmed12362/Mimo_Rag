package com.mimocodes.mimorag.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need the full application context and a real PostgreSQL database.
 * One container is shared by all subclasses (singleton pattern) to keep the suite fast; Ryuk removes it when
 * the JVM exits. All subclasses use identical properties so the Spring context is cached and shared too.
 * <p>
 * {@code allow-insecure-jwks} is enabled only because the tests serve JWKS from a local HTTP server; the https
 * requirement itself is covered by unit tests. OCR is enabled against {@link FakeOcrServer}.
 */
@SpringBootTest(properties = { "mimorag.security.admin-key-sha256=" + TestCredentials.ADMIN_KEY_SHA256,
		"mimorag.security.trust.allow-insecure-jwks=true", "mimorag.security.trust.allow-inline-jwks=false",
		// Short key-cache timings so JWKS rotation tests run in seconds.
		"mimorag.security.assertion.jwks-cache-ttl=PT2S",
		"mimorag.security.assertion.jwks-refresh-min-interval=PT1S",
		// Uploaded test files stay inside the build directory; fast job polling keeps worker tests short.
		"mimorag.ingestion.storage-root=target/test-storage", "mimorag.jobs.poll-interval=PT0.2S",
		// Transient failures retry within a second instead of 30 s.
		"mimorag.jobs.retry-base-delay=PT0.2S", "mimorag.jobs.retry-max-delay=PT1S",
		// OCR against the in-process fake service (FakeOcrServer); the real engine runs only in the ocr-it suite.
		"mimorag.ocr.enabled=true" })
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

	protected static final PostgreSQLContainer POSTGRES = MimoRagPostgres.newContainer();

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		MimoRagPostgres.registerConnectionProperties(POSTGRES, registry);
		// The opt-in benchmark (-Pocr-bench) points the same context at a real OCR container instead.
		String realOcr = System.getProperty("ocr.bench.base-url");
		if (realOcr != null) {
			registry.add("mimorag.ocr.base-url", () -> realOcr);
			registry.add("mimorag.ocr.token", () -> readToken(System.getProperty("ocr.bench.token-file")));
		}
		else {
			registry.add("mimorag.ocr.base-url", FakeOcrServer.instance()::baseUrl);
			registry.add("mimorag.ocr.token", () -> FakeOcrServer.TOKEN);
		}
	}

	private static String readToken(String file) {
		try {
			return java.nio.file.Files.readString(java.nio.file.Path.of(file)).strip();
		}
		catch (java.io.IOException | NullPointerException ex) {
			throw new IllegalStateException("-Docr.bench.token-file must name a readable token file", ex);
		}
	}

}
