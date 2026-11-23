package com.mimocodes.mimorag.shared.persistence;

import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManagerFactory;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantAwareJpaTransactionManagerIntegrationTest extends PostgresIntegrationTest {

	private static final AccessContext TENANT_A = AccessContext.integrationService(UUID.randomUUID(), "a",
			Set.of(Permission.KB_READ), "t");

	private static final AccessContext TENANT_B = AccessContext.integrationService(UUID.randomUUID(), "b",
			Set.of(Permission.KB_READ), "t");

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterEach
	void clearContext() {
		AccessContextHolder.clear();
	}

	@Test
	void appliesTheBoundTenantToTheTransaction() {
		String applied = AccessContextHolder.callAs(TENANT_A, () -> transactionTemplate
			.execute(status -> jdbc.queryForObject("select current_setting('app.integration_id', true)", String.class)));

		assertThat(applied).isEqualTo(TENANT_A.integrationId().toString());
	}

	@Test
	void contextCannotSwitchInsideAnActiveTransaction() {
		assertThatThrownBy(() -> AccessContextHolder.callAs(TENANT_A, () -> transactionTemplate
			.execute(status -> AccessContextHolder.callAs(TENANT_B, () -> "must not run"))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("cannot change inside an active transaction");
	}

	@Test
	void sameContextInsideTransactionAndNewInnerTransactionsAreAllowed() {
		TransactionTemplate requiresNew = new TransactionTemplate(transactionTemplate.getTransactionManager());
		requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

		String inner = AccessContextHolder.callAs(TENANT_A,
				() -> transactionTemplate.execute(outer -> AccessContextHolder.callAs(TENANT_A,
						() -> requiresNew.execute(status -> jdbc
							.queryForObject("select current_setting('app.integration_id', true)", String.class)))));

		assertThat(inner).isEqualTo(TENANT_A.integrationId().toString());
	}

	@Test
	void failureToApplySettingsReleasesAllThreadBoundResources() {
		TenantAwareJpaTransactionManager failing = new TenantAwareJpaTransactionManager(entityManagerFactory) {
			@Override
			protected void applySettings(String integrationId, String accessPlane) {
				throw new IllegalStateException("simulated database failure");
			}
		};
		failing.afterPropertiesSet();

		assertThatThrownBy(() -> new TransactionTemplate(failing).execute(status -> "must not run"))
			.isInstanceOf(CannotCreateTransactionException.class);
		assertThat(TransactionSynchronizationManager.getResourceMap()).isEmpty();
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		// The thread is still usable for a normal transaction afterwards.
		Integer one = transactionTemplate.execute(status -> jdbc.queryForObject("select 1", Integer.class));
		assertThat(one).isEqualTo(1);
	}

}
