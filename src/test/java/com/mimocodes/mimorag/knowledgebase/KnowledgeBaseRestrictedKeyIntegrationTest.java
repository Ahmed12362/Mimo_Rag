package com.mimocodes.mimorag.knowledgebase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ApiTestClient.IssuedKey;
import com.mimocodes.mimorag.support.JwksTestServer;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** M6: API keys optionally restricted to specific Knowledge Bases, enforced server-side. */
class KnowledgeBaseRestrictedKeyIntegrationTest extends PostgresIntegrationTest {

	private static final RSAKey SIGNING_KEY = TestTokens.newRsaKey();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private ApiTestClient api;

	private String manager;

	private String rotator;

	private UUID policies;

	private UUID payroll;

	private String issuer;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		String code = ApiTestClient.uniqueCode("restricted");
		UUID integrationId = api.createIntegration(code);
		manager = api.issueKey(integrationId, "KB_MANAGE", "KB_READ").plaintext();
		rotator = api.issueKey(integrationId, "KEY_ROTATE").plaintext();
		policies = api.createKnowledgeBase(manager, "policies");
		payroll = api.createKnowledgeBase(manager, "payroll");
		issuer = "https://" + code + ".example.com";
		api.putTrustConfig(integrationId, Map.of("issuer", issuer, "audience", "mimorag", "jwksUri",
				JwksTestServer.instance().publish(code, TestTokens.publicJwks(SIGNING_KEY)), "allowedAlgorithms",
				List.of("RS256"), "claimRoles", "roles"));
		api.replaceGrants(manager, policies, List.of(Map.of("role", "hr", "permission", "KB_READ")));
		api.replaceGrants(manager, payroll, List.of(Map.of("role", "hr", "permission", "KB_READ")));
	}

	@Test
	void restrictedServiceKeySeesAndManagesOnlyItsKnowledgeBases() throws Exception {
		String restricted = api.createKey(rotator, List.of("KB_MANAGE"), List.of(policies)).plaintext();

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases"), restricted))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(policies.toString()));
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", payroll), restricted))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", payroll), restricted)
			.content(api.toJson(Map.of("version", 0, "name", "x"))))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", policies), restricted)
			.content(api.toJson(Map.of("version", 0, "name", "Policies (renamed)"))))
			.andExpect(status().isOk());
	}

	@Test
	void restrictedKeyCannotCreateKnowledgeBases() throws Exception {
		String restricted = api.createKey(rotator, List.of("KB_MANAGE"), List.of(policies)).plaintext();

		mockMvc.perform(api.withKey(post("/api/v1/knowledge-bases"), restricted)
			.content(api.toJson(Map.of("code", "widened", "name", "x"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void relayedUsersAreBoundedByTheKeyRestrictionAndTheirGrants() throws Exception {
		String restricted = api.createKey(rotator, List.of("KB_READ"), List.of(policies)).plaintext();
		String hrUser = TestTokens.claims(issuer, "mimorag", "emp-1").roles("hr").signRs256(SIGNING_KEY);

		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases"), restricted, hrUser))
			.andExpect(jsonPath("$.totalItems").value(1))
			.andExpect(jsonPath("$.items[0].id").value(policies.toString()));
		mockMvc.perform(api.asUser(get("/api/v1/knowledge-bases/{id}", payroll), restricted, hrUser))
			.andExpect(status().isNotFound());
	}

	@Test
	void restrictionToUnknownOrForeignKnowledgeBaseIsRejected() throws Exception {
		UUID other = api.createIntegration(ApiTestClient.uniqueCode("foreign"));
		UUID foreignKb = api.createKnowledgeBase(api.issueKey(other, "KB_MANAGE").plaintext(), "theirs");

		for (UUID kb : List.of(foreignKb, UUID.randomUUID())) {
			mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), rotator)
				.content(api.toJson(Map.of("name", "k", "scopes", List.of("KB_READ"), "knowledgeBaseIds", List.of(kb)))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.violations[0].field").value("knowledgeBaseIds"));
		}
	}

	@Test
	void restrictedRotatorCannotEscalateToBroaderKeys() throws Exception {
		IssuedKey restrictedRotator = api.createKey(rotator, List.of("KEY_ROTATE", "KB_READ"), List.of(policies));

		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), restrictedRotator.plaintext())
			.content(api.toJson(Map.of("name", "wider", "scopes", List.of("KB_READ")))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), restrictedRotator.plaintext())
			.content(api.toJson(Map.of("name", "other", "scopes", List.of("KB_READ"), "knowledgeBaseIds",
					List.of(payroll)))))
			.andExpect(status().isBadRequest());
		mockMvc.perform(api.withKey(post("/api/v1/integration/api-keys"), restrictedRotator.plaintext())
			.content(api.toJson(Map.of("name", "same", "scopes", List.of("KB_READ"), "knowledgeBaseIds",
					List.of(policies)))))
			.andExpect(status().isCreated());
	}

	@Test
	void keyListingShowsRestrictions() throws Exception {
		IssuedKey restricted = api.createKey(rotator, List.of("KB_READ"), List.of(policies));

		mockMvc.perform(api.withKey(get("/api/v1/integration/api-keys"), rotator))
			.andExpect(jsonPath("$[?(@.keyId == '" + restricted.keyId() + "')].knowledgeBaseIds[0]")
				.value(policies.toString()));
	}

}
