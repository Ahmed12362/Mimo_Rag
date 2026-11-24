package com.mimocodes.mimorag.access.assertion;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.mimocodes.mimorag.integration.application.TrustConfigurationView;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;

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

/**
 * Builds strict JWT decoders from an integration's trust configuration:
 * <ul>
 * <li>signature algorithms restricted to the configured subset of RS256/ES256 ({@code none} and HS* never accepted);
 * <li>keys only from that integration's JWKS (HTTPS endpoint, or inline keys in development); RSA keys below the
 * configured minimum size are ignored;</li>
 * <li>remote keys are cached for a bounded time; re-fetches triggered by an unknown {@code kid} are
 * rate-limited to at most two per configured interval (Nimbus {@code RateLimitedJWKSetSource}); fetches have timeouts and a size limit and never follow redirects;</li>
 * <li>{@code iss} and {@code aud} must match exactly; {@code exp}, {@code iat} and the user-id claim are mandatory;
 * {@code nbf} honoured; bounded clock skew; total token lifetime capped.</li>
 * </ul>
 */
class UserAssertionDecoderFactory {

	private final UserAssertionProperties properties;

	private final Clock clock;

	UserAssertionDecoderFactory(UserAssertionProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
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
		JWKSource<SecurityContext> keys = withMinimumKeyStrength(configuration.jwksUri() != null
				? remoteKeys(configuration.jwksUri()) : new ImmutableJWKSet<>(parsePublicKeys(configuration.inlineJwks())));
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).jwsAlgorithms(selected -> {
			selected.clear();
			selected.addAll(algorithms);
		}).build();
		decoder.setJwtValidator(validator(configuration));
		return decoder;
	}

	private JWKSource<SecurityContext> remoteKeys(String jwksUri) throws InvalidUserAssertionException {
		URL url;
		try {
			url = URI.create(jwksUri).toURL();
		}
		catch (IllegalArgumentException | MalformedURLException ex) {
			throw new InvalidUserAssertionException(InvalidUserAssertionException.Reason.INVALID_TRUST_CONFIGURATION,
					"invalid JWKS URI");
		}
		NoRedirectResourceRetriever retriever = new NoRedirectResourceRetriever(
				(int) properties.jwksConnectTimeout().toMillis(), (int) properties.jwksReadTimeout().toMillis(),
				properties.jwksMaxBytes());
		long fetchTimeout = properties.jwksConnectTimeout().plus(properties.jwksReadTimeout()).toMillis();
		return JWKSourceBuilder.<SecurityContext>create(url, retriever)
			.cache(properties.jwksCacheTtl().toMillis(), fetchTimeout)
			.rateLimited(properties.jwksRefreshMinInterval().toMillis())
			.refreshAheadCache(false)
			.retrying(false)
			.outageTolerant(false)
			.build();
	}

	/** Ignores RSA keys below the configured size, so tokens signed with weak keys never verify. */
	private JWKSource<SecurityContext> withMinimumKeyStrength(JWKSource<SecurityContext> source) {
		int minBits = properties.minRsaKeyBits();
		return (selector, context) -> source.get(selector, context)
			.stream()
			.filter(key -> !(key instanceof RSAKey rsa) || rsa.size() >= minBits)
			.toList();
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
			return new JWKSet(JWKSet.parse(inlineJwks).toPublicJWKSet().getKeys().stream().map(JWK::toPublicJWK).toList());
		}
		catch (ParseException ex) {
			throw new InvalidUserAssertionException(InvalidUserAssertionException.Reason.INVALID_TRUST_CONFIGURATION,
					"inline JWKS cannot be parsed");
		}
	}

	/** JWKS fetches never follow redirects (limits SSRF and unexpected key origins). */
	private static final class NoRedirectResourceRetriever extends DefaultResourceRetriever {

		NoRedirectResourceRetriever(int connectTimeoutMillis, int readTimeoutMillis, int sizeLimitBytes) {
			super(connectTimeoutMillis, readTimeoutMillis, sizeLimitBytes);
		}

		@Override
		protected HttpURLConnection openHTTPConnection(URL url) throws IOException {
			HttpURLConnection connection = super.openHTTPConnection(url);
			connection.setInstanceFollowRedirects(false);
			return connection;
		}

	}

}
