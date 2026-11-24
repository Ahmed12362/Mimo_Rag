package com.mimocodes.mimorag.shared.web;

import java.util.Comparator;
import java.util.List;

import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;

import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.PayloadTooLargeException;
import com.mimocodes.mimorag.shared.error.QuotaExceededException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;
import com.mimocodes.mimorag.shared.error.UnsupportedContentException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every exception raised inside Spring MVC to the platform error format. Internal details (stack traces,
 * exception messages, SQL, class names) are logged server-side but never returned to the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<ValidationViolation> violations = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> new ValidationViolation(error.getField(), error.getDefaultMessage()))
			.sorted(Comparator.comparing(ValidationViolation::field))
			.toList();
		return ResponseEntity.badRequest().body(ApiProblems.validationFailed(violations));
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ApiProblems.of(HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_REQUEST,
				"The request body is missing or malformed.");
		return ResponseEntity.badRequest().body(problem);
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
		List<ValidationViolation> violations = ex.getConstraintViolations()
			.stream()
			.map(violation -> new ValidationViolation(violation.getPropertyPath().toString(), violation.getMessage()))
			.sorted(Comparator.comparing(ValidationViolation::field))
			.toList();
		return ResponseEntity.badRequest().body(ApiProblems.validationFailed(violations));
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
		return ResponseEntity.badRequest().body(ApiProblems.validationFailed(ex.getViolations()));
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	ResponseEntity<ProblemDetail> handleNotFound(ResourceNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ApiProblems.of(HttpStatus.NOT_FOUND, ApiErrorCode.RESOURCE_NOT_FOUND,
					"The requested resource was not found."));
	}

	@ExceptionHandler(OperationNotPermittedException.class)
	ResponseEntity<ProblemDetail> handleNotPermitted(OperationNotPermittedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
			.body(ApiProblems.of(HttpStatus.FORBIDDEN, ApiErrorCode.ACCESS_DENIED,
					"You are not allowed to perform this operation."));
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ProblemDetail> handleConflict(ConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiProblems.of(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT, ex.getMessage()));
	}

	@ExceptionHandler(QuotaExceededException.class)
	ResponseEntity<ProblemDetail> handleQuotaExceeded(QuotaExceededException ex) {
		boolean queue = ex.getQuota() == QuotaExceededException.Quota.UPLOAD_QUEUE;
		HttpStatus status = queue ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.CONTENT_TOO_LARGE;
		ApiErrorCode code = queue ? ApiErrorCode.UPLOAD_QUEUE_FULL : ApiErrorCode.STORAGE_QUOTA_EXCEEDED;
		ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
		ex.getRetryAfter().ifPresent(delay -> response.header(HttpHeaders.RETRY_AFTER,
				Long.toString(Math.max(1, delay.toSeconds()))));
		return response.body(ApiProblems.of(status, code, ex.getMessage()));
	}

	@ExceptionHandler(UnsupportedContentException.class)
	ResponseEntity<ProblemDetail> handleUnsupportedContent(UnsupportedContentException ex) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.body(ApiProblems.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
					ex.getMessage()));
	}

	@ExceptionHandler(PayloadTooLargeException.class)
	ResponseEntity<ProblemDetail> handlePayloadTooLarge(PayloadTooLargeException ex) {
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
			.body(ApiProblems.of(HttpStatus.CONTENT_TOO_LARGE, ApiErrorCode.PAYLOAD_TOO_LARGE, ex.getMessage()));
	}

	@ExceptionHandler({ ObjectOptimisticLockingFailureException.class, OptimisticLockException.class })
	ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(ApiProblems.of(HttpStatus.CONFLICT, ApiErrorCode.CONFLICT,
					"The resource was modified concurrently. Reload it and retry with the current version."));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		log.error("Unhandled exception while processing request", ex);
		ProblemDetail problem = ApiProblems.of(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
				"An unexpected error occurred. Quote the correlationId when reporting this problem.");
		return ResponseEntity.internalServerError().body(problem);
	}

	/**
	 * Every standard Spring MVC exception (404, 405, 415, ...) flows through here; enrich its body so all
	 * errors share the same shape.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = (body instanceof ProblemDetail detail) ? detail
				: ProblemDetail.forStatus(statusCode);
		ApiProblems.enrich(problem, ApiErrorCode.fromStatus(statusCode));
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

}
