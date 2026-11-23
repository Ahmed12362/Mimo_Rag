package com.mimocodes.mimorag;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.ContextualJdbc;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level isolation, independent of application code: raw SQL as the runtime role, with and without tenant
 * settings. This is the safety net for application bugs such as a forgotten tenant filter.
 */
class RowLevelSecurityIntegrationTest extends PostgresIntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private TransactionTemplate transactionTemplate;

	private ContextualJdbc db;

	private UUID tenantA;

	private UUID tenantB;

	private UUID kbOfA;

	@BeforeEach
	void setUp() throws Exception {
		ApiTestClient api = new ApiTestClient(mockMvc, jsonMapper);
		db = new ContextualJdbc(jdbcTemplate, transactionTemplate);
		tenantA = api.createIntegration(ApiTestClient.uniqueCode("rls-a"));
		tenantB = api.createIntegration(ApiTestClient.uniqueCode("rls-b"));
		kbOfA = api.createKnowledgeBase(api.issueKey(tenantA, "KB_MANAGE").plaintext(), "a-kb");
		api.createKnowledgeBase(api.issueKey(tenantB, "KB_MANAGE").plaintext(), "b-kb");
	}

	@Test
	void withoutTenantContextNoTenantRowsAreVisible() {
		assertThat(db.<Integer>withoutContext(jdbc -> count(jdbc, "knowledge_base"))).isZero();
		assertThat(db.<Integer>withoutContext(jdbc -> count(jdbc, "knowledge_base_role_grant"))).isZero();
		assertThat(db.<Integer>withoutContext(jdbc -> count(jdbc, "integration"))).isZero();
		assertThat(db.<Integer>withoutContext(jdbc -> count(jdbc, "integration_api_key"))).isZero();
	}

	@Test
	void outsideAnyTransactionNoTenantRowsAreVisible() {
		assertThat(count(jdbcTemplate, "knowledge_base")).isZero();
		assertThat(count(jdbcTemplate, "integration")).isZero();
	}

	@Test
	void tenantSeesOnlyItsOwnRows() {
		List<UUID> owners = db.as(service(tenantA),
				jdbc -> jdbc.queryForList("select distinct integration_id from knowledge_base", UUID.class));
		List<UUID> integrations = db.as(service(tenantA),
				jdbc -> jdbc.queryForList("select id from integration", UUID.class));

		assertThat(owners).containsExactly(tenantA);
		assertThat(integrations).containsExactly(tenantA);
	}

	@Test
	void tenantCannotWriteRowsForAnotherTenant() {
		assertThatThrownBy(() -> db.as(service(tenantA), jdbc -> jdbc.update("""
				insert into knowledge_base (id, integration_id, code, name, status, created_at, created_by, updated_at,
				                            version)
				values (gen_random_uuid(), ?, 'smuggled', 'x', 'ACTIVE', now(), 'test', now(), 0)""", tenantB)))
			.rootCause()
			.hasMessageContaining("row-level security");
		assertThatThrownBy(() -> db.as(service(tenantA),
				jdbc -> jdbc.update("update knowledge_base set integration_id = ? where id = ?", tenantB, kbOfA)))
			.rootCause()
			.hasMessageContaining("row-level security");
	}

	@Test
	void tenantCannotModifyPlatformManagedTables() {
		// The own integration row is visible, but the WITH CHECK policy forbids integration-plane writes.
		assertThatThrownBy(() -> db.as(service(tenantA),
				jdbc -> jdbc.update("update integration set status = 'ACTIVE' where id = ?", tenantA)))
			.rootCause()
			.hasMessageContaining("row-level security");
		assertThatThrownBy(() -> db.as(service(tenantA),
				jdbc -> jdbc.update("update integration_api_key set status = 'ACTIVE' where integration_id = ?",
						tenantA)))
			.rootCause()
			.hasMessageContaining("row-level security");
	}

	@Test
	void platformAdminSeesAllIntegrationsButNoTenantContent() {
		AccessContext admin = AccessContext.platformAdmin("t");

		assertThat(db.<List<UUID>>as(admin, jdbc -> jdbc.queryForList("select id from integration", UUID.class)))
			.contains(tenantA, tenantB);
		assertThat(db.<Integer>as(admin, jdbc -> count(jdbc, "knowledge_base"))).isZero();
	}

	@Test
	void auditTrailIsAppendOnly() {
		AccessContext admin = AccessContext.platformAdmin("t");

		assertThatThrownBy(() -> db.as(admin, jdbc -> jdbc.update("update audit_event set action = 'x'")))
			.rootCause()
			.hasMessageContaining("permission denied");
		assertThatThrownBy(() -> db.as(admin, jdbc -> jdbc.update("delete from audit_event")))
			.rootCause()
			.hasMessageContaining("permission denied");
	}

	@Test
	void credentialLookupFunctionWorksBeforeTenantIsKnown() {
		Integer rows = db.withoutContext(jdbc -> jdbc.queryForObject(
				"select count(*) from find_api_key_for_authentication('000000000000000000000000')", Integer.class));
		assertThat(rows).isZero();
	}

	private static AccessContext service(UUID tenant) {
		return AccessContext.integrationService(tenant, "test", Set.of(Permission.KB_MANAGE), "t");
	}

	private static int count(JdbcTemplate jdbc, String table) {
		Integer count = jdbc.queryForObject("select count(*) from " + table, Integer.class);
		return count == null ? 0 : count;
	}

}
