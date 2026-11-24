package com.mimocodes.mimorag.jobs.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Worker-side operations on the job store, used only by {@link JobRunner}.
 * <p>
 * {@link #claim}, {@link #extendLease} and {@link #failAbandoned} are the only cross-tenant operations (narrow
 * SECURITY DEFINER functions). All other methods run under the job's own tenant context and only change a job whose
 * lease this worker still holds, so a worker that lost its lease can never overwrite another worker's result.
 */
public interface JobLeaseStore {

	/** A job handed to a worker together with the identity that enqueued it. */
	record ClaimedJob(UUID id, UUID integrationId, String kind, Map<String, Object> payload,
			JobContextSnapshot context, String correlationId, int attempt, int maxAttempts) {

		public ClaimedJob {
			payload = payload == null ? Map.of() : Map.copyOf(payload);
		}

	}

	List<ClaimedJob> claim(List<String> kinds, int limit, String workerId, Duration lease, int perIntegration);

	boolean extendLease(UUID jobId, String workerId, Duration lease);

	/** @return {@code false} if the lease was lost (nothing changed) */
	boolean complete(UUID jobId, String workerId);

	/** Releases the lease and schedules another attempt after {@code delay}. */
	boolean scheduleRetry(UUID jobId, String workerId, String errorCode, Duration delay);

	/** Ends the job as {@code FAILED}; {@code retryable} tells whether a manual retry makes sense. */
	boolean fail(UUID jobId, String workerId, String errorCode, boolean retryable);

	/** Ends the job as {@code CANCELLED_REVALIDATION}. */
	boolean cancel(UUID jobId, String workerId, String errorCode);

	/** Marks running jobs whose worker died on their last attempt as failed, and returns them. */
	List<ClaimedJob> failAbandoned(List<String> kinds);

}
