package com.mimocodes.mimorag.shared.config;

import java.time.Clock;
import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A single injectable clock, so time-dependent rules (expiry, token lifetime) are testable. It ticks in
 * microseconds, the precision PostgreSQL stores, so timestamps returned by the API always equal the stored values.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
	}

}
