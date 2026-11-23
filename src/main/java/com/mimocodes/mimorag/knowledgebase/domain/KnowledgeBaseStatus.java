package com.mimocodes.mimorag.knowledgebase.domain;

public enum KnowledgeBaseStatus {

	ACTIVE,

	/** Read-only: retained for audit and reference, no new content (enforced from M2). */
	ARCHIVED

}
