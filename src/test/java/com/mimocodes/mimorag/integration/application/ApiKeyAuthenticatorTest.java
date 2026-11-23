package com.mimocodes.mimorag.integration.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Authenticated;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.Rejected;
import com.mimocodes.mimorag.integration.application.ApiKeyAuthenticator.RejectionReason;
import com.mimocodes.mimorag.integration.domain.ApiKeyCredential;
import com.mimocodes.mimorag.integration.domain.ApiKeyStatus;
import com.mimocodes.mimorag.integration.domain.ApiKeyType;
import com.mimocodes.mimorag.integration.domain.IntegrationStatus;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyAuthenticatorTest {

	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");

	private final ApiKeyCredential.Generated generated = ApiKeyCredential.generate();

	private final AtomicInteger recordedUses = new AtomicInteger();

	@Test
	void usageIsRecordedAtMostOncePerIntervalPerKey() {
		MutableClock clock = new MutableClock(NOW);
		ApiKeyAuthenticator authenticator = authenticator(credential(Set.of(Permission.KB_READ), Set.of()), clock);

		for (int i = 0; i < 50; i++) {
			assertThat(authenticator.authenticate(generated.plaintext())).isInstanceOf(Authenticated.class);
		}
		assertThat(recordedUses).hasValue(1);

		clock.advance(ApiKeyAuthenticator.USE_RECORDING_INTERVAL.plusSeconds(1));
		authenticator.authenticate(generated.plaintext());
		assertThat(recordedUses).hasValue(2);
	}

	@Test
	void effectiveScopesAreLimitedByTheIntegrationCeiling() {
		ApiKeyAuthenticator authenticator = authenticator(
				credential(EnumSet.of(Permission.KB_MANAGE, Permission.KB_READ, Permission.KEY_ROTATE),
						EnumSet.of(Permission.KB_READ)),
				new MutableClock(NOW));

		Authenticated result = (Authenticated) authenticator.authenticate(generated.plaintext());

		assertThat(result.scopes()).containsExactlyInAnyOrder(Permission.KB_READ, Permission.KEY_ROTATE);
	}

	@Test
	void expiredKeyIsRejected() {
		MutableClock clock = new MutableClock(NOW);
		ApiKeyAuthenticator authenticator = authenticator(credential(Set.of(Permission.KB_READ), Set.of()), clock);
		clock.advance(Duration.ofDays(91));

		assertThat(authenticator.authenticate(generated.plaintext())).isInstanceOfSatisfying(Rejected.class,
				rejected -> assertThat(rejected.reason()).isEqualTo(RejectionReason.KEY_EXPIRED));
	}

	private ApiKeyCredentialLookup.StoredCredential credential(Set<Permission> scopes, Set<Permission> ceiling) {
		Set<Permission> allowed = ceiling.isEmpty() ? EnumSet.of(Permission.KB_READ, Permission.KB_MANAGE) : ceiling;
		return new ApiKeyCredentialLookup.StoredCredential(UUID.randomUUID(), generated.secretHash(),
				ApiKeyStatus.ACTIVE, ApiKeyType.STANDARD, NOW.plus(Duration.ofDays(90)), scopes, allowed,
				IntegrationStatus.ACTIVE, Set.of());
	}

	private ApiKeyAuthenticator authenticator(ApiKeyCredentialLookup.StoredCredential stored, Clock clock) {
		ApiKeyCredentialLookup lookup = new ApiKeyCredentialLookup() {
			@Override
			public Optional<StoredCredential> findByKeyId(String keyId) {
				return keyId.equals(generated.keyId()) ? Optional.of(stored) : Optional.empty();
			}

			@Override
			public void recordUse(String keyId, Instant usedAt) {
				recordedUses.incrementAndGet();
			}
		};
		return new ApiKeyAuthenticator(lookup, new NoOpAudit(), clock);
	}

	private static final class NoOpAudit implements AuditRecorder {

		@Override
		public void record(AuditEvent event) {
		}

		@Override
		public void recordIndependently(AuditEvent event) {
		}

		@Override
		public void recordUnauthenticated(AuditEvent event) {
		}

	}

	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
