package com.mimocodes.mimorag.ingestion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.mimocodes.mimorag.jobs.application.JobRunner;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Behaviour that depends on what happens between upload and processing. The job runner is paused while a test sets
 * up the situation (revoked key, archived Knowledge Base, full queue), then resumed.
 */
class IngestionJobLifecycleIntegrationTest extends PostgresIntegrationTest {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JobRunner runner;

	private ApiTestClient api;

	private UUID integrationId;

	private String key;

	private UUID kb;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("life"));
		key = api.issueKey(integrationId, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		kb = api.createKnowledgeBase(key, ApiTestClient.uniqueCode("kb"));
	}

	@AfterEach
	void resumeRunner() {
		runner.start();
	}

	@Test
	void revokedKeyCancelsItsQueuedJobAndAnotherAuthorisedKeyCanRequeueIt() throws Exception {
		ApiTestClient.IssuedKey uploader = api.issueKey(integrationId, "KB_READ", "DOCUMENT_UPLOAD");
		runner.stop();
		JsonNode upload = api.uploadAccepted(uploader.plaintext(), kb, pdf());
		api.revokeKey(integrationId, uploader.keyId());
		runner.start();

		JsonNode job = api.awaitJob(key, upload.get("jobId").asString(), JOB_TIMEOUT);
		assertThat(job.get("status").asString()).isEqualTo("CANCELLED_REVALIDATION");
		assertThat(job.get("errorCode").asString()).isEqualTo("CREDENTIAL_KEY_REVOKED");
		assertThat(job.get("versionStatus").asString()).isEqualTo("VALIDATION_FAILED");
		assertThat(job.get("retryable").asBoolean()).isTrue();

		JsonNode requeued = api.readJson(
				api.withKey(post("/api/v1/ingestion-jobs/{id}/retry", upload.get("jobId").asString()), key), 202);
		assertThat(requeued.get("versionStatus").asString()).isEqualTo("UPLOADED");
		// A repeated retry while it is queued or running is harmless.
		api.perform(api.withKey(post("/api/v1/ingestion-jobs/{id}/retry", upload.get("jobId").asString()), key))
			.andExpect(status().isAccepted());

		JsonNode done = api.awaitJob(key, upload.get("jobId").asString(), JOB_TIMEOUT);
		assertThat(done.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(done.get("versionStatus").asString()).isEqualTo("VALIDATED");
		assertThat(done.get("errorCode").isNull()).isTrue();
	}

	@Test
	void archivingTheKnowledgeBaseCancelsQueuedWork() throws Exception {
		runner.stop();
		JsonNode upload = api.uploadAccepted(key, kb, pdf());
		api.archiveKnowledgeBase(key, kb);
		runner.start();

		JsonNode job = api.awaitJob(key, upload.get("jobId").asString(), JOB_TIMEOUT);
		assertThat(job.get("status").asString()).isEqualTo("CANCELLED_REVALIDATION");
		assertThat(job.get("errorCode").asString()).isEqualTo("KNOWLEDGE_BASE_ARCHIVED");
	}

	@Test
	void uploadsBeyondTheUnfinishedJobLimitAreRefusedWithRetryAfter() throws Exception {
		runner.stop();
		List<String> jobs = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			jobs.add(api.uploadAccepted(key, kb, pdf()).get("jobId").asString());
		}

		api.perform(api.upload(key, kb, "one-too-many.pdf", pdf()))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().string("Retry-After", "30"))
			.andExpect(jsonPath("$.errorCode").value("UPLOAD_QUEUE_FULL"));

		runner.start();
		for (String jobId : jobs) {
			assertThat(api.awaitJob(key, jobId, JOB_TIMEOUT).get("status").asString()).isEqualTo("SUCCEEDED");
		}
		api.perform(api.upload(key, kb, "after.pdf", pdf())).andExpect(status().isAccepted());
	}

	@Test
	void uploadThatWouldExceedTheStorageQuotaIsRefused() throws Exception {
		insertStoredBytes(2L * 1024 * 1024 * 1024 - 100);

		api.perform(api.upload(key, kb, "a.pdf", pdf()))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.errorCode").value("STORAGE_QUOTA_EXCEEDED"));
	}

	/** Pretends the integration already stores {@code bytes} (superuser insert, bypassing the API and RLS). */
	private void insertStoredBytes(long bytes) {
		JdbcTemplate superuser = new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
		UUID documentId = UUID.randomUUID();
		superuser.update("""
				insert into mimorag.document (id, integration_id, knowledge_base_id, title, original_filename, created_at,
				                              created_by, updated_at, version)
				values (?, ?, ?, 'quota filler', 'filler.pdf', now(), 'test', now(), 0)""", documentId, integrationId,
				kb);
		byte[] hash = new byte[32];
		ThreadLocalRandom.current().nextBytes(hash);
		superuser.update("""
				insert into mimorag.document_version (id, integration_id, document_id, knowledge_base_id, version_number,
				    storage_key, sha256, size_bytes, content_type, page_count, status, extraction_job_id, uploaded_at,
				    uploaded_by, updated_at, version)
				values (?, ?, ?, ?, 1, ?, ?, ?, 'application/pdf', 1, 'VALIDATED', ?, now(), 'test', now(), 0)""",
				UUID.randomUUID(), integrationId, documentId, kb, "filler/" + UUID.randomUUID(),
				HexFormat.of().formatHex(hash), bytes, UUID.randomUUID());
	}

	private static byte[] pdf() {
		return TestPdfs.withPages(1, UUID.randomUUID().toString());
	}

}
