package com.mimocodes.mimorag.shared.web;

import java.util.Comparator;
import java.util.List;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
