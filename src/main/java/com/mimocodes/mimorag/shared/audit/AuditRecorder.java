package com.mimocodes.mimorag.shared.audit;

/**
 * Append-only audit trail. The acting principal, tenant and correlation id are taken from the current
 * {@code AccessContext}; callers describe only what happened.
 */
public interface AuditRecorder {

	/**
	 * Records an event inside the caller's transaction: it is committed or rolled back together with the change it
	 * describes. Use for successful state changes.
	 */
	void record(AuditEvent event);

	/**
	 * Records an event in its own transaction, so it survives when the caller's transaction rolls back. Use for
	 * denied or failed operations.
	 */
	void recordIndependently(AuditEvent event);

	/** Records an event for a request that has no verified identity (e.g. a failed authentication). */
	void recordUnauthenticated(AuditEvent event);

}
