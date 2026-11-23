package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.Integration;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Integration lifecycle. Registration and status changes are platform-admin operations. */
@Service
public class IntegrationAdminService {

	static final String RESOURCE = "integration";

	private final IntegrationRepository integrations;

	private final AuditRecorder audit;

	private final Clock clock;

	IntegrationAdminService(IntegrationRepository integrations, AuditRecorder audit, Clock clock) {
		this.integrations = integrations;
		this.audit = audit;
		this.clock = clock;
	}

	public record RegisterIntegrationCommand(String code, String name, String description) {
	}

	@Transactional
	public IntegrationView register(AccessContext access, RegisterIntegrationCommand command) {
		access.requirePlatformAdmin();
		if (integrations.existsByCode(command.code())) {
			throw new ConflictException("An integration with this code already exists.");
		}
		Integration integration = Integration.register(command.code(), command.name(), command.description(),
				clock.instant());
		try {
			integrations.save(integration);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("An integration with this code already exists.");
		}
		audit.record(AuditEvent.success("INTEGRATION_REGISTERED", RESOURCE, integration.getId())
			.withDetails(Map.of("code", integration.getCode())));
		return IntegrationView.of(integration);
	}

	@Transactional(readOnly = true)
	public Page<IntegrationView> list(AccessContext access, Pageable pageable) {
		access.requirePlatformAdmin();
		return integrations.findAll(pageable).map(IntegrationView::of);
	}

	@Transactional(readOnly = true)
	public IntegrationView get(AccessContext access, UUID integrationId) {
		access.requirePlatformAdmin();
		return IntegrationView.of(load(integrationId));
	}

	@Transactional
	public IntegrationView changeStatus(AccessContext access, UUID integrationId, IntegrationStatus status) {
		access.requirePlatformAdmin();
		Integration integration = load(integrationId);
		IntegrationStatus previous = integration.getStatus();
		integration.changeStatus(status, clock.instant());
		integrations.saveAndFlush(integration);
		audit.record(AuditEvent.success("INTEGRATION_STATUS_CHANGED", RESOURCE, integrationId)
			.withDetails(Map.of("from", previous.name(), "to", status.name())));
		return IntegrationView.of(integration);
	}

	/** The calling integration's own record (integration plane). */
	@Transactional(readOnly = true)
	public IntegrationView getOwn(AccessContext access) {
		return IntegrationView.of(load(access.requireIntegrationId()));
	}

	Integration load(UUID integrationId) {
		return integrations.findById(integrationId).orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
	}

}
