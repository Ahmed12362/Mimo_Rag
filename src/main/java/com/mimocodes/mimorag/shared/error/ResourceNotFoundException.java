package com.mimocodes.mimorag.shared.error;

/**
 * The resource does not exist <em>or</em> the caller may not know it exists. Both cases deliberately produce the
 * same 404 response so that identifiers of other tenants cannot be probed.
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String resourceType) {
		super(resourceType + " not found");
	}

}
