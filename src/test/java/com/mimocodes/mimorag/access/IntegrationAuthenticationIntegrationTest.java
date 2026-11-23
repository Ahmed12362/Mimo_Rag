package com.mimocodes.mimorag.access;

import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ApiTestClient.IssuedKey;
import com.mimocodes.mimorag.support.ContextualJdbc;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Service-credential (API key) authentication on the integration plane. */
class IntegrationAuthenticationIntegrationTest extends PostgresIntegrationTest {

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

	private UUID integrationId;

	private IssuedKey key;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		db = new ContextualJdbc(jdbcTemplate, transactionTemplate);
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("auth"));
		key = api.issueKey(integrationId, "KB_MANAGE");
	}

	@Test
	void validKeyAuthenticatesAsItsOwnIntegration() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(integrationId.toString()));
	}

	@Test
	void successfulUseIsRecorded() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isOk());

		Object lastUsed = db.as(AccessContext.platformAdmin("t"), jdbc -> jdbc.queryForObject(
				"select last_used_at from integration_api_key where key_id = ?", Object.class, key.keyId()));
		assertThat(lastUsed).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "garbage", "Bearer token", "mrk_000000000000000000000000_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
	void malformedOrUnknownKeysAreRejected(String presented) throws Exception {
		String body = rejectedBody(presented);
		assertThat(body).contains("AUTHENTICATION_REQUIRED");
	}

	@Test
	void wrongSecretForExistingKeyIsRejected() throws Exception {
		String tampered = key.plaintext().substring(0, key.plaintext().length() - 1)
				+ (key.plaintext().endsWith("A") ? "B" : "A");

		mockMvc.perform(api.withKey(get("/api/v1/integration"), tampered)).andExpect(status().isUnauthorized());
	}

	@Test
	void revokedKeyIsRejected() throws Exception {
		api.revokeKey(integrationId, key.keyId());

		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isUnauthorized());
	}

	@Test
	void expiredKeyIsRejected() throws Exception {
		db.as(AccessContext.platformAdmin("t"), jdbc -> jdbc.update(
				"update integration_api_key set created_at = now() - interval '2 days', "
						+ "expires_at = now() - interval '1 minute' where key_id = ?",
				key.keyId()));

		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isUnauthorized());
	}

	@Test
	void suspendedIntegrationIsRejectedAndCanBeReactivated() throws Exception {
		api.changeStatus(integrationId, "SUSPENDED");
		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isUnauthorized());

		api.changeStatus(integrationId, "ACTIVE");
		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isOk());
	}

	@Test
	void allRejectionCausesProduceTheSameResponseBody() throws Exception {
		IssuedKey revoked = api.issueKey(integrationId, "KB_READ");
		api.revokeKey(integrationId, revoked.keyId());

		String unknown = normalize(rejectedBody("mrk_111111111111111111111111_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
		String malformed = normalize(rejectedBody("not-a-key"));
		String revokedBody = normalize(rejectedBody(revoked.plaintext()));

		assertThat(unknown).isEqualTo(malformed).isEqualTo(revokedBody);
	}

	@Test
	void failedAuthenticationOfKnownKeyIsAuditedWithoutSecrets() throws Exception {
		api.revokeKey(integrationId, key.keyId());
		mockMvc.perform(api.withKey(get("/api/v1/integration"), key.plaintext())).andExpect(status().isUnauthorized());

		String details = db.as(AccessContext.platformAdmin("t"), jdbc -> jdbc.queryForObject(
				"select details::text from audit_event where action = 'AUTHENTICATION_FAILED' and target_id = ? "
						+ "order by occurred_at desc limit 1",
				String.class, key.keyId()));
		assertThat(details).contains("KEY_REVOKED").doesNotContain(key.plaintext());
	}

	private String rejectedBody(String presentedKey) throws Exception {
		return mockMvc.perform(api.withKey(get("/api/v1/integration"), presentedKey))
			.andExpect(status().isUnauthorized())
			.andReturn()
			.getResponse()
			.getContentAsString();
	}

	private static String normalize(String json) {
		return json.replaceAll("\"(correlationId|timestamp)\":\"[^\"]*\"", "");
	}

}
