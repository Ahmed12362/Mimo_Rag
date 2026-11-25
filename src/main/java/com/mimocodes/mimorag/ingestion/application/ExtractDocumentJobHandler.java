package com.mimocodes.mimorag.ingestion.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage.StoredObjectMissingException;
import com.mimocodes.mimorag.ingestion.application.PdfInspector.PdfRejectedException;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.jobs.application.JobFailure;
import com.mimocodes.mimorag.jobs.application.JobHandler;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseStatus;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker for {@code EXTRACT_DOCUMENT}: re-checks that the Knowledge Base still accepts the work, then
 * <ol>
 * <li>validates (2b): stored file integrity, PDF structure, page count;</li>
 * <li>extracts native text page by page (2c, {@link NativeExtractionService}).</li>
 * </ol>
 * OCR (2d) continues from {@code AWAITING_OCR}. Every step is idempotent: a resumed or retried job skips what is done.
 * No database transaction is held while the file is read or parsed.
 */
@Component
class ExtractDocumentJobHandler implements JobHandler {

	static final String STORED_FILE_MISSING = "STORED_FILE_MISSING";

	static final String STORED_FILE_INTEGRITY_ERROR = "STORED_FILE_INTEGRITY_ERROR";

	static final String STORAGE_UNAVAILABLE = "STORAGE_UNAVAILABLE";

	private final KnowledgeBaseService knowledgeBases;

	private final DocumentVersionRepository versions;

	private final DocumentStorage storage;

	private final PdfInspector inspector;

	private final AuditRecorder audit;

	private final IngestionProperties properties;

	private final Clock clock;

	private final TransactionTemplate transaction;

	private final NativeExtractionService extraction;

	private final KnowledgeBaseGate gate;

	ExtractDocumentJobHandler(KnowledgeBaseService knowledgeBases, DocumentVersionRepository versions,
			DocumentStorage storage, PdfInspector inspector, AuditRecorder audit, IngestionProperties properties,
			Clock clock, PlatformTransactionManager transactionManager, NativeExtractionService extraction,
			KnowledgeBaseGate gate) {
		this.extraction = extraction;
		this.gate = gate;
		this.knowledgeBases = knowledgeBases;
		this.versions = versions;
		this.storage = storage;
		this.inspector = inspector;
		this.audit = audit;
		this.properties = properties;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Override
	public String kind() {
		return DocumentUploadService.EXTRACT_DOCUMENT;
	}

	@Override
	public void handle(JobExecution execution) {
		UUID versionId = execution.requireUuid("versionId");
		gate.requireStillAcceptsWork(execution.access(), execution.requireUuid("knowledgeBaseId"));

		DocumentVersion version = transaction.execute(status -> {
			DocumentVersion v = load(execution, versionId);
			if (v.getStatus() == DocumentVersionStatus.UPLOADED) {
				v.startValidation(clock.instant());
				return versions.saveAndFlush(v);
			}
			return v;
		});
		if (version.getStatus() == DocumentVersionStatus.VALIDATING) {
			validate(execution, version);
		}
		// Inside a transaction: only then is the tenant applied for Row-Level Security.
		DocumentVersionStatus status = transaction.execute(tx -> load(execution, versionId).getStatus());
		if (status == DocumentVersionStatus.VALIDATED || status == DocumentVersionStatus.EXTRACTING) {
			extraction.extract(execution, versionId);
		}
	}

	/** Slice 2b: integrity, structure and page count. */
	private void validate(JobExecution execution, DocumentVersion version) {
		UUID versionId = version.getId();
		int pages;
		try {
			pages = storage.withLocalFile(version.getStorageKey(), path -> {
				if (!sha256(path).equals(version.getSha256())) {
					throw JobFailure.permanent(STORED_FILE_INTEGRITY_ERROR);
				}
				return inspector.countPages(path, properties.maxPages(), properties.validationTimeout());
			});
		}
		catch (PdfRejectedException rejected) {
			throw JobFailure.permanent(rejected.getCode());
		}
		catch (StoredObjectMissingException missing) {
			throw JobFailure.permanent(STORED_FILE_MISSING);
		}
		catch (UncheckedIOException io) {
			throw JobFailure.transientFailure(STORAGE_UNAVAILABLE, io);
		}

		transaction.executeWithoutResult(status -> {
			DocumentVersion v = load(execution, versionId);
			if (v.getStatus() != DocumentVersionStatus.VALIDATED) {
				v.markValidated(pages, clock.instant());
				versions.saveAndFlush(v);
				audit.record(AuditEvent.success("DOCUMENT_VALIDATED", "document", v.getDocumentId())
					.withDetails(Map.of("versionId", v.getId().toString(), "pageCount", pages)));
			}
		});
	}

	@Override
	public void onFinalFailure(JobExecution execution, String errorCode) {
		UUID versionId = execution.requireUuid("versionId");
		transaction.executeWithoutResult(status -> {
			DocumentVersion v = load(execution, versionId);
			extraction.failRun(v.getIntegrationId(), execution.jobId(), errorCode);
			if (v.markFailed(errorCode, clock.instant())) {
				versions.saveAndFlush(v);
				String action = v.getStatus() == DocumentVersionStatus.EXTRACTION_FAILED ? "DOCUMENT_EXTRACTION_FAILED"
						: "DOCUMENT_VALIDATION_FAILED";
				audit.record(new AuditEvent(action, "document", v.getDocumentId().toString(), AuditOutcome.FAILURE,
						Map.of("versionId", v.getId().toString(), "errorCode", errorCode)));
			}
		});
	}

	private DocumentVersion load(JobExecution execution, UUID versionId) {
		return versions.findByIdAndIntegrationId(versionId, execution.access().requireIntegrationId())
			.orElseThrow(() -> JobFailure.permanent("DOCUMENT_VERSION_NOT_FOUND"));
	}

	private static String sha256(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[64 * 1024];
			for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
				digest.update(buffer, 0, read);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
