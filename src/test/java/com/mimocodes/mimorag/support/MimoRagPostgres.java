package com.mimocodes.mimorag.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Creates PostgreSQL test containers that use the same image and the same role-provisioning script as local
 * development ({@code docker/postgres/initdb}), so tests exercise the real owner/app role separation.
 */
public final class MimoRagPostgres {

	public static final String IMAGE = "pgvector/pgvector:0.8.7-pg17";

	// Test-only throwaway credentials for an ephemeral container; not secrets.
	private static final String OWNER_PASSWORD = "test-owner-password";

	private static final String APP_PASSWORD = "test-app-password";

	private static final String ROLE_SCRIPT = "docker/postgres/initdb/01-create-roles.sh";

	private MimoRagPostgres() {
	}

	public static PostgreSQLContainer newContainer() {
		return new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("mimorag")
			.withEnv("MIMORAG_DB_OWNER_PASSWORD", OWNER_PASSWORD)
			.withEnv("MIMORAG_DB_APP_PASSWORD", APP_PASSWORD)
			.withCopyFileToContainer(MountableFile.forHostPath(ROLE_SCRIPT),
					"/docker-entrypoint-initdb.d/01-create-roles.sh");
	}

	public static void registerConnectionProperties(PostgreSQLContainer container, DynamicPropertyRegistry registry) {
		registry.add("MIMORAG_DB_URL", container::getJdbcUrl);
		registry.add("MIMORAG_DB_APP_PASSWORD", () -> APP_PASSWORD);
		registry.add("MIMORAG_DB_OWNER_PASSWORD", () -> OWNER_PASSWORD);
	}

}
