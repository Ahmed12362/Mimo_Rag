package com.mimocodes.mimorag.shared.audit;

import java.util.Map;
import java.util.Objects;

/**
 * Something security- or governance-relevant that happened. {@code details} must contain only safe metadata
 * (identifiers, counts, changed field names) and never secrets, tokens, document text or personal data.
 */
public record AuditEvent(String action, String targetType, String targetId, AuditOutcome outcome,
		Map<String, Object> details) {

	public AuditEvent {
		Objects.requireNonNull(action, "action");
		Objects.requireNonNull(outcome, "outcome");
		details = details == null ? Map.of() : Map.copyOf(details);
	}

	public static AuditEvent success(String action, String targetType, Object targetId) {
		return new AuditEvent(action, targetType, String.valueOf(targetId), AuditOutcome.SUCCESS, Map.of());
	}

	public static AuditEvent denied(String action, String targetType, Object targetId) {
		return new AuditEvent(action, targetType, targetId == null ? null : String.valueOf(targetId),
				AuditOutcome.DENIED, Map.of());
	}

	public AuditEvent withDetails(Map<String, Object> moreDetails) {
		return new AuditEvent(action, targetType, targetId, outcome, moreDetails);
	}

}
