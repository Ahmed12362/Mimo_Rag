package com.mimocodes.mimorag.shared.access;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Thread-bound holder for the current {@link AccessContext}. Set by the {@code access} module after verification
 * (and by background workers from a stored snapshot), always cleared in a {@code finally} block. Read by the
 * transaction manager to apply database tenant settings, so code that forgets to set it sees no tenant data.
 * <p>
 * Binding is restricted by an architecture rule to the {@code access}, {@code shared.persistence} and job-runner
 * code. The context must not change while a database transaction is active on the thread: the tenant settings are
 * applied when the transaction begins, so a later switch would silently keep the old tenant.
 */
public final class AccessContextHolder {

	private static final ThreadLocal<AccessContext> CURRENT = new ThreadLocal<>();

	private AccessContextHolder() {
	}

	public static Optional<AccessContext> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static void set(AccessContext context) {
		guardAgainstSwitchInsideTransaction(context);
		CURRENT.set(context);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} with {@code context} bound, restoring the previous context afterwards. */
	public static <T> T callAs(AccessContext context, Supplier<T> work) {
		guardAgainstSwitchInsideTransaction(context);
		AccessContext previous = CURRENT.get();
		CURRENT.set(context);
		try {
			return work.get();
		}
		finally {
			if (previous == null) {
				CURRENT.remove();
			}
			else {
				CURRENT.set(previous);
			}
		}
	}

	private static void guardAgainstSwitchInsideTransaction(AccessContext next) {
		if (TransactionSynchronizationManager.isActualTransactionActive() && !Objects.equals(CURRENT.get(), next)) {
			throw new IllegalStateException(
					"The access context cannot change inside an active transaction; start the work outside it");
		}
	}

}
