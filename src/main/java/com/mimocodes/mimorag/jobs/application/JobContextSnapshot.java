package com.mimocodes.mimorag.jobs.application;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AuthMethod;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.access.PrincipalType;

/**
 * The verified identity that enqueued a job, stored with the job so the worker can continue under the same tenant,
 * principal and limits. Contains identifiers and permissions only, never tokens or secrets.
 *
 * @param knowledgeBaseRestriction {@code null} when the API key is integration-wide
 */
public record JobContextSnapshot(UUID integrationId, PrincipalType principalType, String principalRef,
		String externalUserId, Set<String> roles, Set<String> groups, Set<Permission> scopes, String apiKeyId,
		boolean bootstrapKey, Set<UUID> knowledgeBaseRestriction, Instant credentialExpiresAt,
		AuthMethod authMethod) {

	public JobContextSnapshot {
		Objects.requireNonNull(integrationId, "integrationId");
		Objects.requireNonNull(principalType, "principalType");
		Objects.requireNonNull(principalRef, "principalRef");
		Objects.requireNonNull(apiKeyId, "apiKeyId");
		Objects.requireNonNull(authMethod, "authMethod");
		roles = roles == null ? Set.of() : Set.copyOf(roles);
		groups = groups == null ? Set.of() : Set.copyOf(groups);
		scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
		knowledgeBaseRestriction = knowledgeBaseRestriction == null ? null : Set.copyOf(knowledgeBaseRestriction);
	}

	/** Captures an integration-plane context; platform-admin contexts can never enqueue tenant jobs. */
	public static JobContextSnapshot of(AccessContext access) {
		if (access.isPlatformAdmin() || access.credential() == null) {
			throw new IllegalArgumentException("Jobs can only be enqueued by an integration principal");
		}
		return new JobContextSnapshot(access.integrationId(), access.principalType(), access.principalRef(),
				access.externalUserId().orElse(null), access.roles(), access.groups(), access.scopes(),
				access.credential().apiKeyId(), access.credential().bootstrap(),
				access.knowledgeBaseRestriction().orElse(null), access.credential().expiresAt(), access.authMethod());
	}

	/**
	 * Rebuilds the access context for execution, marked as {@link AuthMethod#SYSTEM} (background work acting for the
	 * original principal). {@code currentScopes} are the key's scopes as they are now: the job never gets more than
	 * it had when it was enqueued, and loses what was removed since.
	 */
	public AccessContext toAccessContext(Set<Permission> currentScopes, String correlationId) {
		Set<Permission> effective = scopes.isEmpty() ? EnumSet.noneOf(Permission.class) : EnumSet.copyOf(scopes);
		effective.retainAll(currentScopes);
		AccessContext.Credential credential = new AccessContext.Credential(apiKeyId, bootstrapKey,
				Optional.ofNullable(knowledgeBaseRestriction), credentialExpiresAt);
		return new AccessContext(integrationId, principalType, principalRef, Optional.ofNullable(externalUserId),
				roles, groups, effective, credential, AuthMethod.SYSTEM, correlationId);
	}

}
