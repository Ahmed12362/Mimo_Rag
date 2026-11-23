package com.mimocodes.mimorag.integration.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Development relaxations for user-assertion trust configuration. Both must stay {@code false} in production:
 * production integrations use HTTPS JWKS endpoints only (ADR-0005).
 *
 * @param allowInsecureJwks accept {@code http://} JWKS URIs (local development and tests only)
 * @param allowInlineJwks accept public keys embedded in the configuration instead of a JWKS URI (local only)
 */
@ConfigurationProperties("mimorag.security.trust")
public record TrustPolicyProperties(boolean allowInsecureJwks, boolean allowInlineJwks) {
}
