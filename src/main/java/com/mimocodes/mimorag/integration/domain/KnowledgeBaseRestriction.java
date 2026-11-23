package com.mimocodes.mimorag.integration.domain;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One Knowledge Base an API key is limited to. Stored with the tenant id so a composite foreign key guarantees the
 * KB belongs to the key's own integration (another tenant's KB id is indistinguishable from a non-existent one).
 */
@Embeddable
public class KnowledgeBaseRestriction {

	@Column(name = "integration_id", nullable = false, updatable = false)
	private UUID integrationId;

	@Column(name = "knowledge_base_id", nullable = false, updatable = false)
	private UUID knowledgeBaseId;

	protected KnowledgeBaseRestriction() {
	}

	KnowledgeBaseRestriction(UUID integrationId, UUID knowledgeBaseId) {
		this.integrationId = Objects.requireNonNull(integrationId);
		this.knowledgeBaseId = Objects.requireNonNull(knowledgeBaseId);
	}

	public UUID knowledgeBaseId() {
		return knowledgeBaseId;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof KnowledgeBaseRestriction that && integrationId.equals(that.integrationId)
				&& knowledgeBaseId.equals(that.knowledgeBaseId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(integrationId, knowledgeBaseId);
	}

}
