package com.mimocodes.mimorag.access.assertion;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.mimocodes.mimorag.integration.application.TrustConfigurationView;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuedAtValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * Builds strict JWT decoders from an integration's trust configuration:
 * <ul>
 * <li>signature algorithms restricted to the configured subset of RS256/ES256 ({@code none} and HS* never accepted);
 * <li>keys only from that integration's JWKS (HTTPS endpoint, or inline keys in development);</li>
 * <li>{@code iss} and {@code aud} must match exactly; {@code exp}, {@code iat} and the user-id claim are mandatory;
 * {@code nbf} honoured; bounded clock skew; total token lifetime capped.</li>
 * </ul>
 */
class UserAssertionDecoderFactory {

	private static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);

	private static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(3);

	private final RestOperations jwksClient;

	private final Clock clock;

	UserAssertionDecoderFactory(Clock clock) {
		this.clock = clock;
		this.jwksClient = new RestTemplate(new NoRedirectRequestFactory());
	}

	JwtDecoder create(TrustConfigurationView configuration) throws InvalidUserAssertionException {
		Set<SignatureAlgorithm> algorithms = configuration.allowedAlgorithms()
			.stream()
			.map(SignatureAlgorithm::from)
			.collect(Collectors.toSet());
		if (algorithms.isEmpty() || algorithms.contains(null)) {
			throw new InvalidUserAssertionException(InvalidUserAssertionException.Reason.INVALID_TRUST_CONFIGURATION,
					"unsupported algorithm");
		}
		NimbusJwtDecoder decoder;
		if (configuration.jwksUri() != null) {
			decoder = NimbusJwtDecoder.withJwkSetUri(configuration.jwksUri())
				.jwsAlgorithms(selected -> {
					selected.clear();
					selected.addAll(algorithms);
				})
				.restOperations(jwksClient)
				.build();
		}
		else {
			decoder = NimbusJwtDecoder.withJwkSource(new ImmutableJWKSet<>(parsePublicKeys(configuration.inlineJwks())))
				.jwsAlgorithms(selected -> {
					selected.clear();
					selected.addAll(algorithms);
				})
				.build();
		}
		decoder.setJwtValidator(validator(configuration));
		return decoder;
	}

	private OAuth2TokenValidator<Jwt> validator(TrustConfigurationView configuration) {
		Duration skew = Duration.ofSeconds(configuration.clockSkewSeconds());
		JwtTimestampValidator timestamps = new JwtTimestampValidator(skew);
		timestamps.setAllowEmptyExpiryClaim(false);
		timestamps.setClock(clock);
		JwtIssuedAtValidator issuedAt = new JwtIssuedAtValidator(true);
		issuedAt.setClockSkew(skew);
		issuedAt.setClock(clock);
		return new DelegatingOAuth2TokenValidator<>(List.of(timestamps, issuedAt,
				new JwtIssuerValidator(configuration.issuer()), new JwtAudienceValidator(configuration.audience()),
				maxLifetime(Duration.ofSeconds(configuration.maxTokenLifetimeSeconds())),
				requiredClaim(configuration.claimUserId())));
	}

	private static OAuth2TokenValidator<Jwt> maxLifetime(Duration maxLifetime) {
		return jwt -> {
			Instant issuedAt = jwt.getIssuedAt();
			Instant expiresAt = jwt.getExpiresAt();
			if (issuedAt == null || expiresAt == null
					|| Duration.between(issuedAt, expiresAt).compareTo(maxLifetime) > 0) {
				return OAuth2TokenValidatorResult
					.failure(new OAuth2Error("invalid_token", "Token lifetime exceeds the configured maximum", null));
			}
			return OAuth2TokenValidatorResult.success();
		};
	}

	private static OAuth2TokenValidator<Jwt> requiredClaim(String claim) {
		return jwt -> jwt.hasClaim(claim) ? OAuth2TokenValidatorResult.success()
				: OAuth2TokenValidatorResult
					.failure(new OAuth2Error("invalid_token", "Required claim is missing: " + claim, null));
	}

	private static JWKSet parsePublicKeys(String inlineJwks) throws InvalidUserAssertionException {
		try {
			return JWKSet.parse(inlineJwks).toPublicJWKSet();
		}
		catch (ParseException ex) {
			throw new InvalidUserAssertionException(InvalidUserAssertionException.Reason.INVALID_TRUST_CONFIGURATION,
					"inline JWKS cannot be parsed");
		}
	}

	/** JWKS fetches use short timeouts and never follow redirects (limits SSRF and slow-loris style issues). */
	private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {

		NoRedirectRequestFactory() {
			setConnectTimeout(JWKS_CONNECT_TIMEOUT);
			setReadTimeout(JWKS_READ_TIMEOUT);
		}

		@Override
		protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
			super.prepareConnection(connection, httpMethod);
			connection.setInstanceFollowRedirects(false);
		}

	}

}
