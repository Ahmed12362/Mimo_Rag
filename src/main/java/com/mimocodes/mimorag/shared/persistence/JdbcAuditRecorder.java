package com.mimocodes.mimorag.shared.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.observability.CorrelationId;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class JdbcAuditRecorder implements AuditRecorder {

	private static final String INSERT_SQL = """
			insert into audit_event (id, integration_id, principal_type, principal_ref, action, target_type,
			                         target_id, outcome, correlation_id, occurred_at, details)
			values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)""";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate independentTransaction;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	JdbcAuditRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, JsonMapper jsonMapper,
			Clock clock) {
		this.jdbc = jdbc;
		this.independentTransaction = new TransactionTemplate(transactionManager);
		this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	@Override
	public void record(AuditEvent event) {
		insert(event, AccessContextHolder.current().orElse(null));
	}

	@Override
	public void recordIndependently(AuditEvent event) {
		AccessContext context = AccessContextHolder.current().orElse(null);
		independentTransaction.executeWithoutResult(status -> insert(event, context));
	}

	@Override
	public void recordUnauthenticated(AuditEvent event) {
		insert(event, null);
	}

	private void insert(AuditEvent event, AccessContext context) {
		UUID integrationId = context == null ? null : context.integrationId();
		String principalType = context == null ? "ANONYMOUS" : context.principalType().name();
		String principalRef = context == null ? null : context.principalRef();
		jdbc.update(INSERT_SQL, UUID.randomUUID(), integrationId, principalType, principalRef, event.action(),
				event.targetType(), event.targetId(), event.outcome().name(),
				CorrelationId.current().orElse(null), Timestamp.from(clock.instant()),
				jsonMapper.writeValueAsString(event.details()));
	}

}
