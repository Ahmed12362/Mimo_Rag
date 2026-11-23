package com.mimocodes.mimorag.integration.application;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.integration.domain.TrustConfiguration;
import com.mimocodes.mimorag.integration.domain.TrustConfiguration.TrustSettings;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;
import com.mimocodes.mimorag.shared.web.ValidationViolation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages how each integration's signed user assertions are verified. Configuration is admin-owned; verifiers read
 * it within the integration's own tenant context.
 */
@Service
public class TrustConfigurationService {

	static final Set<String> SUPPORTED_ALGORITHMS = Set.of("RS256", "ES256");

	private static final Pattern CLAIM_NAME = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

	/** JWK members that only private keys (or symmetric keys) carry; public trust material must never contain them. */
	private static final Set<String> PRIVATE_KEY_MEMBERS = Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");

	private static final int MAX_INLINE_JWKS_LENGTH = 20_000;

	private static final String RESOURCE = "trust-configuration";

	private final IntegrationAdminService integrationService;

	private final TrustConfigurationRepository configurations;

	private final TrustPolicyProperties policy;

	private final JsonMapper jsonMapper;

	private final AuditRecorder audit;

	private final Clock clock;

	TrustConfigurationService(IntegrationAdminService integrationService, TrustConfigurationRepository configurations,
			TrustPolicyProperties policy, JsonMapper jsonMapper, AuditRecorder audit, Clock clock) {
		this.integrationService = integrationService;
		this.configurations = configurations;
		this.policy = policy;
		this.jsonMapper = jsonMapper;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional
	public TrustConfigurationView replace(AccessContext access, UUID integrationId, TrustSettings settings) {
		access.requirePlatformAdmin();
		integrationService.load(integrationId);
		validate(settings);
		TrustConfiguration configuration = configurations.findById(integrationId)
			.orElseGet(() -> new TrustConfiguration(integrationId));
		configuration.replace(settings, clock.instant());
		configurations.save(configuration);
		audit.record(AuditEvent.success("TRUST_CONFIGURATION_REPLACED", RESOURCE, integrationId)
			.withDetails(Map.of("issuer", settings.issuer(), "keySource",
					settings.jwksUri() != null ? "JWKS_URI" : "INLINE_JWKS")));
		return TrustConfigurationView.of(configuration);
	}

	@Transactional(readOnly = true)
	public TrustConfigurationView get(AccessContext access, UUID integrationId) {
		access.requirePlatformAdmin();
		return configurations.findById(integrationId)
			.map(TrustConfigurationView::of)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
	}

	/**
	 * Configuration used to verify a user assertion presented by the calling integration. Runs in the caller's
	 * tenant context; RLS guarantees one integration can never read another's trust material.
	 */
	@Transactional(readOnly = true)
	public Optional<TrustConfigurationView> findForVerification(AccessContext access) {
		return configurations.findById(access.requireIntegrationId()).map(TrustConfigurationView::of);
	}

	void validate(TrustSettings settings) {
		List<ValidationViolation> violations = new ArrayList<>();
		requireText(violations, "issuer", settings.issuer(), 500);
		requireText(violations, "audience", settings.audience(), 500);
		boolean hasUri = settings.jwksUri() != null;
		boolean hasInline = settings.inlineJwks() != null;
		if (hasUri == hasInline) {
			violations.add(new ValidationViolation("jwksUri", "exactly one of jwksUri or inlineJwks must be set"));
		}
		if (hasUri) {
			validateJwksUri(violations, settings.jwksUri());
		}
		if (hasInline) {
			validateInlineJwks(violations, settings.inlineJwks());
		}
		List<String> algorithms = settings.allowedAlgorithms();
		if (algorithms == null || algorithms.isEmpty() || !SUPPORTED_ALGORITHMS.containsAll(algorithms)) {
			violations.add(new ValidationViolation("allowedAlgorithms", "must be a non-empty subset of RS256, ES256"));
		}
		if (settings.maxTokenLifetimeSeconds() < 30 || settings.maxTokenLifetimeSeconds() > 3600) {
			violations.add(new ValidationViolation("maxTokenLifetimeSeconds", "must be between 30 and 3600"));
		}
		if (settings.clockSkewSeconds() < 0 || settings.clockSkewSeconds() > 300) {
			violations.add(new ValidationViolation("clockSkewSeconds", "must be between 0 and 300"));
		}
		validateClaimName(violations, "claimUserId", settings.claimUserId(), true);
		validateClaimName(violations, "claimRoles", settings.claimRoles(), false);
		validateClaimName(violations, "claimGroups", settings.claimGroups(), false);
		if (!violations.isEmpty()) {
			throw new InvalidRequestException(violations);
		}
	}

	private void validateJwksUri(List<ValidationViolation> violations, String value) {
		URI uri;
		try {
			uri = URI.create(value);
		}
		catch (IllegalArgumentException ex) {
			violations.add(new ValidationViolation("jwksUri", "must be a valid absolute URI"));
			return;
		}
		boolean httpsOrAllowedHttp = "https".equalsIgnoreCase(uri.getScheme())
				|| (policy.allowInsecureJwks() && "http".equalsIgnoreCase(uri.getScheme()));
		if (!uri.isAbsolute() || uri.getHost() == null || !httpsOrAllowedHttp) {
			violations.add(new ValidationViolation("jwksUri", "must be an absolute https URI"));
		}
		if (uri.getUserInfo() != null || uri.getFragment() != null || value.length() > 2000) {
			violations.add(new ValidationViolation("jwksUri", "must not contain credentials or a fragment"));
		}
	}

	private void validateInlineJwks(List<ValidationViolation> violations, String value) {
		if (!policy.allowInlineJwks()) {
			violations.add(new ValidationViolation("inlineJwks",
					"inline keys are disabled; configure an https jwksUri"));
			return;
		}
		if (value.length() > MAX_INLINE_JWKS_LENGTH) {
			violations.add(new ValidationViolation("inlineJwks", "is too large"));
			return;
		}
		try {
			JsonNode keys = jsonMapper.readTree(value).path("keys");
			if (!keys.isArray() || keys.isEmpty()) {
				violations.add(new ValidationViolation("inlineJwks", "must be a JWK Set with at least one key"));
				return;
			}
			for (JsonNode key : keys) {
				String kty = key.path("kty").asString("");
				if (!"RSA".equals(kty) && !"EC".equals(kty)) {
					violations.add(new ValidationViolation("inlineJwks", "only RSA and EC public keys are allowed"));
				}
				if (PRIVATE_KEY_MEMBERS.stream().anyMatch(key::has)) {
					violations.add(new ValidationViolation("inlineJwks", "must contain public keys only"));
				}
			}
		}
		catch (RuntimeException ex) {
			violations.add(new ValidationViolation("inlineJwks", "must be valid JWK Set JSON"));
		}
	}

	private static void requireText(List<ValidationViolation> violations, String field, String value, int maxLength) {
		if (value == null || value.isBlank()) {
			violations.add(new ValidationViolation(field, "must not be blank"));
		}
		else if (value.length() > maxLength) {
			violations.add(new ValidationViolation(field, "must be at most " + maxLength + " characters"));
		}
	}

	private static void validateClaimName(List<ValidationViolation> violations, String field, String value,
			boolean required) {
		if (value == null) {
			if (required) {
				violations.add(new ValidationViolation(field, "must not be blank"));
			}
			return;
		}
		if (!CLAIM_NAME.matcher(value).matches()) {
			violations.add(new ValidationViolation(field, "must be a simple claim name"));
		}
	}

}
