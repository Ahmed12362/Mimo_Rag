package com.mimocodes.mimorag.ingestion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Upload, background validation, rejections, duplicates and idempotency through the real HTTP API and worker. */
class DocumentUploadIntegrationTest extends PostgresIntegrationTest {

	private static final Duration JOB_TIMEOUT = Duration.ofSeconds(20);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private ApiTestClient api;

	private UUID integrationId;

	private String key;

	private UUID kb;

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		integrationId = api.createIntegration(ApiTestClient.uniqueCode("docs"));
		key = api.issueKey(integrationId, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW").plaintext();
		kb = api.createKnowledgeBase(key, ApiTestClient.uniqueCode("kb"));
	}

	@Test
	void validPdfIsAcceptedImmediatelyAndValidatedInTheBackground() throws Exception {
		byte[] pdf = TestPdfs.withPages(3, UUID.randomUUID().toString());

		MvcResult response = api.perform(api.upload(key, kb, "../../HR Policies‮.pdf", pdf))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.outcome").value("CREATED"))
			.andExpect(jsonPath("$.status").value("UPLOADED"))
			.andReturn();
		JsonNode body = jsonMapper.readTree(response.getResponse().getContentAsString());
		String jobId = body.get("jobId").asString();
		assertThat(response.getResponse().getHeader("Location")).isEqualTo("/api/v1/ingestion-jobs/" + jobId);

		JsonNode job = api.awaitJob(key, jobId, JOB_TIMEOUT);
		assertThat(job.get("status").asString()).isEqualTo("SUCCEEDED");
		assertThat(job.get("versionStatus").asString()).isEqualTo("EXTRACTED"); // 2c: validation continues into extraction
		assertThat(job.get("pageCount").asInt()).isEqualTo(3);

		String documentId = body.get("documentId").asString();
		JsonNode document = api.readJson(api.withKey(get("/api/v1/documents/{id}", documentId), key), 200);
		assertThat(document.get("originalFilename").asString()).isEqualTo("HR Policies.pdf");
		assertThat(document.get("title").asString()).isEqualTo("HR Policies");
		JsonNode version = document.get("versions").get(0);
		assertThat(version.get("sha256").asString()).isEqualTo(sha256(pdf));
		assertThat(version.get("sizeBytes").asLong()).isEqualTo(pdf.length);
		assertThat(version.get("pageCount").asInt()).isEqualTo(3);
		assertThat(document.toString()).doesNotContain("storageKey", "test-storage", "original.pdf");

		JsonNode list = api.readJson(api.withKey(get("/api/v1/knowledge-bases/{id}/documents", kb), key), 200);
		assertThat(list.get("totalItems").asInt()).isEqualTo(1);
		assertThat(list.get("items").get(0).get("currentVersion").get("status").asString()).isEqualTo("EXTRACTED");

		Path stored = Path.of("target/test-storage", integrationId.toString(), documentId,
				body.get("versionId").asString(), "original.pdf");
		assertThat(Files.readAllBytes(stored)).isEqualTo(pdf);
	}

	@Test
	void contentThatIsNotAPdfIsRejectedWhateverItsNameAndDeclaredType() throws Exception {
		api.perform(api.upload(key, kb, "report.pdf", "<html>not a pdf</html>".getBytes()))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"));

		assertNoDocuments();
	}

	@Test
	void emptyFileIsRejected() throws Exception {
		api.perform(api.upload(key, kb, "empty.pdf", new byte[0]))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
		assertNoDocuments();
	}

	@Test
	void fileOverTheSizeLimitIsRejected() throws Exception {
		byte[] oversized = new byte[50 * 1024 * 1024 + 1];
		System.arraycopy("%PDF-1.7".getBytes(), 0, oversized, 0, 8);

		api.perform(api.upload(key, kb, "huge.pdf", oversized))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.errorCode").value("PAYLOAD_TOO_LARGE"));
		assertNoDocuments();
	}

	@ParameterizedTest
	@CsvSource({ "password, PDF_ENCRYPTED", "owner-only, PDF_ENCRYPTED", "corrupt, PDF_CORRUPT",
			"no-pages, PDF_EMPTY", "too-many-pages, PDF_TOO_MANY_PAGES" })
	void invalidPdfsFailInTheWorkerWithASpecificCode(String kind, String expectedCode) throws Exception {
		String marker = UUID.randomUUID().toString();
		byte[] pdf = switch (kind) {
			case "password" -> TestPdfs.passwordProtected(marker);
			case "owner-only" -> TestPdfs.ownerPasswordOnly(marker);
			case "corrupt" -> TestPdfs.corrupt(marker);
			case "no-pages" -> TestPdfs.withoutPages(marker);
			default -> TestPdfs.withPages(501, marker);
		};

		JsonNode accepted = api.uploadAccepted(key, kb, pdf);
		JsonNode job = api.awaitJob(key, accepted.get("jobId").asString(), JOB_TIMEOUT);

		assertThat(job.get("status").asString()).isEqualTo("FAILED");
		assertThat(job.get("errorCode").asString()).isEqualTo(expectedCode);
		assertThat(job.get("retryable").asBoolean()).isFalse();
		assertThat(job.get("attempts").asInt()).as("permanent failures are not retried").isEqualTo(1);
		assertThat(job.get("versionStatus").asString()).isEqualTo("VALIDATION_FAILED");
		JsonNode document = api.readJson(
				api.withKey(get("/api/v1/documents/{id}", accepted.get("documentId").asString()), key), 200);
		assertThat(document.get("versions").get(0).get("failureCode").asString()).isEqualTo(expectedCode);

		api.perform(api.withKey(post("/api/v1/ingestion-jobs/{id}/retry", accepted.get("jobId").asString()), key))
			.andExpect(status().isConflict());
	}

	@Test
	void sameContentInTheSameKnowledgeBaseResolvesToTheExistingDocument() throws Exception {
		byte[] pdf = TestPdfs.withPages(1, UUID.randomUUID().toString());
		JsonNode first = api.uploadAccepted(key, kb, pdf);

		api.perform(api.upload(key, kb, "copy.pdf", pdf))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("DUPLICATE"))
			.andExpect(jsonPath("$.duplicateOf").value(first.get("documentId").asString()))
			.andExpect(jsonPath("$.jobId").value(first.get("jobId").asString()));

		UUID otherKb = api.createKnowledgeBase(key, ApiTestClient.uniqueCode("kb"));
		api.perform(api.upload(key, otherKb, "copy.pdf", pdf))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.outcome").value("CREATED"));
	}

	@Test
	void concurrentUploadsOfTheSameFileCreateOneDocument() throws Exception {
		byte[] pdf = TestPdfs.withPages(2, UUID.randomUUID().toString());
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<MvcResult>> uploads = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				uploads.add(() -> api.perform(api.upload(key, kb, "same.pdf", pdf)).andReturn());
			}
			List<JsonNode> bodies = new ArrayList<>();
			for (Future<MvcResult> result : pool.invokeAll(uploads)) {
				assertThat(result.get().getResponse().getStatus()).isIn(200, 202);
				bodies.add(jsonMapper.readTree(result.get().getResponse().getContentAsString()));
			}
			assertThat(bodies).filteredOn(b -> b.get("outcome").asString().equals("CREATED")).hasSize(1);
			assertThat(bodies).extracting(b -> b.get("documentId").asString()).containsOnly(
					bodies.get(0).get("documentId").asString());
		}
		finally {
			pool.shutdownNow();
		}
		JsonNode list = api.readJson(api.withKey(get("/api/v1/knowledge-bases/{id}/documents", kb), key), 200);
		assertThat(list.get("totalItems").asInt()).isEqualTo(1);
	}

	@Test
	void idempotencyKeyReplaysTheOriginalResultAndRejectsReuseForOtherContent() throws Exception {
		byte[] pdf = TestPdfs.withPages(1, UUID.randomUUID().toString());
		String idempotencyKey = "upload-" + UUID.randomUUID();
		JsonNode first = api.readJson(api.upload(key, kb, "a.pdf", pdf).header("Idempotency-Key", idempotencyKey),
				202);

		api.perform(api.upload(key, kb, "a.pdf", pdf).header("Idempotency-Key", idempotencyKey))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.outcome").value("REPLAYED"))
			.andExpect(jsonPath("$.documentId").value(first.get("documentId").asString()))
			.andExpect(jsonPath("$.jobId").value(first.get("jobId").asString()));

		api.perform(api.upload(key, kb, "b.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString()))
			.header("Idempotency-Key", idempotencyKey)).andExpect(status().isConflict());

		api.perform(api.upload(key, kb, "c.pdf", pdf).header("Idempotency-Key", "bad key!"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
	}

	@Test
	void uploadRequiresTheUploadPermission() throws Exception {
		String readOnlyKey = api.issueKey(integrationId, "KB_READ").plaintext();

		api.perform(api.upload(readOnlyKey, kb, "a.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
		api.perform(api.withKey(get("/api/v1/knowledge-bases/{id}/documents", kb), readOnlyKey))
			.andExpect(status().isForbidden());
	}

	@Test
	void archivedKnowledgeBaseDoesNotAcceptUploads() throws Exception {
		api.archiveKnowledgeBase(key, kb);

		api.perform(api.upload(key, kb, "a.pdf", TestPdfs.withPages(1, UUID.randomUUID().toString())))
			.andExpect(status().isConflict());
	}

	@Test
	void unknownKnowledgeBaseIsNotFound() throws Exception {
		api.perform(api.upload(key, UUID.randomUUID(), "a.pdf", TestPdfs.withPages(1, "x")))
			.andExpect(status().isNotFound())
			.andExpect(header().doesNotExist("Location"));
	}

	private void assertNoDocuments() throws Exception {
		JsonNode list = api.readJson(api.withKey(get("/api/v1/knowledge-bases/{id}/documents", kb), key), 200);
		assertThat(list.get("totalItems").asInt()).isZero();
	}

	private static String sha256(byte[] content) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

}
