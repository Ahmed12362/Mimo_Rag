package com.mimocodes.mimorag.shared.access;

import java.util.EnumSet;
import java.util.Set;

/**
 * Platform permissions. The meaning of an integration's own roles is never interpreted by the platform; roles are
 * mapped to these permissions through explicit grants.
 */
public enum Permission {

	KB_READ,
	KB_MANAGE,
	DOCUMENT_UPLOAD,
	DOCUMENT_REVIEW,
	DOCUMENT_SUBMIT,
	TOOL_EXECUTE,
	TOOL_CATALOG_MANAGE,

	/**
	 * Manage the integration's own API keys (create, list, revoke). Never grants access to tenant content and can
	 * only be held by the service identity; bootstrap keys carry exactly this scope.
	 */
	KEY_ROTATE;

	/** Permissions that can be granted to integration roles on an individual Knowledge Base. */
	public static final Set<Permission> KNOWLEDGE_BASE_PERMISSIONS = EnumSet.of(KB_READ, KB_MANAGE, DOCUMENT_UPLOAD,
			DOCUMENT_REVIEW, DOCUMENT_SUBMIT);

}
