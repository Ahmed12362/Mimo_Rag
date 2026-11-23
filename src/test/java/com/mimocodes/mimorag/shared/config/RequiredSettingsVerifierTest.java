package com.mimocodes.mimorag.shared.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredSettingsVerifierTest {

	@Test
	void reportsEveryMissingSettingByNameOnly() {
		RequiredSettingsVerifier verifier = new RequiredSettingsVerifier();
		verifier.setEnvironment(new MockEnvironment().withProperty("MIMORAG_DB_APP_PASSWORD", "s3cret-value")
			.withProperty("MIMORAG_DB_OWNER_PASSWORD", " "));

		assertThatThrownBy(() -> verifier.postProcessBeanFactory(new DefaultListableBeanFactory()))
			.isInstanceOf(MissingRequiredSettingsException.class)
			.hasMessage("Missing required settings: MIMORAG_DB_URL, MIMORAG_DB_OWNER_PASSWORD")
			.hasMessageNotContaining("s3cret-value");
	}

	@Test
	void passesWhenAllSettingsArePresent() {
		RequiredSettingsVerifier verifier = new RequiredSettingsVerifier();
		verifier.setEnvironment(new MockEnvironment().withProperty("MIMORAG_DB_URL", "jdbc:postgresql://db/x")
			.withProperty("MIMORAG_DB_APP_PASSWORD", "a")
			.withProperty("MIMORAG_DB_OWNER_PASSWORD", "b"));

		assertThatCode(() -> verifier.postProcessBeanFactory(new DefaultListableBeanFactory()))
			.doesNotThrowAnyException();
	}

	@Test
	void failureAnalysisExplainsHowToFixTheProblem() {
		MissingRequiredSettingsException failure = new MissingRequiredSettingsException(List.of("MIMORAG_DB_URL"));

		FailureAnalysis analysis = new MissingRequiredSettingsFailureAnalyzer().analyze(failure);

		assertThat(analysis.getDescription()).contains("MIMORAG_DB_URL");
		assertThat(analysis.getAction()).contains(".env.example");
	}

}
