package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.integration.application.ApiKeyCredentialLookup.StoredCredential;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditOutcome;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;

import org.springframework.stereotype.Service;

/**
 * Verifies a presented integration API key. Every rejection looks identical to the caller; the reason is returned
 * only for internal logging and audit. The secret is compared in constant time, also when the key id is unknown.
 */
@Service
public class ApiKeyAuthenticator {

	public enum RejectionReason {

		MALFORMED, UNKNOWN_KEY, SECRET_MISMATCH, KEY_REVOKED, KEY_EXPIRED, INTEGRATION_SUSPENDED

	}

	public sealed interface Result {

	}

	public record Authenticated(UUID integrationId, String keyId, Set<Permission> scopes) implements Result {
	}

	public record Rejected(RejectionReason reason, String keyId) implements Result {
	}

	private static final String DUMMY_HASH = ApiKeyCredential.hashSecret("timing-equalisation");

	private final ApiKeyCredentialLookup lookup;

	private final AuditRecorder audit;

	private final Clock clock;

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
			lookup.recordUse(presented.keyId(), now);
		}
		else if (result instanceof Rejected rejected) {
			// Known key, failed check: worth an audit record (unknown keys are only logged, to avoid audit flooding).
			audit.recordUnauthenticated(new AuditEvent("AUTHENTICATION_FAILED", "api-key", presented.keyId(),
					AuditOutcome.DENIED, Map.of("reason", rejected.reason().name())));
		}
		return result;
	}

	private static Result evaluate(StoredCredential credential, String keyId, boolean secretMatches, Instant now) {
		if (!secretMatches) {
			return new Rejected(RejectionReason.SECRET_MISMATCH, keyId);
		}
		if (credential.keyStatus() != ApiKeyStatus.ACTIVE) {
			return new Rejected(RejectionReason.KEY_REVOKED, keyId);
		}
		if (credential.expiresAt() != null && !credential.expiresAt().isAfter(now)) {
			return new Rejected(RejectionReason.KEY_EXPIRED, keyId);
		}
		if (credential.integrationStatus() != IntegrationStatus.ACTIVE) {
			return new Rejected(RejectionReason.INTEGRATION_SUSPENDED, keyId);
		}
		return new Authenticated(credential.integrationId(), keyId, credential.scopes());
	}

}
