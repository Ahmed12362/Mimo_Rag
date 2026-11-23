package com.mimocodes.mimorag.integration.domain;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Stored metadata of an integration API key. The secret itself is never stored; see {@link ApiKeyCredential}.
 * Scopes are the permissions the integration's service identity holds, and they also bound what an end user can do
 * through this key.
 */
@Entity
@Table(name = "integration_api_key")
public class ApiKey {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private UUID integrationId;

	@Column(nullable = false, updatable = false)
	private String keyId;

	@Column(nullable = false, updatable = false)
	private String secretHash;

	@Column(name = "last4", nullable = false, updatable = false)
	private String last4;

	@Column(nullable = false, updatable = false)
	private String name;

	@Column(nullable = false, updatable = false)
	private String[] scopes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ApiKeyStatus status;

	@Column(updatable = false)
	private Instant expiresAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant revokedAt;

	/** Maintained by the database ({@code record_api_key_use}); never written through JPA. */
	@Column(insertable = false, updatable = false)
	private Instant lastUsedAt;

	@Version
	private Long version;

	protected ApiKey() {
	}

	private ApiKey(UUID integrationId, ApiKeyCredential.Generated credential, String name, Set<Permission> scopes,
			Instant expiresAt, Instant now) {
		this.id = UUID.randomUUID();
		this.integrationId = integrationId;
		this.keyId = credential.keyId();
		this.secretHash = credential.secretHash();
		this.last4 = credential.last4();
		this.name = name;
		this.scopes = scopes.stream().map(Enum::name).sorted().toArray(String[]::new);
		this.status = ApiKeyStatus.ACTIVE;
		this.expiresAt = expiresAt;
		this.createdAt = now;
	}

	public static ApiKey issue(UUID integrationId, ApiKeyCredential.Generated credential, String name,
			Set<Permission> scopes, Instant expiresAt, Instant now) {
		Objects.requireNonNull(integrationId, "integrationId");
		Objects.requireNonNull(credential, "credential");
		if (scopes == null || scopes.isEmpty()) {
			throw new IllegalArgumentException("An API key needs at least one scope");
		}
		if (expiresAt != null && !expiresAt.isAfter(now)) {
			throw new IllegalArgumentException("Expiry must be in the future");
		}
		return new ApiKey(integrationId, credential, name, scopes, expiresAt, now);
	}

	public void revoke(Instant now) {
		if (status == ApiKeyStatus.ACTIVE) {
			status = ApiKeyStatus.REVOKED;
			revokedAt = now;
		}
	}

	public Set<Permission> getScopes() {
		return Arrays.stream(scopes)
			.map(Permission::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

	public UUID getId() {
		return id;
	}

	public UUID getIntegrationId() {
		return integrationId;
	}

	public String getKeyId() {
		return keyId;
	}

	public String getLast4() {
		return last4;
	}

	public String getName() {
		return name;
	}

	public ApiKeyStatus getStatus() {
		return status;
	}

	public Instant getExpiresAt() {
		return expiresAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

}
