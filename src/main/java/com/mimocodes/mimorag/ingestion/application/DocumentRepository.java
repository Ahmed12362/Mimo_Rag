package com.mimocodes.mimorag.ingestion.application;

import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.domain.Document;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Persistence port for documents. Every method is explicitly tenant-scoped; RLS enforces the same boundary. */
public interface DocumentRepository extends Repository<Document, UUID> {

	Document saveAndFlush(Document document);

	Optional<Document> findByIdAndIntegrationId(UUID id, UUID integrationId);

	Page<Document> findAllByIntegrationIdAndKnowledgeBaseId(UUID integrationId, UUID knowledgeBaseId,
			Pageable pageable);

}
