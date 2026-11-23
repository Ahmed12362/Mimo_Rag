package com.mimocodes.mimorag.knowledgebase.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBase;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseStatus;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Knowledge Base as returned to integration callers. {@code yourPermissions} tells the caller what it may do, so
 * connected systems can adapt their UI without guessing.
 */
public record KnowledgeBaseView(UUID id, String code, String name, String description, KnowledgeBaseStatus status,
		boolean allowServiceSearch, boolean requireDifferentSubmitter, Instant createdAt, String createdBy,
		Instant updatedAt, long version, Set<Permission> yourPermissions) {

	static KnowledgeBaseView of(KnowledgeBase kb, Set<Permission> permissions) {
		return new KnowledgeBaseView(kb.getId(), kb.getCode(), kb.getName(), kb.getDescription(), kb.getStatus(),
				kb.policies().allowServiceSearch(), kb.policies().requireDifferentSubmitter(), kb.getCreatedAt(),
				kb.getCreatedBy(), kb.getUpdatedAt(), kb.getVersion() == null ? 0 : kb.getVersion(),
				Set.copyOf(permissions));
	}

}
