package com.mimocodes.mimorag.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ApiTestClient.IssuedKey;
import com.mimocodes.mimorag.support.ContextualJdbc;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminIntegrationApiIntegrationTest extends PostgresIntegrationTest {

	private static final AccessContext ADMIN = AccessContext.platformAdmin("test");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private ApiTestClient api;

	private ContextualJdbc db;

	@BeforeEach
	void setUp() {
		api = new ApiTestClient(mockMvc, jsonMapper);
		db = new ContextualJdbc(jdbcTemplate, transactionTemplate);
	}

	@Test
	void adminRegistersIntegrationAndReadsItBack() throws Exception {
		String code = ApiTestClient.uniqueCode("hris");
		JsonNode created = api.readJson(api.admin(post("/api/v1/admin/integrations"))
			.content(api.toJson(Map.of("code", code, "name", "HRIS", "description", "Human resources",
					"allowedScopes", List.of("KB_READ", "DOCUMENT_UPLOAD")))), 201);

		assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(created.get("allowedScopes").toString()).contains("KB_READ", "DOCUMENT_UPLOAD");
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations/{id}", created.get("id").asString())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.code").value(code));
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations?page=0&size=100")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items").isArray())
			.andExpect(jsonPath("$.size").value(100));
	}

	@Test
	void duplicateIntegrationCodeIsAConflict() throws Exception {
		String code = ApiTestClient.uniqueCode("crm");
		api.createIntegration(code);

		mockMvc.perform(api.admin(post("/api/v1/admin/integrations"))
			.content(api.toJson(Map.of("code", code, "name", "again", "allowedScopes", List.of("KB_READ")))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("CONFLICT"));
	}

	@Test
	void invalidRegistrationIsRejectedWithViolations() throws Exception {
		mockMvc.perform(api.admin(post("/api/v1/admin/integrations"))
			.content(api.toJson(Map.of("code", "Not Valid!", "name", ""))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.violations[?(@.field == 'code')]").exists())
			.andExpect(jsonPath("$.violations[?(@.field == 'name')]").exists());
	}

	@Test
	void oversizedPageIsRejected() throws Exception {
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations?size=1000")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0].field").value("size"));
	}

	@Test
	void unknownIntegrationIsNotFound() throws Exception {
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations/{id}", UUID.randomUUID())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"));
	}

	@Test
	void issuedKeyIsShownOnceAndOnlyItsHashIsStored() throws Exception {
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("erp"));
		IssuedKey key = api.issueKey(integrationId, "KB_MANAGE");
		String secret = ApiKeyCredential.parse(key.plaintext()).orElseThrow().secret();

		mockMvc.perform(api.admin(get("/api/v1/admin/integrations/{id}/api-keys", integrationId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].keyId").value(key.keyId()))
			.andExpect(jsonPath("$[0].scopes[0]").value("KB_MANAGE"))
			.andExpect(jsonPath("$[0].plaintextKey").doesNotExist())
			.andExpect(jsonPath("$[0].secretHash").doesNotExist())
			.andExpect(content().string(not(containsString(secret))));

		String storedHash = db.as(ADMIN, jdbc -> jdbc.queryForObject(
				"select secret_hash from integration_api_key where key_id = ?", String.class, key.keyId()));
		assertThat(storedHash).isEqualTo(ApiKeyCredential.hashSecret(secret)).doesNotContain(secret);
	}

	@Test
	void adminCanOnlyIssueShortLivedRotationOnlyBootstrapKeys() throws Exception {
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("hr"));

		JsonNode issued = api.readJson(api.admin(post("/api/v1/admin/integrations/{id}/bootstrap-keys", integrationId))
			.content(api.toJson(Map.of("name", "handover"))), 201);
		JsonNode key = issued.get("key");
		assertThat(key.get("keyType").asString()).isEqualTo("BOOTSTRAP");
		assertThat(key.get("scopes").toString()).isEqualTo("[\"KEY_ROTATE\"]");
		assertThat(Instant.parse(key.get("expiresAt").asString()))
			.isBefore(Instant.parse(key.get("createdAt").asString()).plus(Duration.ofHours(24).plusSeconds(1)));

		// There is no admin endpoint that creates content keys any more.
		mockMvc.perform(api.admin(post("/api/v1/admin/integrations/{id}/api-keys", integrationId))
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_MANAGE")))))
			.andExpect(status().isMethodNotAllowed());
	}

	@Test
	void scopeCeilingCannotContainKeyRotate() throws Exception {
		mockMvc.perform(api.admin(post("/api/v1/admin/integrations"))
			.content(api.toJson(Map.of("code", ApiTestClient.uniqueCode("x"), "name", "x", "allowedScopes",
					List.of("KB_READ", "KEY_ROTATE")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0].field").value("allowedScopes"));
	}

	@Test
	void adminPlaneRejectsMissingOrWrongAdminKey() throws Exception {
		mockMvc.perform(get("/api/v1/admin/integrations")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/integrations").header(ApiTestClient.ADMIN_HEADER, "wrong-key"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void integrationKeyIsNotAcceptedOnTheAdminPlane() throws Exception {
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("sys"));
		IssuedKey key = api.issueKey(integrationId, "KB_MANAGE");

		mockMvc.perform(get("/api/v1/admin/integrations").header(ApiTestClient.API_KEY_HEADER, key.plaintext()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/admin/integrations").header(ApiTestClient.ADMIN_HEADER, key.plaintext()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void adminKeyIsNotAcceptedOnTheIntegrationPlane() throws Exception {
		mockMvc.perform(api.admin(get("/api/v1/knowledge-bases"))).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/integration").header(ApiTestClient.API_KEY_HEADER, TestCredentials.ADMIN_KEY))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void adminActionsAreAudited() throws Exception {
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("aud"));
		IssuedKey bootstrap = api.issueBootstrapKey(integrationId);
		api.revokeKey(integrationId, bootstrap.keyId());

		List<String> actions = db.as(ADMIN,
				jdbc -> jdbc.queryForList("select action from audit_event where target_id in (?, ?) order by occurred_at",
						String.class, integrationId.toString(), bootstrap.keyId()));
		assertThat(actions).containsExactly("INTEGRATION_REGISTERED", "BOOTSTRAP_KEY_ISSUED", "API_KEY_REVOKED");
	}

	@Test
	void correlationIdIsReturnedOnAdminResponses() throws Exception {
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations")).header("X-Correlation-Id", "admin-trace-0001"))
			.andExpect(header().string("X-Correlation-Id", "admin-trace-0001"));
	}

}
