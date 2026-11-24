package com.mimocodes.mimorag.ingestion.application;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mimocodes.mimorag.ingestion.application.DocumentViews.DocumentDetails;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.DocumentSummary;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.IngestionJobView;
import com.mimocodes.mimorag.ingestion.domain.Document;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.ExtractionRun;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobQueue.JobView;
import com.mimocodes.mimorag.jobs.application.JobStatus;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Documents and ingestion jobs of the caller's integration. Access requires {@code DOCUMENT_UPLOAD} or
 * {@code DOCUMENT_REVIEW} on the document's Knowledge Base; anything the caller cannot see (another tenant, a KB
 * outside a restricted key, a KB without these permissions) is reported exactly like an unknown id (404).
 */
@Service
public class DocumentQueryService {

	private static final Set<Permission> READ_PERMISSIONS = KnowledgeBaseGate.DOCUMENT_PERMISSIONS;

	private final KnowledgeBaseService knowledgeBases;

	private final DocumentRepository documents;

	private final DocumentVersionRepository versions;

	private final JobQueue jobs;

	private final AuditRecorder audit;

	private final Clock clock;

	private final KnowledgeBaseGate gate;

	private final ExtractionRunRepository runs;

	DocumentQueryService(KnowledgeBaseService knowledgeBases, DocumentRepository documents,
			DocumentVersionRepository versions, JobQueue jobs, AuditRecorder audit, Clock clock, KnowledgeBaseGate gate,
			ExtractionRunRepository runs) {
		this.gate = gate;
		this.runs = runs;
		this.knowledgeBases = knowledgeBases;
		this.documents = documents;
		this.versions = versions;
		this.jobs = jobs;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public Page<DocumentSummary> list(AccessContext access, UUID knowledgeBaseId, Pageable pageable) {
		KnowledgeBaseView kb = knowledgeBases.get(access, knowledgeBaseId);
		if (READ_PERMISSIONS.stream().noneMatch(kb.yourPermissions()::contains)) {
			audit.recordIndependently(AuditEvent.denied("DOCUMENT_LIST", "knowledge-base", kb.id()));
			throw new OperationNotPermittedException(Permission.DOCUMENT_REVIEW);
		}
		UUID integrationId = access.requireIntegrationId();
		Page<Document> page = documents.findAllByIntegrationIdAndKnowledgeBaseId(integrationId, kb.id(), pageable);
		Set<UUID> currentIds = page.stream()
			.map(Document::getCurrentVersionId)
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
		Map<UUID, DocumentVersion> current = currentIds.isEmpty() ? Map.of()
				: versions.findAllByIntegrationIdAndIdIn(integrationId, currentIds)
					.stream()
					.collect(Collectors.toMap(DocumentVersion::getId, Function.identity()));
		return page.map(d -> DocumentSummary.of(d, current.get(d.getCurrentVersionId())));
	}

	@Transactional(readOnly = true)
	public DocumentDetails get(AccessContext access, UUID documentId) {
		UUID integrationId = access.requireIntegrationId();
		Document document = documents.findByIdAndIntegrationId(documentId, integrationId)
			.orElseThrow(() -> new ResourceNotFoundException("document"));
		requireReadable(access, document.getKnowledgeBaseId(), "document");
		return DocumentDetails.of(document,
				versions.findAllByIntegrationIdAndDocumentIdOrderByVersionNumberDesc(integrationId, documentId));
	}

	@Transactional(readOnly = true)
	public IngestionJobView job(AccessContext access, UUID jobId) {
		JobView job = loadJob(access, jobId);
		DocumentVersion version = versionOf(access, job);
		requireReadable(access, version.getKnowledgeBaseId(), "ingestion-job");
		return IngestionJobView.of(job, version, runOf(access, job));
	}

	/**
	 * Requeues a failed or cancelled job. Repeating the call is safe: a job that is already queued or running is
	 * returned unchanged; a finished, successful job is a conflict.
	 */
	@Transactional
	public IngestionJobView retry(AccessContext access, UUID jobId) {
		JobView job = loadJob(access, jobId);
		DocumentVersion version = versionOf(access, job);
		KnowledgeBaseView kb = requireReadable(access, version.getKnowledgeBaseId(), "ingestion-job");
		if (!kb.yourPermissions().contains(Permission.DOCUMENT_UPLOAD)) {
			audit.recordIndependently(AuditEvent.denied("INGESTION_JOB_RETRY", "ingestion-job", jobId));
			throw new OperationNotPermittedException(Permission.DOCUMENT_UPLOAD);
		}
		if (!job.status().isFinished()) {
			return IngestionJobView.of(job, version, runOf(access, job));
		}
		if (job.status() == JobStatus.SUCCEEDED) {
			throw new ConflictException("The job already succeeded.");
		}
		if (!IngestionJobView.canRetry(job)) {
			throw new ConflictException("The job failed with " + job.errorCode() + "; retrying cannot change that.");
		}
		if (!jobs.requeue(access, jobId)) {
			throw new ConflictException("The job changed concurrently. Reload it and retry.");
		}
		if (version.isFailed()) {
			version.resetForRetry(clock.instant());
			versions.saveAndFlush(version);
		}
		audit.record(AuditEvent.success("INGESTION_JOB_RETRIED", "ingestion-job", jobId)
			.withDetails(Map.of("previousErrorCode", String.valueOf(job.errorCode()))));
		return IngestionJobView.of(loadJob(access, jobId), version, runOf(access, job));
	}

	private Optional<ExtractionRun> runOf(AccessContext access,
			JobView job) {
		return runs.findByIntegrationIdAndJobId(access.requireIntegrationId(), job.id());
	}

	private JobView loadJob(AccessContext access, UUID jobId) {
		return jobs.find(access, jobId)
			.filter(job -> DocumentUploadService.EXTRACT_DOCUMENT.equals(job.kind()))
			.orElseThrow(() -> new ResourceNotFoundException("ingestion-job"));
	}

	private DocumentVersion versionOf(AccessContext access, JobView job) {
		return versions.findByIntegrationIdAndExtractionJobId(access.requireIntegrationId(), job.id())
			.orElseThrow(() -> new ResourceNotFoundException("ingestion-job"));
	}

	/** The Knowledge Base, if the caller may read its documents; otherwise "not found" for {@code resource}. */
	private KnowledgeBaseView requireReadable(AccessContext access, UUID knowledgeBaseId, String resource) {
		return gate.requireDocumentAccess(access, knowledgeBaseId, resource);
	}

}
