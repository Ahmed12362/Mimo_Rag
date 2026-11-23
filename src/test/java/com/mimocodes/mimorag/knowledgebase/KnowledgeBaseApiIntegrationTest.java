package com.mimocodes.mimorag.knowledgebase;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Knowledge Base lifecycle through the integration plane, as a service identity. */
class KnowledgeBaseApiIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private ApiTestClient api;

	private String managerKey;

	private String readerKey;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("kbtest"));
		managerKey = api.issueKey(integrationId, "KB_MANAGE").plaintext();
		readerKey = api.issueKey(integrationId, "KB_READ").plaintext();
	}

	@Test
	void createsMultipleKnowledgeBasesWithSafeDefaults() throws Exception {
		JsonNode policies = api.readJson(api.withKey(post("/api/v1/knowledge-bases"), managerKey)
			.content(api.toJson(Map.of("code", "policies", "name", "HR Policies", "description", "Handbook"))), 201);
		api.createKnowledgeBase(managerKey, "benefits");

		assertThat(policies.get("status").asString()).isEqualTo("ACTIVE");
		assertThat(policies.get("allowServiceSearch").asBoolean()).isFalse();
		assertThat(policies.get("requireDifferentSubmitter").asBoolean()).isFalse();
		assertThat(policies.get("createdBy").asString()).startsWith("api-key:");
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases"), managerKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalItems").value(2))
			.andExpect(jsonPath("$.items[*].code", containsInAnyOrder("policies", "benefits")));
	}

	@Test
	void listIsPaginated() throws Exception {
		for (int i = 0; i < 5; i++) {
			api.createKnowledgeBase(managerKey, "kb-" + i);
		}

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases?page=1&size=2"), managerKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.totalItems").value(5))
			.andExpect(jsonPath("$.totalPages").value(3));
	}

	@Test
	void createReturnsLocationAndGetReturnsCallerPermissions() throws Exception {
		mockMvc.perform(api.withKey(post("/api/v1/knowledge-bases"), managerKey)
			.content(api.toJson(Map.of("code", "located", "name", "Located"))))
			.andExpect(status().isCreated())
			.andExpect(header().exists("Location"));
		UUID kbId = api.createKnowledgeBase(managerKey, "perms");

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", kbId), readerKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.yourPermissions", containsInAnyOrder("KB_READ")));
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", kbId), managerKey))
			.andExpect(jsonPath("$.yourPermissions", containsInAnyOrder("KB_READ", "KB_MANAGE")));
	}

	@Test
	void duplicateCodeWithinIntegrationIsAConflict() throws Exception {
		api.createKnowledgeBase(managerKey, "dup");

		mockMvc.perform(api.withKey(post("/api/v1/knowledge-bases"), managerKey)
			.content(api.toJson(Map.of("code", "dup", "name", "again"))))
			.andExpect(status().isConflict());
	}

	@Test
	void concurrentCreatesWithTheSameCodeYieldOneCreatedAndConflicts() throws Exception {
		Callable<Integer> create = () -> mockMvc.perform(api.withKey(post("/api/v1/knowledge-bases"), managerKey)
			.content(api.toJson(Map.of("code", "raced", "name", "Raced")))).andReturn().getResponse().getStatus();

		List<Integer> statuses = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
			for (Future<Integer> result : pool.invokeAll(List.of(create, create, create, create))) {
				statuses.add(result.get());
			}
		}

		assertThat(statuses).containsOnlyOnce(201).allMatch(code -> code == 201 || code == 409);
	}

	@Test
	void keyWithoutManageScopeCannotCreateOrUpdate() throws Exception {
		mockMvc.perform(api.withKey(post("/api/v1/knowledge-bases"), readerKey)
			.content(api.toJson(Map.of("code", "nope", "name", "Nope"))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
		UUID kbId = api.createKnowledgeBase(managerKey, "readonly");

		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", kbId), readerKey)
			.content(api.toJson(Map.of("version", 0, "name", "x"))))
			.andExpect(status().isForbidden());
	}

	@Test
	void updateUsesOptimisticLocking() throws Exception {
		UUID kbId = api.createKnowledgeBase(managerKey, "locking");

		JsonNode updated = api.readJson(api.withKey(patch("/api/v1/knowledge-bases/{id}", kbId), managerKey)
			.content(api.toJson(Map.of("version", 0, "name", "Renamed", "allowServiceSearch", true))), 200);
		assertThat(updated.get("name").asString()).isEqualTo("Renamed");
		assertThat(updated.get("allowServiceSearch").asBoolean()).isTrue();
		assertThat(updated.get("version").asLong()).isEqualTo(1);

		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", kbId), managerKey)
			.content(api.toJson(Map.of("version", 0, "name", "Stale write"))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("CONFLICT"));
	}

	@Test
	void archiveIsAStatusChange() throws Exception {
		UUID kbId = api.createKnowledgeBase(managerKey, "archive-me");

		mockMvc.perform(api.withKey(patch("/api/v1/knowledge-bases/{id}", kbId), managerKey)
			.content(api.toJson(Map.of("version", 0, "status", "ARCHIVED"))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ARCHIVED"));
	}

	@Test
	void roleGrantsAreReplacedAsASetAndValidated() throws Exception {
		UUID kbId = api.createKnowledgeBase(managerKey, "grants");
		api.replaceGrants(managerKey, kbId, List.of(Map.of("role", "hr-reader", "permission", "KB_READ"),
				Map.of("role", "hr-reader", "permission", "KB_READ"),
				Map.of("role", "hr-editor", "permission", "DOCUMENT_REVIEW")));

		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}/role-grants", kbId), managerKey))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.grants.length()").value(2));

		api.replaceGrants(managerKey, kbId, List.of(Map.of("role", "hr-reader", "permission", "KB_READ")));
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}/role-grants", kbId), managerKey))
			.andExpect(jsonPath("$.grants.length()").value(1));

		mockMvc.perform(api.withKey(put("/api/v1/knowledge-bases/{id}/role-grants", kbId), managerKey)
			.content(api.toJson(Map.of("grants", List.of(Map.of("role", "x", "permission", "TOOL_EXECUTE"))))))
			.andExpect(status().isBadRequest());
	}

	@Test
	void malformedIdentifierIsABadRequest() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/not-a-uuid"), managerKey))
			.andExpect(status().isBadRequest());
	}

	@Test
	void unknownKnowledgeBaseIsNotFound() throws Exception {
		mockMvc.perform(api.withKey(get("/api/v1/knowledge-bases/{id}", UUID.randomUUID()), managerKey))
			.andExpect(status().isNotFound());
	}

}
