package com.mimocodes.mimorag.support;

import java.util.function.Function;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs raw SQL as the application role inside a transaction started by the tenant-aware transaction manager, with a
 * chosen {@link AccessContext} (or none). Lets tests observe exactly what Row-Level Security allows.
 */
public final class ContextualJdbc {

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transactions;

	public ContextualJdbc(JdbcTemplate jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	public <T> T as(AccessContext context, Function<JdbcTemplate, T> work) {
		return AccessContextHolder.callAs(context, () -> transactions.execute(status -> work.apply(jdbc)));
	}

	public <T> T withoutContext(Function<JdbcTemplate, T> work) {
		AccessContextHolder.clear();
		return transactions.execute(status -> work.apply(jdbc));
	}

}
