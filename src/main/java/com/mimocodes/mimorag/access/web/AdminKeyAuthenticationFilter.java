package com.mimocodes.mimorag.access.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.observability.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Platform-admin plane authentication (M1 bootstrap mechanism): the {@code X-Admin-Key} header is hashed and
 * compared in constant time with the configured SHA-256. When no hash is configured the admin plane is disabled and
 * every request is rejected. To be replaced by OIDC behind the same chain later.
 */
final class AdminKeyAuthenticationFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Admin-Key";

	private static final int MAX_KEY_LENGTH = 256;

	private static final Logger log = LoggerFactory.getLogger(AdminKeyAuthenticationFilter.class);

	private final byte[] expectedHash;

	AdminKeyAuthenticationFilter(String adminKeySha256) {
		this.expectedHash = adminKeySha256 == null || adminKeySha256.isBlank() ? null
				: HexFormat.of().parseHex(adminKeySha256.trim().toLowerCase());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String presented = request.getHeader(HEADER);
		if (presented == null) {
			chain.doFilter(request, response);
			return;
		}
		if (expectedHash == null || presented.length() > MAX_KEY_LENGTH
				|| !MessageDigest.isEqual(sha256(presented), expectedHash)) {
			log.warn("Admin authentication failed (reason={})", expectedHash == null ? "ADMIN_PLANE_DISABLED" : "BAD_KEY");
			chain.doFilter(request, response);
			return;
		}
		AccessContext context = AccessContext.platformAdmin(CorrelationId.current().orElse(null));
		AccessContextBinding.proceedAs(context, request, response, chain);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
