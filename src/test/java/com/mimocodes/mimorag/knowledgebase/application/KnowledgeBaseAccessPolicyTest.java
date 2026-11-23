package com.mimocodes.mimorag.knowledgebase.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AuthMethod;
import com.mimocodes.mimorag.shared.access.Permission;
import org.junit.jupiter.api.Test;

import static com.mimocodes.mimorag.shared.access.Permission.DOCUMENT_REVIEW;
import static com.mimocodes.mimorag.shared.access.Permission.KB_MANAGE;
import static com.mimocodes.mimorag.shared.access.Permission.KB_READ;
import static com.mimocodes.mimorag.shared.access.Permission.TOOL_EXECUTE;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseAccessPolicyTest {

	private static final UUID TENANT = UUID.randomUUID();

	@Test
	void serviceIdentityGetsItsKnowledgeBaseScopes() {
		AccessContext service = AccessContext.integrationService(TENANT, "k1", Set.of(KB_READ, TOOL_EXECUTE), "c");

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, List.of())).containsExactly(KB_READ);
	}

	@Test
	void manageImpliesRead() {
		AccessContext service = AccessContext.integrationService(TENANT, "k1", Set.of(KB_MANAGE), "c");

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, List.of()))
			.containsExactlyInAnyOrder(KB_MANAGE, KB_READ);
	}

	@Test
	void endUserPermissionsAreGrantsIntersectedWithKeyScopes() {
		AccessContext user = AccessContext.integrationService(TENANT, "k1", Set.of(KB_READ, DOCUMENT_REVIEW), "c")
			.asEndUser("u-1", Set.of("hr-editor"), Set.of(), AuthMethod.API_KEY_AND_JWT_JWKS);

		Set<Permission> effective = KnowledgeBaseAccessPolicy.effectivePermissions(user,
				List.of(KB_MANAGE, DOCUMENT_REVIEW));

		assertThat(effective).containsExactly(DOCUMENT_REVIEW);
	}

	@Test
	void endUserWithoutGrantsHasNoPermissions() {
		AccessContext user = AccessContext.integrationService(TENANT, "k1", Set.of(KB_MANAGE, KB_READ), "c")
			.asEndUser("u-1", Set.of("anything"), Set.of(), AuthMethod.API_KEY_AND_JWT_JWKS);

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(user, List.of())).isEmpty();
	}

	@Test
	void platformAdminHasNoTenantContentPermissions() {
		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(AccessContext.platformAdmin("c"),
				List.of(KB_MANAGE, KB_READ)))
			.isEmpty();
	}

}
