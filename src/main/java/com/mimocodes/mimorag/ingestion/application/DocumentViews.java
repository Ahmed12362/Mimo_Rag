package com.mimocodes.mimorag.ingestion.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.Document;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.jobs.application.JobQueue.JobView;
import com.mimocodes.mimorag.jobs.application.JobStatus;

/** Read models returned by the ingestion API. They never contain storage keys or file paths. */
public final class DocumentViews {

	private DocumentViews() {
	}

	public record VersionView(UUID id, int versionNumber, DocumentVersionStatus status, String failureCode,
			String sha256, long sizeBytes, String contentType, Integer pageCount, UUID jobId, Instant uploadedAt,
			String uploadedBy, Instant validatedAt) {

		static VersionView of(DocumentVersion v) {
			return new VersionView(v.getId(), v.getVersionNumber(), v.getStatus(), v.getFailureCode(), v.getSha256(),
					v.getSizeBytes(), v.getContentType(), v.getPageCount(), v.getExtractionJobId(), v.getUploadedAt(),
					v.getUploadedBy(), v.getValidatedAt());
		}

	}

	/** A document in a listing, with its current version. */
	public record DocumentSummary(UUID id, UUID knowledgeBaseId, String title, String originalFilename,
			Instant createdAt, String createdBy, Instant updatedAt, VersionView currentVersion) {

		static DocumentSummary of(Document d, DocumentVersion current) {
			return new DocumentSummary(d.getId(), d.getKnowledgeBaseId(), d.getTitle(), d.getOriginalFilename(),
					d.getCreatedAt(), d.getCreatedBy(), d.getUpdatedAt(), current == null ? null : VersionView.of(current));
		}

	}

	/** One document with all its versions, newest first. */
	public record DocumentDetails(UUID id, UUID knowledgeBaseId, String title, String originalFilename,
			Instant createdAt, String createdBy, Instant updatedAt, UUID currentVersionId, List<VersionView> versions) {

		static DocumentDetails of(Document d, List<DocumentVersion> versions) {
			return new DocumentDetails(d.getId(), d.getKnowledgeBaseId(), d.getTitle(), d.getOriginalFilename(),
					d.getCreatedAt(), d.getCreatedBy(), d.getUpdatedAt(), d.getCurrentVersionId(),
					versions.stream().map(VersionView::of).toList());
		}

	}

	/**
	 * Result of an upload.
	 *
	 * @param outcome {@code CREATED} (new document, 202), {@code REPLAYED} (same Idempotency-Key and content, 202) or
	 * {@code DUPLICATE} (same content already in this Knowledge Base, 200)
	 * @param duplicateOf the existing document when {@code outcome} is {@code DUPLICATE}
	 */
	public record UploadResult(Outcome outcome, UUID documentId, UUID versionId, UUID jobId,
			DocumentVersionStatus status, UUID duplicateOf) {

		public enum Outcome {

			CREATED, REPLAYED, DUPLICATE

		}

	}

	/** Status of an ingestion job, linked to the document version it processes. */
	public record IngestionJobView(UUID id, String kind, JobStatus status, int attempts, int maxAttempts,
			String errorCode, boolean retryable, UUID knowledgeBaseId, UUID documentId, UUID versionId,
			DocumentVersionStatus versionStatus, Integer pageCount, Instant createdAt, Instant startedAt,
			Instant finishedAt, Instant nextAttemptAt) {

		static IngestionJobView of(JobView job, DocumentVersion version) {
			return new IngestionJobView(job.id(), job.kind(), job.status(), job.attempts(), job.maxAttempts(),
					job.errorCode(), canRetry(job),
					version.getKnowledgeBaseId(), version.getDocumentId(), version.getId(), version.getStatus(),
					version.getPageCount(), job.createdAt(), job.startedAt(), job.finishedAt(),
					job.status() == JobStatus.RETRY_SCHEDULED ? job.runAfter() : null);
		}

		/** Whether {@code POST /ingestion-jobs/{id}/retry} can requeue the job now. */
		static boolean canRetry(JobView job) {
			return job.retryable()
					&& (job.status() == JobStatus.FAILED || job.status() == JobStatus.CANCELLED_REVALIDATION);
		}

	}

}
