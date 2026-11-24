package com.mimocodes.mimorag.shared.error;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** A per-integration limit was reached. Reported as 429 (queue) or 413 (storage) with a specific error code. */
public class QuotaExceededException extends RuntimeException {

	public enum Quota {

		/** Too many unfinished jobs; transient, the caller may retry later. */
		UPLOAD_QUEUE,
		/** Stored bytes would exceed the integration's quota; persists until content is removed or the quota raised. */
		STORAGE

	}

	private final Quota quota;

	private final Duration retryAfter;

	public QuotaExceededException(Quota quota, String message, Duration retryAfter) {
		super(message);
		this.quota = Objects.requireNonNull(quota, "quota");
		this.retryAfter = retryAfter;
	}

	public Quota getQuota() {
		return quota;
	}

	public Optional<Duration> getRetryAfter() {
		return Optional.ofNullable(retryAfter);
	}

}
