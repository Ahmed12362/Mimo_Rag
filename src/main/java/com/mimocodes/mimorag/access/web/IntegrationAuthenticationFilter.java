package com.mimocodes.mimorag.access.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
 * <p>
 * A bootstrap key authenticates <em>only</em> for creating the integration's first own key
 * ({@code POST /api/v1/integration/api-keys}) and never together with a user assertion, so it can never reach tenant
 * content. Keys close to expiry get an {@code X-Api-Key-Expires-At} response header.
 */
final class IntegrationAuthenticationFilter extends OncePerRequestFilter {

	static final String API_KEY_HEADER = "X-Api-Key";

	static final String USER_ASSERTION_HEADER = "X-User-Assertion";

	static final String EXPIRY_WARNING_HEADER = "X-Api-Key-Expires-At";

	static final Duration EXPIRY_WARNING_WINDOW = Duration.ofDays(14);

	/** The only endpoint a bootstrap key may call (owned by the integration module's key controller). */
	static final String BOOTSTRAP_ENDPOINT = "/api/v1/integration/api-keys";

	private static final Logger log = LoggerFactory.getLogger(IntegrationAuthenticationFilter.class);

	private final ApiKeyAuthenticator apiKeyAuthenticator;

	private final UserAssertionVerifier userAssertionVerifier;

	private final AuditRecorder audit;

	private final ProblemDetailSecurityErrorHandler errorHandler;

	private final Clock clock;

	IntegrationAuthenticationFilter(ApiKeyAuthenticator apiKeyAuthenticator, UserAssertionVerifier userAssertionVerifier,
			AuditRecorder audit, ProblemDetailSecurityErrorHandler errorHandler, Clock clock) {
		this.apiKeyAuthenticator = apiKeyAuthenticator;
		this.userAssertionVerifier = userAssertionVerifier;
		this.audit = audit;
		this.errorHandler = errorHandler;
		this.clock = clock;
	}

	/** Outcome of authentication: a context, an unauthenticated request, or an upstream outage. */
	private record Outcome(AccessContext context, boolean serviceUnavailable) {

		static final Outcome UNAUTHENTICATED = new Outcome(null, false);

		static final Outcome UNAVAILABLE = new Outcome(null, true);

	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String apiKey = request.getHeader(API_KEY_HEADER);
		if (apiKey == null) {
			chain.doFilter(request, response);
			return;
		}
		Outcome outcome;
		try {
			outcome = authenticate(apiKey, request);
		}
		catch (DataAccessException ex) {
			log.error("Credential verification unavailable", ex);
			outcome = Outcome.UNAVAILABLE;
		}
		if (outcome.serviceUnavailable()) {
			errorHandler.writeServiceUnavailable(response);
			return;
		}
		if (outcome.context() == null) {
			chain.doFilter(request, response);
			return;
		}
		addExpiryWarning(outcome.context(), response);
		AccessContextBinding.proceedAs(outcome.context(), request, response, chain);
	}

	private Outcome authenticate(String apiKey, HttpServletRequest request) {
		ApiKeyAuthenticator.Result result = apiKeyAuthenticator.authenticate(apiKey);
		if (result instanceof Rejected rejected) {
			log.warn("API key authentication failed (reason={}, keyId={})", rejected.reason(), rejected.keyId());
			return Outcome.UNAUTHENTICATED;
		}
		Authenticated authenticated = (Authenticated) result;
		String userAssertion = request.getHeader(USER_ASSERTION_HEADER);
		if (authenticated.bootstrap() && (userAssertion != null || !isBootstrapEndpoint(request))) {
			log.warn("Bootstrap key used outside key creation (keyId={})", authenticated.keyId());
			audit.recordUnauthenticated(new AuditEvent("AUTHENTICATION_FAILED", "api-key", authenticated.keyId(),
					AuditOutcome.DENIED, Map.of("reason", "BOOTSTRAP_KEY_SCOPE")));
			return Outcome.UNAUTHENTICATED;
		}
		AccessContext service = AccessContext.integrationService(authenticated.integrationId(),
				new AccessContext.Credential(authenticated.keyId(), authenticated.bootstrap(),
						authenticated.knowledgeBaseRestriction(), authenticated.expiresAt()),
				authenticated.scopes(), CorrelationId.current().orElse(null));
		if (userAssertion == null) {
			return new Outcome(service, false);
		}
		try {
			return new Outcome(userAssertionVerifier.verify(userAssertion, service), false);
		}
		catch (InvalidUserAssertionException ex) {
			log.warn("User assertion rejected (reason={}, keyId={}, detail={})", ex.getReason(), authenticated.keyId(),
					ex.getMessage());
			audit.recordUnauthenticated(new AuditEvent("USER_ASSERTION_REJECTED", "api-key", authenticated.keyId(),
					AuditOutcome.DENIED, Map.of("reason", ex.getReason().name())));
			return ex.getReason() == InvalidUserAssertionException.Reason.KEY_SOURCE_UNAVAILABLE ? Outcome.UNAVAILABLE
					: Outcome.UNAUTHENTICATED;
		}
	}

	private static boolean isBootstrapEndpoint(HttpServletRequest request) {
		return "POST".equals(request.getMethod()) && BOOTSTRAP_ENDPOINT.equals(request.getRequestURI());
	}

	private void addExpiryWarning(AccessContext context, HttpServletResponse response) {
		Instant expiresAt = context.credential().expiresAt();
		if (expiresAt != null && expiresAt.isBefore(clock.instant().plus(EXPIRY_WARNING_WINDOW))) {
			response.setHeader(EXPIRY_WARNING_HEADER, expiresAt.toString());
		}
	}

}
