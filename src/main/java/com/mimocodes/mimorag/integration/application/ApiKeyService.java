package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.integration.domain.Integration;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Platform-admin key operations. Admins can hand an integration over with a <em>bootstrap</em> key (rotation only,
 * 24 hours, single use), inspect key metadata, and revoke any key in an incident. They can never create a key that
 * reaches tenant content (ADR-0012).
 */
@Service
public class ApiKeyService {

	static final String RESOURCE = "api-key";

	private final IntegrationAdminService integrationService;

	private final ApiKeyRepository apiKeys;

	private final AuditRecorder audit;

	private final Clock clock;

	ApiKeyService(IntegrationAdminService integrationService, ApiKeyRepository apiKeys, AuditRecorder audit,
			Clock clock) {
		this.integrationService = integrationService;
		this.apiKeys = apiKeys;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public IssuedApiKey issueBootstrap(AccessContext access, UUID integrationId, String name) {
		access.requirePlatformAdmin();
		Integration integration = integrationService.load(integrationId);
		if (!integration.isActive()) {
			throw new ConflictException("Bootstrap keys can only be issued for active integrations.");
		}
		ApiKeyCredential.Generated credential = ApiKeyCredential.generate();
		ApiKey apiKey = ApiKey.issueBootstrap(integrationId, credential, name, clock.instant(),
				access.principalRef());
		apiKeys.saveAndFlush(apiKey);
		audit.record(AuditEvent.success("BOOTSTRAP_KEY_ISSUED", RESOURCE, apiKey.getKeyId())
			.withDetails(Map.of("integrationId", integrationId.toString(), "expiresAt",
					apiKey.getExpiresAt().toString())));
		return new IssuedApiKey(ApiKeyView.of(apiKey), credential.plaintext());
	}

	@Transactional(readOnly = true)
	public List<ApiKeyView> list(AccessContext access, UUID integrationId) {
		access.requirePlatformAdmin();
		integrationService.load(integrationId);
		return apiKeys.findTop200ByIntegrationIdOrderByCreatedAtDesc(integrationId)
			.stream()
			.map(ApiKeyView::of)
			.toList();
	}

	/** Emergency revocation of any key of an integration (incident response). */
	@Transactional
	public ApiKeyView revoke(AccessContext access, UUID integrationId, String keyId) {
		access.requirePlatformAdmin();
		ApiKey apiKey = apiKeys.findByIntegrationIdAndKeyId(integrationId, keyId)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		apiKey.revoke(clock.instant(), access.principalRef());
		apiKeys.saveAndFlush(apiKey);
		audit.record(AuditEvent.success("API_KEY_REVOKED", RESOURCE, keyId)
			.withDetails(Map.of("integrationId", integrationId.toString(), "revokedBy", "platform-admin")));
		return ApiKeyView.of(apiKey);
	}

}
