package com.mimocodes.mimorag.jobs.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Authenticated;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Rejected;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.RejectionReason;
import com.mimocodes.mimorag.jobs.application.JobLeaseStore.ClaimedJob;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.AuthMethod;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.access.PrincipalType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JobRunnerTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final String KEY = "a1b2c3d4e5f6a7b8c9d0e1f2";

	private final RecordingStore store = new RecordingStore();

	private final ApiKeyAuthenticator credentials = mock(ApiKeyAuthenticator.class);

	private final ScriptedHandler handler = new ScriptedHandler();

	private final JobProperties properties = new JobProperties(true, Duration.ofSeconds(1), Duration.ofMinutes(2),
			Duration.ofSeconds(30), 3, Duration.ofSeconds(30), Duration.ofMinutes(15), Map.of(), 2,
			Duration.ofSeconds(5));

	private JobRunner runner;

	@BeforeEach
	void setUp() {
		runner = new JobRunner(store, credentials, properties, new SimpleMeterRegistry(), new NoopTransactions(),
				List.of(handler));
		keyIsValidWith(EnumSet.of(Permission.DOCUMENT_UPLOAD, Permission.KB_READ));
	}

	@Test
	void successfulJobIsCompletedWithTheJobContextBoundDuringExecution() {
		AtomicReference<AccessContext> seen = new AtomicReference<>();
		handler.behaviour = execution -> seen.set(AccessContextHolder.current().orElseThrow());

		runner.execute(job(1), "corr-12345678");

		assertThat(store.calls).containsExactly("complete");
		assertThat(seen.get().integrationId()).isEqualTo(TENANT);
		assertThat(seen.get().authMethod()).isEqualTo(AuthMethod.SYSTEM);
		assertThat(seen.get().correlationId()).isEqualTo("corr-12345678");
		assertThat(AccessContextHolder.current()).as("context is unbound after the job").isEmpty();
	}

	@Test
	void transientFailureIsRetriedWithBackoffWhileAttemptsRemain() {
		handler.behaviour = execution -> {
			throw JobFailure.transientFailure("STORAGE_UNAVAILABLE", null);
		};

		runner.execute(job(1), "corr-12345678");

		assertThat(store.calls).containsExactly("retry:STORAGE_UNAVAILABLE");
		assertThat(store.lastDelay).isBetween(Duration.ofSeconds(24), Duration.ofSeconds(36));
		assertThat(handler.finalFailures).isEmpty();
	}

	@Test
	void transientFailureOnTheLastAttemptFailsTheJobAsRetryable() {
		handler.behaviour = execution -> {
			throw JobFailure.transientFailure("STORAGE_UNAVAILABLE", null);
		};

		runner.execute(job(3), "corr-12345678");

		assertThat(store.calls).containsExactly("fail:STORAGE_UNAVAILABLE:retryable");
		assertThat(handler.finalFailures).containsExactly("STORAGE_UNAVAILABLE");
	}

	@Test
	void permanentFailureEndsTheJobImmediatelyAndIsNotRetryable() {
		handler.behaviour = execution -> {
			throw JobFailure.permanent("PDF_ENCRYPTED");
		};

		runner.execute(job(1), "corr-12345678");

		assertThat(store.calls).containsExactly("fail:PDF_ENCRYPTED:final");
		assertThat(handler.finalFailures).containsExactly("PDF_ENCRYPTED");
	}

	@Test
	void revalidationFailureFromTheHandlerCancelsTheJob() {
		handler.behaviour = execution -> {
			throw JobFailure.revalidation("KNOWLEDGE_BASE_ARCHIVED");
		};

		runner.execute(job(1), "corr-12345678");

		assertThat(store.calls).containsExactly("cancel:KNOWLEDGE_BASE_ARCHIVED");
		assertThat(handler.finalFailures).containsExactly("KNOWLEDGE_BASE_ARCHIVED");
	}

	@Test
	void unexpectedExceptionIsTreatedAsTransient() {
		handler.behaviour = execution -> {
			throw new IllegalStateException("boom with internal details");
		};

		runner.execute(job(1), "corr-12345678");

		assertThat(store.calls).containsExactly("retry:" + JobRunner.UNEXPECTED_ERROR);
	}

	@Test
	void revokedKeyCancelsTheJobWithoutRunningIt() {
		when(credentials.revalidate(KEY)).thenReturn(new Rejected(RejectionReason.KEY_REVOKED, KEY));

		runner.execute(job(1), "corr-12345678");

		assertThat(handler.runs).isZero();
		assertThat(store.calls).containsExactly("cancel:CREDENTIAL_KEY_REVOKED");
		assertThat(handler.finalFailures).containsExactly("CREDENTIAL_KEY_REVOKED");
	}

	@Test
	void keyOfAnotherIntegrationCancelsTheJob() {
		when(credentials.revalidate(KEY)).thenReturn(new Authenticated(UUID.randomUUID(), KEY,
				Set.of(Permission.DOCUMENT_UPLOAD), false, Optional.empty(), Instant.now().plusSeconds(3600)));

		runner.execute(job(1), "corr-12345678");

		assertThat(handler.runs).isZero();
		assertThat(store.calls).containsExactly("cancel:CREDENTIAL_MISMATCH");
	}

	@Test
	void jobRunsWithScopesNarrowedToTheKeysCurrentScopes() {
		keyIsValidWith(EnumSet.of(Permission.KB_READ, Permission.TOOL_EXECUTE));
		AtomicReference<Set<Permission>> scopes = new AtomicReference<>();
		handler.behaviour = execution -> scopes.set(execution.access().scopes());

		runner.execute(job(1), "corr-12345678");

		// Enqueued with DOCUMENT_UPLOAD + KB_READ; the key now has KB_READ + TOOL_EXECUTE: only KB_READ remains.
		assertThat(scopes.get()).containsExactly(Permission.KB_READ);
	}

	@Test
	void lostLeaseDoesNotTriggerTheFinalFailureCallback() {
		store.leaseLost = true;
		handler.behaviour = execution -> {
			throw JobFailure.permanent("PDF_CORRUPT");
		};

		runner.execute(job(1), "corr-12345678");

		assertThat(handler.finalFailures).isEmpty();
	}

	@Test
	void failingReactionStillRecordsTheJobOutcome() {
		handler.behaviour = execution -> {
			throw JobFailure.permanent("PDF_CORRUPT");
		};
		handler.reactionFails = true;

		runner.execute(job(1), "corr-12345678");

		// First inside the shared transaction (rolled back with the failed reaction), then alone.
		assertThat(store.calls).containsExactly("fail:PDF_CORRUPT:final", "fail:PDF_CORRUPT:final");
	}

	@Test
	void duplicateHandlerKindsAreRejected() {
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> new JobRunner(store, credentials, properties, new SimpleMeterRegistry(),
					new NoopTransactions(), List.of(new ScriptedHandler(), new ScriptedHandler())))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void retryDelayGrowsExponentiallyAndIsCapped() {
		assertThat(properties.retryDelay(1, 0.5)).isEqualTo(Duration.ofSeconds(30));
		assertThat(properties.retryDelay(2, 0.5)).isEqualTo(Duration.ofMinutes(2));
		assertThat(properties.retryDelay(3, 0.5)).isEqualTo(Duration.ofMinutes(8));
		assertThat(properties.retryDelay(9, 0.5)).isEqualTo(Duration.ofMinutes(15));
		assertThat(properties.retryDelay(1, 0.0)).isEqualTo(Duration.ofSeconds(24));
		assertThat(properties.retryDelay(1, 1.0)).isEqualTo(Duration.ofSeconds(36));
	}

	private void keyIsValidWith(Set<Permission> scopes) {
		when(credentials.revalidate(KEY)).thenReturn(new Authenticated(TENANT, KEY, scopes, false, Optional.empty(),
				Instant.now().plusSeconds(3600)));
	}

	private static ClaimedJob job(int attempt) {
		JobContextSnapshot snapshot = new JobContextSnapshot(TENANT, PrincipalType.INTEGRATION_SERVICE,
				"api-key:" + KEY, null, Set.of(), Set.of(), Set.of(Permission.DOCUMENT_UPLOAD, Permission.KB_READ), KEY,
				false, null, Instant.now().plusSeconds(3600), AuthMethod.API_KEY);
		return new ClaimedJob(UUID.randomUUID(), TENANT, ScriptedHandler.KIND, Map.of("versionId", "x"), snapshot,
				"corr-12345678", attempt, 3);
	}

	static final class ScriptedHandler implements JobHandler {

		static final String KIND = "TEST_SCRIPTED";

		Consumer<JobExecution> behaviour = execution -> {
		};

		int runs;

		final List<String> finalFailures = new ArrayList<>();

		boolean reactionFails;

		@Override
		public String kind() {
			return KIND;
		}

		@Override
		public void handle(JobExecution execution) {
			runs++;
			behaviour.accept(execution);
		}

		@Override
		public void onFinalFailure(JobExecution execution, String errorCode) {
			assertThat(AccessContextHolder.current()).as("callback runs with the job context").isPresent();
			if (reactionFails) {
				throw new IllegalStateException("reaction failed");
			}
			finalFailures.add(errorCode);
		}

	}

	/** Runs callbacks without a real transaction (the store is in memory). */
	static final class NoopTransactions implements PlatformTransactionManager {

		@Override
		public TransactionStatus getTransaction(TransactionDefinition definition) {
			return new SimpleTransactionStatus();
		}

		@Override
		public void commit(TransactionStatus status) {
		}

		@Override
		public void rollback(TransactionStatus status) {
		}

	}

	static final class RecordingStore implements JobLeaseStore {

		final List<String> calls = new ArrayList<>();

		Duration lastDelay;

		boolean leaseLost;

		@Override
		public List<ClaimedJob> claim(List<String> kinds, int limit, String workerId, Duration lease,
				int perIntegration) {
			return List.of();
		}

		@Override
		public boolean extendLease(UUID jobId, String workerId, Duration lease) {
			return !leaseLost;
		}

		@Override
		public boolean complete(UUID jobId, String workerId) {
			calls.add("complete");
			return !leaseLost;
		}

		@Override
		public boolean scheduleRetry(UUID jobId, String workerId, String errorCode, Duration delay) {
			calls.add("retry:" + errorCode);
			lastDelay = delay;
			return !leaseLost;
		}

		@Override
		public boolean fail(UUID jobId, String workerId, String errorCode, boolean retryable) {
			calls.add("fail:" + errorCode + ":" + (retryable ? "retryable" : "final"));
			return !leaseLost;
		}

		@Override
		public boolean cancel(UUID jobId, String workerId, String errorCode) {
			calls.add("cancel:" + errorCode);
			return !leaseLost;
		}

		@Override
		public List<ClaimedJob> failAbandoned(List<String> kinds) {
			return List.of();
		}

	}

}
