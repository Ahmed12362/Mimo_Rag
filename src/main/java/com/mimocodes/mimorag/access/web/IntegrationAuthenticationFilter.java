package com.mimocodes.mimorag.access.web;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mimocodes.mimorag.access.assertion.InvalidUserAssertionException;
import com.mimocodes.mimorag.access.assertion.UserAssertionVerifier;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Authenticated;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Rejected;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.dao.DataAccessException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Integration plane authentication.
 * <ol>
 * <li>{@code X-Api-Key} (required): identifies the integration and therefore the tenant.</li>
 * <li>{@code X-User-Assertion} (optional): a signed JWT from the same integration identifying the end user.</li>
 * </ol>
 * Any failure leaves the request unauthenticated, which produces the same generic 401 for every cause. Secrets and
 * tokens are never logged; only reason codes and the public key id.
 */
final class IntegrationAuthenticationFilter extends OncePerRequestFilter {

	static final String API_KEY_HEADER = "X-Api-Key";

	static final String USER_ASSERTION_HEADER = "X-User-Assertion";

	private static final Logger log = LoggerFactory.getLogger(IntegrationAuthenticationFilter.class);

	private final ApiKeyAuthenticator apiKeyAuthenticator;

	private final UserAssertionVerifier userAssertionVerifier;

	private final AuditRecorder audit;

	private final ProblemDetailSecurityErrorHandler errorHandler;

	IntegrationAuthenticationFilter(ApiKeyAuthenticator apiKeyAuthenticator, UserAssertionVerifier userAssertionVerifier,
			AuditRecorder audit, ProblemDetailSecurityErrorHandler errorHandler) {
		this.apiKeyAuthenticator = apiKeyAuthenticator;
		this.userAssertionVerifier = userAssertionVerifier;
		this.audit = audit;
		this.errorHandler = errorHandler;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String apiKey = request.getHeader(API_KEY_HEADER);
		if (apiKey == null) {
			chain.doFilter(request, response);
			return;
		}
		AccessContext context;
		try {
			context = authenticate(apiKey, request.getHeader(USER_ASSERTION_HEADER));
		}
		catch (DataAccessException ex) {
			log.error("Credential verification unavailable", ex);
			errorHandler.writeServiceUnavailable(response);
			return;
		}
		if (context == null) {
			chain.doFilter(request, response);
			return;
		}
		AccessContextBinding.proceedAs(context, request, response, chain);
	}

	/** @return the verified context, or {@code null} if authentication failed */
	private AccessContext authenticate(String apiKey, String userAssertion) {
		ApiKeyAuthenticator.Result result = apiKeyAuthenticator.authenticate(apiKey);
		if (result instanceof Rejected rejected) {
			log.warn("API key authentication failed (reason={}, keyId={})", rejected.reason(), rejected.keyId());
			return null;
		}
		Authenticated authenticated = (Authenticated) result;
		AccessContext service = AccessContext.integrationService(authenticated.integrationId(),
				authenticated.keyId(), authenticated.scopes(), CorrelationId.current().orElse(null));
		if (userAssertion == null) {
			return service;
		}
		try {
			return userAssertionVerifier.verify(userAssertion, service);
		}
		catch (InvalidUserAssertionException ex) {
			log.warn("User assertion rejected (reason={}, keyId={}, detail={})", ex.getReason(), authenticated.keyId(),
					ex.getMessage());
			audit.recordUnauthenticated(new AuditEvent("USER_ASSERTION_REJECTED", "api-key", authenticated.keyId(),
					AuditOutcome.DENIED, Map.of("reason", ex.getReason().name())));
			return null;
		}
	}

}
