package com.mimocodes.mimorag.ingestion.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One uploaded file. The stored original, its hash, size and storage key never change; only the processing state
 * moves forward through explicit transitions.
 */
@Entity
@Table(name = "document_version")
public class DocumentVersion {

	public static final String PDF_CONTENT_TYPE = "application/pdf";

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private UUID documentId;

	@Column(nullable = false, updatable = false)
	private UUID knowledgeBaseId;

	@Column(nullable = false, updatable = false)
	private int versionNumber;

	@Column(nullable = false, updatable = false)
	private String storageKey;

	@Column(nullable = false, updatable = false)
	private String sha256;

	@Column(nullable = false, updatable = false)
	private long sizeBytes;

	@Column(nullable = false, updatable = false)
	private String contentType;

	private Integer pageCount;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DocumentVersionStatus status;

	private String failureCode;

	@Column(updatable = false)
	private String idempotencyKey;

	@Column(nullable = false, updatable = false)
	private UUID extractionJobId;

	@Column(nullable = false, updatable = false)
	private Instant uploadedAt;

	@Column(nullable = false, updatable = false)
	private String uploadedBy;

	private Instant validatedAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected DocumentVersion() {
	}

	/** Facts about a stored upload. */
	public record StoredFile(String storageKey, String sha256, long sizeBytes) {

		public StoredFile {
			Objects.requireNonNull(storageKey, "storageKey");
			if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
			}
			if (sizeBytes <= 0) {
				throw new IllegalArgumentException("sizeBytes must be positive");
			}
		}

	}

	public static DocumentVersion firstVersion(UUID id, Document document, StoredFile file, String idempotencyKey,
			UUID extractionJobId, String uploadedBy, Instant now) {
		DocumentVersion version = new DocumentVersion();
		version.id = Objects.requireNonNull(id, "id");
		version.integrationId = document.getIntegrationId();
		version.documentId = document.getId();
		version.knowledgeBaseId = document.getKnowledgeBaseId();
		version.versionNumber = 1;
		version.storageKey = file.storageKey();
		version.sha256 = file.sha256();
		version.sizeBytes = file.sizeBytes();
		version.contentType = PDF_CONTENT_TYPE;
		version.status = DocumentVersionStatus.UPLOADED;
		version.idempotencyKey = idempotencyKey;
		version.extractionJobId = Objects.requireNonNull(extractionJobId, "extractionJobId");
		version.uploadedBy = Objects.requireNonNull(uploadedBy, "uploadedBy");
		version.uploadedAt = now;
		version.updatedAt = now;
		return version;
	}

	public void startValidation(Instant now) {
		requireStatus(DocumentVersionStatus.UPLOADED, DocumentVersionStatus.VALIDATING);
		this.status = DocumentVersionStatus.VALIDATING;
		this.updatedAt = now;
	}

	public void markValidated(int pages, Instant now) {
		requireStatus(DocumentVersionStatus.UPLOADED, DocumentVersionStatus.VALIDATING);
		if (pages < 1) {
			throw new IllegalArgumentException("A validated PDF has at least one page");
		}
		this.status = DocumentVersionStatus.VALIDATED;
		this.pageCount = pages;
		this.failureCode = null;
		this.validatedAt = now;
		this.updatedAt = now;
	}

	public void startExtraction(Instant now) {
		requireStatus(DocumentVersionStatus.VALIDATED, DocumentVersionStatus.EXTRACTING);
		this.status = DocumentVersionStatus.EXTRACTING;
		this.updatedAt = now;
	}

	/** @param needsOcr some pages (or image regions) need OCR */
	public void finishExtraction(boolean needsOcr, Instant now) {
		requireStatus(DocumentVersionStatus.EXTRACTING);
		this.status = needsOcr ? DocumentVersionStatus.AWAITING_OCR : DocumentVersionStatus.EXTRACTED;
		this.updatedAt = now;
	}

	/**
	 * Records a failure in the phase the version is in. A finished extraction keeps its result (a late failure, e.g.
	 * a lost lease, cannot undo it).
	 */
	public boolean markFailed(String code, Instant now) {
		DocumentVersionStatus failed = switch (status) {
			case UPLOADED, VALIDATING, VALIDATION_FAILED -> DocumentVersionStatus.VALIDATION_FAILED;
			case VALIDATED, EXTRACTING, EXTRACTION_FAILED -> DocumentVersionStatus.EXTRACTION_FAILED;
			case EXTRACTED, AWAITING_OCR -> null;
		};
		if (failed == null) {
			return false;
		}
		this.status = failed;
		this.failureCode = Objects.requireNonNull(code, "code");
		this.updatedAt = now;
		return true;
	}

	/**
	 * Back to the queue after an authorised manual retry: a validation failure starts over, an extraction failure
	 * resumes from the validated state (pages already extracted are kept).
	 */
	public void resetForRetry(Instant now) {
		requireStatus(DocumentVersionStatus.VALIDATION_FAILED, DocumentVersionStatus.EXTRACTION_FAILED);
		this.status = status == DocumentVersionStatus.VALIDATION_FAILED ? DocumentVersionStatus.UPLOADED
				: DocumentVersionStatus.VALIDATED;
		this.failureCode = null;
		this.updatedAt = now;
	}

	public boolean isFailed() {
		return status == DocumentVersionStatus.VALIDATION_FAILED || status == DocumentVersionStatus.EXTRACTION_FAILED;
	}

	private void requireStatus(DocumentVersionStatus... allowed) {
		for (DocumentVersionStatus candidate : allowed) {
			if (status == candidate) {
				return;
			}
		}
		throw new IllegalStateException("Document version " + id + " is " + status);
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public UUID getDocumentId() {
		return documentId;
	}

	public UUID getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public int getVersionNumber() {
		return versionNumber;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getSha256() {
		return sha256;
	}

	public long getSizeBytes() {
		return sizeBytes;
	}

	public String getContentType() {
		return contentType;
	}

	public Integer getPageCount() {
		return pageCount;
	}

	public DocumentVersionStatus getStatus() {
		return status;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public UUID getExtractionJobId() {
		return extractionJobId;
	}

	public Instant getUploadedAt() {
		return uploadedAt;
	}

	public String getUploadedBy() {
		return uploadedBy;
	}

	public Instant getValidatedAt() {
		return validatedAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

}
