package com.mimocodes.mimorag.jobs.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.jobs.application.JobContextSnapshot;
import com.mimocodes.mimorag.jobs.application.JobLeaseStore;
import com.mimocodes.mimorag.jobs.application.JobProperties;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.observability.CorrelationId;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL job queue (ADR-0011). Timestamps that drive scheduling and leases use the database clock
 * ({@code now()}), so all workers agree on them regardless of JVM clock skew.
 */
@Repository
class PostgresJobQueue implements JobQueue, JobLeaseStore {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private static final String UNFINISHED = "('QUEUED', 'RUNNING', 'RETRY_SCHEDULED')";

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	private final JobProperties properties;

	PostgresJobQueue(JdbcTemplate jdbc, JsonMapper json, JobProperties properties) {
		this.jdbc = jdbc;
		this.json = json;
		this.properties = properties;
	}

	// ---- business side --------------------------------------------------------------------------------------------

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public UUID enqueue(AccessContext access, NewJob job) {
		UUID id = UUID.randomUUID();
		String correlationId = CorrelationId.current().filter(CorrelationId::isAcceptable).orElse(null);
		jdbc.update("""
				insert into job (id, integration_id, kind, status, payload, context_snapshot, correlation_id, attempts,
				                 max_attempts, run_after, retryable, created_at, updated_at)
				values (?, ?, ?, 'QUEUED', ?::jsonb, ?::jsonb, ?, 0, ?, now(), true, now(), now())""", id,
				access.requireIntegrationId(), job.kind(), json.writeValueAsString(job.payload()),
				json.writeValueAsString(JobContextSnapshot.of(access)), correlationId, properties.maxAttempts());
		return id;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<JobView> find(AccessContext access, UUID jobId) {
		return jdbc.query("""
				select id, kind, status, payload, attempts, max_attempts, error_code, retryable, run_after, created_at,
				       updated_at, started_at, finished_at
				from job where id = ? and integration_id = ?""", this::view, jobId, access.requireIntegrationId())
			.stream()
			.findFirst();
	}

	@Override
	@Transactional(readOnly = true)
	public long countUnfinished(AccessContext access, String kind) {
		Long count = jdbc.queryForObject(
				"select count(*) from job where integration_id = ? and kind = ? and status in " + UNFINISHED,
				Long.class, access.requireIntegrationId(), kind);
		return count == null ? 0 : count;
	}

	@Override
	@Transactional
	public boolean requeue(AccessContext access, UUID jobId) {
		return jdbc.update("""
				update job set status = 'QUEUED', attempts = 0, error_code = null, run_after = now(),
				               finished_at = null, context_snapshot = ?::jsonb, updated_at = now()
				where id = ? and integration_id = ? and retryable and status in ('FAILED', 'CANCELLED_REVALIDATION')""",
				json.writeValueAsString(JobContextSnapshot.of(access)), jobId, access.requireIntegrationId()) == 1;
	}

	// ---- worker side ----------------------------------------------------------------------------------------------

	@Override
	@Transactional
	public List<ClaimedJob> claim(List<String> kinds, int limit, String workerId, Duration lease,
			int perIntegration) {
		return jdbc.query("select * from claim_jobs(?, ?, ?, ?, ?)", this::claimed, textArray(kinds), limit,
				workerId, seconds(lease), perIntegration);
	}

	@Override
	@Transactional
	public boolean extendLease(UUID jobId, String workerId, Duration lease) {
		return Boolean.TRUE.equals(jdbc.queryForObject("select extend_job_lease(?, ?, ?)", Boolean.class, jobId,
				workerId, seconds(lease)));
	}

	@Override
	@Transactional
	public boolean complete(UUID jobId, String workerId) {
		return jdbc.update("""
				update job set status = 'SUCCEEDED', error_code = null, lease_owner = null, lease_expires_at = null,
				               finished_at = now(), updated_at = now()
				where id = ? and lease_owner = ? and status = 'RUNNING'""", jobId, workerId) == 1;
	}

	@Override
	@Transactional
	public boolean scheduleRetry(UUID jobId, String workerId, String errorCode, Duration delay) {
		return jdbc.update("""
				update job set status = 'RETRY_SCHEDULED', error_code = ?, lease_owner = null, lease_expires_at = null,
				               run_after = now() + make_interval(secs => ?), updated_at = now()
				where id = ? and lease_owner = ? and status = 'RUNNING'""", errorCode, delay.toMillis() / 1000.0,
				jobId, workerId) == 1;
	}

	@Override
	@Transactional
	public boolean fail(UUID jobId, String workerId, String errorCode, boolean retryable) {
		return finish(jobId, workerId, JobStatus.FAILED, errorCode, retryable);
	}

	@Override
	@Transactional
	public boolean cancel(UUID jobId, String workerId, String errorCode) {
		return finish(jobId, workerId, JobStatus.CANCELLED_REVALIDATION, errorCode, true);
	}

	@Override
	@Transactional
	public List<ClaimedJob> failAbandoned(List<String> kinds) {
		return jdbc.query("select * from fail_abandoned_jobs(?)", (rs, row) -> new ClaimedJob(
				rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class), rs.getString("kind"),
				readMap(rs.getString("payload")), readSnapshot(rs.getString("context_snapshot")),
				rs.getString("correlation_id"), 0, 0), new Object[] { textArray(kinds) });
	}

	private boolean finish(UUID jobId, String workerId, JobStatus status, String errorCode, boolean retryable) {
		return jdbc.update("""
				update job set status = ?, error_code = ?, retryable = ?, lease_owner = null, lease_expires_at = null,
				               finished_at = now(), updated_at = now()
				where id = ? and lease_owner = ? and status = 'RUNNING'""", status.name(), errorCode, retryable,
				jobId, workerId) == 1;
	}

	// ---- mapping --------------------------------------------------------------------------------------------------

	private JobView view(ResultSet rs, int row) throws SQLException {
		return new JobView(rs.getObject("id", UUID.class), rs.getString("kind"),
				JobStatus.valueOf(rs.getString("status")), readMap(rs.getString("payload")), rs.getInt("attempts"),
				rs.getInt("max_attempts"), rs.getString("error_code"), rs.getBoolean("retryable"),
				instant(rs, "run_after"), instant(rs, "created_at"), instant(rs, "updated_at"),
				instant(rs, "started_at"), instant(rs, "finished_at"));
	}

	private ClaimedJob claimed(ResultSet rs, int row) throws SQLException {
		return new ClaimedJob(rs.getObject("id", UUID.class), rs.getObject("integration_id", UUID.class),
				rs.getString("kind"), readMap(rs.getString("payload")),
				readSnapshot(rs.getString("context_snapshot")), rs.getString("correlation_id"),
				rs.getInt("attempts"), rs.getInt("max_attempts"));
	}

	private Map<String, Object> readMap(String value) {
		return value == null ? Map.of() : json.readValue(value, MAP);
	}

	private JobContextSnapshot readSnapshot(String value) {
		return json.readValue(value, JobContextSnapshot.class);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}

	private static String[] textArray(List<String> values) {
		return values.toArray(String[]::new);
	}

	private static int seconds(Duration duration) {
		return (int) Math.max(1, duration.toSeconds());
	}

}
