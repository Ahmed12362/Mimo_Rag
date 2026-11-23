package com.mimocodes.mimorag;

import java.util.Map;

import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the database privilege model that Row-Level Security will rely on from M1: the runtime connection
 * uses a non-owner, non-superuser role without BYPASSRLS that cannot change the schema or migration history.
 */
class DatabaseRolesIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private Flyway flyway;

	@Test
	void runtimeConnectionUsesLeastPrivilegedApplicationRole() {
		Map<String, Object> role = jdbc.queryForMap(
				"select current_user as name, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb "
						+ "from pg_roles where rolname = current_user");

		assertThat(role).containsEntry("name", "mimorag_app")
			.containsEntry("rolsuper", false)
			.containsEntry("rolbypassrls", false)
			.containsEntry("rolcreaterole", false)
			.containsEntry("rolcreatedb", false);
	}

	@Test
	void baselineMigrationWasAppliedByOwnerRole() {
		MigrationInfo baseline = flyway.info().current();

		assertThat(baseline.getVersion().getVersion()).isEqualTo("1");
		assertThat(baseline.getState().isApplied()).isTrue();
		assertThat(baseline.getInstalledBy()).isEqualTo("mimorag_owner");
	}

	@Test
	void applicationRoleCannotChangeTheSchema() {
		assertThatThrownBy(() -> jdbc.execute("create table mimorag.should_not_exist (id int)")).rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.execute("create table public.should_not_exist (id int)")).rootCause()
			.hasMessageContaining("permission denied");
	}

	@Test
	void applicationRoleCannotReadOrModifyMigrationHistory() {
		assertThatThrownBy(() -> jdbc.queryForList("select * from mimorag.flyway_schema_history")).rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("delete from mimorag.flyway_schema_history")).rootCause()
			.hasMessageContaining("permission denied");
	}

}
