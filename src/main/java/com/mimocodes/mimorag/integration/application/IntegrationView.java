package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.Integration;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;

public record IntegrationView(UUID id, String code, String name, String description, IntegrationStatus status,
		Instant createdAt, Instant updatedAt, long version) {

	static IntegrationView of(Integration integration) {
		return new IntegrationView(integration.getId(), integration.getCode(), integration.getName(),
				integration.getDescription(), integration.getStatus(), integration.getCreatedAt(),
				integration.getUpdatedAt(), integration.getVersion() == null ? 0 : integration.getVersion());
	}

}
