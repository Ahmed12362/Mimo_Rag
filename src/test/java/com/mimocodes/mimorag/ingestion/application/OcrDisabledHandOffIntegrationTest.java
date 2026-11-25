package com.mimocodes.mimorag.ingestion.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.jobs.application.JobHandler.JobExecution;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobRunner;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * With OCR disabled, extraction ends in {@code AWAITING_OCR} and enqueues nothing. The suite's context has OCR
 * enabled, so the service is built here with disabled settings (a second Spring context would start competing job
 * workers on the same database).
 */
class OcrDisabledHandOffIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JobRunner runner;

	@Autowired
	private ExtractionRunRepository runs;

	@Autowired
	private PageExtractionStore pages;

	@Autowired
	private DocumentVersionRepository versions;

	@Autowired
	private DocumentStorage storage;

	@Autowired
	private NativeTextExtractor extractor;

	@Autowired
	private AuditRecorder audit;

	@Autowired
	private IngestionProperties properties;

	@Autowired
	private Clock clock;

	@Autowired
	private MeterRegistry meters;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JobQueue jobs;

	@AfterEach
	void resumeRunner() {
		runner.start();
	}

	@Test
	void scannedPageWaitsForOcrWhenOcrIsDisabled() throws Exception {
		ApiTestClient api = new ApiTestClient(mockMvc, jsonMapper);
		UUID integrationId = api.createIntegration(ApiTestClient.uniqueCode("noocr"));
		String key = api.issueKey(integrationId, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD").plaintext();
		UUID kb = api.createKnowledgeBase(key, ApiTestClient.uniqueCode("kb"));
		runner.stop();
		JsonNode upload = api.uploadAccepted(key, kb, TestPdfs.imageOnly(UUID.randomUUID().toString()));
		UUID versionId = UUID.fromString(upload.get("versionId").asString());
		UUID jobId = UUID.fromString(upload.get("jobId").asString());
		superuser().update("""
				update mimorag.document_version set status = 'VALIDATED', page_count = 1, validated_at = now()
				where id = ?""", versionId);

		NativeExtractionService disabled = new NativeExtractionService(runs, pages, versions, storage, extractor,
				audit, properties, clock, meters, transactionManager, jobs, disabledOcr());
		AccessContext context = AccessContext.integrationService(integrationId, "a1b2c3d4e5f6a7b8c9d0e1f2",
				Set.of(Permission.DOCUMENT_UPLOAD), "corr-noocr-1234");
		JobExecution execution = new JobExecution(jobId, DocumentUploadService.EXTRACT_DOCUMENT,
				Map.of("versionId", versionId.toString()), context, 1, 3);
		DocumentVersionStatus status = AccessContextHolder.callAs(context,
				() -> disabled.extract(execution, versionId));

		assertThat(status).isEqualTo(DocumentVersionStatus.AWAITING_OCR);
		JdbcTemplate superuser = superuser();
		assertThat(superuser.queryForObject("select ocr_job_id from mimorag.document_version where id = ?", UUID.class,
				versionId)).isNull();
		assertThat(superuser.queryForObject(
				"select count(*) from mimorag.job where integration_id = ? and kind = 'OCR_DOCUMENT'", Integer.class,
				integrationId)).isZero();
	}

	private static OcrProperties disabledOcr() {
		return new OcrProperties(false, URI.create("http://127.0.0.1:8090"), "", Duration.ofSeconds(2),
				Duration.ofSeconds(90), DataSize.ofMegabytes(5), 2000, 5000, 64, 300, 40_000_000L,
				DataSize.ofMegabytes(30), 4, 32, "auto", 3);
	}

	private static JdbcTemplate superuser() {
		return new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

}
