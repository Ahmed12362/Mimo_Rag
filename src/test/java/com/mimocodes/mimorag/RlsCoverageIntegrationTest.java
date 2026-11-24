package com.mimocodes.mimorag;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for every future migration: each table in the platform schema must be explicitly classified, and its
 * Row-Level Security configuration must match the class. A new table that is not classified fails this test, so
 * forgetting RLS on (for example) a documents or jobs table cannot go unnoticed.
 */
class RlsCoverageIntegrationTest extends PostgresIntegrationTest {

	/** Tenant content: RLS enabled AND forced, no admin exception. */
	private static final Set<String> TENANT_CONTENT = Set.of("knowledge_base", "knowledge_base_role_grant",
			"document", "document_version", "job", "extraction_run", "page_extraction");

	/**
	 * Platform and credential metadata: RLS enabled but not forced, because SECURITY DEFINER authentication
	 * functions (running as the owner) must read it before a tenant is known. Policies restrict the runtime role.
	 */
	private static final Set<String> PLATFORM_AND_CREDENTIALS = Set.of("integration", "integration_api_key",
			"integration_api_key_kb", "integration_trust_config", "audit_event");

	/** No RLS, but the runtime role has no privileges at all. */
	private static final Set<String> NO_APPLICATION_ACCESS = Set.of("flyway_schema_history");

	@Test
	void everyTableIsClassifiedAndProtectedAccordingly() {
		JdbcTemplate superuser = superuserJdbc();
		List<Map<String, Object>> tables = superuser.queryForList("""
				select c.relname as name, c.relrowsecurity as rls, c.relforcerowsecurity as forced
				from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'mimorag' and c.relkind in ('r', 'p')""");

		Set<String> unclassified = new TreeSet<>();
		for (Map<String, Object> table : tables) {
			String name = (String) table.get("name");
			boolean rls = (Boolean) table.get("rls");
			boolean forced = (Boolean) table.get("forced");
			if (TENANT_CONTENT.contains(name)) {
				assertThat(rls && forced).as("tenant table %s must ENABLE and FORCE row level security", name).isTrue();
			}
			else if (PLATFORM_AND_CREDENTIALS.contains(name)) {
				assertThat(rls).as("platform table %s must ENABLE row level security", name).isTrue();
			}
			else if (NO_APPLICATION_ACCESS.contains(name)) {
				Boolean anyPrivilege = superuser.queryForObject(
						"select has_table_privilege('mimorag_app', 'mimorag.' || ?, 'SELECT,INSERT,UPDATE,DELETE')",
						Boolean.class, name);
				assertThat(anyPrivilege).as("runtime role must have no access to %s", name).isFalse();
			}
			else {
				unclassified.add(name);
			}
		}
		assertThat(unclassified)
			.as("Classify new tables in RlsCoverageIntegrationTest and give them matching RLS policies")
			.isEmpty();
	}

	@Test
	void everyRlsTableHasAtLeastOnePolicy() {
		List<String> withoutPolicies = superuserJdbc().queryForList("""
				select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where n.nspname = 'mimorag' and c.relkind = 'r' and c.relrowsecurity
				  and not exists (select 1 from pg_policy p where p.polrelid = c.oid)""", String.class);

		assertThat(withoutPolicies).isEmpty();
	}

	private static JdbcTemplate superuserJdbc() {
		PostgreSQLContainer container = POSTGRES;
		return new JdbcTemplate(new DriverManagerDataSource(container.getJdbcUrl(), container.getUsername(),
				container.getPassword()));
	}

}
