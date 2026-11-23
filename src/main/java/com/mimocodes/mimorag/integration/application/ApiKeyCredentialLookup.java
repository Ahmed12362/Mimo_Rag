package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.ApiKeyType;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Pre-tenant credential lookup used during authentication, before any tenant context exists. Implemented with
 * narrowly scoped SECURITY DEFINER database functions instead of relaxing Row-Level Security.
 */
public interface ApiKeyCredentialLookup {

	/**
	 * @param allowedScopes the integration's scope ceiling (without the implicit {@code KEY_ROTATE})
	 * @param knowledgeBaseIds the key's KB restriction; empty means integration-wide
	 */
	record StoredCredential(UUID integrationId, String secretHash, ApiKeyStatus keyStatus, ApiKeyType keyType,
			Instant expiresAt, Set<Permission> scopes, Set<Permission> allowedScopes,
			IntegrationStatus integrationStatus, Set<UUID> knowledgeBaseIds) {

		@Override
		public String toString() {
			return "StoredCredential[integrationId=" + integrationId + ", keyStatus=" + keyStatus + ", keyType="
					+ keyType + "]";
		}

	}

	Optional<StoredCredential> findByKeyId(String keyId);

	/** Records that the key was used; the database ignores updates more frequent than every 5 minutes. */
	void recordUse(String keyId, Instant usedAt);

}
