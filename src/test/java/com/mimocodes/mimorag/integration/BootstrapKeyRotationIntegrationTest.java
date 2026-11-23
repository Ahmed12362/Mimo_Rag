package com.mimocodes.mimorag.integration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ApiTestClient.IssuedKey;
import com.mimocodes.mimorag.support.ContextualJdbc;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-0012: admins hand integrations over with single-use bootstrap keys; integrations create, rotate and revoke
 * their own keys within the admin-defined scope ceiling, with mandatory expiry.
 */
class BootstrapKeyRotationIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private ApiTestClient api;

	private UUID integrationId;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("boot"),
				List.of("KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD"));
	}

	@Test
	void bootstrapKeyIsExchangedOnceForAStandardKeyAndThenRevoked() throws Exception {
		IssuedKey bootstrap = api.issueBootstrapKey(integrationId);

		JsonNode created = api.readJson(api.withKey(post("/api/v1/integration/api-keys"), bootstrap.plaintext())
			.content(api.toJson(Map.of("name", "hris-backend", "scopes", List.of("KB_MANAGE", "KEY_ROTATE")))), 201);
		String standardKey = created.get("plaintextKey").asString();
		JsonNode key = created.get("key");
		assertThat(key.get("keyType").asString()).isEqualTo("STANDARD");
		assertThat(key.get("createdBy").asString()).isEqualTo("api-key:" + bootstrap.keyId());
		Instant createdAt = Instant.parse(key.get("createdAt").asString());
		assertThat(Instant.parse(key.get("expiresAt").asString())).isEqualTo(createdAt.plus(Duration.ofDays(90)));

		mockMvc.perform(api.withKey(get("/api/v1/integration"), standardKey)).andExpect(status().isOk());
		// Single use: the bootstrap key is revoked by the exchange.
		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), bootstrap.plaintext())
			.content(api.toJson(Map.of("name", "again", "scopes", List.of("KB_READ")))))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(api.admin(get("/api/v1/admin/integrations/{id}/api-keys", integrationId)))
			.andExpect(jsonPath("$[?(@.keyId == '" + bootstrap.keyId() + "')].status").value("REVOKED"));
	}

	@Test
	void bootstrapKeyNeverReachesTenantContentOrActsForUsers() throws Exception {
		String bootstrap = api.issueBootstrapKey(integrationId).plaintext();

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases"), bootstrap)).andExpect(status().isUnauthorized());
		mockMvc.perform(api.withKey(get("/api/v1/integration"), bootstrap)).andExpect(status().isUnauthorized());
		mockMvc.perform(api.withKey(get("/api/v1/integration/api-keys"), bootstrap))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(api.asUser(post("/api/v1/integration/api-keys"), bootstrap, "any.user.assertion")
			.content(api.toJson(Map.of("name", "x", "scopes", List.of("KB_READ")))))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void bootstrapKeyCarriesAnExpiryWarning() throws Exception {
		String bootstrap = api.issueBootstrapKey(integrationId).plaintext();

		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), bootstrap)
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ")))))
			.andExpect(status().isCreated())
			.andExpect(header().exists("X-Api-Key-Expires-At"));
	}

	@Test
	void concurrentUseOfOneBootstrapKeyYieldsExactlyOneNewKey() throws Exception {
		String bootstrap = api.issueBootstrapKey(integrationId).plaintext();
		Callable<Integer> exchange = () -> mockMvc
			.perform(api.withKey(post("/api/v1/integration/api-keys"), bootstrap)
				.content(api.toJson(Map.of("name", "race", "scopes", List.of("KB_READ")))))
			.andReturn()
			.getResponse()
			.getStatus();

		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			List<Future<Integer>> results = pool.invokeAll(List.of(exchange, exchange, exchange, exchange));
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
		}

		assertThat(statuses).containsOnlyOnce(201);
		assertThat(statuses).allMatch(code -> code == 201 || code == 409 || code == 401);
		Integer standardKeys = new ContextualJdbc(jdbcTemplate, transactionTemplate).as(
				AccessContext.platformAdmin("t"),
				jdbc -> jdbc.queryForObject(
						"select count(*) from integration_api_key where integration_id = ? and key_type = 'STANDARD'",
						Integer.class, integrationId));
		assertThat(standardKeys).isEqualTo(1);
	}

	@Test
	void scopesMustStayWithinTheAdminDefinedCeiling() throws Exception {
		String bootstrap = api.issueBootstrapKey(integrationId).plaintext();

		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), bootstrap)
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ", "TOOL_EXECUTE")))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0].field").value("scopes"));
	}

	@Test
	void loweringTheCeilingTakesEffectImmediatelyForExistingKeys() throws Exception {
		String key = api.issueKey(integrationId, "KB_MANAGE").plaintext();
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases"), key)).andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0));
		api.createKnowledgeBase(key, "ceiling-kb");

		mockMvc.perform(api.admin(put("/api/v1/admin/integrations/{id}/allowed-scopes", integrationId))
			.content(api.toJson(Map.of("allowedScopes", List.of("DOCUMENT_UPLOAD")))))
			.andExpect(status().isOk());

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases"), key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void expiryIsMandatoryAndBounded() throws Exception {
		String rotator = api.issueKey(integrationId, "KEY_ROTATE").plaintext();

		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), rotator)
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ"), "validityDays", 400))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0].field").value("validityDays"));
		JsonNode shortLived = api.readJson(api.withKey(post("/api/v1/integration/api-keys"), rotator)
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ"), "validityDays", 10))), 201);

		mockMvc.perform(api.withKey(get("/api/v1/integration"), shortLived.get("plaintextKey").asString()))
			.andExpect(status().isOk())
			.andExpect(header().string("X-Api-Key-Expires-At", shortLived.get("key").get("expiresAt").asString()));
	}

	@Test
	void overlappingKeysWorkDuringRotationAndOldKeyCanBeRevoked() throws Exception {
		String oldKey = api.issueKey(integrationId, "KB_READ", "KEY_ROTATE").plaintext();
		IssuedKey newKey = api.createKey(oldKey, List.of("KB_READ", "KEY_ROTATE"), null);

		mockMvc.perform(api.withKey(get("/api/v1/integration"), oldKey)).andExpect(status().isOk());
		mockMvc.perform(api.withKey(get("/api/v1/integration"), newKey.plaintext())).andExpect(status().isOk());

		String oldKeyId = oldKey.split("_")[1];
		mockMvc.perform(api.withKey(delete("/api/v1/integration/api-keys/{keyId}", oldKeyId), newKey.plaintext()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("REVOKED"));
		mockMvc.perform(api.withKey(get("/api/v1/integration"), oldKey)).andExpect(status().isUnauthorized());
	}

	@Test
	void keyManagementRequiresKeyRotateScopeAndIsNeverAvailableToEndUsers() throws Exception {
		String contentOnly = api.issueKey(integrationId, "KB_READ").plaintext();

		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), contentOnly)
			.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ")))))
			.andExpect(status().isForbidden());
		mockMvc.perform(api.withKey(get("/api/v1/integration/api-keys"), contentOnly))
			.andExpect(status().isForbidden());
	}

	@Test
	void keysOfOtherIntegrationsCannotBeRevoked() throws Exception {
		UUID other = api.createIntegration(ApiTestClient.uniqueCode("other"));
		IssuedKey foreign = api.issueKey(other, "KB_READ");
		String rotator = api.issueKey(integrationId, "KEY_ROTATE").plaintext();

		mockMvc.perform(api.withKey(delete("/api/v1/integration/api-keys/{keyId}", foreign.keyId()), rotator))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.withKey(get("/api/v1/integration"), foreign.plaintext())).andExpect(status().isOk());
	}

	@Test
	void keyCreationIsAuditedWithTheCreatingKey() throws Exception {
		IssuedKey bootstrap = api.issueBootstrapKey(integrationId);
		IssuedKey created = api.createKey(bootstrap.plaintext(), List.of("KB_READ"), null);

		List<Map<String, Object>> rows = new ContextualJdbc(jdbcTemplate, transactionTemplate)
			.as(AccessContext.platformAdmin("t"), jdbc -> jdbc.queryForList(
					"select action, api_key_id from audit_event where target_id in (?, ?) order by occurred_at",
					created.keyId(), bootstrap.keyId()));
		assertThat(rows).extracting(row -> row.get("action"))
			.contains("BOOTSTRAP_KEY_ISSUED", "BOOTSTRAP_KEY_CONSUMED", "API_KEY_CREATED");
		assertThat(rows).filteredOn(row -> "API_KEY_CREATED".equals(row.get("action")))
			.extracting(row -> row.get("api_key_id"))
			.containsExactly(bootstrap.keyId());
	}

}
