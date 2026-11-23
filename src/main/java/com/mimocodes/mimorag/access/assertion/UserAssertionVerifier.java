package com.mimocodes.mimorag.access.assertion;

import java.time.Clock;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.access.assertion.InvalidUserAssertionException.Reason;
import com.mimocodes.mimorag.integration.application.TrustConfigurationService;
import com.mimocodes.mimorag.integration.application.TrustConfigurationView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.AccessContextHolder;
import com.mimocodes.mimorag.shared.access.AuthMethod;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Verifies a signed user assertion presented together with an already-authenticated integration credential and turns
 * it into an end-user {@link AccessContext}.
 * <p>
 * <strong>Binding:</strong> keys come only from the trust configuration of the integration that authenticated with
 * the service credential, so a token signed by another integration can never be accepted.
 */
@Component
public class UserAssertionVerifier {

	static final int MAX_TOKEN_LENGTH = 8192;

	private static final int MAX_VALUE_LENGTH = 200;

	private static final int MAX_ROLES = 100;

	private static final Pattern SAFE_VALUE = Pattern.compile("[^\\p{Cntrl}]+");

	private final TrustConfigurationService trustConfigurations;

	private final UserAssertionDecoderFactory decoderFactory;

	private final ConcurrentMap<UUID, CachedDecoder> decoders = new ConcurrentHashMap<>();

	private record CachedDecoder(long configurationVersion, JwtDecoder decoder) {
	}

	UserAssertionVerifier(TrustConfigurationService trustConfigurations, Clock clock) {
		this.trustConfigurations = trustConfigurations;
		this.decoderFactory = new UserAssertionDecoderFactory(clock);
	}

	public AccessContext verify(String token, AccessContext integration) throws InvalidUserAssertionException {
		if (token.length() > MAX_TOKEN_LENGTH) {
			throw new InvalidUserAssertionException(Reason.TOO_LARGE, "assertion exceeds " + MAX_TOKEN_LENGTH);
		}
		TrustConfigurationView configuration = AccessContextHolder
			.callAs(integration, () -> trustConfigurations.findForVerification(integration))
			.orElseThrow(() -> new InvalidUserAssertionException(Reason.NO_TRUST_CONFIGURATION,
					"integration has no trust configuration"));
		Jwt jwt;
		try {
			jwt = decoderFor(configuration).decode(token);
		}
		catch (JwtException ex) {
			throw new InvalidUserAssertionException(Reason.REJECTED_BY_VALIDATION, ex.getMessage());
		}
		String userId = singleValue(jwt.getClaims().get(configuration.claimUserId()));
		if (userId == null) {
			throw new InvalidUserAssertionException(Reason.INVALID_USER_CLAIMS, "user id claim is not a valid string");
		}
		Set<String> roles = multipleValues(jwt, configuration.claimRoles());
		Set<String> groups = multipleValues(jwt, configuration.claimGroups());
		AuthMethod method = configuration.jwksUri() != null ? AuthMethod.API_KEY_AND_JWT_JWKS
				: AuthMethod.API_KEY_AND_JWT_INLINE_JWKS;
		return integration.asEndUser(userId, roles, groups, method);
	}

	private JwtDecoder decoderFor(TrustConfigurationView configuration) throws InvalidUserAssertionException {
		CachedDecoder cached = decoders.get(configuration.integrationId());
		if (cached != null && cached.configurationVersion() == configuration.version()) {
			return cached.decoder();
		}
		JwtDecoder decoder = decoderFactory.create(configuration);
		decoders.put(configuration.integrationId(), new CachedDecoder(configuration.version(), decoder));
		return decoder;
	}

	private static String singleValue(Object value) {
		if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_VALUE_LENGTH
				|| !SAFE_VALUE.matcher(text).matches()) {
			return null;
		}
		return text;
	}

	/** Accepts a JSON array of strings or a space-separated string; invalid entries invalidate the assertion. */
	private static Set<String> multipleValues(Jwt jwt, String claim) throws InvalidUserAssertionException {
		if (claim == null || !jwt.hasClaim(claim)) {
			return Set.of();
		}
		Object raw = jwt.getClaims().get(claim);
		Collection<?> values;
		if (raw instanceof Collection<?> collection) {
			values = collection;
		}
		else if (raw instanceof String text) {
			values = text.isBlank() ? Set.of() : Set.of(text.trim().split("\\s+"));
		}
		else {
			throw new InvalidUserAssertionException(Reason.INVALID_USER_CLAIMS, "claim " + claim + " has invalid type");
		}
		if (values.size() > MAX_ROLES) {
			throw new InvalidUserAssertionException(Reason.INVALID_USER_CLAIMS, "too many values in " + claim);
		}
		Set<String> result = new LinkedHashSet<>();
		for (Object value : values) {
			String text = singleValue(value);
			if (text == null) {
				throw new InvalidUserAssertionException(Reason.INVALID_USER_CLAIMS, "invalid value in " + claim);
			}
			result.add(text);
		}
		return result;
	}

}
