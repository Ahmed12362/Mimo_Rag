package com.mimocodes.mimorag.jobs.application;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Background job runner settings ({@code mimorag.jobs.*}).
 *
 * @param enabled whether this instance runs workers (an API-only instance can disable them)
 * @param pollInterval idle delay between claim attempts per job kind
 * @param lease how long a claimed job is reserved for its worker; extended by heartbeats while it runs
 * @param heartbeatInterval how often running jobs extend their lease (must be well below {@code lease})
 * @param maxAttempts automatic attempts per job (transient failures only)
 * @param retryBaseDelay delay before the second attempt; multiplied by 4 per further attempt, with jitter
 * @param retryMaxDelay upper bound for the retry delay
 * @param workers worker threads per job kind; kinds without an entry get one worker
 * @param perIntegrationConcurrency maximum running jobs of one kind per integration (fairness between tenants)
 * @param kindConcurrency per-kind overrides of {@code perIntegrationConcurrency}; {@code OCR_DOCUMENT} defaults to 1
 *     so one tenant's large scan cannot occupy the whole OCR service
 * @param shutdownTimeout how long a graceful shutdown waits for running jobs
 */
@ConfigurationProperties("mimorag.jobs")
public record JobProperties(@DefaultValue("true") boolean enabled, @DefaultValue("PT1S") Duration pollInterval,
		@DefaultValue("PT2M") Duration lease, @DefaultValue("PT30S") Duration heartbeatInterval,
		@DefaultValue("3") int maxAttempts, @DefaultValue("PT30S") Duration retryBaseDelay,
		@DefaultValue("PT15M") Duration retryMaxDelay, Map<String, Integer> workers,
		@DefaultValue("2") int perIntegrationConcurrency, @DefaultValue("PT30S") Duration shutdownTimeout,
		Map<String, Integer> kindConcurrency) {

	public JobProperties {
		// Keys are job kinds; accept any spelling that property binding may produce (extract-document, extract.document).
		workers = workers == null ? Map.of("EXTRACT_DOCUMENT", 2)
				: workers.entrySet()
					.stream()
					.collect(java.util.stream.Collectors.toUnmodifiableMap(entry -> normaliseKind(entry.getKey()),
							Map.Entry::getValue, (first, second) -> second));
		Map<String, Integer> concurrency = new java.util.HashMap<>(Map.of("OCR_DOCUMENT", 1));
		if (kindConcurrency != null) {
			kindConcurrency.forEach((kind, limit) -> concurrency.put(normaliseKind(kind), limit));
		}
		if (concurrency.values().stream().anyMatch(limit -> limit == null || limit < 1)) {
			throw new IllegalArgumentException("mimorag.jobs.kind-concurrency values must be at least 1");
		}
		kindConcurrency = Map.copyOf(concurrency);
		if (heartbeatInterval.compareTo(lease.dividedBy(2)) > 0) {
			throw new IllegalArgumentException("mimorag.jobs.heartbeat-interval must be at most half of the lease");
		}
		if (maxAttempts < 1 || maxAttempts > 20) {
			throw new IllegalArgumentException("mimorag.jobs.max-attempts must be between 1 and 20");
		}
		if (perIntegrationConcurrency < 1) {
			throw new IllegalArgumentException("mimorag.jobs.per-integration-concurrency must be at least 1");
		}
	}

	public int workersFor(String kind) {
		return Math.max(1, workers.getOrDefault(normaliseKind(kind), 1));
	}

	/** Maximum running jobs of this kind per integration. */
	public int perIntegrationConcurrencyFor(String kind) {
		return kindConcurrency.getOrDefault(normaliseKind(kind), perIntegrationConcurrency);
	}

	private static String normaliseKind(String key) {
		return key.trim().toUpperCase(java.util.Locale.ROOT).replace('-', '_').replace('.', '_');
	}

	/** Exponential backoff: base × 4^(attempt-1), capped, ±20% jitter so retries of many jobs do not align. */
	public Duration retryDelay(int failedAttempt, double jitter) {
		double factor = Math.pow(4, Math.max(0, failedAttempt - 1));
		long millis = (long) Math.min(retryMaxDelay.toMillis(), retryBaseDelay.toMillis() * factor);
		return Duration.ofMillis(Math.max(0, (long) (millis * (0.8 + 0.4 * jitter))));
	}

}
