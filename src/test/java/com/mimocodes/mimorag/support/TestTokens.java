package com.mimocodes.mimorag.support;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/** Generates signing keys and mints user assertions the way a connected system would. */
public final class TestTokens {

	private TestTokens() {
	}

	public static RSAKey newRsaKey() {
		try {
			return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** A 1024-bit RSA key: below the platform minimum, used to prove weak keys are rejected. */
	public static RSAKey newWeakRsaKey() {
		try {
			return new RSAKeyGenerator(1024, true).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static ECKey newEcKey() {
		try {
			return new ECKeyGenerator(Curve.P_256).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static String publicJwks(JWK... keys) {
		return new JWKSet(List.of(keys)).toPublicJWKSet().toString();
	}

	/** Builder with valid defaults: 2-minute lifetime, issued now. */
	public static Claims claims(String issuer, String audience, String subject) {
		return new Claims(issuer, audience, subject);
	}

	public static final class Claims {

		private final JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();

		private Claims(String issuer, String audience, String subject) {
			Instant now = Instant.now();
			builder.issuer(issuer).audience(audience).subject(subject).issueTime(Date.from(now))
				.expirationTime(Date.from(now.plus(Duration.ofMinutes(2))))
				.jwtID(UUID.randomUUID().toString());
		}

		public Claims roles(String... roles) {
			builder.claim("roles", List.of(roles));
			return this;
		}

		public Claims issuedAt(Instant issuedAt) {
			builder.issueTime(Date.from(issuedAt));
			return this;
		}

		public Claims expiresAt(Instant expiresAt) {
			builder.expirationTime(Date.from(expiresAt));
			return this;
		}

		public Claims without(String claim) {
			builder.claim(claim, null);
			return this;
		}

		public Claims claim(String name, Object value) {
			builder.claim(name, value);
			return this;
		}

		public String signRs256(RSAKey key) {
			return sign(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), signer(key));
		}

		public String signEs256(ECKey key) {
			return sign(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(key.getKeyID()).build(), signer(key));
		}

		public String signHs256(byte[] secret) {
			try {
				return sign(new JWSHeader(JWSAlgorithm.HS256), new MACSigner(secret));
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		public String unsigned() {
			return new PlainJWT(builder.build()).serialize();
		}

		public Map<String, Object> asMap() {
			return builder.build().toJSONObject();
		}

		private String sign(JWSHeader header, JWSSigner signer) {
			try {
				SignedJWT jwt = new SignedJWT(header, builder.build());
				jwt.sign(signer);
				return jwt.serialize();
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private static JWSSigner signer(JWK key) {
			try {
				return key instanceof RSAKey rsa ? new RSASSASigner(rsa.toPrivateKey(), true)
						: new ECDSASigner((ECKey) key);
			}
			catch (JOSEException ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
