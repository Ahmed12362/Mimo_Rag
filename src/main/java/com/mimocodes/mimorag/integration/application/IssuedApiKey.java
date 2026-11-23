package com.mimocodes.mimorag.integration.application;

/**
 * Result of issuing a key. {@code plaintextKey} is returned exactly once and is not recoverable afterwards.
 */
public record IssuedApiKey(ApiKeyView key, String plaintextKey) {

	@Override
	public String toString() {
		return "IssuedApiKey[key=" + key.keyId() + "]";
	}

}
