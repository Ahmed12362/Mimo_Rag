package com.mimocodes.mimorag.access.web;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * M0 security baseline: a stateless, deny-by-default API. Only liveness/health and info are public.
 * <p>
 * Integration-plane authentication (API keys + signed user assertions) and the separate platform-admin plane
 * are added in M1 as additional, path-scoped filter chains; see {@code docs/security-architecture.md}.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {

	private static final String[] API_DOC_PATHS = { "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html",
			"/swagger-ui/**" };

	@Bean
	SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http, ProblemDetailSecurityErrorHandler errorHandler,
			AccessSecurityProperties properties) throws Exception {
		http.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.authorizeHttpRequests(authorize -> {
				authorize.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
				authorize.requestMatchers(EndpointRequest.to("health", "info")).permitAll();
				if (properties.publicApiDocs()) {
					authorize.requestMatchers(API_DOC_PATHS).permitAll();
				}
				authorize.anyRequest().denyAll();
			});
		return http.build();
	}

}
