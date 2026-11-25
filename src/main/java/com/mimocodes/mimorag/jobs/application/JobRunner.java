package com.mimocodes.mimorag.jobs.application;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Authenticated;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Rejected;
import com.mimocodes.mimorag.jobs.application.JobHandler.JobExecution;
import com.mimocodes.mimorag.jobs.application.JobLeaseStore.ClaimedJob;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.observability.CorrelationId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs background jobs from the PostgreSQL queue (ADR-0011).
 * <ul>
 * <li>One bounded worker pool per job kind; a poller claims only as many jobs as there are idle workers, so the
 * queue (not memory) absorbs bursts.</li>
 * <li>Before running, the identity that enqueued the job is re-validated (key active and unexpired, integration
 * active); the job runs with that identity's current scopes, never more than it had at enqueue time.</li>
 * <li>Leases are extended by heartbeats; a crashed worker's jobs are reclaimed after the lease expires.</li>
 * <li>Transient failures are retried with exponential backoff and jitter; permanent failures end the job.</li>
 * </ul>
 * Request threads never execute jobs, so long-running work cannot block the API.
 */
@Component
public class JobRunner implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(JobRunner.class);

	static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

	private final JobLeaseStore store;

	private final ApiKeyAuthenticator credentials;

	private final JobProperties properties;

	private final MeterRegistry meters;

	private final TransactionTemplate transaction;

	private final Map<String, JobHandler> handlers = new HashMap<>();

	private final Map<String, Semaphore> capacity = new HashMap<>();

	private final Map<String, ExecutorService> pools = new HashMap<>();

	private final Map<UUID, ClaimedJob> running = new ConcurrentHashMap<>();

	private final String workerId;

	private ScheduledExecutorService scheduler;

	private volatile boolean started;

	public JobRunner(JobLeaseStore store, ApiKeyAuthenticator credentials, JobProperties properties,
			MeterRegistry meters, PlatformTransactionManager transactionManager, List<JobHandler> handlerBeans) {
		this.store = store;
		this.credentials = credentials;
		this.properties = properties;
		this.meters = meters;
		this.transaction = new TransactionTemplate(transactionManager);
		for (JobHandler handler : handlerBeans) {
			if (handlers.put(handler.kind(), handler) != null) {
				throw new IllegalStateException("Two job handlers registered for kind " + handler.kind());
			}
		}
		this.workerId = ManagementFactory.getRuntimeMXBean().getName() + "-"
				+ UUID.randomUUID().toString().substring(0, 8);
	}

	// ---- lifecycle ------------------------------------------------------------------------------------------------

	@Override
	public synchronized void start() {
		if (started || !properties.enabled() || handlers.isEmpty()) {
			return;
		}
		scheduler = Executors.newScheduledThreadPool(2, named("job-scheduler"));
		for (String kind : handlers.keySet()) {
			int workers = properties.workersFor(kind);
			capacity.put(kind, new Semaphore(workers));
			pools.put(kind, Executors.newFixedThreadPool(workers, named("job-" + kind.toLowerCase())));
			scheduler.scheduleWithFixedDelay(() -> poll(kind), 0, properties.pollInterval().toMillis(),
					TimeUnit.MILLISECONDS);
		}
		scheduler.scheduleWithFixedDelay(this::heartbeat, properties.heartbeatInterval().toMillis(),
				properties.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
		scheduler.scheduleWithFixedDelay(this::sweepAbandoned, properties.lease().toMillis(),
				properties.lease().toMillis(), TimeUnit.MILLISECONDS);
		started = true;
		log.info("Job runner {} started for kinds {}", workerId, handlers.keySet());
	}

	@Override
	public synchronized void stop() {
		if (!started) {
			return;
		}
		started = false;
		scheduler.shutdownNow();
		pools.values().forEach(ExecutorService::shutdown);
		long deadline = System.nanoTime() + properties.shutdownTimeout().toNanos();
		for (ExecutorService pool : pools.values()) {
			try {
				pool.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			pool.shutdownNow();
		}
		pools.clear();
		capacity.clear();
		// Jobs still running are reclaimed by another worker once their lease expires.
		log.info("Job runner {} stopped ({} jobs left to lease expiry)", workerId, running.size());
	}

	@Override
	public boolean isRunning() {
		return started;
	}

	// ---- polling --------------------------------------------------------------------------------------------------

	private void poll(String kind) {
		Semaphore free = capacity.get(kind);
		if (!started || free == null) {
			return;
		}
		int idle = free.availablePermits();
		if (idle == 0) {
			return;
		}
		List<ClaimedJob> claimed;
		try {
			claimed = store.claim(List.of(kind), idle, workerId, properties.lease(),
					properties.perIntegrationConcurrencyFor(kind));
		}
		catch (RuntimeException ex) {
			log.warn("Claiming {} jobs failed; will retry on the next poll", kind, ex);
			return;
		}
		for (ClaimedJob job : claimed) {
			free.acquireUninterruptibly();
			running.put(job.id(), job);
			try {
				pools.get(kind).execute(() -> runAndRelease(job, free));
			}
			catch (RuntimeException rejected) {
				// Shutting down: the lease expires and another worker picks the job up.
				running.remove(job.id());
				free.release();
			}
		}
	}

	private void runAndRelease(ClaimedJob job, Semaphore free) {
		String correlationId = CorrelationId.isAcceptable(job.correlationId()) ? job.correlationId()
				: CorrelationId.generate();
		MDC.put(CorrelationId.MDC_KEY, correlationId);
		try {
			execute(job, correlationId);
		}
		finally {
			running.remove(job.id());
			free.release();
			MDC.remove(CorrelationId.MDC_KEY);
		}
	}

	// ---- execution ------------------------------------------------------------------------------------------------

	/** Runs one claimed job to an outcome. Package-private for tests. */
	void execute(ClaimedJob job, String correlationId) {
		JobHandler handler = handlers.get(job.kind());
		Timer.Sample sample = Timer.start(meters);
		String outcome = "succeeded";
		AccessContext context;
		ApiKeyAuthenticator.Result credential = credentials.revalidate(job.context().apiKeyId());
		if (credential instanceof Authenticated valid && valid.integrationId().equals(job.integrationId())) {
			context = job.context().toAccessContext(valid.scopes(), correlationId);
		}
		else {
			String reason = credential instanceof Rejected rejected ? "CREDENTIAL_" + rejected.reason().name()
					: "CREDENTIAL_MISMATCH";
			AccessContext snapshotContext = job.context().toAccessContext(job.context().scopes(), correlationId);
			record(sample, job.kind(), cancel(handler, job, executionOf(job, snapshotContext), reason));
			return;
		}

		JobExecution execution = executionOf(job, context);
		try {
			AccessContextHolder.callAs(context, () -> {
				handler.handle(execution);
				return null;
			});
			AccessContextHolder.callAs(context, () -> store.complete(job.id(), workerId));
		}
		catch (JobFailure failure) {
			outcome = switch (failure.getType()) {
				case REVALIDATION -> cancel(handler, job, execution, failure.getErrorCode());
				case TRANSIENT -> handleFailure(handler, job, execution, failure.getErrorCode(), true);
				case PERMANENT -> handleFailure(handler, job, execution, failure.getErrorCode(), false);
			};
		}
		catch (RuntimeException unexpected) {
			log.error("Job {} ({}) failed unexpectedly", job.id(), job.kind(), unexpected);
			outcome = handleFailure(handler, job, execution, UNEXPECTED_ERROR, true);
		}
		record(sample, job.kind(), outcome);
	}

	private String handleFailure(JobHandler handler, ClaimedJob job, JobExecution execution, String errorCode,
			boolean transientFailure) {
		AccessContext context = execution.access();
		if (transientFailure && job.attempt() < job.maxAttempts()) {
			Duration delay = properties.retryDelay(job.attempt(), ThreadLocalRandom.current().nextDouble());
			AccessContextHolder.callAs(context, () -> store.scheduleRetry(job.id(), workerId, errorCode, delay));
			log.warn("Job {} attempt {}/{} failed with {}; retrying in {}", job.id(), job.attempt(),
					job.maxAttempts(), errorCode, delay);
			return "retry";
		}
		endUnsuccessfully(handler, execution, errorCode,
				() -> store.fail(job.id(), workerId, errorCode, transientFailure));
		log.warn("Job {} failed with {} after {} attempt(s)", job.id(), errorCode, job.attempt());
		return "failed";
	}

	private String cancel(JobHandler handler, ClaimedJob job, JobExecution execution, String reason) {
		endUnsuccessfully(handler, execution, reason, () -> store.cancel(job.id(), workerId, reason));
		log.info("Job {} cancelled: {}", job.id(), reason);
		return "cancelled";
	}

	/**
	 * Records the job's final state and the owning module's reaction in ONE transaction, so API callers never see a
	 * failed job whose document still looks in progress. If the reaction fails, the job outcome is recorded alone.
	 * Nothing happens if this worker no longer holds the lease ({@code endJob} returns {@code false}).
	 */
	private void endUnsuccessfully(JobHandler handler, JobExecution execution, String errorCode,
			Supplier<Boolean> endJob) {
		AccessContextHolder.callAs(execution.access(), () -> {
			try {
				transaction.executeWithoutResult(status -> {
					if (endJob.get()) {
						handler.onFinalFailure(execution, errorCode);
					}
				});
			}
			catch (RuntimeException reactionFailure) {
				log.error("Final-failure handling of job {} failed; recording the job outcome alone",
						execution.jobId(), reactionFailure);
				endJob.get();
			}
			return null;
		});
	}

	private void notifyFinalFailure(JobHandler handler, JobExecution execution, String errorCode) {
		try {
			handler.onFinalFailure(execution, errorCode);
		}
		catch (RuntimeException ex) {
			log.error("Final-failure callback of job {} failed", execution.jobId(), ex);
		}
	}

	// ---- leases ---------------------------------------------------------------------------------------------------

	private void heartbeat() {
		for (UUID jobId : new ArrayList<>(running.keySet())) {
			try {
				if (!store.extendLease(jobId, workerId, properties.lease())) {
					log.warn("Lease of job {} was lost; its result will be discarded", jobId);
				}
			}
			catch (RuntimeException ex) {
				log.warn("Extending the lease of job {} failed", jobId, ex);
			}
		}
	}

	private void sweepAbandoned() {
		try {
			for (ClaimedJob job : store.failAbandoned(new ArrayList<>(handlers.keySet()))) {
				AccessContext context = job.context().toAccessContext(job.context().scopes(), job.correlationId());
				AccessContextHolder.callAs(context, () -> {
					notifyFinalFailure(handlers.get(job.kind()), executionOf(job, context), "LEASE_EXPIRED");
					return null;
				});
				log.warn("Job {} abandoned by a crashed worker on its last attempt; marked failed", job.id());
			}
		}
		catch (RuntimeException ex) {
			log.warn("Sweeping abandoned jobs failed", ex);
		}
	}

	// ---- helpers --------------------------------------------------------------------------------------------------

	private static JobExecution executionOf(ClaimedJob job, AccessContext context) {
		return new JobExecution(job.id(), job.kind(), job.payload(), context, job.attempt(), job.maxAttempts());
	}

	private void record(Timer.Sample sample, String kind, String outcome) {
		sample.stop(meters.timer("mimorag.jobs.execution", "kind", kind, "outcome", outcome));
	}

	String workerId() {
		return workerId;
	}

	private static java.util.concurrent.ThreadFactory named(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

}
