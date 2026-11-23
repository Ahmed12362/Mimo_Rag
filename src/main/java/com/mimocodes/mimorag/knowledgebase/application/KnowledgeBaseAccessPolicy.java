package com.mimocodes.mimorag.knowledgebase.application;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Computes what a caller may do on one Knowledge Base. Pure function of verified identity and stored grants; the
 * LLM and request content never take part in this decision.
 * <ul>
 * <li>Service identity: its API-key scopes (restricted to KB permissions).</li>
 * <li>End user: permissions granted to any of the user's roles, <em>intersected</em> with the API key's scopes, so a
 * narrowly scoped key cannot be used to exercise broader user rights.</li>
 * <li>Platform admin: nothing (no default access to tenant content).</li>
 * </ul>
 * {@code KB_MANAGE} implies {@code KB_READ}. A KB with no effective permission is invisible (reported as 404).
 */
public final class KnowledgeBaseAccessPolicy {

	private KnowledgeBaseAccessPolicy() {
	}

	public static Set<Permission> effectivePermissions(AccessContext access, Collection<Permission> grantedToRoles) {
		EnumSet<Permission> effective = EnumSet.noneOf(Permission.class);
		switch (access.principalType()) {
			case INTEGRATION_SERVICE -> effective.addAll(access.scopes());
			case END_USER -> {
				effective.addAll(grantedToRoles);
				effective.retainAll(access.scopes());
			}
			case PLATFORM_ADMIN -> {
				return Set.of();
			}
		}
		effective.retainAll(Permission.KNOWLEDGE_BASE_PERMISSIONS);
		if (effective.contains(Permission.KB_MANAGE)) {
			effective.add(Permission.KB_READ);
		}
		return effective;
	}

	/** Permissions whose presence makes a KB visible to a user (any KB permission the key also allows). */
	public static Set<Permission> visibilityPermissions(AccessContext access) {
		EnumSet<Permission> permissions = EnumSet.copyOf(Permission.KNOWLEDGE_BASE_PERMISSIONS);
		permissions.retainAll(access.scopes());
		return permissions;
	}

}
