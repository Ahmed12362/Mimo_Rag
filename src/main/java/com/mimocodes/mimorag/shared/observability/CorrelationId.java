package com.mimocodes.mimorag.shared.observability;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * Correlation ID conventions shared by HTTP requests, background jobs and logs.
 * <p>
 * Client-supplied IDs are accepted only if they match a conservative pattern, so they cannot be used for
 * log injection or to smuggle arbitrary data into logs and job records.
 */
public final class CorrelationId {

	public static final String HEADER_NAME = "X-Correlation-Id";

	public static final String MDC_KEY = "correlationId";

	private static final Pattern ACCEPTED_FORMAT = Pattern.compile("[A-Za-z0-9._-]{8,64}");

	private CorrelationId() {
	}

	public static String generate() {
		return UUID.randomUUID().toString();
	}

	public static boolean isAcceptable(String candidate) {
		return candidate != null && ACCEPTED_FORMAT.matcher(candidate).matches();
	}

	public static Optional<String> current() {
		return Optional.ofNullable(MDC.get(MDC_KEY));
	}

}
