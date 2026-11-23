package com.mimocodes.mimorag.access.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param publicApiDocs whether the OpenAPI/Swagger UI endpoints are reachable without authentication. Must stay
 * {@code false} outside local development.
 */
@ConfigurationProperties("mimorag.security")
public record AccessSecurityProperties(boolean publicApiDocs) {
}
