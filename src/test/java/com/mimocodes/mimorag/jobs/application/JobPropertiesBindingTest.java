package com.mimocodes.mimorag.jobs.application;

import java.util.Map;

import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/** Worker counts bind from the documented property spellings and from environment-style names. */
class JobPropertiesBindingTest {

	@Test
	void workerCountsBindFromKebabCaseProperty() {
		assertThat(bind(Map.of("mimorag.jobs.workers.extract-document", "3")).workersFor("EXTRACT_DOCUMENT"))
			.isEqualTo(3);
	}

	@Test
	void workerCountsBindFromBracketNotation() {
		assertThat(bind(Map.of("mimorag.jobs.workers[EXTRACT_DOCUMENT]", "4")).workersFor("EXTRACT_DOCUMENT"))
			.isEqualTo(4);
	}

	@Test
	void defaultsApplyWithoutConfiguration() {
		JobProperties properties = bind(Map.of());
		assertThat(properties.workersFor("EXTRACT_DOCUMENT")).isEqualTo(2);
		assertThat(properties.workersFor("UNKNOWN_KIND")).isEqualTo(1);
		assertThat(properties.maxAttempts()).isEqualTo(3);
	}

	private static JobProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
			.bindOrCreate("mimorag.jobs", JobProperties.class);
	}

}
