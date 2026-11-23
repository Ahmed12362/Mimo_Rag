package com.mimocodes.mimorag.access.web;

import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicApiDocs whether the OpenAPI/Swagger UI endpoints are reachable without authentication. Must stay
 * {@code false} outside local development.
 * @param adminKeySha256 hex SHA-256 of the bootstrap platform-admin key; empty disables the admin plane
 */
@ConfigurationProperties("mimorag.security")
public record AccessSecurityProperties(boolean publicApiDocs, String adminKeySha256) {

	private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-fA-F]{64}");

	public AccessSecurityProperties {
		if (adminKeySha256 != null && !adminKeySha256.isBlank() && !SHA256_HEX.matcher(adminKeySha256.trim()).matches()) {
			throw new IllegalArgumentException(
					"mimorag.security.admin-key-sha256 (MIMORAG_ADMIN_KEY_SHA256) must be 64 hexadecimal characters");
		}
	}

	public boolean adminPlaneEnabled() {
		return adminKeySha256 != null && !adminKeySha256.isBlank();
	}

}
