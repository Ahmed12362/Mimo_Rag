package com.mimocodes.mimorag.jobs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import com.mimocodes.mimorag.jobs.application.JobLeaseStore;
import com.mimocodes.mimorag.jobs.application.JobLeaseStore.ClaimedJob;
import com.mimocodes.mimorag.jobs.application.JobQueue;
import com.mimocodes.mimorag.jobs.application.JobQueue.JobView;
import com.mimocodes.mimorag.jobs.application.JobQueue.NewJob;
import com.mimocodes.mimorag.jobs.application.JobStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PostgreSQL job queue semantics (ADR-0011). Every test uses its own job kind, which has no handler, so the real job
 * runner never claims these jobs.
 */
class JobQueueIntegrationTest extends PostgresIntegrationTest {

	private static final Duration LEASE = Duration.ofMinutes(2);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JobQueue queue;

	@Autowired
	private JobLeaseStore store;

	@Autowired
	private PlatformTransactionManager transactionManager;

	private AccessContext tenantA;

	private AccessContext tenantB;

	private String kind;

	@BeforeEach
	void setUp() throws Exception {
		ApiTestClient api = new ApiTestClient(mockMvc, jsonMapper);
		tenantA = contextFor(api.createIntegration(ApiTestClient.uniqueCode("qa")));
		tenantB = contextFor(api.createIntegration(ApiTestClient.uniqueCode("qb")));
		kind = "TEST_" + Long.toString(ThreadLocalRandom.current().nextLong(Long.MAX_VALUE), 36).toUpperCase();
	}

	@Test
	void enqueueOnlyJoinsAnExistingTransaction() {
		assertThatThrownBy(() -> AccessContextHolder.callAs(tenantA,
				() -> queue.enqueue(tenantA, new NewJob(kind, Map.of()))))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	/** Repeated with many competing workers: a double claim depends on timing, so one run proves little. */
	@RepeatedTest(5)
	void concurrentWorkersNeverClaimTheSameJob() throws Exception {
		for (int i = 0; i < 100; i++) {
			enqueue(tenantA);
		}
		List<UUID> claimed = Collections.synchronizedList(new ArrayList<>());
		ExecutorService workers = Executors.newFixedThreadPool(6);
		try {
			List<Callable<Void>> tasks = new ArrayList<>();
			for (int w = 0; w < 6; w++) {
				String worker = "worker-" + w;
				tasks.add(() -> {
					List<ClaimedJob> batch;
					do {
						batch = store.claim(List.of(kind), 3, worker, LEASE, 100);
						batch.forEach(job -> claimed.add(job.id()));
					}
					while (!batch.isEmpty());
					return null;
				});
			}
			for (Future<Void> done : workers.invokeAll(tasks)) {
				done.get();
			}
		}
		finally {
			workers.shutdownNow();
		}
		assertThat(claimed).hasSize(100).doesNotHaveDuplicates();
	}

	@Test
	void expiredLeaseIsReclaimedAndTheOldWorkerCanNoLongerFinishTheJob() throws Exception {
		UUID id = enqueue(tenantA);
		assertThat(store.claim(List.of(kind), 1, "w1", Duration.ofSeconds(1), 10)).hasSize(1);
		assertThat(store.claim(List.of(kind), 1, "w2", LEASE, 10)).as("lease still held").isEmpty();

		Thread.sleep(1500);
		List<ClaimedJob> reclaimed = store.claim(List.of(kind), 1, "w2", LEASE, 10);

		assertThat(reclaimed).extracting(ClaimedJob::id).containsExactly(id);
		assertThat(reclaimed.get(0).attempt()).isEqualTo(2);
		assertThat(as(tenantA, () -> store.complete(id, "w1"))).as("stale worker").isFalse();
		assertThat(as(tenantA, () -> store.complete(id, "w2"))).isTrue();
		assertThat(find(tenantA, id).status()).isEqualTo(JobStatus.SUCCEEDED);
	}

	@Test
	void heartbeatKeepsTheLeaseOnlyForItsOwner() {
		UUID id = enqueue(tenantA);
		store.claim(List.of(kind), 1, "w1", LEASE, 10);

		assertThat(store.extendLease(id, "w1", LEASE)).isTrue();
		assertThat(store.extendLease(id, "intruder", LEASE)).isFalse();
	}

	@Test
	void retryIsNotClaimedBeforeItsDelay() throws Exception {
		UUID id = enqueue(tenantA);
		store.claim(List.of(kind), 1, "w1", LEASE, 10);
		as(tenantA, () -> store.scheduleRetry(id, "w1", "STORAGE_UNAVAILABLE", Duration.ofSeconds(2)));

		assertThat(find(tenantA, id).status()).isEqualTo(JobStatus.RETRY_SCHEDULED);
		assertThat(store.claim(List.of(kind), 1, "w1", LEASE, 10)).isEmpty();
		Thread.sleep(2300);
		assertThat(store.claim(List.of(kind), 1, "w1", LEASE, 10)).extracting(ClaimedJob::id).containsExactly(id);
	}

	@Test
	void jobAbandonedOnItsLastAttemptIsFailed() throws Exception {
		UUID id = enqueue(tenantA);
		superuser().update("update mimorag.job set max_attempts = 1 where id = ?", id);
		store.claim(List.of(kind), 1, "w1", Duration.ofSeconds(1), 10);
		Thread.sleep(1500);

		assertThat(store.claim(List.of(kind), 1, "w2", LEASE, 10)).as("no attempts left").isEmpty();
		List<ClaimedJob> abandoned = store.failAbandoned(List.of(kind));

		assertThat(abandoned).extracting(ClaimedJob::id).containsExactly(id);
		assertThat(abandoned.get(0).context().integrationId()).isEqualTo(tenantA.integrationId());
		JobView job = find(tenantA, id);
		assertThat(job.status()).isEqualTo(JobStatus.FAILED);
		assertThat(job.errorCode()).isEqualTo("LEASE_EXPIRED");
	}

	@Test
	void oneIntegrationCannotOccupyEveryWorker() {
		for (int i = 0; i < 3; i++) {
			enqueue(tenantA);
		}
		UUID fromB = enqueue(tenantB);

		List<ClaimedJob> claimed = store.claim(List.of(kind), 4, "w1", LEASE, 1);

		assertThat(claimed).hasSize(2);
		assertThat(claimed).extracting(ClaimedJob::integrationId)
			.containsExactlyInAnyOrder(tenantA.integrationId(), tenantB.integrationId());
		assertThat(claimed).extracting(ClaimedJob::id).contains(fromB);
	}

	@Test
	void jobsAreVisibleOnlyToTheirOwnIntegration() {
		UUID id = enqueue(tenantA);

		assertThat(as(tenantB, () -> queue.find(tenantB, id))).isEmpty();
		assertThat(as(tenantB, () -> queue.countUnfinished(tenantB, kind))).isZero();
		assertThat(as(tenantA, () -> queue.countUnfinished(tenantA, kind))).isEqualTo(1);
		store.claim(List.of(kind), 1, "w1", LEASE, 10);
		as(tenantA, () -> store.fail(id, "w1", "SOMETHING", true));
		assertThat(as(tenantB, () -> queue.requeue(tenantB, id))).isFalse();
		assertThat(find(tenantA, id).status()).isEqualTo(JobStatus.FAILED);
	}

	@Test
	void onlyRetryableFailedJobsCanBeRequeued() {
		UUID permanent = enqueue(tenantA);
		UUID transientFailure = enqueue(tenantA);
		store.claim(List.of(kind), 2, "w1", LEASE, 10);
		as(tenantA, () -> store.fail(permanent, "w1", "PDF_ENCRYPTED", false));
		as(tenantA, () -> store.fail(transientFailure, "w1", "STORAGE_UNAVAILABLE", true));

		assertThat(as(tenantA, () -> queue.requeue(tenantA, permanent))).isFalse();
		assertThat(as(tenantA, () -> queue.requeue(tenantA, transientFailure))).isTrue();
		JobView requeued = find(tenantA, transientFailure);
		assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
		assertThat(requeued.attempts()).isZero();
		assertThat(requeued.errorCode()).isNull();
	}

	@Test
	void storedContextContainsIdentityButNoSecrets() {
		UUID id = enqueue(tenantA);

		String snapshot = superuser().queryForObject("select context_snapshot::text from mimorag.job where id = ?",
				String.class, id);
		assertThat(snapshot).contains(tenantA.integrationId().toString(), "DOCUMENT_UPLOAD")
			.doesNotContain("mrk_", "secret", "password");
		Set<String> keys = new HashSet<>(jsonMapper.readTree(snapshot).propertyNames());
		assertThat(keys).doesNotContain("secretHash", "token", "assertion");
	}

	private UUID enqueue(AccessContext context) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		return as(context, () -> transaction.execute(status -> queue.enqueue(context, new NewJob(kind,
				Map.of("documentId", UUID.randomUUID().toString())))));
	}

	private JobView find(AccessContext context, UUID id) {
		return as(context, () -> queue.find(context, id)).orElseThrow();
	}

	private static <T> T as(AccessContext context, java.util.function.Supplier<T> work) {
		return AccessContextHolder.callAs(context, work);
	}

	private static AccessContext contextFor(UUID integrationId) {
		String keyId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
		return AccessContext.integrationService(integrationId, keyId,
				Set.of(Permission.DOCUMENT_UPLOAD, Permission.KB_READ), "corr-" + keyId);
	}

	private static JdbcTemplate superuser() {
		return new JdbcTemplate(
				new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

}
