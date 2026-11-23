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
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Calls the SECURITY DEFINER functions defined in {@code V2__integrations_and_credentials.sql}. */
@Component
class JdbcApiKeyCredentialLookup implements ApiKeyCredentialLookup {

	private final JdbcTemplate jdbc;

	JdbcApiKeyCredentialLookup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<StoredCredential> findByKeyId(String keyId) {
		List<StoredCredential> rows = jdbc.query(
				"select integration_id, secret_hash, key_status, expires_at, scopes, integration_status "
						+ "from find_api_key_for_authentication(?)",
				(rs, rowNum) -> {
					Timestamp expiresAt = rs.getTimestamp("expires_at");
					return new StoredCredential(rs.getObject("integration_id", UUID.class),
							rs.getString("secret_hash"), ApiKeyStatus.valueOf(rs.getString("key_status")),
							expiresAt == null ? null : expiresAt.toInstant(), toScopes(rs.getArray("scopes")),
							IntegrationStatus.valueOf(rs.getString("integration_status")));
				}, keyId);
		return rows.stream().findFirst();
	}

	@Override
	public void recordUse(String keyId, Instant usedAt) {
		jdbc.query("select record_api_key_use(?, ?)", rs -> null, keyId, Timestamp.from(usedAt));
	}

	private static Set<Permission> toScopes(Array array) throws SQLException {
		if (array == null) {
			return Set.of();
		}
		String[] values = (String[]) array.getArray();
		return Arrays.stream(values)
			.map(Permission::valueOf)
			.collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
	}

}
