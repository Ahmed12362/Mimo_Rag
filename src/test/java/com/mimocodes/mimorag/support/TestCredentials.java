package com.mimocodes.mimorag.support;

/** Throwaway credentials for ephemeral test environments only. */
public final class TestCredentials {

	public static final String ADMIN_KEY = "test-admin-key-for-integration-tests";

	/** SHA-256 (hex) of {@link #ADMIN_KEY}. */
	public static final String ADMIN_KEY_SHA256 = "e7c78569c4a45dad40672da6efa673ca7476fb2e5726b7248e560161807c54af";

	private TestCredentials() {
	}

}
