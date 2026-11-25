package com.mimocodes.mimorag.ingestion.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.PageExtractionStore.PageDetail;
import com.mimocodes.mimorag.ingestion.application.PageExtractionStore.PageSummary;
import com.mimocodes.mimorag.ingestion.domain.Document;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.ingestion.domain.ExtractionRun;
import com.mimocodes.mimorag.ingestion.domain.OcrRun;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extraction results of a document's current version (decision B): the summary needs {@code DOCUMENT_UPLOAD} or
 * {@code DOCUMENT_REVIEW}; page listings and page text need {@code DOCUMENT_REVIEW}. Untrusted native text is never
 * returned (decision E).
 */
@Service
public class PageQueryService {

	private static final String RESOURCE = "document";

	private final DocumentRepository documents;

	private final DocumentVersionRepository versions;

	private final ExtractionRunRepository runs;

	private final PageExtractionStore pages;

	private final KnowledgeBaseGate gate;

	private final AuditRecorder audit;

	private final OcrRunRepository ocrRuns;

	private final PageOcrStore ocrPages;

	PageQueryService(DocumentRepository documents, DocumentVersionRepository versions, ExtractionRunRepository runs,
			PageExtractionStore pages, KnowledgeBaseGate gate, AuditRecorder audit, OcrRunRepository ocrRuns,
			PageOcrStore ocrPages) {
		this.ocrRuns = ocrRuns;
		this.ocrPages = ocrPages;
		this.documents = documents;
		this.versions = versions;
		this.runs = runs;
		this.pages = pages;
		this.gate = gate;
		this.audit = audit;
	}

	/** Extraction progress and page counts per class, plus the OCR run (if any). */
	public record ExtractionSummary(UUID documentId, UUID versionId, DocumentVersionStatus versionStatus,
			Integer pageCount, RunView run, OcrRunView ocr) {
	}

	/** OCR progress. OCR results are proposals awaiting human review; they are never used as approved content. */
	public record OcrRunView(UUID id, OcrRun.Status status, int pagesTotal, int pagesDone, int pagesFailed,
			String failureCode, String reviewState, Instant startedAt, Instant finishedAt) {

		static OcrRunView of(OcrRun run) {
			return new OcrRunView(run.getId(), run.getStatus(), run.getPagesTotal(), run.getPagesDone(),
					run.getPagesFailed(), run.getFailureCode(), "UNREVIEWED", run.getStartedAt(), run.getFinishedAt());
		}

	}

	public record RunView(UUID id, ExtractionRun.Status status, String engine, String engineVersion, int pagesTotal,
			int pagesDone, int pagesNative, int pagesOcrRequired, int pagesMixed, int pagesEmpty, int pagesFailed,
			String failureCode, Instant startedAt, Instant finishedAt) {

		static RunView of(ExtractionRun run) {
			return new RunView(run.getId(), run.getStatus(), run.getEngine(), run.getEngineVersion(),
					run.getPagesTotal(), run.getPagesDone(), run.getPagesNative(), run.getPagesOcrRequired(),
					run.getPagesMixed(), run.getPagesEmpty(), run.getPagesFailed(), run.getFailureCode(),
					run.getStartedAt(), run.getFinishedAt());
		}

	}

	@Transactional(readOnly = true)
	public ExtractionSummary summary(AccessContext access, UUID documentId) {
		Target target = load(access, documentId);
		Optional<ExtractionRun> run = latestRun(access, target.version());
		return new ExtractionSummary(documentId, target.version().getId(), target.version().getStatus(),
				target.version().getPageCount(), run.map(RunView::of).orElse(null),
				latestOcrRun(access, target.version()).map(OcrRunView::of).orElse(null));
	}

	@Transactional(readOnly = true)
	public Page<PageSummary> pages(AccessContext access, UUID documentId, Pageable pageable) {
		Target target = requireReview(access, documentId, "DOCUMENT_PAGES_LIST");
		return latestRun(access, target.version())
			.map(run -> pages.list(access.requireIntegrationId(), run.getId(), pageable))
			.orElseGet(() -> Page.empty(pageable));
	}

	@Transactional(readOnly = true)
	public PageDetail page(AccessContext access, UUID documentId, int pageNumber) {
		Target target = requireReview(access, documentId, "DOCUMENT_PAGE_READ");
		UUID integrationId = access.requireIntegrationId();
		PageDetail detail = latestRun(access, target.version())
			.flatMap(run -> pages.find(integrationId, run.getId(), pageNumber))
			.orElseThrow(() -> new ResourceNotFoundException("page"));
		return latestOcrRun(access, target.version())
			.flatMap(run -> ocrPages.find(integrationId, run.getId(), pageNumber))
			.map(detail::withOcr)
			.orElse(detail);
	}

	private record Target(KnowledgeBaseView knowledgeBase, DocumentVersion version) {
	}

	private Target load(AccessContext access, UUID documentId) {
		UUID integrationId = access.requireIntegrationId();
		Document document = documents.findByIdAndIntegrationId(documentId, integrationId)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		KnowledgeBaseView kb = gate.requireDocumentAccess(access, document.getKnowledgeBaseId(), RESOURCE);
		DocumentVersion version = versions.findByIdAndIntegrationId(document.getCurrentVersionId(), integrationId)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		return new Target(kb, version);
	}

	/** Page content: 404 if the document is invisible, 403 if it is visible but the caller may only upload. */
	private Target requireReview(AccessContext access, UUID documentId, String action) {
		Target target = load(access, documentId);
		if (!target.knowledgeBase().yourPermissions().contains(Permission.DOCUMENT_REVIEW)) {
			audit.recordIndependently(AuditEvent.denied(action, RESOURCE, documentId));
			throw new OperationNotPermittedException(Permission.DOCUMENT_REVIEW);
		}
		return target;
	}

	private Optional<OcrRun> latestOcrRun(AccessContext access, DocumentVersion version) {
		return ocrRuns.findFirstByIntegrationIdAndDocumentVersionIdOrderByStartedAtDesc(access.requireIntegrationId(),
				version.getId());
	}

	private Optional<ExtractionRun> latestRun(AccessContext access, DocumentVersion version) {
		return runs.findFirstByIntegrationIdAndDocumentVersionIdOrderByStartedAtDesc(access.requireIntegrationId(),
				version.getId());
	}

}
