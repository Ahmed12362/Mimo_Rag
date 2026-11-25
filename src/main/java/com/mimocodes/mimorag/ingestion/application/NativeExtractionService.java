package com.mimocodes.mimorag.ingestion.application;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage.StoredObjectMissingException;
import com.mimocodes.mimorag.ingestion.application.NativeTextExtractor.NativePdf;
import com.mimocodes.mimorag.ingestion.application.NativeTextExtractor.PageReadException;
import com.mimocodes.mimorag.ingestion.application.NativeTextExtractor.PageTimeoutException;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.ingestion.domain.ExtractionRun;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;
import com.mimocodes.mimorag.jobs.application.JobFailure;
import com.mimocodes.mimorag.jobs.application.JobHandler.JobExecution;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobQueue.NewJob;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Extracts native text page by page for a validated document version.
 * <ul>
 * <li><b>Bounded memory:</b> one page in memory at a time; the PDF stays open between pages with caches off.</li>
 * <li><b>Crash recovery:</b> one run per job; pages are committed in batches together with the run's counters, and a
 * resumed job continues after the highest committed page. Page rows are insert-only and unique per run, so even a
 * second worker after a lost lease cannot store a page twice.</li>
 * <li><b>Isolation of failures:</b> a page that times out or cannot be parsed is stored as {@code FAILED} with a
 * warning; the other pages continue.</li>
 * </ul>
 * No database transaction is open while a page is read.
 */
@Service
public class NativeExtractionService {

	private final ExtractionRunRepository runs;

	private final PageExtractionStore pages;

	private final DocumentVersionRepository versions;

	private final DocumentStorage storage;

	private final NativeTextExtractor extractor;

	private final AuditRecorder audit;

	private final IngestionProperties properties;

	private final Clock clock;

	private final MeterRegistry meters;

	private final TransactionTemplate transaction;

	private final JobQueue jobs;

	private final OcrProperties ocr;

	NativeExtractionService(ExtractionRunRepository runs, PageExtractionStore pages,
			DocumentVersionRepository versions, DocumentStorage storage, NativeTextExtractor extractor,
			AuditRecorder audit, IngestionProperties properties, Clock clock, MeterRegistry meters,
			PlatformTransactionManager transactionManager, JobQueue jobs, OcrProperties ocr) {
		this.jobs = jobs;
		this.ocr = ocr;
		this.runs = runs;
		this.pages = pages;
		this.versions = versions;
		this.storage = storage;
		this.extractor = extractor;
		this.audit = audit;
		this.properties = properties;
		this.clock = clock;
		this.meters = meters;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/** Extracts (or resumes extracting) the version's pages; returns the version's final status. */
	public DocumentVersionStatus extract(JobExecution execution, UUID versionId) {
		UUID integrationId = execution.access().requireIntegrationId();
		ExtractionRun run = openRun(execution, versionId);
		// Inside a transaction: only then is the tenant applied for Row-Level Security.
		DocumentVersion version = transaction.execute(status -> load(integrationId, versionId));
		if (run.getStatus() != ExtractionRun.Status.COMPLETED) {
			int from = pages.highestPage(integrationId, run.getId()) + 1;
			if (from <= run.getPagesTotal()) {
				extractPages(integrationId, run.getId(), version.getStorageKey(), from, run.getPagesTotal());
			}
		}
		return finish(execution.access(), run.getId(), versionId);
	}

	private ExtractionRun openRun(JobExecution execution, UUID versionId) {
		UUID integrationId = execution.access().requireIntegrationId();
		try {
			return transaction.execute(status -> {
				DocumentVersion version = load(integrationId, versionId);
				if (version.getStatus() == DocumentVersionStatus.VALIDATED) {
					version.startExtraction(clock.instant());
					versions.saveAndFlush(version);
				}
				ExtractionRun run = runs.findByIntegrationIdAndJobId(integrationId, execution.jobId())
					.orElseGet(() -> ExtractionRun.start(version, execution.jobId(), extractor.engine(),
							extractor.engineVersion(), clock.instant()));
				run.resume(clock.instant());
				return runs.saveAndFlush(run);
			});
		}
		catch (DataIntegrityViolationException concurrentStart) {
			// Another worker created the run for this job a moment earlier: continue with it.
			return transaction.execute(status -> runs.findByIntegrationIdAndJobId(integrationId, execution.jobId())
				.orElseThrow(() -> concurrentStart));
		}
	}

	private void extractPages(UUID integrationId, UUID runId, String storageKey, int from, int to) {
		try {
			storage.withLocalFile(storageKey, path -> {
				try (NativePdf pdf = extractor.open(path)) {
					List<PageAnalysis> batch = new ArrayList<>(properties.extractionBatchSize());
					for (int pageNumber = from; pageNumber <= to; pageNumber++) {
						batch.add(analyse(pdf, pageNumber));
						if (batch.size() == properties.extractionBatchSize() || pageNumber == to) {
							commit(integrationId, runId, batch);
							batch.clear();
						}
					}
				}
				return null;
			});
		}
		catch (StoredObjectMissingException missing) {
			throw JobFailure.permanent("STORED_FILE_MISSING");
		}
		catch (UncheckedIOException io) {
			throw JobFailure.transientFailure("STORAGE_UNAVAILABLE", io);
		}
	}

	private PageAnalysis analyse(NativePdf pdf, int pageNumber) {
		long started = System.nanoTime();
		try {
			PageAnalysis analysis = PageClassifier.classify(pdf.page(pageNumber, properties.pageTimeout()));
			return analysis.withDuration(elapsedMillis(started));
		}
		catch (PageTimeoutException timeout) {
			return PageClassifier.failed(pageNumber, PageWarning.PAGE_TIMEOUT, elapsedMillis(started));
		}
		catch (PageReadException unreadable) {
			return PageClassifier.failed(pageNumber, PageWarning.PAGE_READ_ERROR, elapsedMillis(started));
		}
		finally {
			meters.timer("mimorag.ingestion.extraction.page").record(java.time.Duration.ofNanos(System.nanoTime() - started));
		}
	}

	/** One transaction: the batch's page rows (duplicates skipped) and the run's counters recounted from the table. */
	private void commit(UUID integrationId, UUID runId, List<PageAnalysis> batch) {
		transaction.executeWithoutResult(status -> {
			pages.insert(integrationId, runId, batch, clock.instant());
			ExtractionRun run = runs.findByIdAndIntegrationId(runId, integrationId).orElseThrow();
			run.recordProgress(pages.countByClass(integrationId, runId), clock.instant());
			runs.saveAndFlush(run);
		});
	}

	/**
	 * Completes the run and the version in one transaction. When OCR is enabled and pages need it, the
	 * {@code OCR_DOCUMENT} job is enqueued in the same transaction, so a crash can never leave a version waiting for an
	 * OCR job that does not exist (and a resumed job never enqueues a second one).
	 */
	private DocumentVersionStatus finish(AccessContext access, UUID runId, UUID versionId) {
		UUID integrationId = access.requireIntegrationId();
		return transaction.execute(status -> {
			ExtractionRun run = runs.findByIdAndIntegrationId(runId, integrationId).orElseThrow();
			run.recordProgress(pages.countByClass(integrationId, runId), clock.instant());
			if (run.getStatus() != ExtractionRun.Status.COMPLETED) {
				run.complete(clock.instant());
			}
			runs.saveAndFlush(run);
			DocumentVersion version = load(integrationId, versionId);
			if (version.getStatus() == DocumentVersionStatus.EXTRACTING) {
				version.finishExtraction(run.needsOcr(), clock.instant());
				versions.saveAndFlush(version);
				audit.record(AuditEvent.success("DOCUMENT_EXTRACTED", "document", version.getDocumentId())
					.withDetails(Map.of("versionId", versionId.toString(), "runId", runId.toString(), "pagesNative",
							run.getPagesNative(), "pagesOcrRequired", run.getPagesOcrRequired(), "pagesMixed",
							run.getPagesMixed(), "pagesEmpty", run.getPagesEmpty(), "pagesFailed",
							run.getPagesFailed())));
			}
			if (version.getStatus() == DocumentVersionStatus.AWAITING_OCR && version.getOcrJobId() == null
					&& ocr.enabled()) {
				UUID jobId = jobs.enqueue(access, new NewJob(OcrService.OCR_DOCUMENT,
						Map.of("documentId", version.getDocumentId().toString(), "versionId", versionId.toString(),
								"knowledgeBaseId", version.getKnowledgeBaseId().toString())));
				version.assignOcrJob(jobId, clock.instant());
				versions.saveAndFlush(version);
			}
			return version.getStatus();
		});
	}

	/** Marks the job's run failed (called when the job ends without success). */
	public void failRun(UUID integrationId, UUID jobId, String code) {
		runs.findByIntegrationIdAndJobId(integrationId, jobId).ifPresent(run -> {
			run.fail(code, clock.instant());
			runs.saveAndFlush(run);
		});
	}

	private DocumentVersion load(UUID integrationId, UUID versionId) {
		return versions.findByIdAndIntegrationId(versionId, integrationId)
			.orElseThrow(() -> JobFailure.permanent("DOCUMENT_VERSION_NOT_FOUND"));
	}

	private static long elapsedMillis(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
