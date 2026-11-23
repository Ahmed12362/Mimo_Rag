package com.mimocodes.mimorag.shared.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** A single injectable clock, so time-dependent rules (expiry, token lifetime) are testable. */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
