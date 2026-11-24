package com.mimocodes.mimorag.ingestion.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Stable identity of uploaded content inside one Knowledge Base of one integration. The content itself lives in
 * immutable {@link DocumentVersion}s.
 */
@Entity
@Table(name = "document")
public class Document {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private UUID knowledgeBaseId;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false, updatable = false)
	private String originalFilename;

	private UUID currentVersionId;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, updatable = false)
	private String createdBy;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Document() {
	}

	private Document(UUID id, UUID integrationId, UUID knowledgeBaseId, String title, String originalFilename,
			String createdBy, Instant now) {
		this.id = Objects.requireNonNull(id, "id");
		this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
		this.knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId, "knowledgeBaseId");
		this.title = Objects.requireNonNull(title, "title");
		this.originalFilename = Objects.requireNonNull(originalFilename, "originalFilename");
		this.createdBy = Objects.requireNonNull(createdBy, "createdBy");
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Document create(UUID id, UUID integrationId, UUID knowledgeBaseId, String title,
			String originalFilename, String createdBy, Instant now) {
		return new Document(id, integrationId, knowledgeBaseId, title, originalFilename, createdBy, now);
	}

	public void makeCurrent(DocumentVersion documentVersion, Instant now) {
		if (!documentVersion.getDocumentId().equals(id)) {
			throw new IllegalArgumentException("Version belongs to another document");
		}
		this.currentVersionId = documentVersion.getId();
		this.updatedAt = now;
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public UUID getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getTitle() {
		return title;
	}

	public String getOriginalFilename() {
		return originalFilename;
	}

	public UUID getCurrentVersionId() {
		return currentVersionId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

}
