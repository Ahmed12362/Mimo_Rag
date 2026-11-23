package com.mimocodes.mimorag.access;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ContextualJdbc;
import com.mimocodes.mimorag.support.JwksTestServer;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestTokens;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End users of connected systems: signed user assertions verified against the integration's JWKS, mapped roles,
 * KB grants, and the intersection with API-key scopes.
 */
class UserAssertionIntegrationTest extends PostgresIntegrationTest {

	private static final String AUDIENCE = "mimorag";

	private static final RSAKey HRIS_RSA = TestTokens.newRsaKey();

	private static final ECKey HRIS_EC = TestTokens.newEcKey();

	private static final RSAKey CRM_RSA = TestTokens.newRsaKey();

	private static final RSAKey UNPUBLISHED_RSA = TestTokens.newRsaKey();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private ApiTestClient api;

	private String hrisIssuer;

	private String relayKey;

	private String narrowKey;

	private String crmKey;

	private String crmIssuer;

	private UUID hrisId;

	private UUID policiesKb;

	private UUID payrollKb;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		String hrisCode = ApiTestClient.uniqueCode("hris");
		hrisId = api.createIntegration(hrisCode);
		hrisIssuer = "https://" + hrisCode + ".example.com";
		api.putTrustConfig(hrisId, trustConfig(hrisIssuer,
				JwksTestServer.instance().publish(hrisCode, TestTokens.publicJwks(HRIS_RSA, HRIS_EC))));
		relayKey = api.issueKey(hrisId, "KB_READ", "KB_MANAGE").plaintext();
		narrowKey = api.issueKey(hrisId, "KB_READ").plaintext();

		policiesKb = api.createKnowledgeBase(relayKey, "policies");
		payrollKb = api.createKnowledgeBase(relayKey, "payroll");
		api.replaceGrants(relayKey, policiesKb, List.of(Map.of("role", "hr-reader", "permission", "KB_READ"),
				Map.of("role", "hr-admin", "permission", "KB_MANAGE")));

		String crmCode = ApiTestClient.uniqueCode("crm");
		UUID crmId = api.createIntegration(crmCode);
		crmIssuer = "https://" + crmCode + ".example.com";
		api.putTrustConfig(crmId,
				trustConfig(crmIssuer, JwksTestServer.instance().publish(crmCode, TestTokens.publicJwks(CRM_RSA))));
		crmKey = api.issueKey(crmId, "KB_READ", "KB_MANAGE").plaintext();
	}

	@Test
	void rs256UserSeesOnlyGrantedKnowledgeBases() throws Exception {
		String token = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-1001").roles("hr-reader").signRs256(HRIS_RSA);

		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", policiesKb), relayKey, token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.yourPermissions", contains("KB_READ")));
		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", payrollKb), relayKey, token))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases"), relayKey, token))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(policiesKb.toString()));
	}

	@Test
	void es256AssertionsAreAccepted() throws Exception {
		String token = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-1002").roles("hr-reader").signEs256(HRIS_EC);

		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", policiesKb), relayKey, token))
			.andExpect(status().isOk());
	}

	@Test
	void userWithoutMatchingRoleSeesNothing() throws Exception {
		String token = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-2000").roles("sales").signRs256(HRIS_RSA);

		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", policiesKb), relayKey, token))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases"), relayKey, token))
			.andExpect(jsonPath("$.totalItems").value(0));
	}

	@Test
	void usersCannotCreateKnowledgeBases() throws Exception {
		String token = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-1").roles("hr-admin").signRs256(HRIS_RSA);

		mockMvc.perform(api.asUser(post("/api/v1/knowledge-bases"), relayKey, token)
			.content(api.toJson(Map.of("code", "user-made", "name", "x"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void userPermissionsAreBoundedByApiKeyScopes() throws Exception {
		String admin = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-9").roles("hr-admin").signRs256(HRIS_RSA);

		mockMvc.perform(api.asUser(patch("/api/v1/knowledge-bases/{id}", policiesKb), relayKey, admin)
			.content(api.toJson(Map.of("version", 0, "description", "updated by hr-admin"))))
			.andExpect(status().isOk());
		// Same user, but through a key that only carries KB_READ: KB_MANAGE is cut off, nothing remains.
		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", policiesKb), narrowKey, admin))
			.andExpect(status().isNotFound());
	}

	@Test
	void readerCannotManage() throws Exception {
		String reader = TestTokens.claims(hrisIssuer, AUDIENCE, "emp-3").roles("hr-reader").signRs256(HRIS_RSA);

		mockMvc.perform(api.asUser(patch("/api/v1/knowledge-bases/{id}", policiesKb), relayKey, reader)
			.content(api.toJson(Map.of("version", 0, "name", "x"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void anotherIntegrationsTokenIsRejectedEvenIfValidForThatIntegration() throws Exception {
		String crmToken = TestTokens.claims(crmIssuer, AUDIENCE, "crm-user").roles("hr-reader").signRs256(CRM_RSA);

		mockMvc.perform(api.asUser(get("/api/v1/integration"), crmKey, crmToken)).andExpect(status().isOk());
		mockMvc.perform(api.asUser(get("/api/v1/integration"), relayKey, crmToken))
			.andExpect(status().isUnauthorized());
		String forgedIssuer = TestTokens.claims(hrisIssuer, AUDIENCE, "crm-user").roles("hr-reader")
			.signRs256(CRM_RSA);
		mockMvc.perform(api.asUser(get("/api/v1/integration"), relayKey, forgedIssuer))
			.andExpect(status().isUnauthorized());
	}

	static Stream<Arguments> invalidAssertions() {
		return Stream.of(Arguments.of("HS256", "hs256"), Arguments.of("alg none", "none"),
				Arguments.of("wrong issuer", "issuer"), Arguments.of("wrong audience", "audience"),
				Arguments.of("expired", "expired"), Arguments.of("lifetime over maximum", "lifetime"),
				Arguments.of("missing subject", "no-sub"), Arguments.of("missing expiry", "no-exp"),
				Arguments.of("issued in the future", "future"), Arguments.of("key not published", "unpublished"),
				Arguments.of("not a JWT", "garbage"), Arguments.of("oversized", "oversized"),
				Arguments.of("role claim with wrong type", "bad-roles"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidAssertions")
	void invalidAssertionsAreRejectedWithGeneric401(String description, String variant) throws Exception {
		mockMvc.perform(api.asUser(get("/api/v1/integration"), relayKey, invalidToken(variant)))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
	}

	@Test
	void integrationWithoutTrustConfigurationCannotActForUsers() throws Exception {
		UUID bare = api.createIntegration(ApiTestClient.uniqueCode("bare"));
		String bareKey = api.issueKey(bare, "KB_READ").plaintext();
		String token = TestTokens.claims("https://bare.example.com", AUDIENCE, "u").signRs256(HRIS_RSA);

		mockMvc.perform(api.withKey(get("/api/v1/integration"), bareKey)).andExpect(status().isOk());
		mockMvc.perform(api.asUser(get("/api/v1/integration"), bareKey, token)).andExpect(status().isUnauthorized());
	}

	@Test
	void inlineKeysAreRefusedWhenNotExplicitlyEnabled() throws Exception {
		Map<String, Object> config = new HashMap<>(trustConfig(hrisIssuer, null));
		config.remove("jwksUri");
		config.put("inlineJwks", TestTokens.publicJwks(HRIS_RSA));

		mockMvc.perform(api.admin(put("/api/v1/admin/integrations/{id}/trust-config", hrisId))
			.content(api.toJson(config)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.violations[0].field").value("inlineJwks"));
	}

	@Test
	void rejectedAssertionsAreAudited() throws Exception {
		mockMvc.perform(api.asUser(get("/api/v1/integration"), relayKey, invalidToken("expired")))
			.andExpect(status().isUnauthorized());

		ContextualJdbc db = new ContextualJdbc(jdbcTemplate, transactionTemplate);
		Integer count = db.as(AccessContext.platformAdmin("t"), jdbc -> jdbc.queryForObject(
				"select count(*) from audit_event where action = 'USER_ASSERTION_REJECTED' "
						+ "and details->>'reason' = 'REJECTED_BY_VALIDATION'",
				Integer.class));
		assertThat(count).isPositive();
	}

	private String invalidToken(String variant) {
		Instant now = Instant.now();
		return switch (variant) {
			case "hs256" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").signHs256(new byte[32]);
			case "none" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").unsigned();
			case "issuer" -> TestTokens.claims("https://evil.example.com", AUDIENCE, "u").signRs256(HRIS_RSA);
			case "audience" -> TestTokens.claims(hrisIssuer, "someone-else", "u").signRs256(HRIS_RSA);
			case "expired" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u")
				.issuedAt(now.minus(Duration.ofMinutes(10)))
				.expiresAt(now.minus(Duration.ofMinutes(8)))
				.signRs256(HRIS_RSA);
			case "lifetime" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u")
				.expiresAt(now.plus(Duration.ofHours(2)))
				.signRs256(HRIS_RSA);
			case "no-sub" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").without("sub").signRs256(HRIS_RSA);
			case "no-exp" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").without("exp").signRs256(HRIS_RSA);
			case "future" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u")
				.issuedAt(now.plus(Duration.ofMinutes(10)))
				.expiresAt(now.plus(Duration.ofMinutes(12)))
				.signRs256(HRIS_RSA);
			case "unpublished" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").signRs256(UNPUBLISHED_RSA);
			case "garbage" -> "this.is.not-a-jwt";
			case "oversized" -> "x".repeat(9000);
			case "bad-roles" -> TestTokens.claims(hrisIssuer, AUDIENCE, "u").claim("roles", 42).signRs256(HRIS_RSA);
			default -> throw new IllegalArgumentException(variant);
		};
	}

	private static Map<String, Object> trustConfig(String issuer, String jwksUri) {
		Map<String, Object> config = new HashMap<>();
		config.put("issuer", issuer);
		config.put("audience", AUDIENCE);
		config.put("jwksUri", jwksUri);
		config.put("allowedAlgorithms", List.of("RS256", "ES256"));
		config.put("maxTokenLifetimeSeconds", 300);
		config.put("clockSkewSeconds", 30);
		config.put("claimUserId", "sub");
		config.put("claimRoles", "roles");
		return config;
	}

}
