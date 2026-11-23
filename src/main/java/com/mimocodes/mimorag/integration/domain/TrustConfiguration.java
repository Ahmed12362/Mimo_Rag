package com.mimocodes.mimorag.integration.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * How the platform verifies signed user assertions issued by one integration: which issuer and audience to require,
 * where the issuer's public keys are, which algorithms are acceptable, and where user id, roles and groups live in
 * the token. Policy validation (allowed URI schemes, dev-only inline keys) happens in the application layer.
 */
@Entity
@Table(name = "integration_trust_config")
public class TrustConfiguration {

	@Id
	private UUID integrationId;

	@Column(nullable = false)
	private String issuer;

	@Column(nullable = false)
	private String audience;

	private String jwksUri;

	private String inlineJwks;

	@Column(nullable = false)
	private String[] allowedAlgorithms;

	@Column(nullable = false)
	private int maxTokenLifetimeSeconds;

	@Column(nullable = false)
	private int clockSkewSeconds;

	@Column(nullable = false)
	private String claimUserId;

	private String claimRoles;

	private String claimGroups;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected TrustConfiguration() {
	}

	public TrustConfiguration(UUID integrationId) {
		this.integrationId = Objects.requireNonNull(integrationId, "integrationId");
	}

	/** Replaces the whole configuration (PUT semantics). Exactly one key source must be provided. */
	public void replace(TrustSettings settings, Instant now) {
		Objects.requireNonNull(settings, "settings");
		if ((settings.jwksUri() == null) == (settings.inlineJwks() == null)) {
			throw new IllegalArgumentException("Exactly one of jwksUri or inlineJwks must be set");
		}
		this.issuer = settings.issuer();
		this.audience = settings.audience();
		this.jwksUri = settings.jwksUri();
		this.inlineJwks = settings.inlineJwks();
		this.allowedAlgorithms = settings.allowedAlgorithms().stream().sorted().toArray(String[]::new);
		this.maxTokenLifetimeSeconds = settings.maxTokenLifetimeSeconds();
		this.clockSkewSeconds = settings.clockSkewSeconds();
		this.claimUserId = settings.claimUserId();
		this.claimRoles = settings.claimRoles();
		this.claimGroups = settings.claimGroups();
		this.updatedAt = now;
	}

	/** Immutable value describing the configuration content. */
	public record TrustSettings(String issuer, String audience, String jwksUri, String inlineJwks,
			List<String> allowedAlgorithms, int maxTokenLifetimeSeconds, int clockSkewSeconds, String claimUserId,
			String claimRoles, String claimGroups) {
	}

	public TrustSettings settings() {
		return new TrustSettings(issuer, audience, jwksUri, inlineJwks, List.of(allowedAlgorithms),
				maxTokenLifetimeSeconds, clockSkewSeconds, claimUserId, claimRoles, claimGroups);
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

}
