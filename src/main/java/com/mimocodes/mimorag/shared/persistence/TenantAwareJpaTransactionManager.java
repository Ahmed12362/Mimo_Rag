package com.mimocodes.mimorag.shared.persistence;

import java.sql.PreparedStatement;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import org.hibernate.Session;

import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Applies the caller's tenant to every database transaction, so PostgreSQL Row-Level Security policies see it.
 * <p>
 * At transaction start it sets {@code app.integration_id} and {@code app.access_plane} with
 * {@code set_config(..., is_local => true)}: the values live only until commit/rollback and can never leak to the
 * next user of a pooled connection. Without an {@link AccessContext} both are set to empty strings, which makes
 * tenant policies match zero rows (fail closed). If applying the settings fails, the just-started transaction is
 * rolled back and all thread-bound resources are released before the failure is reported.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

	private static final String APPLY_SETTINGS_SQL = "select set_config('app.integration_id', ?, true), "
			+ "set_config('app.access_plane', ?, true)";

	public TenantAwareJpaTransactionManager(EntityManagerFactory entityManagerFactory) {
		super(entityManagerFactory);
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		super.doBegin(transaction, definition);
		AccessContext context = AccessContextHolder.current().orElse(null);
		String integrationId = context == null || context.integrationId() == null ? ""
				: context.integrationId().toString();
		String accessPlane = context == null ? "" : context.principalType().name();
		try {
			applySettings(integrationId, accessPlane);
		}
		catch (RuntimeException ex) {
			abortBegunTransaction(transaction);
			throw new CannotCreateTransactionException("Could not apply tenant settings to the transaction", ex);
		}
	}

	/** Executes the tenant-settings statement; protected so tests can simulate a failure. */
	protected void applySettings(String integrationId, String accessPlane) {
		currentEntityManager().unwrap(Session.class).doWork(connection -> {
			try (PreparedStatement statement = connection.prepareStatement(APPLY_SETTINGS_SQL)) {
				statement.setString(1, integrationId);
				statement.setString(2, accessPlane);
				statement.execute();
			}
		});
	}

	private void abortBegunTransaction(Object transaction) {
		try {
			EntityTransaction entityTransaction = currentEntityManager().getTransaction();
			if (entityTransaction.isActive()) {
				entityTransaction.rollback();
			}
		}
		catch (RuntimeException rollbackFailure) {
			logger.warn("Rollback after failed tenant-settings application also failed", rollbackFailure);
		}
		finally {
			// Unbinds the EntityManager and JDBC connection from the thread and closes them.
			doCleanupAfterCompletion(transaction);
		}
	}

	private EntityManager currentEntityManager() {
		EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager
			.getResource(obtainEntityManagerFactory());
		return holder.getEntityManager();
	}

}
