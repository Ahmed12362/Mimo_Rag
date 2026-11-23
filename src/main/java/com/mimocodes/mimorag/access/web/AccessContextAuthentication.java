package com.mimocodes.mimorag.access.web;

import java.util.List;

import com.mimocodes.mimorag.shared.access.AccessContext;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * Spring Security representation of a verified {@link AccessContext}. Only the coarse plane is expressed as an
 * authority (used by the filter chains); fine-grained authorization happens in application services.
 */
final class AccessContextAuthentication extends AbstractAuthenticationToken {

	static final String ROLE_PLATFORM_ADMIN = "PLATFORM_ADMIN";

	static final String ROLE_INTEGRATION = "INTEGRATION";

	private final AccessContext accessContext;

	AccessContextAuthentication(AccessContext accessContext) {
		super(List.of(new SimpleGrantedAuthority(
				"ROLE_" + (accessContext.isPlatformAdmin() ? ROLE_PLATFORM_ADMIN : ROLE_INTEGRATION))));
		this.accessContext = accessContext;
		setAuthenticated(true);
	}

	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public Object getPrincipal() {
		return accessContext.principalRef();
	}

	AccessContext accessContext() {
		return accessContext;
	}

}
