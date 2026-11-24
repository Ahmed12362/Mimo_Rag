package com.mimocodes.mimorag.ingestion.application;

import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.ExtractionRun;

import org.springframework.data.repository.Repository;

/** Persistence port for extraction runs. Every method is explicitly tenant-scoped; RLS enforces the same boundary. */
public interface ExtractionRunRepository extends Repository<ExtractionRun, UUID> {

	ExtractionRun saveAndFlush(ExtractionRun run);

	Optional<ExtractionRun> findByIdAndIntegrationId(UUID id, UUID integrationId);

	Optional<ExtractionRun> findByIntegrationIdAndJobId(UUID integrationId, UUID jobId);

	Optional<ExtractionRun> findFirstByIntegrationIdAndDocumentVersionIdOrderByStartedAtDesc(UUID integrationId,
			UUID documentVersionId);

}
