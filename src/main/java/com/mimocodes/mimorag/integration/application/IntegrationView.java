package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.Integration;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;

/**
 * @param allowedScopes scope ceiling for the integration's own keys ({@code KEY_ROTATE} is always allowed in
 * addition)
 */
public record IntegrationView(UUID id, String code, String name, String description, IntegrationStatus status,
		Set<Permission> allowedScopes, Instant createdAt, Instant updatedAt, long version) {

	static IntegrationView of(Integration integration) {
		return new IntegrationView(integration.getId(), integration.getCode(), integration.getName(),
				integration.getDescription(), integration.getStatus(), integration.getAllowedScopes(),
				integration.getCreatedAt(), integration.getUpdatedAt(),
				integration.getVersion() == null ? 0 : integration.getVersion());
	}

}
