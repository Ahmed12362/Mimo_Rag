package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.integration.domain.Integration;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration-managed API keys (ADR-0012): an integration creates, lists and revokes its own keys with a credential
 * holding {@link Permission#KEY_ROTATE}. Rules:
 * <ul>
 * <li>Only the service identity may manage keys (never an end user).</li>
 * <li>New scopes must lie within the integration's scope ceiling (set by admins) plus {@code KEY_ROTATE}.</li>
 * <li>Expiry is mandatory: 90 days by default, at most 365 days. Several keys may be active at once.</li>
 * <li>A KB-restricted caller can only create or manage keys restricted to a subset of its own KBs.</li>
 * <li>A bootstrap caller can only create one key and is revoked in the same transaction (single use).</li>
 * </ul>
 */
@Service
public class SelfServiceKeyService {

	static final int DEFAULT_VALIDITY_DAYS = 90;

	static final int MAX_VALIDITY_DAYS = 365;

	private static final String RESOURCE = ApiKeyService.RESOURCE;

	private final IntegrationAdminService integrationService;

	private final ApiKeyRepository apiKeys;

	private final AuditRecorder audit;

	private final Clock clock;

	SelfServiceKeyService(IntegrationAdminService integrationService, ApiKeyRepository apiKeys, AuditRecorder audit,
			Clock clock) {
		this.integrationService = integrationService;
		this.apiKeys = apiKeys;
		this.audit = audit;
		this.clock = clock;
	}

	/**
	 * @param validityDays 1–365; {@code null} means the 90-day default
	 * @param knowledgeBaseIds optional restriction; empty means integration-wide (not allowed for restricted callers)
	 */
	public record CreateKeyCommand(String name, Set<Permission> scopes, Integer validityDays,
			Set<UUID> knowledgeBaseIds) {
	}

	@Transactional
	public IssuedApiKey create(AccessContext access, CreateKeyCommand command) {
		requireKeyManagement(access);
		UUID integrationId = access.requireIntegrationId();
		Integration integration = integrationService.load(integrationId);
		Set<Permission> scopes = validateScopes(command.scopes(), integration);
		Set<UUID> knowledgeBases = validateRestriction(access, command.knowledgeBaseIds());
		Instant now = clock.instant();
		Instant expiresAt = now.plus(Duration.ofDays(validateValidity(command.validityDays())));

		ApiKeyCredential.Generated credential = ApiKeyCredential.generate();
		ApiKey apiKey = ApiKey.issueStandard(integrationId, credential, command.name(), scopes, knowledgeBases,
				expiresAt, now, access.principalRef());
		try {
			apiKeys.saveAndFlush(apiKey);
		}
		catch (DataIntegrityViolationException ex) {
			// Composite FK: a KB of another tenant is indistinguishable from a non-existent one.
			throw new InvalidRequestException("knowledgeBaseIds", "contains an unknown knowledge base");
		}
		if (access.credential().bootstrap()) {
			consumeBootstrapKey(access, integrationId, now);
		}
		Map<String, Object> details = new HashMap<>();
		details.put("scopes", scopes.stream().map(Enum::name).sorted().toList());
		details.put("expiresAt", expiresAt.toString());
		details.put("knowledgeBaseRestricted", !knowledgeBases.isEmpty());
		details.put("viaBootstrap", access.credential().bootstrap());
		audit.record(AuditEvent.success("API_KEY_CREATED", RESOURCE, apiKey.getKeyId()).withDetails(details));
		return new IssuedApiKey(ApiKeyView.of(apiKey), credential.plaintext());
	}

	@Transactional(readOnly = true)
	public List<ApiKeyView> list(AccessContext access) {
		requireKeyManagement(access);
		forbidBootstrap(access);
		return apiKeys.findTop200ByIntegrationIdOrderByCreatedAtDesc(access.requireIntegrationId())
			.stream()
			.filter(key -> manageable(access, key))
			.map(ApiKeyView::of)
			.toList();
	}

	@Transactional
	public ApiKeyView revoke(AccessContext access, String keyId) {
		requireKeyManagement(access);
		forbidBootstrap(access);
		ApiKey apiKey = apiKeys.findByIntegrationIdAndKeyId(access.requireIntegrationId(), keyId)
			.filter(key -> manageable(access, key))
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		apiKey.revoke(clock.instant(), access.principalRef());
		apiKeys.saveAndFlush(apiKey);
		audit.record(AuditEvent.success("API_KEY_REVOKED", RESOURCE, keyId)
			.withDetails(Map.of("revokedBy", "integration", "self", keyId.equals(access.credential().apiKeyId()))));
		return ApiKeyView.of(apiKey);
	}

	private void consumeBootstrapKey(AccessContext access, UUID integrationId, Instant now) {
		ApiKey bootstrap = apiKeys.findByIntegrationIdAndKeyId(integrationId, access.credential().apiKeyId())
			.orElseThrow(() -> new ConflictException("The bootstrap key is no longer available."));
		if (!bootstrap.isActive(now)) {
			throw new ConflictException("The bootstrap key has already been used.");
		}
		bootstrap.revoke(now, access.principalRef());
		try {
			// Optimistic lock: of two concurrent uses of the same bootstrap key, exactly one can commit.
			apiKeys.saveAndFlush(bootstrap);
		}
		catch (ObjectOptimisticLockingFailureException ex) {
			throw new ConflictException("The bootstrap key has already been used.");
		}
		audit.record(AuditEvent.success("BOOTSTRAP_KEY_CONSUMED", RESOURCE, bootstrap.getKeyId()));
	}

	private void requireKeyManagement(AccessContext access) {
		if (!access.isIntegrationService() || !access.hasScope(Permission.KEY_ROTATE)) {
			audit.recordIndependently(AuditEvent.denied("API_KEY_MANAGEMENT", RESOURCE, null));
			throw new OperationNotPermittedException(Permission.KEY_ROTATE);
		}
	}

	private static void forbidBootstrap(AccessContext access) {
		if (access.credential().bootstrap()) {
			throw new OperationNotPermittedException("A bootstrap key can only create the integration's first key");
		}
	}

	/** A KB-restricted caller manages only keys restricted to a subset of its own Knowledge Bases. */
	private static boolean manageable(AccessContext access, ApiKey key) {
		return access.knowledgeBaseRestriction()
			.map(own -> !key.getKnowledgeBaseIds().isEmpty() && own.containsAll(key.getKnowledgeBaseIds()))
			.orElse(true);
	}

	private static Set<Permission> validateScopes(Set<Permission> requested, Integration integration) {
		if (requested == null || requested.isEmpty()) {
			throw new InvalidRequestException("scopes", "must contain at least one permission");
		}
		Set<Permission> notAllowed = EnumSet.copyOf(requested);
		notAllowed.removeAll(integration.grantableScopes());
		if (!notAllowed.isEmpty()) {
			throw new InvalidRequestException("scopes", "exceed the integration's allowed scopes: " + notAllowed);
		}
		return EnumSet.copyOf(requested);
	}

	private static Set<UUID> validateRestriction(AccessContext access, Set<UUID> requested) {
		Set<UUID> knowledgeBases = requested == null ? Set.of() : Set.copyOf(requested);
		if (knowledgeBases.size() > 100) {
			throw new InvalidRequestException("knowledgeBaseIds", "must contain at most 100 entries");
		}
		access.knowledgeBaseRestriction().ifPresent(own -> {
			if (knowledgeBases.isEmpty() || !own.containsAll(knowledgeBases)) {
				throw new InvalidRequestException("knowledgeBaseIds",
						"a restricted key can only create keys restricted to a subset of its own knowledge bases");
			}
		});
		return knowledgeBases;
	}

	private static int validateValidity(Integer validityDays) {
		if (validityDays == null) {
			return DEFAULT_VALIDITY_DAYS;
		}
		if (validityDays < 1 || validityDays > MAX_VALIDITY_DAYS) {
			throw new InvalidRequestException("validityDays", "must be between 1 and " + MAX_VALIDITY_DAYS);
		}
		return validityDays;
	}

}
