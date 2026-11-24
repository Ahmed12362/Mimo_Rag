package com.mimocodes.mimorag.ingestion.application;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage.IncomingFile;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.UploadResult;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.UploadResult.Outcome;
import com.mimocodes.mimorag.ingestion.domain.Document;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobQueue.NewJob;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.PayloadTooLargeException;
import com.mimocodes.mimorag.shared.error.QuotaExceededException;
import com.mimocodes.mimorag.shared.error.UnsupportedContentException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts PDF uploads without ever parsing them on the request thread (decision D-26). The request only streams the
 * file to storage (hashing it on the way), checks limits and the PDF signature, and records the document and its
 * extraction job in one transaction. The worker does everything else.
 */
@Service
public class DocumentUploadService {

	public static final String EXTRACT_DOCUMENT = "EXTRACT_DOCUMENT";

	private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,200}");

	private static final String RESOURCE = "document";

	private final KnowledgeBaseService knowledgeBases;

	private final DocumentRepository documents;

	private final DocumentVersionRepository versions;

	private final DocumentStorage storage;

	private final JobQueue jobs;

	private final AuditRecorder audit;

	private final IngestionProperties properties;

	private final Clock clock;

	private final MeterRegistry meters;

	private final TransactionTemplate transaction;

	private final TransactionTemplate readOnly;

	DocumentUploadService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
			DocumentVersionRepository versions, DocumentStorage storage, JobQueue jobs, AuditRecorder audit,
			IngestionProperties properties, Clock clock, MeterRegistry meters,
			PlatformTransactionManager transactionManager) {
		this.knowledgeBases = knowledgeBases;
		this.documents = documents;
		this.versions = versions;
		this.storage = storage;
		this.jobs = jobs;
		this.audit = audit;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
		this.transaction = new TransactionTemplate(transactionManager);
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
	}

	/**
	 * @param filename client file name (metadata only, sanitised)
	 * @param title optional display title
	 * @param idempotencyKey optional client key; repeating a request with it returns the original result
	 */
	public record UploadCommand(UUID knowledgeBaseId, String filename, String title, String idempotencyKey,
			InputStream content) {
	}

	public UploadResult upload(AccessContext access, UploadCommand command) {
		Timer.Sample sample = Timer.start(meters);
		String outcome = "rejected";
		try {
			UploadResult result = doUpload(access, command);
			outcome = result.outcome().name().toLowerCase();
			return result;
		}
		finally {
			sample.stop(meters.timer("mimorag.ingestion.upload", "outcome", outcome));
		}
	}

	private UploadResult doUpload(AccessContext access, UploadCommand command) {
		if (command.idempotencyKey() != null && !IDEMPOTENCY_KEY.matcher(command.idempotencyKey()).matches()) {
			throw new InvalidRequestException("Idempotency-Key",
					"must be 8-200 characters: letters, digits, '.', '_', ':' or '-'");
		}
		KnowledgeBaseView kb = requireUploadTarget(access, command.knowledgeBaseId());
		if (jobs.countUnfinished(access, EXTRACT_DOCUMENT) >= properties.maxUnfinishedJobsPerIntegration()) {
			rejected(kb.id(), "UPLOAD_QUEUE_FULL");
			throw new QuotaExceededException(QuotaExceededException.Quota.UPLOAD_QUEUE,
					"Too many documents are waiting to be processed. Retry later.", properties.queueFullRetryAfter());
		}

		IncomingFile incoming;
		try {
			incoming = storage.receive(command.content(), properties.maxFileSize().toBytes());
		}
		catch (PayloadTooLargeException tooLarge) {
			rejected(kb.id(), "FILE_TOO_LARGE");
			throw tooLarge;
		}
		boolean kept = false;
		try {
			if (incoming.sizeBytes() == 0) {
				rejected(kb.id(), "EMPTY_FILE");
				throw new InvalidRequestException("file", "must not be empty");
			}
			if (!incoming.startsLikePdf()) {
				rejected(kb.id(), "NOT_A_PDF");
				throw new UnsupportedContentException("Only PDF files are accepted, and this content is not a PDF.");
			}
			Optional<UploadResult> earlier = earlierResult(access, kb.id(), command.idempotencyKey(), incoming);
			if (earlier.isPresent()) {
				return earlier.get();
			}
			long stored = readOnly.execute(status -> versions.totalStoredBytes(access.requireIntegrationId()));
			if (stored + incoming.sizeBytes() > properties.storageQuotaPerIntegration().toBytes()) {
				rejected(kb.id(), "STORAGE_QUOTA_EXCEEDED");
				throw new QuotaExceededException(QuotaExceededException.Quota.STORAGE,
						"This upload would exceed the integration's storage quota.", null);
			}
			UploadResult created = storeAndRecord(access, kb, command, incoming);
			kept = true;
			return created;
		}
		finally {
			if (!kept) {
				storage.discard(incoming);
			}
		}
	}

	/** Moves the file to its final key, then records everything in one transaction; removes the file if that fails. */
	private UploadResult storeAndRecord(AccessContext access, KnowledgeBaseView kb, UploadCommand command,
			IncomingFile incoming) {
		UUID integrationId = access.requireIntegrationId();
		UUID documentId = UUID.randomUUID();
		UUID versionId = UUID.randomUUID();
		String key = DocumentStorage.originalKey(integrationId, documentId, versionId);
		storage.store(incoming, key);
		try {
			return transaction.execute(status -> {
				Instant now = clock.instant();
				String filename = UntrustedText.filename(command.filename());
				Document document = documents.saveAndFlush(Document.create(documentId, integrationId, kb.id(),
						UntrustedText.title(command.title(), filename), filename, access.principalRef(), now));
				UUID jobId = jobs.enqueue(access, new NewJob(EXTRACT_DOCUMENT, Map.of("documentId",
						documentId.toString(), "versionId", versionId.toString(), "knowledgeBaseId", kb.id().toString())));
				DocumentVersion version = versions.saveAndFlush(DocumentVersion.firstVersion(versionId, document,
						new DocumentVersion.StoredFile(key, incoming.sha256(), incoming.sizeBytes()),
						command.idempotencyKey(), jobId, access.principalRef(), now));
				document.makeCurrent(version, now);
				documents.saveAndFlush(document);
				Map<String, Object> details = new HashMap<>();
				details.put("knowledgeBaseId", kb.id().toString());
				details.put("versionId", versionId.toString());
				details.put("jobId", jobId.toString());
				details.put("sizeBytes", incoming.sizeBytes());
				details.put("sha256", incoming.sha256());
				audit.record(AuditEvent.success("DOCUMENT_UPLOADED", RESOURCE, documentId).withDetails(details));
				return new UploadResult(Outcome.CREATED, documentId, versionId, jobId, DocumentVersionStatus.UPLOADED,
						null);
			});
		}
		catch (DataIntegrityViolationException concurrentDuplicate) {
			// Another request stored the same content (or used the same Idempotency-Key) a moment earlier.
			storage.delete(key);
			return earlierResult(access, kb.id(), command.idempotencyKey(), incoming)
				.orElseThrow(() -> new ConflictException("The upload conflicts with a concurrent upload. Retry."));
		}
		catch (RuntimeException ex) {
			storage.delete(key);
			throw ex;
		}
	}

	/** Same Idempotency-Key (replay) or same content in the same Knowledge Base (duplicate, D-28). */
	private Optional<UploadResult> earlierResult(AccessContext access, UUID knowledgeBaseId, String idempotencyKey,
			IncomingFile incoming) {
		UUID integrationId = access.requireIntegrationId();
		if (idempotencyKey != null) {
			Optional<DocumentVersion> replay = readOnly
				.execute(status -> versions.findByIntegrationIdAndIdempotencyKey(integrationId, idempotencyKey));
			if (replay.isPresent()) {
				DocumentVersion v = replay.get();
				if (!v.getSha256().equals(incoming.sha256()) || !v.getKnowledgeBaseId().equals(knowledgeBaseId)) {
					throw new ConflictException("This Idempotency-Key was already used for a different upload.");
				}
				return Optional.of(new UploadResult(Outcome.REPLAYED, v.getDocumentId(), v.getId(),
						v.getExtractionJobId(), v.getStatus(), null));
			}
		}
		Optional<DocumentVersion> duplicate = readOnly.execute(status -> versions
			.findByIntegrationIdAndKnowledgeBaseIdAndSha256(integrationId, knowledgeBaseId, incoming.sha256()));
		if (duplicate.isEmpty()) {
			return Optional.empty();
		}
		DocumentVersion v = duplicate.get();
		transaction.executeWithoutResult(status -> audit.record(AuditEvent
			.success("DOCUMENT_UPLOAD_DUPLICATE", RESOURCE, v.getDocumentId())
			.withDetails(Map.of("knowledgeBaseId", knowledgeBaseId.toString(), "versionId", v.getId().toString()))));
		return Optional.of(new UploadResult(Outcome.DUPLICATE, v.getDocumentId(), v.getId(), v.getExtractionJobId(),
				v.getStatus(), v.getDocumentId()));
	}

	private KnowledgeBaseView requireUploadTarget(AccessContext access, UUID knowledgeBaseId) {
		KnowledgeBaseView kb = knowledgeBases.get(access, knowledgeBaseId);
		if (!kb.yourPermissions().contains(Permission.DOCUMENT_UPLOAD)) {
			audit.recordIndependently(AuditEvent.denied("DOCUMENT_UPLOAD", "knowledge-base", kb.id()));
			throw new OperationNotPermittedException(Permission.DOCUMENT_UPLOAD);
		}
		if (kb.status() != KnowledgeBaseStatus.ACTIVE) {
			throw new ConflictException("The knowledge base is archived and does not accept uploads.");
		}
		return kb;
	}

	private void rejected(UUID knowledgeBaseId, String reason) {
		audit.recordIndependently(new AuditEvent("DOCUMENT_UPLOAD_REJECTED", "knowledge-base",
				knowledgeBaseId.toString(), AuditOutcome.DENIED, Map.of("reason", reason)));
	}

}
