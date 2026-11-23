package com.mimocodes.mimorag.shared.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata. The documentation endpoints themselves are disabled by default and enabled only in the
 * {@code local} profile (see {@code application-local.yml}).
 */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

	@Bean
	OpenAPI mimoRagOpenApi() {
		return new OpenAPI().info(new Info().title("MimoRag Platform API")
			.version("v1")
			.description("Generic, multi-tenant RAG platform API. Errors use RFC 9457 Problem Details "
					+ "extended with errorCode and correlationId."));
	}

}
