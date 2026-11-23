package com.mimocodes.mimorag.shared.access;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;

/**
 * The verified identity of the current caller. Created only by the {@code access} module from verified credentials
 * (never from request bodies, headers chosen by the client, or LLM output) and consumed by core modules for
 * authorization and tenant scoping.
 *
 * @param integrationId the tenant; {@code null} only for {@link PrincipalType#PLATFORM_ADMIN}
 * @param principalType who is acting
 * @param principalRef stable, non-secret reference for audit (API key id, external user id, or admin marker)
 * @param externalUserId the connected system's user id, present only for {@link PrincipalType#END_USER}
 * @param roles opaque integration roles from the verified user assertion
 * @param groups opaque integration groups from the verified user assertion
 * @param scopes effective permissions of the service credential (key scopes within the integration's ceiling);
 * they also bound what an end user may do through it
 * @param credential the integration API key that authenticated the request (also for relayed end users);
 * {@code null} for platform admins
 * @param authMethod how the caller was authenticated
 * @param correlationId request correlation id
 */
public record AccessContext(UUID integrationId, PrincipalType principalType, String principalRef,
		Optional<String> externalUserId, Set<String> roles, Set<String> groups, Set<Permission> scopes,
		Credential credential, AuthMethod authMethod, String correlationId) {

	/**
	 * The integration API key behind a request.
	 *
	 * @param apiKeyId public key id (never the secret)
	 * @param bootstrap whether this is a single-use bootstrap key (key rotation only)
	 * @param knowledgeBaseRestriction if present, the key is valid only for these Knowledge Bases
	 * @param expiresAt when the key expires
	 */
	public record Credential(String apiKeyId, boolean bootstrap, Optional<Set<UUID>> knowledgeBaseRestriction,
			Instant expiresAt) {

		public Credential {
			Objects.requireNonNull(apiKeyId, "apiKeyId");
			knowledgeBaseRestriction = knowledgeBaseRestriction == null ? Optional.empty()
					: knowledgeBaseRestriction.map(Set::copyOf);
		}

		public static Credential unrestricted(String apiKeyId, Instant expiresAt) {
			return new Credential(apiKeyId, false, Optional.empty(), expiresAt);
		}

	}

	public AccessContext {
		Objects.requireNonNull(principalType, "principalType");
		Objects.requireNonNull(principalRef, "principalRef");
		Objects.requireNonNull(authMethod, "authMethod");
		externalUserId = externalUserId == null ? Optional.empty() : externalUserId;
		roles = roles == null ? Set.of() : Set.copyOf(roles);
		groups = groups == null ? Set.of() : Set.copyOf(groups);
		scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
		if (principalType == PrincipalType.PLATFORM_ADMIN) {
			if (integrationId != null || credential != null) {
				throw new IllegalArgumentException("Platform admin contexts are not bound to a tenant or API key");
			}
		}
		else {
			Objects.requireNonNull(integrationId, "integrationId is required for integration principals");
			Objects.requireNonNull(credential, "credential is required for integration principals");
		}
		if (principalType == PrincipalType.END_USER && externalUserId.isEmpty()) {
			throw new IllegalArgumentException("End-user contexts require an external user id");
		}
	}

	public static AccessContext integrationService(UUID integrationId, Credential credential, Set<Permission> scopes,
			String correlationId) {
		return new AccessContext(integrationId, PrincipalType.INTEGRATION_SERVICE,
				"api-key:" + credential.apiKeyId(), Optional.empty(), Set.of(), Set.of(), scopes, credential,
				AuthMethod.API_KEY, correlationId);
	}

	/** Convenience for an unrestricted, non-bootstrap key (mainly for tests and internal tooling). */
	public static AccessContext integrationService(UUID integrationId, String keyId, Set<Permission> scopes,
			String correlationId) {
		return integrationService(integrationId, Credential.unrestricted(keyId, null), scopes, correlationId);
	}

	public static AccessContext platformAdmin(String correlationId) {
		return new AccessContext(null, PrincipalType.PLATFORM_ADMIN, "platform-admin", Optional.empty(), Set.of(),
				Set.of(), Set.of(), null, AuthMethod.ADMIN_KEY, correlationId);
	}

	public AccessContext asEndUser(String userId, Set<String> userRoles, Set<String> userGroups, AuthMethod method) {
		if (principalType != PrincipalType.INTEGRATION_SERVICE) {
			throw new IllegalStateException("Only an authenticated integration can act on behalf of a user");
		}
		if (credential.bootstrap()) {
			throw new IllegalStateException("Bootstrap keys cannot act on behalf of users");
		}
		return new AccessContext(integrationId, PrincipalType.END_USER, "user:" + userId, Optional.of(userId),
				userRoles, userGroups, scopes, credential, method, correlationId);
	}

	public boolean isPlatformAdmin() {
		return principalType == PrincipalType.PLATFORM_ADMIN;
	}

	public boolean isEndUser() {
		return principalType == PrincipalType.END_USER;
	}

	public boolean isIntegrationService() {
		return principalType == PrincipalType.INTEGRATION_SERVICE;
	}

	public boolean hasScope(Permission permission) {
		return scopes.contains(permission);
	}

	/** Knowledge Bases the credential is restricted to; empty when the key is integration-wide. */
	public Optional<Set<UUID>> knowledgeBaseRestriction() {
		return credential == null ? Optional.empty() : credential.knowledgeBaseRestriction();
	}

	/** Whether the credential allows working with this Knowledge Base at all (before role/scope checks). */
	public boolean credentialAllowsKnowledgeBase(UUID knowledgeBaseId) {
		return knowledgeBaseRestriction().map(allowed -> allowed.contains(knowledgeBaseId)).orElse(true);
	}

	/** Public id of the API key that authenticated (or relayed) this request; empty for admins. */
	public Optional<String> apiKeyId() {
		return credential == null ? Optional.empty() : Optional.of(credential.apiKeyId());
	}

	/** Defense in depth for admin-plane services (the security chain already restricts the admin paths). */
	public void requirePlatformAdmin() {
		if (!isPlatformAdmin()) {
			throw new OperationNotPermittedException("Platform administrator required");
		}
	}

	/** Tenant of an integration principal; fails for admin contexts, which must never reach tenant content. */
	public UUID requireIntegrationId() {
		if (integrationId == null) {
			throw new IllegalStateException("Operation requires an integration (tenant) context");
		}
		return integrationId;
	}

}
