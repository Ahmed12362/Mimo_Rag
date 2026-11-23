package com.mimocodes.mimorag.integration.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.ApiKey;

import org.springframework.data.repository.Repository;

public interface ApiKeyRepository extends Repository<ApiKey, UUID> {

	ApiKey save(ApiKey apiKey);

	Optional<ApiKey> findByIntegrationIdAndKeyId(UUID integrationId, String keyId);

	List<ApiKey> findTop200ByIntegrationIdOrderByCreatedAtDesc(UUID integrationId);

}
