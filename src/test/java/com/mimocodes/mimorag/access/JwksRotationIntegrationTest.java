package com.mimocodes.mimorag.access;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.JwksTestServer;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import com.mimocodes.mimorag.support.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Key rotation at the integration's JWKS endpoint. Test timings (base class): key cache 2 s, minimum re-fetch
 * interval 1 s. Production defaults are 5 min and 30 s.
 */
class JwksRotationIntegrationTest extends PostgresIntegrationTest {

	private static final Duration WAIT_LIMIT = Duration.ofSeconds(10);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	private ApiTestClient api;

	private String code;

	private String issuer;

	private String apiKey;

	private final RSAKey current = TestTokens.newRsaKey();

	private final RSAKey next = TestTokens.newRsaKey();

	@BeforeEach
	void setUp() throws Exception {
		api = new ApiTestClient(mockMvc, jsonMapper);
		code = ApiTestClient.uniqueCode("jwks");
		UUID integrationId = api.createIntegration(code);
		issuer = "https://" + code + ".example.com";
		String jwksUri = JwksTestServer.instance().publish(code, TestTokens.publicJwks(current));
		api.putTrustConfig(integrationId, Map.of("issuer", issuer, "audience", "mimorag", "jwksUri", jwksUri,
				"allowedAlgorithms", List.of("RS256")));
		apiKey = api.issueKey(integrationId, "KB_READ").plaintext();
	}

	@Test
	void newlyPublishedKeyIsAcceptedAfterARefetch() throws Exception {
		assertThat(statusFor(current)).isEqualTo(200);
		JwksTestServer.instance().publish(code, TestTokens.publicJwks(current, next));

		assertThat(eventuallyStatus(next, 200)).isEqualTo(200);
	}

	@Test
	void removedKeyStopsWorkingAfterTheCacheExpires() throws Exception {
		JwksTestServer.instance().publish(code, TestTokens.publicJwks(current, next));
		assertThat(statusFor(current)).isEqualTo(200);
		JwksTestServer.instance().publish(code, TestTokens.publicJwks(next));

		assertThat(eventuallyStatus(current, 401)).isEqualTo(401);
		assertThat(statusFor(next)).isEqualTo(200);
	}

	@Test
	void burstOfUnknownKeyIdsCausesAtMostOneRefetchPerInterval() throws Exception {
		List<String> forged = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			forged.add(token(TestTokens.newRsaKey()));
		}
		assertThat(statusFor(current)).isEqualTo(200);
		int before = JwksTestServer.instance().fetchCount(code);

		Instant start = Instant.now();
		for (String token : forged) {
			mockMvc.perform(api.asUser(get("/api/v1/integration"), apiKey, token)).andExpect(status().isUnauthorized());
		}
		long elapsedSeconds = Duration.between(start, Instant.now()).toSeconds();

		// At most one re-fetch per 1 s interval (test setting), plus one for a cache expiry during the burst.
		assertThat(JwksTestServer.instance().fetchCount(code) - before).isLessThanOrEqualTo((int) elapsedSeconds + 2);
	}

	@Test
	void unreachableKeyEndpointIsReportedAsServiceUnavailable() throws Exception {
		JwksTestServer.instance().unpublish(code);
		Thread.sleep(2100);

		mockMvc.perform(api.asUser(get("/api/v1/integration"), apiKey, token(current)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.errorCode").value("SERVICE_UNAVAILABLE"));
	}

	@Test
	void weakRsaKeysAreRejectedEvenWhenPublished() throws Exception {
		RSAKey weak = TestTokens.newWeakRsaKey();
		JwksTestServer.instance().publish(code, TestTokens.publicJwks(current, weak));
		Thread.sleep(2100);

		assertThat(statusFor(weak)).isEqualTo(401);
		assertThat(statusFor(current)).isEqualTo(200);
	}

	private String token(RSAKey key) {
		return TestTokens.claims(issuer, "mimorag", "emp-1").signRs256(key);
	}

	private int statusFor(RSAKey key) throws Exception {
		return mockMvc.perform(api.asUser(get("/api/v1/integration"), apiKey, token(key)))
			.andReturn()
			.getResponse()
			.getStatus();
	}

	private int eventuallyStatus(RSAKey key, int expected) throws Exception {
		Instant deadline = Instant.now().plus(WAIT_LIMIT);
		int last;
		do {
			last = statusFor(key);
			if (last == expected) {
				return last;
			}
			Thread.sleep(250);
		}
		while (Instant.now().isBefore(deadline));
		return last;
	}

}
