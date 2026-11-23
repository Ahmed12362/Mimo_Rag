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
 * requirement itself is covered by unit tests.
 */
@SpringBootTest(properties = { "mimorag.security.admin-key-sha256=" + TestCredentials.ADMIN_KEY_SHA256,
		"mimorag.security.trust.allow-insecure-jwks=true", "mimorag.security.trust.allow-inline-jwks=false",
		// Short key-cache timings so JWKS rotation tests run in seconds.
		"mimorag.security.assertion.jwks-cache-ttl=PT2S",
		"mimorag.security.assertion.jwks-refresh-min-interval=PT1S" })
@AutoConfigureMockMvc
public abstract class PostgresIntegrationTest {

	protected static final PostgreSQLContainer POSTGRES = MimoRagPostgres.newContainer();

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		MimoRagPostgres.registerConnectionProperties(POSTGRES, registry);
	}

}
