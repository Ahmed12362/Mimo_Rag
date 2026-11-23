package com.mimocodes.mimorag.integration.application;

import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.TrustConfiguration;

import org.springframework.data.repository.Repository;

public interface TrustConfigurationRepository extends Repository<TrustConfiguration, UUID> {

	TrustConfiguration save(TrustConfiguration configuration);

	Optional<TrustConfiguration> findById(UUID integrationId);

}
