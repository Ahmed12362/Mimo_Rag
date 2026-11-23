package com.mimocodes.mimorag.shared.access;

public enum PrincipalType {

	/** A connected system acting as itself (service credential only). */
	INTEGRATION_SERVICE,

	/** An end user of a connected system (service credential plus a verified, signed user assertion). */
	END_USER,

	/** A MimoRag platform administrator (separate admin plane). */
	PLATFORM_ADMIN

}
