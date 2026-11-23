package com.mimocodes.mimorag.shared.config;

import java.util.List;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fails startup early, with an actionable message, when mandatory settings are missing. Runs before any bean is
 * created, after every property source (environment variables, .env import, test properties) is in place.
 * Only setting names are reported; values are never logged.
 */
@Component
class RequiredSettingsVerifier implements BeanFactoryPostProcessor, EnvironmentAware {

	static final List<String> REQUIRED_SETTINGS = List.of("MIMORAG_DB_URL", "MIMORAG_DB_APP_PASSWORD",
			"MIMORAG_DB_OWNER_PASSWORD");

	private Environment environment;

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
		List<String> missing = REQUIRED_SETTINGS.stream()
			.filter(name -> !StringUtils.hasText(environment.getProperty(name)))
			.toList();
		if (!missing.isEmpty()) {
			throw new MissingRequiredSettingsException(missing);
		}
	}

}
