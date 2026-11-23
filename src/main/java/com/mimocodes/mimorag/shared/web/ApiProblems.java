package com.mimocodes.mimorag.shared.web;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mimocodes.mimorag.shared.observability.CorrelationId;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the platform's single error format: RFC 9457 Problem Details extended with {@code errorCode},
 * {@code correlationId}, {@code timestamp} and (for validation failures) {@code violations}.
 */
public final class ApiProblems {

	public static final String ERROR_CODE = "errorCode";

	public static final String CORRELATION_ID = "correlationId";

	public static final String TIMESTAMP = "timestamp";

	public static final String VIOLATIONS = "violations";

	private static final String TYPE_PREFIX = "urn:mimorag:problem:";

	private ApiProblems() {
	}

	public static ProblemDetail of(HttpStatusCode status, ApiErrorCode errorCode, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		return enrich(problem, errorCode);
	}

	public static ProblemDetail validationFailed(List<ValidationViolation> violations) {
		ProblemDetail problem = of(HttpStatusCode.valueOf(400), ApiErrorCode.VALIDATION_FAILED,
				"The request contains invalid fields.");
		problem.setProperty(VIOLATIONS, violations);
		return problem;
	}

	/**
	 * Adds the platform-specific fields to a problem created elsewhere (for example by Spring MVC), and
	 * replaces its type with a stable URN derived from the error code.
	 */
	public static ProblemDetail enrich(ProblemDetail problem, ApiErrorCode errorCode) {
		problem.setType(URI.create(TYPE_PREFIX + errorCode.name().toLowerCase().replace('_', '-')));
		problem.setProperty(ERROR_CODE, errorCode.name());
		problem.setProperty(CORRELATION_ID, CorrelationId.current().orElse(null));
		problem.setProperty(TIMESTAMP, Instant.now().toString());
		return problem;
	}

	/**
	 * Flattens a problem into the JSON shape produced by Spring MVC, for writers outside MVC (e.g. security
	 * filters) that serialize the body themselves.
	 */
	public static Map<String, Object> toJsonMap(ProblemDetail problem) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", problem.getType().toString());
		body.put("title", problem.getTitle());
		body.put("status", problem.getStatus());
		body.put("detail", problem.getDetail());
		if (problem.getInstance() != null) {
			body.put("instance", problem.getInstance().toString());
		}
		if (problem.getProperties() != null) {
			body.putAll(problem.getProperties());
		}
		return body;
	}

}
