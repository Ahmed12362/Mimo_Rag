package com.mimocodes.mimorag.shared.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns {@link MissingRequiredSettingsException} into Spring Boot's "APPLICATION FAILED TO START" report.
 */
class MissingRequiredSettingsFailureAnalyzer extends AbstractFailureAnalyzer<MissingRequiredSettingsException> {

	@Override
	protected FailureAnalysis analyze(Throwable rootFailure, MissingRequiredSettingsException cause) {
		String description = "MimoRag cannot start because these required settings are not set: "
				+ String.join(", ", cause.getMissingSettings());
		String action = """
				Provide them as environment variables. For local development, copy .env.example to .env, \
				fill in the values, and start with --spring.profiles.active=local (see README.md).""";
		return new FailureAnalysis(description, action, cause);
	}

}
