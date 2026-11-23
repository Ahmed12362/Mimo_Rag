package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.ApiKeyType;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * API key metadata. Never contains the secret or its hash. {@code knowledgeBaseIds} empty means integration-wide.
 */
public record ApiKeyView(String keyId, UUID integrationId, String name, ApiKeyType keyType, String last4,
		Set<Permission> scopes, Set<UUID> knowledgeBaseIds, ApiKeyStatus status, Instant expiresAt,
		Instant createdAt, String createdBy, Instant revokedAt, String revokedBy, Instant lastUsedAt) {

	static ApiKeyView of(ApiKey key) {
		return new ApiKeyView(key.getKeyId(), key.getIntegrationId(), key.getName(), key.getKeyType(),
				key.getLast4(), key.getScopes(), key.getKnowledgeBaseIds(), key.getStatus(), key.getExpiresAt(),
				key.getCreatedAt(), key.getCreatedBy(), key.getRevokedAt(), key.getRevokedBy(), key.getLastUsedAt());
	}

}
