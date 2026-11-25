package com.mimocodes.mimorag.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mimocodes.mimorag.ingestion.application.PageAnalysis;
import com.mimocodes.mimorag.ingestion.application.PageExtractionStore;
import com.mimocodes.mimorag.ingestion.application.PageClassifier;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;
import com.mimocodes.mimorag.jobs.application.JobRunner;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Native extraction through the real API and worker: results, permissions, isolation, resume and read-only rows. */
class NativeExtractionIntegrationTest extends PostgresIntegrationTest {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(60);

	private static final Path CORPUS = Path.of("src/test/resources/corpus/synthetic/digital");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JobRunner runner;

	@Autowired
	private PageExtractionStore pageStore;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate appRoleJdbc;

	private ApiTestClient api;

	private UUID integrationId;

	private String reviewKey;

	private UUID kb;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("extract"));
		reviewKey = api.issueKey(integrationId, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		kb = api.createKnowledgeBase(reviewKey, ApiTestClient.uniqueCode("kb"));
	}

	@AfterEach
	void resumeRunner() {
		runner.start();
	}

	@Test
	void arabicDocumentIsExtractedInReadingOrder() throws Exception {
		JsonNode job = uploadAndWait(Files.readAllBytes(CORPUS.resolve("ar_policy_amiri.pdf")));
		String documentId = job.get("documentId").asString();

		assertThat(job.get("versionStatus").asString()).isEqualTo("EXTRACTED");
		assertThat(job.get("progress").get("pagesDone").asInt()).isEqualTo(1);
		assertThat(job.get("progress").get("pagesTotal").asInt()).isEqualTo(1);

		JsonNode page = api.readJson(api.withKey(get("/api/v1/documents/{id}/pages/1", documentId), reviewKey), 200);
		assertThat(page.get("pageClass").asString()).isEqualTo("NATIVE_TEXT");
		assertThat(page.get("nativeTextTrusted").asBoolean()).isTrue();
		assertThat(page.get("text").asString()).startsWith("سياسة الإجازات السنوية\n")
			.contains("يستحق الموظف إجازة سنوية مدفوعة الأجر بعد إتمام ستة أشهر");
		assertThat(page.get("scripts").get(0).asString()).isEqualTo("ARABIC");
		assertThat(page.get("lines").size()).isEqualTo(9);
		assertThat(page.get("lines").get(0).get("direction").asString()).isEqualTo("RTL");

		JsonNode summary = api.readJson(api.withKey(get("/api/v1/documents/{id}/extraction", documentId), reviewKey),
				200);
		assertThat(summary.get("run").get("status").asString()).isEqualTo("COMPLETED");
		assertThat(summary.get("run").get("pagesNative").asInt()).isEqualTo(1);
		assertThat(summary.get("run").get("engine").asString()).isEqualTo("pdfbox+linebuilder");
	}

	@Test
	void scannedPageIsHandedToOcrAndExposesNoText() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.imageOnly(UUID.randomUUID().toString()));
		JsonNode page = page(job, 1);

		// OCR is enabled in the test suite, so the version may already have moved on (OcrIntegrationTest).
		assertThat(job.get("versionStatus").asString()).isIn("AWAITING_OCR", "OCR_RUNNING", "OCR_COMPLETED");
		assertThat(job.get("nextJobId").isNull()).as("the OCR job was enqueued with the extraction result").isFalse();
		assertThat(page.get("pageClass").asString()).isEqualTo("OCR_REQUIRED");
		assertThat(page.get("text").isNull()).isTrue();
	}

	@Test
	void mixedPageKeepsNativeTextAndImageRegionsSeparately() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.textWithLargeImage(UUID.randomUUID().toString()));
		JsonNode page = page(job, 1);

		assertThat(job.get("versionStatus").asString()).isIn("AWAITING_OCR", "OCR_RUNNING", "OCR_COMPLETED");
		assertThat(page.get("pageClass").asString()).isEqualTo("MIXED");
		assertThat(page.get("text").asString()).contains("Employees must read this notice carefully");
		assertThat(page.get("images").size()).isEqualTo(1);
		assertThat(page.get("images").get(0).get("width").asDouble()).isBetween(399.0, 401.0);
	}

	@Test
	void hiddenTextIsFlaggedInThePageResult() throws Exception {
		JsonNode page = page(uploadAndWait(TestPdfs.hiddenText(UUID.randomUUID().toString())), 1);

		List<String> warnings = new ArrayList<>();
		page.get("warnings").forEach(w -> warnings.add(w.asString()));
		assertThat(warnings).contains("INVISIBLE_TEXT", "WHITE_TEXT", "TINY_TEXT", "OFF_PAGE_TEXT");
		assertThat(page.get("text").asString()).contains("Invisible instruction", "White instruction");
	}

	@Test
	void pagesAreListedWithoutTextAndPaginated() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.textOnEveryPage(45, UUID.randomUUID().toString()));
		String documentId = job.get("documentId").asString();

		JsonNode first = api.readJson(
				api.withKey(get("/api/v1/documents/{id}/pages?page=0&size=20", documentId), reviewKey), 200);
		JsonNode last = api.readJson(
				api.withKey(get("/api/v1/documents/{id}/pages?page=2&size=20", documentId), reviewKey), 200);

		assertThat(first.get("totalItems").asInt()).isEqualTo(45);
		assertThat(first.get("items").get(0).get("pageNumber").asInt()).isEqualTo(1);
		assertThat(first.get("items").get(0).has("text")).as("listings never carry text").isFalse();
		assertThat(last.get("items").size()).isEqualTo(5);
		assertThat(last.get("items").get(4).get("pageNumber").asInt()).isEqualTo(45);
		api.perform(api.withKey(get("/api/v1/documents/{id}/pages/46", documentId), reviewKey))
			.andExpect(status().isNotFound());
	}

	@Test
	void uploadOnlyKeySeesTheSummaryButNoPageContent() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.textOnEveryPage(2, UUID.randomUUID().toString()));
		String documentId = job.get("documentId").asString();
		String uploadOnly = api.issueKey(integrationId, "KB_READ", "DOCUMENT_UPLOAD").plaintext();

		api.perform(api.withKey(get("/api/v1/documents/{id}/extraction", documentId), uploadOnly))
			.andExpect(status().isOk());
		api.perform(api.withKey(get("/api/v1/documents/{id}/pages", documentId), uploadOnly))
			.andExpect(status().isForbidden());
		api.perform(api.withKey(get("/api/v1/documents/{id}/pages/1", documentId), uploadOnly))
			.andExpect(status().isForbidden());
	}

	@Test
	void anotherIntegrationSeesNothing() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.textOnEveryPage(1, UUID.randomUUID().toString()));
		String documentId = job.get("documentId").asString();
		UUID other = api.createIntegration(ApiTestClient.uniqueCode("other"));
		String otherKey = api.issueKey(other, "KB_READ", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();

		for (String path : List.of("/api/v1/documents/{id}/extraction", "/api/v1/documents/{id}/pages",
				"/api/v1/documents/{id}/pages/1")) {
			api.perform(api.withKey(get(path, documentId), otherKey)).andExpect(status().isNotFound());
		}
	}

	@Test
	void crashedExtractionResumesAfterTheLastCommittedPageWithoutDuplicates() throws Exception {
		runner.stop();
		JsonNode upload = api.uploadAccepted(reviewKey, kb, TestPdfs.textOnEveryPage(45, UUID.randomUUID().toString()));
		UUID versionId = UUID.fromString(upload.get("versionId").asString());
		UUID jobId = UUID.fromString(upload.get("jobId").asString());
		UUID runId = simulateCrashAfterFirstBatch(versionId, jobId, 45, 20);

		runner.start();
		JsonNode job = api.awaitJob(reviewKey, jobId.toString(), JOB_TIMEOUT);

		assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(job.get("versionStatus").asString()).isEqualTo("EXTRACTED");
		JdbcTemplate superuser = superuser();
		assertThat(superuser.queryForObject("select count(*) from mimorag.page_extraction where extraction_run_id = ?",
				Integer.class, runId)).isEqualTo(45);
		assertThat(superuser.queryForObject("""
				select count(*) from mimorag.page_extraction
				where extraction_run_id = ? and page_number <= 20 and native_text like 'BEFORE CRASH%'""",
				Integer.class, runId)).as("committed pages are kept, not redone").isEqualTo(20);
		assertThat(page(job, 21).get("text").asString()).startsWith("Page 21 of the employee handbook");
		assertThat(superuser.queryForObject("select pages_done from mimorag.extraction_run where id = ?",
				Integer.class, runId)).isEqualTo(45);
	}

	@Test
	void storingTheSamePageTwiceKeepsOneRowEvenConcurrently() throws Exception {
		JsonNode job = uploadAndWait(TestPdfs.textOnEveryPage(1, UUID.randomUUID().toString()));
		UUID runId = superuser().queryForObject(
				"select id from mimorag.extraction_run where job_id = ?", UUID.class,
				UUID.fromString(job.get("id").asString()));
		AccessContext context = AccessContext.integrationService(integrationId, "a1b2c3d4e5f6a7b8c9d0e1f2",
				Set.of(Permission.DOCUMENT_UPLOAD), "corr-12345678");
		PageAnalysis duplicate = PageClassifier.failed(1, PageWarning.PAGE_READ_ERROR, 0);
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		int sequential = AccessContextHolder.callAs(context, () -> transaction
			.execute(status -> pageStore.insert(integrationId, runId, List.of(duplicate), Instant.now())));
		assertThat(sequential).isZero();

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Integer>> attempts = new ArrayList<>();
			PageAnalysis page2 = PageClassifier.failed(2, PageWarning.PAGE_READ_ERROR, 0);
			for (int i = 0; i < 4; i++) {
				attempts.add(() -> AccessContextHolder.callAs(context, () -> transaction
					.execute(status -> pageStore.insert(integrationId, runId, List.of(page2), Instant.now()))));
			}
			int inserted = 0;
			for (var result : pool.invokeAll(attempts)) {
				inserted += result.get();
			}
			assertThat(inserted).isEqualTo(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(superuser().queryForObject("""
				select count(*) from mimorag.page_extraction where extraction_run_id = ? and page_number = 2""",
				Integer.class, runId)).isEqualTo(1);
	}

	@Test
	void pageRowsAreReadOnlyForTheApplicationRole() throws Exception {
		uploadAndWait(TestPdfs.textOnEveryPage(1, UUID.randomUUID().toString()));

		assertThatThrownBy(() -> appRoleJdbc.update("update page_extraction set native_text = 'changed'"))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied for table page_extraction");
		assertThatThrownBy(() -> appRoleJdbc.update("delete from page_extraction"))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied for table page_extraction");
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private JsonNode uploadAndWait(byte[] pdf) throws Exception {
		JsonNode upload = api.uploadAccepted(reviewKey, kb, pdf);
		JsonNode job = api.awaitJob(reviewKey, upload.get("jobId").asString(), JOB_TIMEOUT);
		assertThat(job.get("status").asString()).as(job.toString()).isEqualTo("SUCCEEDED");
		return job;
	}

	private JsonNode page(JsonNode job, int number) throws Exception {
		return api.readJson(api.withKey(get("/api/v1/documents/{id}/pages/{n}", job.get("documentId").asString(),
				number), reviewKey), 200);
	}

	/**
	 * The state a worker leaves when it dies after committing its first batch: version validated and extracting, a
	 * running run owned by the job, and the first {@code committed} pages stored (marked so the test can tell them
	 * apart from pages extracted after the restart).
	 */
	private UUID simulateCrashAfterFirstBatch(UUID versionId, UUID jobId, int pages, int committed) {
		JdbcTemplate superuser = superuser();
		superuser.update("update mimorag.document_version set status = 'EXTRACTING', page_count = ? where id = ?",
				pages, versionId);
		UUID runId = UUID.randomUUID();
		superuser.update("""
				insert into mimorag.extraction_run (id, integration_id, document_version_id, job_id, engine, engine_version,
				    status, pages_total, pages_done, pages_native, pages_ocr_required, pages_mixed, pages_empty,
				    pages_failed, started_at, updated_at, version)
				values (?, ?, ?, ?, 'pdfbox+linebuilder', 'test', 'RUNNING', ?, ?, ?, 0, 0, 0, 0, now(), now(), 0)""",
				runId, integrationId, versionId, jobId, pages, committed, committed);
		for (int page = 1; page <= committed; page++) {
			superuser.update("""
					insert into mimorag.page_extraction (id, integration_id, extraction_run_id, page_number, page_class,
					    native_text, native_text_trusted, result_text, scripts, warnings, quality, lines, images,
					    image_coverage, page_width, page_height, rotation, duration_ms, created_at)
					values (?, ?, ?, ?, 'NATIVE_TEXT', ?, true, ?, '{}', '{}', '{}', '[]', '[]', 0, 612, 792, 0, 0, now())""",
					UUID.randomUUID(), integrationId, runId, page, "BEFORE CRASH " + page, "BEFORE CRASH " + page);
		}
		return runId;
	}

	private static JdbcTemplate superuser() {
		return new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

}
