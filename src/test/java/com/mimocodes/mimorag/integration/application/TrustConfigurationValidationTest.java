package com.mimocodes.mimorag.integration.application;

import java.util.List;

import com.mimocodes.mimorag.integration.domain.TrustConfiguration.TrustSettings;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.web.ValidationViolation;
import com.mimocodes.mimorag.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Policy checks on trust configuration, with production defaults (https only, no inline keys). */
class TrustConfigurationValidationTest {

	private static final RSAKey KEY = TestTokens.newRsaKey();

	private final TrustConfigurationService production = service(false, false);

	private final TrustConfigurationService localDevelopment = service(true, true);

	@Test
	void acceptsHttpsJwksUriInProduction() {
		assertThatCode(() -> production.validate(settings("https://hris.example.com/.well-known/jwks.json", null,
				List.of("RS256")))).doesNotThrowAnyException();
	}

	@Test
	void rejectsPlainHttpJwksUriInProduction() {
		assertViolation(() -> production.validate(settings("http://hris.example.com/jwks", null, List.of("RS256"))),
				"jwksUri");
	}

	@Test
	void rejectsCredentialsInJwksUri() {
		assertViolation(
				() -> production.validate(settings("https://user:pass@hris.example.com/jwks", null, List.of("RS256"))),
				"jwksUri");
	}

	@Test
	void rejectsInlineKeysInProduction() {
		assertViolation(() -> production.validate(settings(null, TestTokens.publicJwks(KEY), List.of("RS256"))),
				"inlineJwks");
	}

	@Test
	void acceptsInlinePublicKeysInLocalDevelopment() {
		assertThatCode(() -> localDevelopment.validate(settings(null, TestTokens.publicJwks(KEY), List.of("RS256"))))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsPrivateKeyMaterialEvenInLocalDevelopment() {
		String privateJwks = "{\"keys\":[" + KEY.toJSONString() + "]}";

		assertViolation(() -> localDevelopment.validate(settings(null, privateJwks, List.of("RS256"))), "inlineJwks");
	}

	@Test
	void rejectsWeakRsaKeysEvenInLocalDevelopment() {
		assertViolation(() -> localDevelopment
			.validate(settings(null, TestTokens.publicJwks(TestTokens.newWeakRsaKey()), List.of("RS256"))), "inlineJwks");
	}

	@Test
	void rejectsUnsupportedAlgorithms() {
		assertViolation(() -> production.validate(settings("https://h.example.com/jwks", null, List.of("HS256"))),
				"allowedAlgorithms");
		assertViolation(() -> production.validate(settings("https://h.example.com/jwks", null, List.of("none"))),
				"allowedAlgorithms");
	}

	@Test
	void rejectsBothOrNeitherKeySource() {
		assertViolation(() -> localDevelopment
			.validate(settings("https://h.example.com/jwks", TestTokens.publicJwks(KEY), List.of("RS256"))), "jwksUri");
		assertViolation(() -> production.validate(settings(null, null, List.of("RS256"))), "jwksUri");
	}

	@Test
	void rejectsOutOfRangeLifetimeAndSkew() {
		TrustSettings tooLong = new TrustSettings("iss", "aud", "https://h.example.com/jwks", null, List.of("RS256"),
				7200, 30, "sub", null, null);
		TrustSettings tooMuchSkew = new TrustSettings("iss", "aud", "https://h.example.com/jwks", null,
				List.of("RS256"), 300, 900, "sub", null, null);

		assertViolation(() -> production.validate(tooLong), "maxTokenLifetimeSeconds");
		assertViolation(() -> production.validate(tooMuchSkew), "clockSkewSeconds");
	}

	@Test
	void rejectsUnsafeClaimNames() {
		TrustSettings settings = new TrustSettings("iss", "aud", "https://h.example.com/jwks", null, List.of("RS256"),
				300, 30, "sub", "roles; drop table", null);

		assertViolation(() -> production.validate(settings), "claimRoles");
	}

	private static TrustSettings settings(String jwksUri, String inlineJwks, List<String> algorithms) {
		return new TrustSettings("https://hris.example.com", "mimorag", jwksUri, inlineJwks, algorithms, 300, 30,
				"sub", "roles", null);
	}

	private static void assertViolation(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
		assertThatThrownBy(call).isInstanceOf(InvalidRequestException.class)
			.satisfies(ex -> org.assertj.core.api.Assertions
				.assertThat(((InvalidRequestException) ex).getViolations())
				.extracting(ValidationViolation::field)
				.contains(field));
	}

	private static TrustConfigurationService service(boolean allowInsecure, boolean allowInline) {
		return new TrustConfigurationService(null, null, new TrustPolicyProperties(allowInsecure, allowInline),
				JsonMapper.builder().build(), null, null);
	}

}
