package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Issuing, listing and revoking integration API keys (platform-admin operations). */
@Service
public class ApiKeyService {

	static final Duration MAX_VALIDITY = Duration.ofDays(730);

	private static final String RESOURCE = "api-key";

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

	public record IssueApiKeyCommand(String name, Set<Permission> scopes, Instant expiresAt) {
	}

	@Transactional
	public IssuedApiKey issue(AccessContext access, UUID integrationId, IssueApiKeyCommand command) {
		access.requirePlatformAdmin();
		integrationService.load(integrationId);
		Instant now = clock.instant();
		validate(command, now);
		ApiKeyCredential.Generated credential = ApiKeyCredential.generate();
		ApiKey apiKey = ApiKey.issue(integrationId, credential, command.name(), command.scopes(), command.expiresAt(),
				now);
		apiKeys.save(apiKey);
		audit.record(AuditEvent.success("API_KEY_ISSUED", RESOURCE, apiKey.getKeyId())
			.withDetails(Map.of("integrationId", integrationId.toString(), "scopes",
					apiKey.getScopes().stream().map(Enum::name).sorted().toList())));
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

	@Transactional
	public ApiKeyView revoke(AccessContext access, UUID integrationId, String keyId) {
		access.requirePlatformAdmin();
		ApiKey apiKey = apiKeys.findByIntegrationIdAndKeyId(integrationId, keyId)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		apiKey.revoke(clock.instant());
		apiKeys.save(apiKey);
		audit.record(AuditEvent.success("API_KEY_REVOKED", RESOURCE, keyId)
			.withDetails(Map.of("integrationId", integrationId.toString())));
		return ApiKeyView.of(apiKey);
	}

	private static void validate(IssueApiKeyCommand command, Instant now) {
		if (command.scopes() == null || command.scopes().isEmpty()) {
			throw new InvalidRequestException("scopes", "must contain at least one permission");
		}
		if (command.expiresAt() != null) {
			if (!command.expiresAt().isAfter(now)) {
				throw new InvalidRequestException("expiresAt", "must be in the future");
			}
			if (command.expiresAt().isAfter(now.plus(MAX_VALIDITY))) {
				throw new InvalidRequestException("expiresAt", "must be at most 730 days in the future");
			}
		}
	}

}
