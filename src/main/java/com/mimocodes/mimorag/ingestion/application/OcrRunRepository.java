package com.mimocodes.mimorag.ingestion.application;

import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.OcrRun;

import org.springframework.data.repository.Repository;

/** Persistence port for OCR runs. Every method is explicitly tenant-scoped; RLS enforces the same boundary. */
public interface OcrRunRepository extends Repository<OcrRun, UUID> {

	OcrRun saveAndFlush(OcrRun run);

	Optional<OcrRun> findByIdAndIntegrationId(UUID id, UUID integrationId);

	Optional<OcrRun> findByIntegrationIdAndJobId(UUID integrationId, UUID jobId);

	Optional<OcrRun> findFirstByIntegrationIdAndDocumentVersionIdOrderByStartedAtDesc(UUID integrationId,
			UUID documentVersionId);

}
