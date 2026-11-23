package com.mimocodes.mimorag.integration.infrastructure;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mimocodes.mimorag.integration.application.ApiKeyCredentialLookup;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.ApiKeyType;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Calls the SECURITY DEFINER functions defined in V2/V5 migrations. */
@Component
class JdbcApiKeyCredentialLookup implements ApiKeyCredentialLookup {

	private final JdbcTemplate jdbc;

	JdbcApiKeyCredentialLookup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<StoredCredential> findByKeyId(String keyId) {
		List<StoredCredential> rows = jdbc.query("""
				select integration_id, secret_hash, key_status, key_type, expires_at, scopes, allowed_scopes,
				       integration_status, knowledge_base_ids
				from find_api_key_for_authentication(?)""", (rs, rowNum) -> {
			Timestamp expiresAt = rs.getTimestamp("expires_at");
			return new StoredCredential(rs.getObject("integration_id", UUID.class), rs.getString("secret_hash"),
					ApiKeyStatus.valueOf(rs.getString("key_status")), ApiKeyType.valueOf(rs.getString("key_type")),
					expiresAt.toInstant(), toPermissions(rs.getArray("scopes")),
					toPermissions(rs.getArray("allowed_scopes")),
					IntegrationStatus.valueOf(rs.getString("integration_status")),
					toUuids(rs.getArray("knowledge_base_ids")));
		}, keyId);
		return rows.stream().findFirst();
	}

	@Override
	public void recordUse(String keyId, Instant usedAt) {
		jdbc.query("select record_api_key_use(?, ?)", rs -> null, keyId, Timestamp.from(usedAt));
	}

	private static Set<Permission> toPermissions(Array array) throws SQLException {
		if (array == null) {
			return Set.of();
		}
		return Arrays.stream((String[]) array.getArray())
			.map(Permission::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

	private static Set<UUID> toUuids(Array array) throws SQLException {
		if (array == null) {
			return Set.of();
		}
		return Set.of((UUID[]) array.getArray());
	}

}
