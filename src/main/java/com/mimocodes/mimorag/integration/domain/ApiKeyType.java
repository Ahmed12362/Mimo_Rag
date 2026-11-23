package com.mimocodes.mimorag.integration.domain;

public enum ApiKeyType {

	/**
	 * Issued by a platform admin to hand over an integration: scope {@code KEY_ROTATE} only, valid at most 24 hours,
	 * consumed (revoked) by its first successful key creation, never usable for tenant content.
	 */
	BOOTSTRAP,

	/** Created by the integration itself; mandatory expiry of at most 365 days. */
	STANDARD

}
