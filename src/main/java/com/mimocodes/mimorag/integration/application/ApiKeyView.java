package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.shared.access.Permission;

/** API key metadata. Never contains the secret or its hash. */
public record ApiKeyView(String keyId, UUID integrationId, String name, String last4, Set<Permission> scopes,
		ApiKeyStatus status, Instant expiresAt, Instant createdAt, Instant revokedAt, Instant lastUsedAt) {

	static ApiKeyView of(ApiKey key) {
		return new ApiKeyView(key.getKeyId(), key.getIntegrationId(), key.getName(), key.getLast4(), key.getScopes(),
				key.getStatus(), key.getExpiresAt(), key.getCreatedAt(), key.getRevokedAt(), key.getLastUsedAt());
	}

}
