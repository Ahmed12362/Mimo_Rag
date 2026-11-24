package com.mimocodes.mimorag.jobs.application;

/** Lifecycle of a background job (ADR-0011). */
public enum JobStatus {

	/** Waiting to be claimed by a worker. */
	QUEUED,
	/** Claimed by a worker that holds a lease. */
	RUNNING,
	/** A transient failure occurred; it will be claimed again after {@code runAfter}. */
	RETRY_SCHEDULED,
	SUCCEEDED,
	/** Failed permanently, or ran out of attempts. */
	FAILED,
	/** The identity that enqueued the job is no longer valid (key revoked/expired, integration suspended). */
	CANCELLED_REVALIDATION;

	public boolean isFinished() {
		return this == SUCCEEDED || this == FAILED || this == CANCELLED_REVALIDATION;
	}

}
