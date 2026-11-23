package com.mimocodes.mimorag.shared.persistence;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
class PersistenceConfiguration {

	/** Replaces Spring Boot's default JPA transaction manager (it backs off when this bean exists). */
	@Bean
	PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
		return new TenantAwareJpaTransactionManager(entityManagerFactory);
	}

}
