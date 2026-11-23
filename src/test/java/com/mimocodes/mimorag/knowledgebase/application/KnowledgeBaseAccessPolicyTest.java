package com.mimocodes.mimorag.knowledgebase.application;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AuthMethod;
import com.mimocodes.mimorag.shared.access.Permission;
import org.junit.jupiter.api.Test;

import static com.mimocodes.mimorag.shared.access.Permission.DOCUMENT_REVIEW;
import static com.mimocodes.mimorag.shared.access.Permission.KB_MANAGE;
import static com.mimocodes.mimorag.shared.access.Permission.KB_READ;
import static com.mimocodes.mimorag.shared.access.Permission.KEY_ROTATE;
import static com.mimocodes.mimorag.shared.access.Permission.TOOL_EXECUTE;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeBaseAccessPolicyTest {

	private static final UUID TENANT = UUID.randomUUID();

	private static final UUID KB = UUID.randomUUID();

	private static final UUID OTHER_KB = UUID.randomUUID();

	@Test
	void serviceIdentityGetsItsKnowledgeBaseScopes() {
		AccessContext service = AccessContext.integrationService(TENANT, "k1", Set.of(KB_READ, TOOL_EXECUTE), "c");

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, KB, List.of())).containsExactly(KB_READ);
	}

	@Test
	void manageImpliesRead() {
		AccessContext service = AccessContext.integrationService(TENANT, "k1", Set.of(KB_MANAGE), "c");

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, KB, List.of()))
			.containsExactlyInAnyOrder(KB_MANAGE, KB_READ);
	}

	@Test
	void keyRotationScopeGrantsNoContentAccess() {
		AccessContext rotationOnly = AccessContext.integrationService(TENANT, "k1", Set.of(KEY_ROTATE), "c");

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(rotationOnly, KB, List.of())).isEmpty();
	}

	@Test
	void endUserPermissionsAreGrantsIntersectedWithKeyScopes() {
		AccessContext user = AccessContext.integrationService(TENANT, "k1", Set.of(KB_READ, DOCUMENT_REVIEW), "c")
			.asEndUser("u-1", Set.of("hr-editor"), Set.of(), AuthMethod.API_KEY_AND_JWT_JWKS);

		Set<Permission> effective = KnowledgeBaseAccessPolicy.effectivePermissions(user, KB,
				List.of(KB_MANAGE, DOCUMENT_REVIEW));

		assertThat(effective).containsExactly(DOCUMENT_REVIEW);
	}

	@Test
	void endUserWithoutGrantsHasNoPermissions() {
		AccessContext user = AccessContext.integrationService(TENANT, "k1", Set.of(KB_MANAGE, KB_READ), "c")
			.asEndUser("u-1", Set.of("anything"), Set.of(), AuthMethod.API_KEY_AND_JWT_JWKS);

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(user, KB, List.of())).isEmpty();
	}

	@Test
	void knowledgeBaseRestrictedKeyReachesOnlyItsKnowledgeBases() {
		AccessContext.Credential restricted = new AccessContext.Credential("k1", false, Optional.of(Set.of(KB)), null);
		AccessContext service = AccessContext.integrationService(TENANT, restricted, Set.of(KB_MANAGE), "c");
		AccessContext user = service.asEndUser("u-1", Set.of("r"), Set.of(), AuthMethod.API_KEY_AND_JWT_JWKS);

		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, KB, List.of())).contains(KB_MANAGE);
		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(service, OTHER_KB, List.of())).isEmpty();
		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(user, OTHER_KB, List.of(KB_MANAGE))).isEmpty();
	}

	@Test
	void platformAdminHasNoTenantContentPermissions() {
		assertThat(KnowledgeBaseAccessPolicy.effectivePermissions(AccessContext.platformAdmin("c"), KB,
				List.of(KB_MANAGE, KB_READ)))
			.isEmpty();
	}

}
