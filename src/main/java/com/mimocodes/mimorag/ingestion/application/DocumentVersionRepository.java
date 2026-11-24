package com.mimocodes.mimorag.ingestion.application;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Persistence port for document versions. Every method is explicitly tenant-scoped; RLS enforces the same boundary. */
public interface DocumentVersionRepository extends Repository<DocumentVersion, UUID> {

	DocumentVersion saveAndFlush(DocumentVersion version);

	Optional<DocumentVersion> findByIdAndIntegrationId(UUID id, UUID integrationId);

	Optional<DocumentVersion> findByIntegrationIdAndIdempotencyKey(UUID integrationId, String idempotencyKey);

	Optional<DocumentVersion> findByIntegrationIdAndKnowledgeBaseIdAndSha256(UUID integrationId,
			UUID knowledgeBaseId, String sha256);

	Optional<DocumentVersion> findByIntegrationIdAndExtractionJobId(UUID integrationId, UUID extractionJobId);

	List<DocumentVersion> findAllByIntegrationIdAndIdIn(UUID integrationId, Collection<UUID> ids);

	List<DocumentVersion> findAllByIntegrationIdAndDocumentIdOrderByVersionNumberDesc(UUID integrationId,
			UUID documentId);

	@Query("select coalesce(sum(v.sizeBytes), 0) from DocumentVersion v where v.integrationId = :integrationId")
	long totalStoredBytes(@Param("integrationId") UUID integrationId);

}
