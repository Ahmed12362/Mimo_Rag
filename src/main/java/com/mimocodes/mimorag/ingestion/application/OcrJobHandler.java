package com.mimocodes.mimorag.ingestion.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.jobs.application.JobFailure;
import com.mimocodes.mimorag.jobs.application.JobHandler;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Worker for {@code OCR_DOCUMENT} (own worker pool, isolated from extraction). Registered only when OCR is enabled; the
 * job runner then polls this kind.
 */
@Component
@ConditionalOnProperty(name = "mimorag.ocr.enabled", havingValue = "true")
class OcrJobHandler implements JobHandler {

	private final OcrService ocr;

	private final KnowledgeBaseGate gate;

	private final DocumentVersionRepository versions;

	private final AuditRecorder audit;

	private final Clock clock;

	private final TransactionTemplate transaction;

	OcrJobHandler(OcrService ocr, KnowledgeBaseGate gate, DocumentVersionRepository versions, AuditRecorder audit,
			Clock clock, PlatformTransactionManager transactionManager) {
		this.ocr = ocr;
		this.gate = gate;
		this.versions = versions;
		this.audit = audit;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	@Override
	public String kind() {
		return OcrService.OCR_DOCUMENT;
	}

	@Override
	public void handle(JobExecution execution) {
		gate.requireStillAcceptsWork(execution.access(), execution.requireUuid("knowledgeBaseId"));
		ocr.ocr(execution, execution.requireUuid("versionId"));
	}

	@Override
	public void onFinalFailure(JobExecution execution, String errorCode) {
		UUID versionId = execution.requireUuid("versionId");
		transaction.executeWithoutResult(status -> {
			DocumentVersion version = versions
				.findByIdAndIntegrationId(versionId, execution.access().requireIntegrationId())
				.orElseThrow(() -> JobFailure.permanent("DOCUMENT_VERSION_NOT_FOUND"));
			ocr.failRun(version.getIntegrationId(), execution.jobId(), errorCode);
			if (version.markOcrFailed(errorCode, clock.instant())) {
				versions.saveAndFlush(version);
				audit.record(new AuditEvent("DOCUMENT_OCR_FAILED", "document", version.getDocumentId().toString(),
						AuditOutcome.FAILURE, Map.of("versionId", versionId.toString(), "errorCode", errorCode)));
			}
		});
	}

}
