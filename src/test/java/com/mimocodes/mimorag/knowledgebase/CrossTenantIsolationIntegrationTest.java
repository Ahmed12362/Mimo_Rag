package com.mimocodes.mimorag.knowledgebase;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quality gate: one integration must never see or change another integration's data, and must not be able to tell
 * whether another integration's identifiers exist.
 */
class CrossTenantIsolationIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private ApiTestClient api;

	private UUID hrisId;

	private String hrisKey;

	private String crmKey;

	private UUID hrisKb;

	private UUID crmKb;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		hrisId = api.createIntegration(ApiTestClient.uniqueCode("hris"));
		UUID crmId = api.createIntegration(ApiTestClient.uniqueCode("crm"));
		hrisKey = api.issueKey(hrisId, "KB_MANAGE").plaintext();
		crmKey = api.issueKey(crmId, "KB_MANAGE").plaintext();
		hrisKb = api.createKnowledgeBase(hrisKey, "shared-code");
		crmKb = api.createKnowledgeBase(crmKey, "shared-code");
	}

	@Test
	void sameKnowledgeBaseCodeIsIndependentPerIntegration() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", hrisKb), hrisKey)).andExpect(status().isOk());
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", crmKb), crmKey)).andExpect(status().isOk());
	}

	@Test
	void otherIntegrationsKnowledgeBaseIsIndistinguishableFromNonExistent() throws Exception {
		String foreign = mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", crmKb), hrisKey))
			.andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();
		String missing = mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", UUID.randomUUID()), hrisKey))
			.andExpect(status().isNotFound())
			.andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(strip(foreign)).isEqualTo(strip(missing));
	}

	@Test
	void listingNeverIncludesOtherIntegrationsKnowledgeBases() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases?size=100"), hrisKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].id", hasItem(hrisKb.toString())))
			.andExpect(jsonPath("$.items[*].id", everyItem(not(crmKb.toString()))));
	}

	@Test
	void cannotModifyOtherIntegrationsKnowledgeBaseOrGrants() throws Exception {
		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", crmKb), hrisKey)
			.content(api.toJson(Map.of("version", 0, "name", "hijacked"))))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.withKey(put("/api/v1/knowledge-bases/{id}/role-grants", crmKb), hrisKey)
			.content(api.toJson(Map.of("grants", List.of(Map.of("role", "x", "permission", "KB_READ"))))))
			.andExpect(status().isNotFound());
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", crmKb), crmKey))
			.andExpect(jsonPath("$.name").value("KB shared-code"))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void selfEndpointAlwaysReturnsTheCallersOwnIntegration() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/integration"), hrisKey))
			.andExpect(jsonPath("$.id").value(hrisId.toString()));
	}

	private static String strip(String json) {
		return json.replaceAll("\"(correlationId|timestamp)\":\"[^\"]*\"", "");
	}

}
