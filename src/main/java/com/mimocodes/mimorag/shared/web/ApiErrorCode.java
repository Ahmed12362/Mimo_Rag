package com.mimocodes.mimorag.shared.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Stable, machine-readable error codes returned in the {@code errorCode} field of every error response.
 * Clients should branch on these codes, never on human-readable titles or details.
 */
public enum ApiErrorCode {

	VALIDATION_FAILED,
	MALFORMED_REQUEST,
	AUTHENTICATION_REQUIRED,
	ACCESS_DENIED,
	RESOURCE_NOT_FOUND,
	METHOD_NOT_ALLOWED,
	UNSUPPORTED_MEDIA_TYPE,
	NOT_ACCEPTABLE,
	PAYLOAD_TOO_LARGE,
	CONFLICT,
	RATE_LIMITED,
	/** The integration has too many unfinished ingestion jobs; retry after the {@code Retry-After} delay. */
	UPLOAD_QUEUE_FULL,
	/** Storing this upload would exceed the integration's storage quota. */
	STORAGE_QUOTA_EXCEEDED,
	SERVICE_UNAVAILABLE,
	INTERNAL_ERROR;

	public static ApiErrorCode fromStatus(HttpStatusCode status) {
		HttpStatus resolved = HttpStatus.resolve(status.value());
		if (resolved == null) {
			return status.is4xxClientError() ? MALFORMED_REQUEST : INTERNAL_ERROR;
		}
		return switch (resolved) {
			case BAD_REQUEST -> MALFORMED_REQUEST;
			case UNAUTHORIZED -> AUTHENTICATION_REQUIRED;
			case FORBIDDEN -> ACCESS_DENIED;
			case NOT_FOUND -> RESOURCE_NOT_FOUND;
			case METHOD_NOT_ALLOWED -> METHOD_NOT_ALLOWED;
			case NOT_ACCEPTABLE -> NOT_ACCEPTABLE;
			case UNSUPPORTED_MEDIA_TYPE -> UNSUPPORTED_MEDIA_TYPE;
			case CONTENT_TOO_LARGE -> PAYLOAD_TOO_LARGE;
			case CONFLICT -> CONFLICT;
			case TOO_MANY_REQUESTS -> RATE_LIMITED;
			case SERVICE_UNAVAILABLE -> SERVICE_UNAVAILABLE;
			default -> resolved.is4xxClientError() ? MALFORMED_REQUEST : INTERNAL_ERROR;
		};
	}

}
