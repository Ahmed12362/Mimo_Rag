package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Pre-tenant credential lookup used during authentication, before any tenant context exists. Implemented with
 * narrowly scoped SECURITY DEFINER database functions instead of relaxing Row-Level Security.
 */
public interface ApiKeyCredentialLookup {

	record StoredCredential(UUID integrationId, String secretHash, ApiKeyStatus keyStatus, Instant expiresAt,
			Set<Permission> scopes, IntegrationStatus integrationStatus) {

		@Override
		public String toString() {
			return "StoredCredential[integrationId=" + integrationId + ", keyStatus=" + keyStatus + "]";
		}

	}

	Optional<StoredCredential> findByKeyId(String keyId);

	/** Records that the key was used; implementations may throttle writes. */
	void recordUse(String keyId, Instant usedAt);

}
