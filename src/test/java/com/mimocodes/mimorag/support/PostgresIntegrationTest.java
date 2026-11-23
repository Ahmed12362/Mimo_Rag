package com.mimocodes.mimorag.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests that need the full application context and a real PostgreSQL database.
 * One container is shared by all subclasses (singleton pattern) to keep the suite fast; Ryuk removes it when
 * the JVM exits.
 */
@SpringBootTest
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
