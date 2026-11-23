package com.mimocodes.mimorag.access.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mimocodes.mimorag.shared.web.ApiErrorCode;
import com.mimocodes.mimorag.shared.web.ApiProblems;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes 401/403 responses raised by the security filter chain in the same Problem Details format used by
 * the MVC layer. Responses never reveal whether the requested resource exists.
 */
@Component
class ProblemDetailSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	ProblemDetailSecurityErrorHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		write(response, ApiProblems.of(HttpStatus.UNAUTHORIZED, ApiErrorCode.AUTHENTICATION_REQUIRED,
				"Valid credentials are required to access this resource."));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		write(response, ApiProblems.of(HttpStatus.FORBIDDEN, ApiErrorCode.ACCESS_DENIED,
				"You are not allowed to perform this operation."));
	}

	/** Credential verification could not run (e.g. database unavailable): fail closed, but report it honestly. */
	void writeServiceUnavailable(HttpServletResponse response) throws IOException {
		write(response, ApiProblems.of(HttpStatus.SERVICE_UNAVAILABLE, ApiErrorCode.SERVICE_UNAVAILABLE,
				"The service is temporarily unavailable. Retry later."));
	}

	private void write(HttpServletResponse response, ProblemDetail problem) throws IOException {
		response.setStatus(problem.getStatus());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		jsonMapper.writeValue(response.getOutputStream(), ApiProblems.toJsonMap(problem));
	}

}
