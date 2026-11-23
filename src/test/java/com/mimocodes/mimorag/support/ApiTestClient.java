package com.mimocodes.mimorag.support;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Thin helper over MockMvc for setting up integrations, keys and Knowledge Bases through the real HTTP API. */
public final class ApiTestClient {

	public static final String ADMIN_HEADER = "X-Admin-Key";

	public static final String API_KEY_HEADER = "X-Api-Key";

	public static final String USER_ASSERTION_HEADER = "X-User-Assertion";

	private final MockMvc mockMvc;

	private final JsonMapper json;

	public ApiTestClient(MockMvc mockMvc, JsonMapper json) {
		this.mockMvc = mockMvc;
		this.json = json;
	}

	public record IssuedKey(String keyId, String plaintext) {
	}

	public static String uniqueCode(String prefix) {
		return prefix + "-" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36);
	}

	// ---- admin plane ----------------------------------------------------------------------------------------

	public UUID createIntegration(String code) throws Exception {
		JsonNode body = readJson(admin(post("/api/v1/admin/integrations"))
			.content(toJson(Map.of("code", code, "name", "Integration " + code))), 201);
		return UUID.fromString(body.get("id").asString());
	}

	public IssuedKey issueKey(UUID integrationId, String... scopes) throws Exception {
		JsonNode body = readJson(admin(post("/api/v1/admin/integrations/{id}/api-keys", integrationId))
			.content(toJson(Map.of("name", "test key", "scopes", Arrays.asList(scopes)))), 201);
		return new IssuedKey(body.get("key").get("keyId").asString(), body.get("plaintextKey").asString());
	}

	public void revokeKey(UUID integrationId, String keyId) throws Exception {
		perform(admin(delete("/api/v1/admin/integrations/{id}/api-keys/{keyId}", integrationId, keyId)))
			.andExpect(status().isOk());
	}

	public void changeStatus(UUID integrationId, String newStatus) throws Exception {
		perform(admin(patch("/api/v1/admin/integrations/{id}/status", integrationId))
			.content(toJson(Map.of("status", newStatus)))).andExpect(status().isOk());
	}

	public void putTrustConfig(UUID integrationId, Map<String, Object> config) throws Exception {
		perform(admin(put("/api/v1/admin/integrations/{id}/trust-config", integrationId)).content(toJson(config)))
			.andExpect(status().isOk());
	}

	// ---- integration plane ----------------------------------------------------------------------------------

	public UUID createKnowledgeBase(String apiKey, String code) throws Exception {
		JsonNode body = readJson(withKey(post("/api/v1/knowledge-bases"), apiKey)
			.content(toJson(Map.of("code", code, "name", "KB " + code))), 201);
		return UUID.fromString(body.get("id").asString());
	}

	public void replaceGrants(String apiKey, UUID kbId, List<Map<String, String>> grants) throws Exception {
		perform(withKey(put("/api/v1/knowledge-bases/{id}/role-grants", kbId), apiKey)
			.content(toJson(Map.of("grants", grants)))).andExpect(status().isOk());
	}

	// ---- low-level ------------------------------------------------------------------------------------------

	public MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
		return request.header(ADMIN_HEADER, TestCredentials.ADMIN_KEY).contentType(MediaType.APPLICATION_JSON);
	}

	public MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder request, String apiKey) {
		return request.header(API_KEY_HEADER, apiKey).contentType(MediaType.APPLICATION_JSON);
	}

	public MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request, String apiKey,
			String assertion) {
		return withKey(request, apiKey).header(USER_ASSERTION_HEADER, assertion);
	}

	public ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request);
	}

	public JsonNode readJson(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
		String content = perform(request).andExpect(status().is(expectedStatus))
			.andReturn()
			.getResponse()
			.getContentAsString();
		return json.readTree(content);
	}

	public String toJson(Object value) {
		return json.writeValueAsString(value);
	}

}
