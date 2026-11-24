package com.mimocodes.mimorag.jobs.application;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A classified job failure. {@code errorCode} is stored on the job and shown to API callers, so it must be a stable,
 * safe code (upper snake case), never an exception message.
 */
public class JobFailure extends RuntimeException {

	/** How the runner reacts to a failure. */
	public enum Type {

		/** Retrying cannot help (e.g. an encrypted PDF): the job fails at once and is not manually retryable. */
		PERMANENT,
		/** May succeed later (e.g. storage temporarily unavailable): retried automatically with backoff. */
		TRANSIENT,
		/**
		 * The job is no longer allowed to run (e.g. its Knowledge Base was archived): the job is cancelled, and can be
		 * requeued by an authorised caller once the situation is resolved.
		 */
		REVALIDATION

	}

	private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");

	private final String errorCode;

	private final Type type;

	private JobFailure(String errorCode, Type type, Throwable cause) {
		super(errorCode, cause);
		if (!CODE.matcher(Objects.requireNonNull(errorCode, "errorCode")).matches()) {
			throw new IllegalArgumentException("Invalid job error code");
		}
		this.errorCode = errorCode;
		this.type = Objects.requireNonNull(type, "type");
	}

	public static JobFailure permanent(String errorCode) {
		return new JobFailure(errorCode, Type.PERMANENT, null);
	}

	public static JobFailure transientFailure(String errorCode, Throwable cause) {
		return new JobFailure(errorCode, Type.TRANSIENT, cause);
	}

	public static JobFailure revalidation(String errorCode) {
		return new JobFailure(errorCode, Type.REVALIDATION, null);
	}

	public String getErrorCode() {
		return errorCode;
	}

	public Type getType() {
		return type;
	}

}
