package com.mimocodes.mimorag.ingestion.application;

import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.stereotype.Component;

/**
 * Access to document content through its Knowledge Base. A caller that cannot see the Knowledge Base, or has none of
 * the document permissions on it, gets exactly the same "not found" as for an unknown id.
 */
@Component
class KnowledgeBaseGate {

	static final Set<Permission> DOCUMENT_PERMISSIONS = Set.of(Permission.DOCUMENT_UPLOAD, Permission.DOCUMENT_REVIEW);

	private final KnowledgeBaseService knowledgeBases;

	KnowledgeBaseGate(KnowledgeBaseService knowledgeBases) {
		this.knowledgeBases = knowledgeBases;
	}

	/** The Knowledge Base if the caller holds any document permission on it; otherwise "not found" for resource. */
	KnowledgeBaseView requireDocumentAccess(AccessContext access, UUID knowledgeBaseId, String resource) {
		KnowledgeBaseView kb;
		try {
			kb = knowledgeBases.get(access, knowledgeBaseId);
		}
		catch (ResourceNotFoundException invisible) {
			throw new ResourceNotFoundException(resource);
		}
		if (DOCUMENT_PERMISSIONS.stream().noneMatch(kb.yourPermissions()::contains)) {
			throw new ResourceNotFoundException(resource);
		}
		return kb;
	}

}
