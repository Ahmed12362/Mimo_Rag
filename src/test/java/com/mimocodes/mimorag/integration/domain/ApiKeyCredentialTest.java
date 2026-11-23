package com.mimocodes.mimorag.integration.domain;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyCredentialTest {

	@Test
	void generatedKeyHasDocumentedFormatAndRoundTrips() {
		ApiKeyCredential.Generated generated = ApiKeyCredential.generate();

		assertThat(generated.plaintext()).matches("mrk_[a-f0-9]{24}_[A-Za-z0-9_-]{43}");
		assertThat(generated.secretHash()).matches("[a-f0-9]{64}");
		ApiKeyCredential.Presented parsed = ApiKeyCredential.parse(generated.plaintext()).orElseThrow();
		assertThat(parsed.keyId()).isEqualTo(generated.keyId());
		assertThat(ApiKeyCredential.secretMatches(parsed.secret(), generated.secretHash())).isTrue();
		assertThat(generated.plaintext()).endsWith(generated.last4());
	}

	@Test
	void generatedKeysAreUnique() {
		Set<String> keys = new HashSet<>();
		for (int i = 0; i < 1000; i++) {
			keys.add(ApiKeyCredential.generate().plaintext());
		}
		assertThat(keys).hasSize(1000);
	}

	@Test
	void wrongSecretDoesNotMatch() {
		ApiKeyCredential.Generated generated = ApiKeyCredential.generate();
		ApiKeyCredential.Generated other = ApiKeyCredential.generate();
		String otherSecret = ApiKeyCredential.parse(other.plaintext()).orElseThrow().secret();

		assertThat(ApiKeyCredential.secretMatches(otherSecret, generated.secretHash())).isFalse();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "mrk_abc", "xyz_0123456789abcdef01234567_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"mrk_0123456789ABCDEF01234567_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
			"mrk_0123456789abcdef01234567_short", "mrk_0123456789abcdef01234567_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA!",
			"Bearer mrk_0123456789abcdef01234567_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" })
	void malformedKeysAreRejectedBeforeAnyLookup(String presented) {
		assertThat(ApiKeyCredential.parse(presented)).isEmpty();
	}

	@Test
	void toStringNeverRevealsSecrets() {
		ApiKeyCredential.Generated generated = ApiKeyCredential.generate();
		String secret = ApiKeyCredential.parse(generated.plaintext()).orElseThrow().secret();

		assertThat(generated.toString()).doesNotContain(secret).doesNotContain(generated.secretHash());
		assertThat(ApiKeyCredential.parse(generated.plaintext()).orElseThrow().toString()).doesNotContain(secret);
	}

}
