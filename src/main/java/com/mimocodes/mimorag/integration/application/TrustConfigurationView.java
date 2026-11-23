package com.mimocodes.mimorag.integration.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.TrustConfiguration;

/**
 * Trust configuration as needed by verifiers and admins. {@code version} changes on every update and lets verifiers
 * invalidate cached key material.
 */
public record TrustConfigurationView(UUID integrationId, String issuer, String audience, String jwksUri,
		String inlineJwks, List<String> allowedAlgorithms, int maxTokenLifetimeSeconds, int clockSkewSeconds,
		String claimUserId, String claimRoles, String claimGroups, Instant updatedAt, long version) {

	static TrustConfigurationView of(TrustConfiguration configuration) {
		TrustConfiguration.TrustSettings s = configuration.settings();
		return new TrustConfigurationView(configuration.getIntegrationId(), s.issuer(), s.audience(), s.jwksUri(),
				s.inlineJwks(), s.allowedAlgorithms(), s.maxTokenLifetimeSeconds(), s.clockSkewSeconds(),
				s.claimUserId(), s.claimRoles(), s.claimGroups(), configuration.getUpdatedAt(),
				configuration.getVersion() == null ? 0 : configuration.getVersion());
	}

}
