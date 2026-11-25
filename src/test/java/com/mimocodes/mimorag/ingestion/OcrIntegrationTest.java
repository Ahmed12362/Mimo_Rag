package com.mimocodes.mimorag.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mimocodes.mimorag.ingestion.application.PageOcrStore;
import com.mimocodes.mimorag.ingestion.application.PageOcrStore.PageOcrResult;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.FakeOcrServer;
import com.mimocodes.mimorag.support.FakeOcrServer.Line;
import com.mimocodes.mimorag.support.FakeOcrServer.Outcome;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
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
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OCR through the real API, worker and HTTP client against {@link FakeOcrServer}: results kept apart from approved
 * text, MIXED regions and de-duplication, every failure path, retries, resume without duplicates, isolation and
 * read-only rows. The real engine is covered by the opt-in {@code ocr-it} suite.
 */
class OcrIntegrationTest extends PostgresIntegrationTest {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(60);

	private static final FakeOcrServer OCR = FakeOcrServer.instance();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private PageOcrStore ocrStore;

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
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("ocr"));
		reviewKey = api.issueKey(integrationId, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		kb = api.createKnowledgeBase(reviewKey, ApiTestClient.uniqueCode("kb"));
	}

	@Test
	void scannedPageIsRecognisedButKeptApartFromApprovedText() throws Exception {
		String corr = correlation("scan");
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		JsonNode ocrJob = awaitOcr(doc);

		assertThat(ocrJob.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(ocrJob.get("kind").asString()).isEqualTo("OCR_DOCUMENT");
		assertThat(ocrJob.get("versionStatus").asString()).isEqualTo("OCR_COMPLETED");
		assertThat(ocrJob.get("progress").get("pagesDone").asInt()).isEqualTo(1);
		assertThat(ocrJob.get("progress").get("pagesTotal").asInt()).isEqualTo(1);

		JsonNode page = page(doc, 1);
		assertThat(page.get("text").isNull()).as("OCR never becomes approved page text").isTrue();
		JsonNode ocr = page.get("ocr");
		assertThat(ocr.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(ocr.get("reviewState").asString()).isEqualTo("UNREVIEWED");
		assertThat(ocr.get("mode").asString()).isEqualTo("PAGE");
		assertThat(ocr.get("renderDpi").asInt()).isEqualTo(300);
		assertThat(ocr.get("originalText").asString()).isEqualTo("سياسة الإجازات");
		assertThat(ocr.get("proposedText").asString()).isEqualTo("سياسة الإجازات 2026");
		assertThat(ocr.get("recoveredTokens").asInt()).isEqualTo(1);
		assertThat(ocr.get("flags").toString()).contains("TOKEN_RECOVERED");
		assertThat(ocr.get("engine").get("pipeline").asString()).isEqualTo("fake/1");
		JsonNode box = ocr.get("lines").get(0).get("box");
		assertThat(box.get("x").asDouble()).as("boxes are stored in page points").isCloseTo(61.2, within(0.5));
		assertThat(box.get("width").asDouble()).isCloseTo(489.6, within(0.5));

		JsonNode summary = api.readJson(api.withKey(get("/api/v1/documents/{id}/extraction", doc.documentId()),
				reviewKey), 200);
		assertThat(summary.get("ocr").get("status").asString()).isEqualTo("COMPLETED");
		assertThat(summary.get("ocr").get("reviewState").asString()).isEqualTo("UNREVIEWED");

		List<FakeOcrServer.Request> requests = OCR.requests(corr);
		assertThat(requests).hasSize(1);
		FakeOcrServer.Request request = requests.get(0);
		assertThat(request.tokenValid()).isTrue();
		assertThat(request.contentType()).isEqualTo("image/png");
		assertThat(request.profile()).isEqualTo("ar-en");
		assertThat(request.orientation()).isEqualTo("auto");
		// 612 x 792 points at 300 DPI (PDFBox rounding may lose a pixel).
		assertThat(request.width()).isCloseTo(2550, within(1));
		assertThat(request.height()).isCloseTo(3300, within(1));
	}

	@Test
	void mixedPageSendsOnlyItsPhotoAndDropsLinesAlreadyInTheNativeText() throws Exception {
		String corr = correlation("mixed");
		// Region with a 4 pt margin: x 68-476, y 288-596 (top-left origin), i.e. 408 x 308 points.
		Line caption = new Line((80 - 68) / 408.0, (435 - 288) / 308.0, 120 / 408.0, 7 / 308.0,
				"تعليق", "تعليق", List.of());
		Line photoText = Line.arabic(0.1, 0.1, 0.5, 0.05);
		OCR.script(corr, List.of(caption, photoText));
		Uploaded doc = upload(TestPdfs.photoWithCaption(corr), corr);
		JsonNode ocrJob = awaitOcr(doc);

		assertThat(ocrJob.get("status").asString()).isEqualTo("SUCCEEDED");
		JsonNode page = page(doc, 1);
		assertThat(page.get("pageClass").asString()).isEqualTo("MIXED");
		assertThat(page.get("text").asString()).contains("Employees must read this notice carefully");
		JsonNode ocr = page.get("ocr");
		assertThat(ocr.get("mode").asString()).isEqualTo("REGIONS");
		assertThat(ocr.get("droppedOverlappingLines").asInt()).isEqualTo(1);
		assertThat(ocr.get("lines").size()).isEqualTo(1);
		assertThat(ocr.get("lines").get(0).get("originalText").asString()).isEqualTo("سياسة الإجازات");
		assertThat(ocr.get("regions").size()).isEqualTo(1);
		FakeOcrServer.Request request = OCR.requests(corr).get(0);
		assertThat(request.width()).as("only the photo region is rendered").isCloseTo(1700, within(1));
		assertThat(request.height()).isCloseTo(1283, within(1));
	}

	@Test
	void engineErrorsAreRetriedForThePage() throws Exception {
		String corr = correlation("engine");
		OCR.script(corr, Outcome.ENGINE_ERROR, Outcome.ENGINE_ERROR);
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		JsonNode ocrJob = awaitOcr(doc);

		assertThat(ocrJob.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(ocrJob.get("attempts").asInt()).isEqualTo(1);
		assertThat(page(doc, 1).get("ocr").get("status").asString()).isEqualTo("COMPLETED");
		assertThat(OCR.requests(corr)).hasSize(3);
	}

	@Test
	void pageFailuresAreStoredPerPageWhileTheOtherPagesContinue() throws Exception {
		String corr = correlation("pagefail");
		OCR.script(corr, Outcome.ENGINE_ERROR, Outcome.ENGINE_ERROR, Outcome.ENGINE_ERROR, // page 1: engine fails 3x
				Outcome.REJECTED, // page 2: image refused
				Outcome.ALTERS_ARABIC, // page 3: proposal changes the Arabic text
				Outcome.WRONG_SIZE, // page 4: answer for another image
				Outcome.NOT_JSON, // page 5: not contract JSON
				Outcome.TIMEOUT, Outcome.TIMEOUT, Outcome.TIMEOUT); // page 6: over the time limit 3x; page 7: OK
		Uploaded doc = upload(TestPdfs.imageOnlyPages(7, corr), corr);
		JsonNode ocrJob = awaitOcr(doc);

		assertThat(ocrJob.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(ocrJob.get("versionStatus").asString()).isEqualTo("OCR_COMPLETED");
		Map<Integer, String> expected = Map.of(1, "OCR_ENGINE_ERROR", 2, "IMAGE_TOO_LARGE", 3, "OCR_RESPONSE_INVALID", 4,
				"OCR_RESPONSE_INVALID", 5, "OCR_RESPONSE_INVALID", 6, "OCR_TIMEOUT");
		for (var entry : expected.entrySet()) {
			JsonNode ocr = page(doc, entry.getKey()).get("ocr");
			assertThat(ocr.get("status").asString()).as("page " + entry.getKey()).isEqualTo("FAILED");
			assertThat(ocr.get("errorCode").asString()).as("page " + entry.getKey()).isEqualTo(entry.getValue());
			assertThat(ocr.get("proposedText").asString()).isEmpty();
		}
		assertThat(page(doc, 7).get("ocr").get("status").asString()).isEqualTo("COMPLETED");
		JsonNode summary = api.readJson(api.withKey(get("/api/v1/documents/{id}/extraction", doc.documentId()),
				reviewKey), 200);
		assertThat(summary.get("ocr").get("pagesDone").asInt()).isEqualTo(7);
		assertThat(summary.get("ocr").get("pagesFailed").asInt()).isEqualTo(6);
	}

	@Test
	void unavailableServiceFailsTheVersionAfterAllAttemptsAndCanBeRetried() throws Exception {
		String corr = correlation("busy");
		OCR.script(corr, Outcome.BUSY, Outcome.BUSY, Outcome.BUSY);
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		JsonNode failed = awaitOcr(doc);

		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.get("errorCode").asString()).isEqualTo("SERVICE_BUSY");
		assertThat(failed.get("attempts").asInt()).isEqualTo(3);
		assertThat(failed.get("versionStatus").asString()).isEqualTo("OCR_FAILED");
		assertThat(failed.get("retryable").asBoolean()).isTrue();
		JsonNode summary = api.readJson(api.withKey(get("/api/v1/documents/{id}/extraction", doc.documentId()),
				reviewKey), 200);
		assertThat(summary.get("ocr").get("status").asString()).isEqualTo("FAILED");

		api.readJson(api.withKey(post("/api/v1/ingestion-jobs/{id}/retry", doc.ocrJobId()), reviewKey), 202);
		JsonNode retried = api.awaitJob(reviewKey, doc.ocrJobId(), JOB_TIMEOUT);

		assertThat(retried.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(retried.get("versionStatus").asString()).isEqualTo("OCR_COMPLETED");
		assertThat(page(doc, 1).get("ocr").get("status").asString()).isEqualTo("COMPLETED");
	}

	@Test
	void rejectedServiceTokenFailsTheJobWithoutRetrying() throws Exception {
		String corr = correlation("token");
		OCR.script(corr, Outcome.UNAUTHORIZED);
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		JsonNode failed = awaitOcr(doc);

		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.get("errorCode").asString()).isEqualTo("OCR_CONFIGURATION_ERROR");
		assertThat(failed.get("attempts").asInt()).isEqualTo(1);
		assertThat(failed.get("versionStatus").asString()).isEqualTo("OCR_FAILED");
	}

	@Test
	void archivingTheKnowledgeBaseCancelsPendingOcr() throws Exception {
		String corr = correlation("archive");
		OCR.script(corr, Outcome.HOLD);
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		awaitRequests(corr, 1);
		api.archiveKnowledgeBase(reviewKey, kb);
		OCR.release(corr);
		JsonNode cancelled = api.awaitJob(reviewKey, doc.ocrJobId(), JOB_TIMEOUT);

		assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED_REVALIDATION");
		assertThat(cancelled.get("errorCode").asString()).isEqualTo("KNOWLEDGE_BASE_ARCHIVED");
		assertThat(OCR.requests(corr)).hasSize(1);
	}

	@Test
	void crashedOcrResumesAfterTheStoredPagesWithoutDuplicates() throws Exception {
		String corr = correlation("resume");
		Uploaded doc = upload(TestPdfs.imageOnlyPages(3, corr), corr);
		assertThat(awaitOcr(doc).get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(OCR.requests(corr)).hasSize(3);

		// The state a worker leaves when it dies after storing page 1: job running with an expired lease, run and
		// version still running, pages 2-3 not stored yet.
		JdbcTemplate superuser = superuser();
		UUID jobId = UUID.fromString(doc.ocrJobId());
		UUID runId = superuser.queryForObject("select id from mimorag.ocr_run where job_id = ?", UUID.class, jobId);
		superuser.update("update mimorag.page_ocr set original_text = 'BEFORE CRASH' where ocr_run_id = ? and page_number = 1",
				runId);
		superuser.update("delete from mimorag.page_ocr where ocr_run_id = ? and page_number > 1", runId);
		superuser.update("""
				update mimorag.ocr_run set status = 'RUNNING', pages_done = 1, finished_at = null where id = ?""", runId);
		superuser.update("update mimorag.document_version set status = 'OCR_RUNNING' where ocr_job_id = ?", jobId);
		superuser.update("""
				update mimorag.job set status = 'RUNNING', lease_owner = 'crashed-worker',
				    lease_expires_at = now() - interval '1 second', finished_at = null, attempts = 1 where id = ?""",
				jobId);

		JsonNode resumed = awaitJobAttempt(doc.ocrJobId(), 2);

		assertThat(resumed.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(resumed.get("versionStatus").asString()).isEqualTo("OCR_COMPLETED");
		assertThat(superuser.queryForObject("select count(*) from mimorag.page_ocr where ocr_run_id = ?", Integer.class,
				runId)).isEqualTo(3);
		assertThat(page(doc, 1).get("ocr").get("originalText").asString()).as("stored pages are kept, not redone")
			.isEqualTo("BEFORE CRASH");
		assertThat(OCR.requests(corr)).as("only pages 2 and 3 are recognised again").hasSize(5);
		assertThat(superuser.queryForObject("select pages_done from mimorag.ocr_run where id = ?", Integer.class, runId))
			.isEqualTo(3);
	}

	@Test
	void storingTheSamePageTwiceKeepsOneRowEvenConcurrently() throws Exception {
		String corr = correlation("dup");
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		awaitOcr(doc);
		UUID runId = superuser().queryForObject("select id from mimorag.ocr_run where job_id = ?", UUID.class,
				UUID.fromString(doc.ocrJobId()));
		AccessContext context = AccessContext.integrationService(integrationId, "a1b2c3d4e5f6a7b8c9d0e1f2",
				Set.of(Permission.DOCUMENT_UPLOAD), "corr-12345678");
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		PageOcrResult again = PageOcrResult.failed(1, "PAGE", 300, "OCR_ENGINE_ERROR", 0);

		int sequential = AccessContextHolder.callAs(context,
				() -> transaction.execute(status -> ocrStore.insert(integrationId, runId, again, Instant.now())));
		assertThat(sequential).isZero();

		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			PageOcrResult page2 = PageOcrResult.failed(2, "PAGE", 300, "OCR_ENGINE_ERROR", 0);
			List<Callable<Integer>> attempts = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				attempts.add(() -> AccessContextHolder.callAs(context,
						() -> transaction.execute(status -> ocrStore.insert(integrationId, runId, page2, Instant.now()))));
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
	}

	@Test
	void ocrRowsAreReadOnlyForTheApplicationRole() throws Exception {
		String corr = correlation("readonly");
		awaitOcr(upload(TestPdfs.imageOnly(corr), corr));

		assertThatThrownBy(() -> appRoleJdbc.update("update page_ocr set proposed_text = 'changed'"))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied for table page_ocr");
		assertThatThrownBy(() -> appRoleJdbc.update("delete from page_ocr"))
			.isInstanceOf(DataAccessException.class)
			.rootCause()
			.hasMessageContaining("permission denied for table page_ocr");
	}

	@Test
	void ocrResultsAreInvisibleToOtherIntegrationsAndUploadOnlyKeys() throws Exception {
		String corr = correlation("iso");
		Uploaded doc = upload(TestPdfs.imageOnly(corr), corr);
		awaitOcr(doc);
		UUID other = api.createIntegration(ApiTestClient.uniqueCode("other"));
		String otherKey = api.issueKey(other, "KB_READ", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		String uploadOnly = api.issueKey(integrationId, "KB_READ", "DOCUMENT_UPLOAD").plaintext();

		api.perform(api.withKey(get("/api/v1/ingestion-jobs/{id}", doc.ocrJobId()), otherKey))
			.andExpect(status().isNotFound());
		api.perform(api.withKey(get("/api/v1/documents/{id}/pages/1", doc.documentId()), otherKey))
			.andExpect(status().isNotFound());
		api.perform(api.withKey(get("/api/v1/documents/{id}/pages/1", doc.documentId()), uploadOnly))
			.andExpect(status().isForbidden());
		assertThat(superuser().queryForObject("""
				select count(*) from mimorag.page_ocr p join mimorag.ocr_run r on r.id = p.ocr_run_id
				where r.job_id = ? and p.integration_id <> r.integration_id""", Integer.class,
				UUID.fromString(doc.ocrJobId()))).isZero();
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private record Uploaded(String documentId, String extractionJobId, String ocrJobId) {
	}

	private static String correlation(String scenario) {
		return "ocr-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	/** Uploads, waits for extraction, and returns the OCR job it enqueued. */
	private Uploaded upload(byte[] pdf, String correlationId) throws Exception {
		JsonNode upload = api.uploadAccepted(reviewKey, kb, pdf, correlationId);
		JsonNode extraction = api.awaitJob(reviewKey, upload.get("jobId").asString(), JOB_TIMEOUT);
		assertThat(extraction.get("status").asString()).as(extraction.toString()).isEqualTo("SUCCEEDED");
		assertThat(extraction.get("nextJobId").isNull()).as("extraction enqueued OCR: " + extraction).isFalse();
		return new Uploaded(upload.get("documentId").asString(), upload.get("jobId").asString(),
				extraction.get("nextJobId").asString());
	}

	private JsonNode awaitOcr(Uploaded doc) throws Exception {
		return api.awaitJob(reviewKey, doc.ocrJobId(), JOB_TIMEOUT);
	}

	private JsonNode awaitJobAttempt(String jobId, int attempt) throws Exception {
		Instant deadline = Instant.now().plus(JOB_TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			JsonNode job = api.readJson(api.withKey(get("/api/v1/ingestion-jobs/{id}", jobId), reviewKey), 200);
			if (job.get("attempts").asInt() >= attempt && job.get("status").asString().equals("SUCCEEDED")) {
				return job;
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Job " + jobId + " did not finish attempt " + attempt);
	}

	private void awaitRequests(String prefix, int count) throws InterruptedException {
		Instant deadline = Instant.now().plus(JOB_TIMEOUT);
		while (OCR.requests(prefix).size() < count) {
			if (Instant.now().isAfter(deadline)) {
				throw new AssertionError("OCR service did not receive " + count + " requests for " + prefix);
			}
			Thread.sleep(50);
		}
	}

	private JsonNode page(Uploaded doc, int number) throws Exception {
		return api.readJson(api.withKey(get("/api/v1/documents/{id}/pages/{n}", doc.documentId(), number), reviewKey),
				200);
	}

	private static JdbcTemplate superuser() {
		return new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

}
