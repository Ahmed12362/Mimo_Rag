package com.mimocodes.mimorag.shared.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns a correlation ID to every request before any other filter (including security) runs, so that
 * rejected requests are traceable too. The ID is exposed in the logging MDC and echoed in the response.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String correlationId = resolveCorrelationId(request);
		MDC.put(CorrelationId.MDC_KEY, correlationId);
		response.setHeader(CorrelationId.HEADER_NAME, correlationId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}

	private static String resolveCorrelationId(HttpServletRequest request) {
		String supplied = request.getHeader(CorrelationId.HEADER_NAME);
		return CorrelationId.isAcceptable(supplied) ? supplied : CorrelationId.generate();
	}

}
