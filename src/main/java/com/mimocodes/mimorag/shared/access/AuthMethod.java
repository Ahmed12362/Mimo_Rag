package com.mimocodes.mimorag.shared.access;

public enum AuthMethod {

	API_KEY,

	/** API key plus a user assertion verified against the integration's JWKS endpoint. */
	API_KEY_AND_JWT_JWKS,

	/** API key plus a user assertion verified against an inline JWKS (development only). */
	API_KEY_AND_JWT_INLINE_JWKS,

	ADMIN_KEY,

	/** Internal work (e.g. background jobs) acting with a stored context snapshot. */
	SYSTEM

}
