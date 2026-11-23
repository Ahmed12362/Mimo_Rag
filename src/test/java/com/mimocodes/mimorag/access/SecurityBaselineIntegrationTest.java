package com.mimocodes.mimorag.access;

import com.mimocodes.mimorag.shared.observability.CorrelationId;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityBaselineIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1/integrations", "/api/v1/does-not-exist", "/actuator/env",
			"/actuator/heapdump", "/actuator/metrics", "/v3/api-docs", "/swagger-ui.html", "/" })
	void everythingExceptHealthAndInfoIsDeniedByDefault(String path) throws Exception {
		mockMvc.perform(get(path))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
			.andExpect(jsonPath("$.correlationId").isNotEmpty())
			.andExpect(jsonPath("$.status").value(401));
	}

	@Test
	void unknownAndExistingProtectedPathsAreIndistinguishable() throws Exception {
		String existing = mockMvc.perform(get("/actuator/env")).andReturn().getResponse().getContentAsString();
		String unknown = mockMvc.perform(get("/api/v1/nothing-here")).andReturn().getResponse().getContentAsString();

		assertThat(stripVolatileFields(existing))
			.isEqualTo(stripVolatileFields(unknown));
	}

	@Test
	void writesAreDeniedWithoutCsrfOrSessionSideEffects() throws Exception {
		mockMvc.perform(post("/api/v1/integrations").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void suppliedCorrelationIdIsPropagatedToErrorResponses() throws Exception {
		mockMvc.perform(get("/api/v1/anything").header(CorrelationId.HEADER_NAME, "client-trace-12345"))
			.andExpect(header().string(CorrelationId.HEADER_NAME, "client-trace-12345"))
			.andExpect(jsonPath("$.correlationId").value("client-trace-12345"));
	}

	@Test
	void securityHeadersArePresent() throws Exception {
		mockMvc.perform(get("/actuator/health"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("X-Frame-Options", "DENY"))
			.andExpect(header().exists("Cache-Control"));
	}

	private static String stripVolatileFields(String json) {
		return json.replaceAll("\"(correlationId|timestamp)\":\"[^\"]*\"", "");
	}

}
