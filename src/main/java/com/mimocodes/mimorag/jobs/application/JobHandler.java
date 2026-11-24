package com.mimocodes.mimorag.jobs.application;

import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;

/**
 * Executes one kind of job. Implemented by the module that owns the work (e.g. ingestion); the job runner never
 * depends on those modules. Handlers run with the job's {@link AccessContext} bound, so all data access is ordinary
 * tenant-scoped (RLS) access. They must be idempotent: a job can run again after a crash or a retry.
 */
public interface JobHandler {

	/** What the runner passes to a handler. */
	record JobExecution(UUID jobId, String kind, Map<String, Object> payload, AccessContext access, int attempt,
			int maxAttempts) {

		public String requireString(String key) {
			Object value = payload.get(key);
			if (!(value instanceof String text) || text.isBlank()) {
				throw JobFailure.permanent("INVALID_JOB_PAYLOAD");
			}
			return text;
		}

		public UUID requireUuid(String key) {
			try {
				return UUID.fromString(requireString(key));
			}
			catch (IllegalArgumentException ex) {
				throw JobFailure.permanent("INVALID_JOB_PAYLOAD");
			}
		}

	}

	String kind();

	/**
	 * Does the work. Throw {@link JobFailure} to report a classified failure; any other exception is treated as a
	 * transient {@code UNEXPECTED_ERROR}.
	 */
	void handle(JobExecution execution);

	/**
	 * Called once when the job ends without success (failed permanently, ran out of attempts, abandoned, or cancelled
	 * because its identity is no longer valid), so the owning module can record the outcome on its own data.
	 */
	default void onFinalFailure(JobExecution execution, String errorCode) {
	}

}
