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
 * A connected system (HRIS, CRM, ERP, ...) registered with the platform. The integration is the tenant: every piece
 * of tenant data is owned by exactly one integration.
 */
@Entity
@Table(name = "integration")
public class Integration {

	@Id
	private UUID id;

	@Column(nullable = false, updatable = false)
	private String code;

	@Column(nullable = false)
	private String name;

	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private IntegrationStatus status;

	/**
	 * Scope ceiling set by platform admins: the permissions this integration may grant to its own keys.
	 * {@link Permission#KEY_ROTATE} is always allowed in addition and is never stored here.
	 */
	@Column(nullable = false)
	private String[] allowedScopes;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Integration() {
	}

	private Integration(UUID id, String code, String name, String description, Set<Permission> allowedScopes,
			Instant now) {
		this.id = id;
		this.code = code;
		this.name = name;
		this.description = description;
		this.status = IntegrationStatus.ACTIVE;
		this.allowedScopes = toColumn(allowedScopes);
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Integration register(String code, String name, String description, Set<Permission> allowedScopes,
			Instant now) {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(name, "name");
		return new Integration(UUID.randomUUID(), code, name, description, allowedScopes, now);
	}

	public void changeAllowedScopes(Set<Permission> newAllowedScopes, Instant now) {
		this.allowedScopes = toColumn(newAllowedScopes);
		this.updatedAt = now;
	}

	/** The stored ceiling (without the implicit {@code KEY_ROTATE}). */
	public Set<Permission> getAllowedScopes() {
		return Arrays.stream(allowedScopes)
			.map(Permission::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

	/** Scopes this integration may put on its own keys: the ceiling plus {@code KEY_ROTATE}. */
	public Set<Permission> grantableScopes() {
		Set<Permission> grantable = EnumSet.noneOf(Permission.class);
		grantable.addAll(getAllowedScopes());
		grantable.add(Permission.KEY_ROTATE);
		return grantable;
	}

	private static String[] toColumn(Set<Permission> scopes) {
		if (scopes == null) {
			return new String[0];
		}
		if (scopes.contains(Permission.KEY_ROTATE)) {
			throw new IllegalArgumentException("KEY_ROTATE is implicit and cannot be part of the scope ceiling");
		}
		return scopes.stream().map(Enum::name).sorted().toArray(String[]::new);
	}

	public void changeStatus(IntegrationStatus newStatus, Instant now) {
		Objects.requireNonNull(newStatus, "newStatus");
		if (status != newStatus) {
			status = newStatus;
			updatedAt = now;
		}
	}

	public boolean isActive() {
		return status == IntegrationStatus.ACTIVE;
	}

	public UUID getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public IntegrationStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Long getVersion() {
		return version;
	}

}
