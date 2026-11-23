package com.mimocodes.mimorag.access.web;

import java.time.Clock;

import jakarta.servlet.DispatcherType;

import com.mimocodes.mimorag.access.assertion.UserAssertionVerifier;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Three stateless, path-scoped filter chains (see docs/security-architecture.md §2):
 * <ol>
 * <li>{@code /api/v1/admin/**} — platform-admin plane, admin credential only;</li>
 * <li>{@code /api/v1/**} — integration plane, integration API key (+ optional signed user assertion);</li>
 * <li>everything else — only health/info (and API docs locally) are public; the rest is denied.</li>
 * </ol>
 * Each chain recognises only its own credential type, so an integration key on an admin path (or vice versa) is
 * simply unauthenticated. Filters are created here, not as beans, so they are never registered globally.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	private static final String[] API_DOC_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
			"/swagger-ui/**" };

	@Bean
	@Order(1)
	SecurityFilterChain adminPlaneSecurityFilterChain(HttpSecurity http, ProblemDetailSecurityErrorHandler errorHandler,
			AccessSecurityProperties properties) throws Exception {
		applyStatelessDefaults(http, errorHandler);
		http.securityMatcher("/api/v1/admin/**")
			.addFilterBefore(new AdminKeyAuthenticationFilter(properties.adminKeySha256()),
					AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(authorize -> authorize.anyRequest()
				.hasRole(AccessContextAuthentication.ROLE_PLATFORM_ADMIN));
		return http.build();
	}

	@Bean
	@Order(2)
	SecurityFilterChain integrationPlaneSecurityFilterChain(HttpSecurity http,
			ProblemDetailSecurityErrorHandler errorHandler, ApiKeyAuthenticator apiKeyAuthenticator,
			UserAssertionVerifier userAssertionVerifier, AuditRecorder audit, Clock clock) throws Exception {
		applyStatelessDefaults(http, errorHandler);
		http.securityMatcher("/api/v1/**")
			.addFilterBefore(new IntegrationAuthenticationFilter(apiKeyAuthenticator, userAssertionVerifier, audit,
					errorHandler, clock), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(authorize -> authorize.anyRequest()
				.hasRole(AccessContextAuthentication.ROLE_INTEGRATION));
		return http.build();
	}

	@Bean
	@Order(3)
	SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, ProblemDetailSecurityErrorHandler errorHandler,
			AccessSecurityProperties properties) throws Exception {
		applyStatelessDefaults(http, errorHandler);
		http.authorizeHttpRequests(authorize -> {
			authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
			authorize.requestMatchers(EndpointRequest.to("health", "info")).permitAll();
			if (properties.publicApiDocs()) {
				authorize.requestMatchers(API_DOC_PATHS).permitAll();
			}
			authorize.anyRequest().denyAll();
		});
		return http.build();
	}

	private static void applyStatelessDefaults(HttpSecurity http, ProblemDetailSecurityErrorHandler errorHandler)
			throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler));
	}

}
