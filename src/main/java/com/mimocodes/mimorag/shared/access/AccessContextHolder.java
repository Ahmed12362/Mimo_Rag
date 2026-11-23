package com.mimocodes.mimorag.shared.access;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * Thread-bound holder for the current {@link AccessContext}. Set by the {@code access} module after verification
 * (and by background workers from a stored snapshot), always cleared in a {@code finally} block. Read by the
 * transaction manager to apply database tenant settings, so code that forgets to set it sees no tenant data.
 */
public final class AccessContextHolder {

	private static final ThreadLocal<AccessContext> CURRENT = new ThreadLocal<>();

	private AccessContextHolder() {
	}

	public static Optional<AccessContext> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static void set(AccessContext context) {
		CURRENT.set(context);
	}

	public static void clear() {
		CURRENT.remove();
	}

	/** Runs {@code work} with {@code context} bound, restoring the previous context afterwards. */
	public static <T> T callAs(AccessContext context, Supplier<T> work) {
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

}
