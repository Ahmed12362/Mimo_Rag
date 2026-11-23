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

import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Grants a platform permission on one KB to an integration-defined role. The role string is opaque: the platform
 * only matches it against roles in verified user assertions.
 */
@Entity
@Table(name = "knowledge_base_role_grant")
public class KnowledgeBaseRoleGrant {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private UUID knowledgeBaseId;

	@Column(nullable = false, updatable = false)
	private String role;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private Permission permission;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected KnowledgeBaseRoleGrant() {
	}

	public KnowledgeBaseRoleGrant(KnowledgeBase knowledgeBase, String role, Permission permission, Instant now) {
		Objects.requireNonNull(knowledgeBase, "knowledgeBase");
		if (!Permission.KNOWLEDGE_BASE_PERMISSIONS.contains(permission)) {
			throw new IllegalArgumentException("Permission " + permission + " cannot be granted on a Knowledge Base");
		}
		this.id = UUID.randomUUID();
		this.integrationId = knowledgeBase.getIntegrationId();
		this.knowledgeBaseId = knowledgeBase.getId();
		this.role = Objects.requireNonNull(role, "role");
		this.permission = permission;
		this.createdAt = now;
	}

	public UUID getKnowledgeBaseId() {
		return knowledgeBaseId;
	}

	public String getRole() {
		return role;
	}

	public Permission getPermission() {
		return permission;
	}

}
