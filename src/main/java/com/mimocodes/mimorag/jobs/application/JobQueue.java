package com.mimocodes.mimorag.jobs.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;

/**
 * The job queue as seen by business modules. Every method is tenant-scoped by the given {@link AccessContext} (and
 * by RLS). A broker-backed implementation could replace the PostgreSQL one without changing callers (ADR-0011).
 */
public interface JobQueue {

	/**
	 * A job to enqueue.
	 *
	 * @param kind handler kind, e.g. {@code EXTRACT_DOCUMENT}
	 * @param payload small JSON-serialisable map of identifiers; never document content or secrets
	 */
	record NewJob(String kind, Map<String, Object> payload) {

		public NewJob {
			payload = payload == null ? Map.of() : Map.copyOf(payload);
		}

	}

	/** A job as reported to API callers. */
	record JobView(UUID id, String kind, JobStatus status, Map<String, Object> payload, int attempts,
			int maxAttempts, String errorCode, boolean retryable, Instant runAfter, Instant createdAt,
			Instant updatedAt, Instant startedAt, Instant finishedAt) {
	}

	/**
	 * Enqueues a job inside the caller's transaction (mandatory), so the job exists if and only if the business change
	 * that requires it is committed. The caller's identity is stored with the job.
	 */
	UUID enqueue(AccessContext access, NewJob job);

	Optional<JobView> find(AccessContext access, UUID jobId);

	/** Jobs of this kind that are queued, running or waiting for a retry, for the caller's integration. */
	long countUnfinished(AccessContext access, String kind);

	/**
	 * Puts a failed or cancelled job back in the queue under the caller's identity, with its attempt count reset.
	 *
	 * @return {@code false} if the job is not in a requeueable state (or not retryable)
	 */
	boolean requeue(AccessContext access, UUID jobId);

}
