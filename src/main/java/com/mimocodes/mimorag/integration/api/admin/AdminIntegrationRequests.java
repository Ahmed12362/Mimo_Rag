package com.mimocodes.mimorag.integration.api.admin;

import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.integration.domain.TrustConfiguration.TrustSettings;
import com.mimocodes.mimorag.shared.access.Permission;

/** Request bodies of the platform-admin integration API. */
final class AdminIntegrationRequests {

	static final String CODE_PATTERN = "^[a-z][a-z0-9-]{1,63}$";

	private AdminIntegrationRequests() {
	}

	/**
	 * @param allowedScopes scope ceiling for the integration's own keys ({@code KEY_ROTATE} is implicit)
	 */
	record RegisterIntegrationRequest(
			@NotBlank @Pattern(regexp = CODE_PATTERN, message = "must be 2-64 lowercase letters, digits or hyphens, starting with a letter") String code,
			@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
			@NotNull @Size(max = 10) Set<@NotNull Permission> allowedScopes) {
	}

	record ChangeStatusRequest(@NotNull IntegrationStatus status) {
	}

	record AllowedScopesRequest(@NotNull @Size(max = 10) Set<@NotNull Permission> allowedScopes) {
	}

	record IssueBootstrapKeyRequest(@NotBlank @Size(max = 100) String name) {
	}

	record TrustConfigurationRequest(@NotBlank @Size(max = 500) String issuer, @NotBlank @Size(max = 500) String audience,
			@Size(max = 2000) String jwksUri, @Size(max = 20000) String inlineJwks,
			@NotEmpty @Size(max = 2) List<@NotBlank String> allowedAlgorithms,
			@Min(30) @Max(3600) Integer maxTokenLifetimeSeconds, @Min(0) @Max(300) Integer clockSkewSeconds,
			@Size(max = 100) String claimUserId, @Size(max = 100) String claimRoles,
			@Size(max = 100) String claimGroups) {

		TrustSettings toSettings() {
			return new TrustSettings(issuer, audience, jwksUri, inlineJwks, allowedAlgorithms,
					maxTokenLifetimeSeconds == null ? 300 : maxTokenLifetimeSeconds,
					clockSkewSeconds == null ? 30 : clockSkewSeconds, claimUserId == null ? "sub" : claimUserId,
					claimRoles, claimGroups);
		}

	}

}
