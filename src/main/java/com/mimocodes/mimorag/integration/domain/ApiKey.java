package com.mimocodes.mimorag.integration.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.mimocodes.mimorag.shared.access.Permission;

/**
 * Stored metadata of an integration API key. The secret itself is never stored; see {@link ApiKeyCredential}.
 * Scopes are the permissions the integration's service identity holds, and they also bound what an end user can do
 * through this key. Lifecycle rules (bootstrap vs standard, expiry bounds) are enforced here and again by database
 * CHECK constraints.
 */
@Entity
@Table(name = "integration_api_key")
public class ApiKey {

	public static final Duration BOOTSTRAP_MAX_VALIDITY = Duration.ofHours(24);

	public static final Duration STANDARD_DEFAULT_VALIDITY = Duration.ofDays(90);

	public static final Duration STANDARD_MAX_VALIDITY = Duration.ofDays(365);

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

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, updatable = false)
	private ApiKeyType keyType;

	@Column(nullable = false, updatable = false)
	private String[] scopes;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private ApiKeyStatus status;

	@Column(nullable = false, updatable = false)
	private Instant expiresAt;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false, updatable = false)
	private String createdBy;

	private Instant revokedAt;

	private String revokedBy;

	/** Maintained by the database ({@code record_api_key_use}); never written through JPA. */
	@Column(insertable = false, updatable = false)
	private Instant lastUsedAt;

	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "integration_api_key_kb", joinColumns = @JoinColumn(name = "api_key_id"))
	private Set<KnowledgeBaseRestriction> knowledgeBaseRestrictions = new HashSet<>();

	@Version
	private Long version;

	protected ApiKey() {
	}

	private ApiKey(UUID integrationId, ApiKeyCredential.Generated credential, String name, ApiKeyType keyType,
			Set<Permission> scopes, Set<UUID> knowledgeBaseIds, Instant expiresAt, Instant now, String createdBy) {
		this.id = UUID.randomUUID();
		this.integrationId = integrationId;
		this.keyId = credential.keyId();
		this.secretHash = credential.secretHash();
		this.last4 = credential.last4();
		this.name = name;
		this.keyType = keyType;
		this.scopes = scopes.stream().map(Enum::name).sorted().toArray(String[]::new);
		this.status = ApiKeyStatus.ACTIVE;
		this.expiresAt = expiresAt;
		this.createdAt = now;
		this.createdBy = createdBy;
		knowledgeBaseIds.forEach(kbId -> knowledgeBaseRestrictions.add(new KnowledgeBaseRestriction(integrationId, kbId)));
	}

	/** Admin hand-over key: rotation only, short-lived, single use. */
	public static ApiKey issueBootstrap(UUID integrationId, ApiKeyCredential.Generated credential, String name,
			Instant now, String createdBy) {
		Objects.requireNonNull(integrationId, "integrationId");
		return new ApiKey(integrationId, credential, name, ApiKeyType.BOOTSTRAP, EnumSet.of(Permission.KEY_ROTATE),
				Set.of(), now.plus(BOOTSTRAP_MAX_VALIDITY), now, createdBy);
	}

	/** Key created by the integration itself. Expiry is mandatory and bounded. */
	public static ApiKey issueStandard(UUID integrationId, ApiKeyCredential.Generated credential, String name,
			Set<Permission> scopes, Set<UUID> knowledgeBaseIds, Instant expiresAt, Instant now, String createdBy) {
		Objects.requireNonNull(integrationId, "integrationId");
		Objects.requireNonNull(expiresAt, "expiresAt");
		if (scopes == null || scopes.isEmpty()) {
			throw new IllegalArgumentException("An API key needs at least one scope");
		}
		if (!expiresAt.isAfter(now) || expiresAt.isAfter(now.plus(STANDARD_MAX_VALIDITY))) {
			throw new IllegalArgumentException("Expiry must be in the future and at most 365 days away");
		}
		return new ApiKey(integrationId, credential, name, ApiKeyType.STANDARD, scopes,
				knowledgeBaseIds == null ? Set.of() : knowledgeBaseIds, expiresAt, now, createdBy);
	}

	public void revoke(Instant now, String revokedByPrincipal) {
		if (status == ApiKeyStatus.ACTIVE) {
			status = ApiKeyStatus.REVOKED;
			revokedAt = now;
			revokedBy = revokedByPrincipal;
		}
	}

	public boolean isActive(Instant now) {
		return status == ApiKeyStatus.ACTIVE && expiresAt.isAfter(now);
	}

	public boolean isBootstrap() {
		return keyType == ApiKeyType.BOOTSTRAP;
	}

	public Set<Permission> getScopes() {
		return Arrays.stream(scopes)
			.map(Permission::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

	/** Knowledge Bases this key is restricted to; empty means integration-wide. */
	public Set<UUID> getKnowledgeBaseIds() {
		return knowledgeBaseRestrictions.stream()
			.map(KnowledgeBaseRestriction::knowledgeBaseId)
			.collect(Collectors.toUnmodifiableSet());
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

	public ApiKeyType getKeyType() {
		return keyType;
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

	public String getCreatedBy() {
		return createdBy;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public String getRevokedBy() {
		return revokedBy;
	}

	public Instant getLastUsedAt() {
		return lastUsedAt;
	}

}
