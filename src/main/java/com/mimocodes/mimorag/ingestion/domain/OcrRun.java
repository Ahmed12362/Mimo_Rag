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

/** One OCR pass over the pages of a version that need it, owned by one {@code OCR_DOCUMENT} job. */
@Entity
@Table(name = "ocr_run")
public class OcrRun {

	public enum Status {

		RUNNING, COMPLETED, FAILED

	}

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private UUID documentVersionId;

	@Column(nullable = false, updatable = false)
	private UUID extractionRunId;

	@Column(nullable = false, updatable = false)
	private UUID jobId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false, updatable = false)
	private int pagesTotal;

	@Column(nullable = false)
	private int pagesDone;

	@Column(nullable = false)
	private int pagesFailed;

	private String failureCode;

	@Column(nullable = false, updatable = false)
	private Instant startedAt;

	private Instant finishedAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected OcrRun() {
	}

	public static OcrRun start(DocumentVersion documentVersion, UUID extractionRunId, UUID jobId, int pagesTotal,
			Instant now) {
		OcrRun run = new OcrRun();
		run.id = UUID.randomUUID();
		run.integrationId = documentVersion.getIntegrationId();
		run.documentVersionId = documentVersion.getId();
		run.extractionRunId = Objects.requireNonNull(extractionRunId, "extractionRunId");
		run.jobId = Objects.requireNonNull(jobId, "jobId");
		run.status = Status.RUNNING;
		run.pagesTotal = pagesTotal;
		run.startedAt = now;
		run.updatedAt = now;
		return run;
	}

	/** Replaces the counters with values counted from the committed page rows. */
	public void recordProgress(int done, int failed, Instant now) {
		this.pagesDone = done;
		this.pagesFailed = failed;
		this.updatedAt = now;
	}

	public void complete(Instant now) {
		if (pagesDone != pagesTotal) {
			throw new IllegalStateException("OCR run " + id + " has " + pagesDone + " of " + pagesTotal + " pages");
		}
		this.status = Status.COMPLETED;
		this.finishedAt = now;
		this.updatedAt = now;
	}

	public void fail(String code, Instant now) {
		if (status == Status.COMPLETED) {
			return;
		}
		this.status = Status.FAILED;
		this.failureCode = Objects.requireNonNull(code, "code");
		this.finishedAt = now;
		this.updatedAt = now;
	}

	public void resume(Instant now) {
		if (status == Status.FAILED) {
			this.status = Status.RUNNING;
			this.failureCode = null;
			this.finishedAt = null;
			this.updatedAt = now;
		}
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public UUID getDocumentVersionId() {
		return documentVersionId;
	}

	public UUID getExtractionRunId() {
		return extractionRunId;
	}

	public UUID getJobId() {
		return jobId;
	}

	public Status getStatus() {
		return status;
	}

	public int getPagesTotal() {
		return pagesTotal;
	}

	public int getPagesDone() {
		return pagesDone;
	}

	public int getPagesFailed() {
		return pagesFailed;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public Instant getFinishedAt() {
		return finishedAt;
	}

}
