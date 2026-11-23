package com.mimocodes.mimorag;

import com.mimocodes.mimorag.shared.observability.CorrelationId;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HealthEndpointIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthIsPublicAndReportsDatabaseComponent() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"))
			.andExpect(header().string(CorrelationId.HEADER_NAME, not(emptyOrNullString())));
	}

	@Test
	void healthDoesNotExposeInternalDetailsAnonymously() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(jsonPath("$.components.db.details").doesNotExist())
			.andExpect(jsonPath("$.components.diskSpace.details").doesNotExist());
	}

	@Test
	void readinessProbeIncludesDatabase() throws Exception {
		mockMvc.perform(get("/actuator/health/readiness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"))
			.andExpect(jsonPath("$.components.db.status").value("UP"));
	}

	@Test
	void livenessProbeIsAvailable() throws Exception {
		mockMvc.perform(get("/actuator/health/liveness"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("UP"));
	}

}
