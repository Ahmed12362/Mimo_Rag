package com.mimocodes.mimorag.integration.application;

import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.integration.domain.Integration;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/**
 * Persistence port for integrations, implemented by Spring Data JPA. Row visibility is additionally restricted by
 * PostgreSQL RLS (own integration, or all rows for platform admins).
 */
public interface IntegrationRepository extends Repository<Integration, UUID> {

	Integration save(Integration integration);

	Integration saveAndFlush(Integration integration);

	Optional<Integration> findById(UUID id);

	boolean existsByCode(String code);

	Page<Integration> findAll(Pageable pageable);

}
