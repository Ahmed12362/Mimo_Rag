package com.mimocodes.mimorag.access.assertion;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Key-fetching behaviour for user-assertion verification.
 *
 * @param jwksCacheTtl how long fetched public keys are trusted before re-fetching (a key removed from an
 * integration's JWKS stays valid at most this long)
 * @param jwksRefreshMinInterval minimum time between re-fetches triggered by unknown key ids (limits amplification)
 * @param jwksConnectTimeout connect timeout for JWKS requests
 * @param jwksReadTimeout read timeout for JWKS requests
 * @param jwksMaxBytes maximum accepted JWKS document size
 * @param minRsaKeyBits RSA public keys smaller than this are ignored
 */
@ConfigurationProperties("mimorag.security.assertion")
public record UserAssertionProperties(@DefaultValue("PT5M") Duration jwksCacheTtl,
		@DefaultValue("PT30S") Duration jwksRefreshMinInterval, @DefaultValue("PT2S") Duration jwksConnectTimeout,
		@DefaultValue("PT3S") Duration jwksReadTimeout, @DefaultValue("51200") int jwksMaxBytes,
		@DefaultValue("2048") int minRsaKeyBits) {

	public UserAssertionProperties {
		if (jwksRefreshMinInterval.compareTo(jwksCacheTtl) >= 0) {
			throw new IllegalArgumentException("jwks-refresh-min-interval must be shorter than jwks-cache-ttl");
		}
	}

}
