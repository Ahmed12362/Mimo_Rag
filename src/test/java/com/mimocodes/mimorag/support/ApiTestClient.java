package com.mimocodes.mimorag.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

	/** Scope ceiling used for test integrations: every grantable scope. */
	public static final List<String> ALL_SCOPES = List.of("KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD",
			"DOCUMENT_REVIEW", "DOCUMENT_SUBMIT", "TOOL_EXECUTE", "TOOL_CATALOG_MANAGE");

	public UUID createIntegration(String code) throws Exception {
		return createIntegration(code, ALL_SCOPES);
	}

	public UUID createIntegration(String code, List<String> allowedScopes) throws Exception {
		JsonNode body = readJson(admin(post("/api/v1/admin/integrations"))
			.content(toJson(Map.of("code", code, "name", "Integration " + code, "allowedScopes", allowedScopes))),
				201);
		return UUID.fromString(body.get("id").asString());
	}

	/** Admin hand-over: a bootstrap key (KEY_ROTATE only, 24h, single use). */
	public IssuedKey issueBootstrapKey(UUID integrationId) throws Exception {
		JsonNode body = readJson(admin(post("/api/v1/admin/integrations/{id}/bootstrap-keys", integrationId))
			.content(toJson(Map.of("name", "bootstrap"))), 201);
		return new IssuedKey(body.get("key").get("keyId").asString(), body.get("plaintextKey").asString());
	}

	/** The integration creates one of its own keys with a key holding KEY_ROTATE. */
	public IssuedKey createKey(String callerKey, List<String> scopes, List<UUID> knowledgeBaseIds) throws Exception {
		Map<String, Object> request = new HashMap<>();
		request.put("name", "test key");
		request.put("scopes", scopes);
		if (knowledgeBaseIds != null) {
			request.put("knowledgeBaseIds", knowledgeBaseIds);
		}
		JsonNode body = readJson(withKey(post("/api/v1/integration/api-keys"), callerKey).content(toJson(request)),
				201);
		return new IssuedKey(body.get("key").get("keyId").asString(), body.get("plaintextKey").asString());
	}

	/** Realistic flow: admin issues a bootstrap key, the integration exchanges it for a standard key. */
	public IssuedKey issueKey(UUID integrationId, String... scopes) throws Exception {
		return createKey(issueBootstrapKey(integrationId).plaintext(), Arrays.asList(scopes), null);
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

	public void archiveKnowledgeBase(String apiKey, UUID kbId) throws Exception {
		JsonNode kb = readJson(withKey(get("/api/v1/knowledge-bases/{id}", kbId), apiKey), 200);
		perform(withKey(patch("/api/v1/knowledge-bases/{id}", kbId), apiKey)
			.content(toJson(Map.of("version", kb.get("version").asLong(), "status", "ARCHIVED"))))
			.andExpect(status().isOk());
	}

	// ---- documents ------------------------------------------------------------------------------------------

	/** Multipart upload request (no JSON content type; the builder sets multipart/form-data). */
	public MockMultipartHttpServletRequestBuilder upload(String apiKey, UUID kbId, String filename, byte[] content) {
		return multipart("/api/v1/knowledge-bases/{id}/documents", kbId)
			.file(new MockMultipartFile("file", filename, "application/pdf", content))
			.header(API_KEY_HEADER, apiKey);
	}

	/** Uploads and expects 202; returns the response body. */
	public JsonNode uploadAccepted(String apiKey, UUID kbId, byte[] content) throws Exception {
		return readJson(upload(apiKey, kbId, "test.pdf", content), 202);
	}

	/**
	 * Uploads with a caller-chosen correlation id and expects 202. Background jobs inherit the id, and the OCR
	 * requests carry it, which lets {@link FakeOcrServer} scripts target one upload.
	 */
	public JsonNode uploadAccepted(String apiKey, UUID kbId, byte[] content, String correlationId) throws Exception {
		return readJson(upload(apiKey, kbId, "test.pdf", content).header("X-Correlation-Id", correlationId), 202);
	}

	/** Polls an ingestion job until it reaches a final state, or fails the test after {@code timeout}. */
	public JsonNode awaitJob(String apiKey, String jobId, Duration timeout) throws Exception {
		Instant deadline = Instant.now().plus(timeout);
		JsonNode job;
		do {
			job = readJson(withKey(get("/api/v1/ingestion-jobs/{id}", jobId), apiKey), 200);
			String status = job.get("status").asString();
			if (status.equals("SUCCEEDED") || status.equals("FAILED") || status.equals("CANCELLED_REVALIDATION")) {
				return job;
			}
			Thread.sleep(100);
		}
		while (Instant.now().isBefore(deadline));
		throw new AssertionError("Job " + jobId + " did not finish in " + timeout + "; last state: " + job);
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

	public ResultActions perform(RequestBuilder request) throws Exception {
		return mockMvc.perform(request);
	}

	public JsonNode readJson(RequestBuilder request, int expectedStatus) throws Exception {
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
