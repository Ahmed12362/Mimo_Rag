package com.mimocodes.mimorag.integration.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@Version
	private Long version;

	protected Integration() {
	}

	private Integration(UUID id, String code, String name, String description, Instant now) {
		this.id = id;
		this.code = code;
		this.name = name;
		this.description = description;
		this.status = IntegrationStatus.ACTIVE;
		this.createdAt = now;
		this.updatedAt = now;
	}

	public static Integration register(String code, String name, String description, Instant now) {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(name, "name");
		return new Integration(UUID.randomUUID(), code, name, description, now);
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
