package com.mimocodes.mimorag.ingestion.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One native-extraction pass over a document version, owned by one job. Page results are separate, read-only rows;
 * the run only tracks progress, so a crashed or retried job resumes where its last committed batch ended.
 */
@Entity
@Table(name = "extraction_run")
public class ExtractionRun {

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
	private UUID jobId;

	@Column(nullable = false, updatable = false)
	private String engine;

	@Column(nullable = false, updatable = false)
	private String engineVersion;

	@jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	@Column(nullable = false, updatable = false)
	private int pagesTotal;

	@Column(nullable = false)
	private int pagesDone;

	@Column(nullable = false)
	private int pagesNative;

	@Column(nullable = false)
	private int pagesOcrRequired;

	@Column(nullable = false)
	private int pagesMixed;

	@Column(nullable = false)
	private int pagesEmpty;

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

	protected ExtractionRun() {
	}

	public static ExtractionRun start(DocumentVersion documentVersion, UUID jobId, String engine,
			String engineVersion, Instant now) {
		ExtractionRun run = new ExtractionRun();
		run.id = UUID.randomUUID();
		run.integrationId = documentVersion.getIntegrationId();
		run.documentVersionId = documentVersion.getId();
		run.jobId = Objects.requireNonNull(jobId, "jobId");
		run.engine = Objects.requireNonNull(engine, "engine");
		run.engineVersion = Objects.requireNonNull(engineVersion, "engineVersion");
		run.status = Status.RUNNING;
		run.pagesTotal = Objects.requireNonNull(documentVersion.getPageCount(), "pageCount");
		run.startedAt = now;
		run.updatedAt = now;
		return run;
	}

	/** Replaces the counters with values counted from the committed page rows. */
	public void recordProgress(Map<PageClass, Integer> pagesByClass, Instant now) {
		this.pagesNative = pagesByClass.getOrDefault(PageClass.NATIVE_TEXT, 0);
		this.pagesOcrRequired = pagesByClass.getOrDefault(PageClass.OCR_REQUIRED, 0);
		this.pagesMixed = pagesByClass.getOrDefault(PageClass.MIXED, 0);
		this.pagesEmpty = pagesByClass.getOrDefault(PageClass.EMPTY, 0);
		this.pagesFailed = pagesByClass.getOrDefault(PageClass.FAILED, 0);
		this.pagesDone = pagesNative + pagesOcrRequired + pagesMixed + pagesEmpty + pagesFailed;
		this.updatedAt = now;
	}

	public void complete(Instant now) {
		if (pagesDone != pagesTotal) {
			throw new IllegalStateException("Run " + id + " has " + pagesDone + " of " + pagesTotal + " pages");
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

	/** A retried job continues a failed run. */
	public void resume(Instant now) {
		if (status == Status.FAILED) {
			this.status = Status.RUNNING;
			this.failureCode = null;
			this.finishedAt = null;
			this.updatedAt = now;
		}
	}

	public boolean needsOcr() {
		return pagesOcrRequired > 0 || pagesMixed > 0;
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

	public UUID getJobId() {
		return jobId;
	}

	public String getEngine() {
		return engine;
	}

	public String getEngineVersion() {
		return engineVersion;
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

	public int getPagesNative() {
		return pagesNative;
	}

	public int getPagesOcrRequired() {
		return pagesOcrRequired;
	}

	public int getPagesMixed() {
		return pagesMixed;
	}

	public int getPagesEmpty() {
		return pagesEmpty;
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

	public Instant getUpdatedAt() {
		return updatedAt;
	}

}
