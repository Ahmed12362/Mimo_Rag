package com.mimocodes.mimorag.knowledgebase.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * A Knowledge Base owned by exactly one integration (tenant). Its policies control behaviour of later milestones:
 * {@code allowServiceSearch} (search/chat without a user, M5) and {@code requireDifferentSubmitter} (M3).
 */
@Entity
@Table(name = "knowledge_base")
public class KnowledgeBase {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private KnowledgeBaseStatus status;

	@Column(nullable = false)
	private boolean allowServiceSearch;

	@Column(nullable = false)
	private boolean requireDifferentSubmitter;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, updatable = false)
	private String createdBy;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected KnowledgeBase() {
	}

	private KnowledgeBase(UUID integrationId, String code, String name, String description, KnowledgeBasePolicies policies,
			String createdBy, Instant now) {
		this.id = UUID.randomUUID();
		this.integrationId = integrationId;
		this.code = code;
		this.name = name;
		this.description = description;
		this.status = KnowledgeBaseStatus.ACTIVE;
		this.allowServiceSearch = policies.allowServiceSearch();
		this.requireDifferentSubmitter = policies.requireDifferentSubmitter();
		this.createdAt = now;
		this.createdBy = createdBy;
		this.updatedAt = now;
	}

	public static KnowledgeBase create(UUID integrationId, String code, String name, String description,
			KnowledgeBasePolicies policies, String createdBy, Instant now) {
		Objects.requireNonNull(integrationId, "integrationId");
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(name, "name");
		return new KnowledgeBase(integrationId, code, name, description,
				policies == null ? KnowledgeBasePolicies.defaults() : policies, createdBy, now);
	}

	public void rename(String newName, Instant now) {
		this.name = Objects.requireNonNull(newName, "name");
		this.updatedAt = now;
	}

	public void describe(String newDescription, Instant now) {
		this.description = newDescription;
		this.updatedAt = now;
	}

	public void applyPolicies(KnowledgeBasePolicies policies, Instant now) {
		this.allowServiceSearch = policies.allowServiceSearch();
		this.requireDifferentSubmitter = policies.requireDifferentSubmitter();
		this.updatedAt = now;
	}

	public void changeStatus(KnowledgeBaseStatus newStatus, Instant now) {
		this.status = Objects.requireNonNull(newStatus, "status");
		this.updatedAt = now;
	}

	public KnowledgeBasePolicies policies() {
		return new KnowledgeBasePolicies(allowServiceSearch, requireDifferentSubmitter);
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public KnowledgeBaseStatus getStatus() {
		return status;
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
