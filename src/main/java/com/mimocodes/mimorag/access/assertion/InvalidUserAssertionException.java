package com.mimocodes.mimorag.access.assertion;

/**
 * A presented user assertion was not accepted. The reason is for internal logs and audit only; callers always
 * receive the same generic 401 response.
 */
public class InvalidUserAssertionException extends Exception {

	public enum Reason {

		TOO_LARGE, NO_TRUST_CONFIGURATION, INVALID_TRUST_CONFIGURATION, REJECTED_BY_VALIDATION, INVALID_USER_CLAIMS

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
