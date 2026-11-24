package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mimocodes.mimorag.integration.application.ApiKeyCredentialLookup.StoredCredential;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.ApiKeyType;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;

import org.springframework.stereotype.Service;

/**
 * Verifies a presented integration API key. Every rejection looks identical to the caller; the reason is returned
 * only for internal logging and audit. The secret is compared in constant time, also when the key id is unknown.
 * <p>
 * Effective scopes are the key's scopes limited to the integration's current scope ceiling, so lowering the ceiling
 * takes effect immediately for existing keys.
 */
@Service
public class ApiKeyAuthenticator {

	static final Duration USE_RECORDING_INTERVAL = Duration.ofMinutes(5);

	private static final int MAX_TRACKED_KEYS = 10_000;

	public enum RejectionReason {

		MALFORMED, UNKNOWN_KEY, SECRET_MISMATCH, KEY_REVOKED, KEY_EXPIRED, INTEGRATION_SUSPENDED

	}

	public sealed interface Result {

	}

	/**
	 * @param knowledgeBaseRestriction present when the key is limited to specific Knowledge Bases
	 */
	public record Authenticated(UUID integrationId, String keyId, Set<Permission> scopes, boolean bootstrap,
			Optional<Set<UUID>> knowledgeBaseRestriction, Instant expiresAt) implements Result {
	}

	public record Rejected(RejectionReason reason, String keyId) implements Result {
	}

	private static final String DUMMY_HASH = ApiKeyCredential.hashSecret("timing-equalisation");

	private final ApiKeyCredentialLookup lookup;

	private final AuditRecorder audit;

	private final Clock clock;

	/** Last time this JVM recorded a use per key: avoids a database round trip on every request. */
	private final Map<String, Instant> lastRecordedUse = new ConcurrentHashMap<>();

	ApiKeyAuthenticator(ApiKeyCredentialLookup lookup, AuditRecorder audit, Clock clock) {
		this.lookup = lookup;
		this.audit = audit;
		this.clock = clock;
	}

	public Result authenticate(String presentedKey) {
		Optional<ApiKeyCredential.Presented> parsed = ApiKeyCredential.parse(presentedKey);
		if (parsed.isEmpty()) {
			return new Rejected(RejectionReason.MALFORMED, null);
		}
		ApiKeyCredential.Presented presented = parsed.get();
		Optional<StoredCredential> stored = lookup.findByKeyId(presented.keyId());
		boolean secretMatches = ApiKeyCredential.secretMatches(presented.secret(),
				stored.map(StoredCredential::secretHash).orElse(DUMMY_HASH));
		if (stored.isEmpty()) {
			return new Rejected(RejectionReason.UNKNOWN_KEY, presented.keyId());
		}
		Instant now = clock.instant();
		Result result = evaluate(stored.get(), presented.keyId(), secretMatches, now);
		if (result instanceof Authenticated) {
			recordUseThrottled(presented.keyId(), now);
		}
		else if (result instanceof Rejected rejected) {
			// Known key, failed check: worth an audit record (unknown keys are only logged, to avoid audit flooding).
			audit.recordUnauthenticated(new AuditEvent("AUTHENTICATION_FAILED", "api-key", presented.keyId(),
					AuditOutcome.DENIED, Map.of("reason", rejected.reason().name())));
		}
		return result;
	}

	/**
	 * Re-checks a key that authenticated earlier (e.g. before a background job runs), with the same rules as
	 * {@link #authenticate} except the secret, which is not available. Neither audited nor recorded as a key use.
	 */
	public Result revalidate(String keyId) {
		return lookup.findByKeyId(keyId)
			.map(stored -> evaluate(stored, keyId, true, clock.instant()))
			.orElseGet(() -> new Rejected(RejectionReason.UNKNOWN_KEY, keyId));
	}

	private void recordUseThrottled(String keyId, Instant now) {
		Instant last = lastRecordedUse.get(keyId);
		if (last != null && last.plus(USE_RECORDING_INTERVAL).isAfter(now)) {
			return;
		}
		if (lastRecordedUse.size() > MAX_TRACKED_KEYS) {
			lastRecordedUse.clear();
		}
		lastRecordedUse.put(keyId, now);
		lookup.recordUse(keyId, now);
	}

	private static Result evaluate(StoredCredential credential, String keyId, boolean secretMatches, Instant now) {
		if (!secretMatches) {
			return new Rejected(RejectionReason.SECRET_MISMATCH, keyId);
		}
		if (credential.keyStatus() != ApiKeyStatus.ACTIVE) {
			return new Rejected(RejectionReason.KEY_REVOKED, keyId);
		}
		if (!credential.expiresAt().isAfter(now)) {
			return new Rejected(RejectionReason.KEY_EXPIRED, keyId);
		}
		if (credential.integrationStatus() != IntegrationStatus.ACTIVE) {
			return new Rejected(RejectionReason.INTEGRATION_SUSPENDED, keyId);
		}
		Set<Permission> grantable = EnumSet.noneOf(Permission.class);
		grantable.addAll(credential.allowedScopes());
		grantable.add(Permission.KEY_ROTATE);
		Set<Permission> effective = EnumSet.noneOf(Permission.class);
		effective.addAll(credential.scopes());
		effective.retainAll(grantable);
		Optional<Set<UUID>> restriction = credential.knowledgeBaseIds().isEmpty() ? Optional.empty()
				: Optional.of(credential.knowledgeBaseIds());
		return new Authenticated(credential.integrationId(), keyId, effective,
				credential.keyType() == ApiKeyType.BOOTSTRAP, restriction, credential.expiresAt());
	}

}
