package com.mimocodes.mimorag.knowledgebase.domain;

/**
 * Per-KB policies. Both default to the safe choice ({@code false}).
 *
 * @param allowServiceSearch allow search/chat by a service identity without a user assertion (enforced in M5)
 * @param requireDifferentSubmitter require the submitter to differ from page editors (enforced in M3)
 */
public record KnowledgeBasePolicies(boolean allowServiceSearch, boolean requireDifferentSubmitter) {

	public static KnowledgeBasePolicies defaults() {
		return new KnowledgeBasePolicies(false, false);
	}

}
