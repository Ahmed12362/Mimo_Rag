package com.mimocodes.mimorag.ingestion;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Documents and ingestion jobs are invisible outside their tenant, outside a KB-restricted key's scope, and to callers
 * without document permissions: every such request gets the same 404 as an unknown id.
 */
class DocumentIsolationIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate appRoleJdbc;

	private ApiTestClient api;

	private String hrisKey;

	private UUID hrisKb;

	private String documentId;

	private String jobId;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		UUID hris = api.createIntegration(ApiTestClient.uniqueCode("hris"));
		hrisKey = api.issueKey(hris, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		hrisKb = api.createKnowledgeBase(hrisKey, ApiTestClient.uniqueCode("policies"));
		JsonNode upload = api.uploadAccepted(hrisKey, hrisKb, TestPdfs.withPages(1, UUID.randomUUID().toString()));
		documentId = upload.get("documentId").asString();
		jobId = upload.get("jobId").asString();
		api.awaitJob(hrisKey, jobId, Duration.ofSeconds(20));
	}

	@Test
	void anotherIntegrationCannotSeeOrTouchTheDocumentOrItsJob() throws Exception {
		UUID crm = api.createIntegration(ApiTestClient.uniqueCode("crm"));
		String crmKey = api.issueKey(crm, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();

		String unknownDocument = notFoundBody(get("/api/v1/documents/{id}", UUID.randomUUID()), crmKey);
		assertThat(notFoundBody(get("/api/v1/documents/{id}", documentId), crmKey)).isEqualTo(unknownDocument);
		String unknownJob = notFoundBody(get("/api/v1/ingestion-jobs/{id}", UUID.randomUUID()), crmKey);
		assertThat(notFoundBody(get("/api/v1/ingestion-jobs/{id}", jobId), crmKey)).isEqualTo(unknownJob);
		notFoundBody(post("/api/v1/ingestion-jobs/{id}/retry", jobId), crmKey);
		notFoundBody(get("/api/v1/knowledge-bases/{id}/documents", hrisKb), crmKey);
		api.perform(api.upload(crmKey, hrisKb, "x.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString())))
			.andExpect(status().isNotFound());
	}

	@Test
	void knowledgeBaseRestrictedKeySeesOnlyItsKnowledgeBases() throws Exception {
		UUID otherKb = api.createKnowledgeBase(hrisKey, ApiTestClient.uniqueCode("benefits"));
		String rotateKey = api.issueBootstrapKey(integrationOf()).plaintext();
		String restricted = api.createKey(rotateKey, List.of("KB_READ", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW"),
				List.of(otherKb)).plaintext();

		notFoundBody(get("/api/v1/documents/{id}", documentId), restricted);
		notFoundBody(get("/api/v1/ingestion-jobs/{id}", jobId), restricted);
		api.perform(api.upload(restricted, hrisKb, "x.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString())))
			.andExpect(status().isNotFound());
		api.perform(api.upload(restricted, otherKb, "x.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString())))
			.andExpect(status().isAccepted());
	}

	@Test
	void callerWithoutDocumentPermissionsCannotSeeDocuments() throws Exception {
		String kbOnly = api.issueKey(integrationOf(), "KB_READ", "KB_MANAGE").plaintext();

		notFoundBody(get("/api/v1/documents/{id}", documentId), kbOnly);
		notFoundBody(get("/api/v1/ingestion-jobs/{id}", jobId), kbOnly);
	}

	@Test
	void reviewerCanReadButNotRetry() throws Exception {
		String reviewer = api.issueKey(integrationOf(), "KB_READ", "DOCUMENT_REVIEW").plaintext();

		api.perform(api.withKey(get("/api/v1/ingestion-jobs/{id}", jobId), reviewer)).andExpect(status().isOk());
		api.perform(api.withKey(post("/api/v1/ingestion-jobs/{id}/retry", jobId), reviewer))
			.andExpect(status().isForbidden());
	}

	@Test
	void databaseShowsNoDocumentsOrJobsWithoutATenantContext() {
		// Runtime role, no transaction-local tenant setting: RLS must hide every row.
		assertThat(appRoleJdbc.queryForObject("select count(*) from document", Long.class)).isZero();
		assertThat(appRoleJdbc.queryForObject("select count(*) from document_version", Long.class)).isZero();
		assertThat(appRoleJdbc.queryForObject("select count(*) from job", Long.class)).isZero();
	}

	private UUID integrationOf() throws Exception {
		JsonNode self = api.readJson(api.withKey(get("/api/v1/integration"), hrisKey), 200);
		return UUID.fromString(self.get("id").asString());
	}

	private String notFoundBody(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
			String key) throws Exception {
		String body = api.perform(api.withKey(request, key))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.errorCode").value("RESOURCE_NOT_FOUND"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		// correlationId and timestamp differ per request; the rest must be identical.
		return body.replaceAll("\"(correlationId|timestamp)\":\"[^\"]*\"", "");
	}

}
