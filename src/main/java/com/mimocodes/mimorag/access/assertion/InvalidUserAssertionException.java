package com.mimocodes.mimorag.access.assertion;

/**
 * A presented user assertion was not accepted. The reason is for internal logs and audit only; callers receive a
 * generic 401, except {@link Reason#KEY_SOURCE_UNAVAILABLE}, which is reported as 503 because the integration's own
 * key endpoint is down (retrying later can succeed).
 */
public class InvalidUserAssertionException extends Exception {

	public enum Reason {

		TOO_LARGE, NO_TRUST_CONFIGURATION, INVALID_TRUST_CONFIGURATION, REJECTED_BY_VALIDATION, INVALID_USER_CLAIMS,
		KEY_SOURCE_UNAVAILABLE

	}

	private final Reason reason;

	public InvalidUserAssertionException(Reason reason, String detail) {
		super(reason + ": " + detail);
		this.reason = reason;
	}

	public Reason getReason() {
		return reason;
	}

}
