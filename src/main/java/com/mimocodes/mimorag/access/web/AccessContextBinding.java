package com.mimocodes.mimorag.access.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;

/** Binds a verified context for the rest of the request and always unbinds it afterwards. */
final class AccessContextBinding {

	private AccessContextBinding() {
	}

	static void proceedAs(AccessContext context, HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws IOException, ServletException {
		SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
		SecurityContext securityContext = strategy.createEmptyContext();
		securityContext.setAuthentication(new AccessContextAuthentication(context));
		strategy.setContext(securityContext);
		AccessContextHolder.set(context);
		try {
			chain.doFilter(request, response);
		}
		finally {
			AccessContextHolder.clear();
			strategy.clearContext();
		}
	}

}
