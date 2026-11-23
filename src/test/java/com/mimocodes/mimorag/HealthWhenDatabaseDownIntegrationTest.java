package com.mimocodes.mimorag;

import com.mimocodes.mimorag.support.MimoRagPostgres;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the health endpoint reports the database accurately: when PostgreSQL becomes unavailable, health and
 * readiness turn DOWN (HTTP 503) while liveness stays UP, so an orchestrator stops routing traffic instead of
 * restarting a healthy JVM. Uses a dedicated container because the test stops it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class HealthWhenDatabaseDownIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = MimoRagPostgres.newContainer();

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		MimoRagPostgres.registerConnectionProperties(POSTGRES, registry);
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	void reportsDownWhenDatabaseIsUnavailable() throws Exception {
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());

		POSTGRES.stop();

		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"))
			.andExpect(jsonPath("$.components.db.status").value("DOWN"));
		mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
		mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
	}

}
