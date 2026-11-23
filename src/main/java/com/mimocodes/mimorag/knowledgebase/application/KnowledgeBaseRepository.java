package com.mimocodes.mimorag.knowledgebase.application;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBase;
import com.mimocodes.mimorag.shared.access.Permission;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence port for Knowledge Bases. Every method is explicitly tenant-scoped, and PostgreSQL RLS enforces the
 * same boundary independently.
 */
public interface KnowledgeBaseRepository extends Repository<KnowledgeBase, UUID> {

	KnowledgeBase save(KnowledgeBase knowledgeBase);

	/** Flushes immediately, so the incremented optimistic-lock version is visible in the response. */
	KnowledgeBase saveAndFlush(KnowledgeBase knowledgeBase);

	Optional<KnowledgeBase> findByIdAndIntegrationId(UUID id, UUID integrationId);

	boolean existsByIntegrationIdAndCode(UUID integrationId, String code);

	Page<KnowledgeBase> findAllByIntegrationId(UUID integrationId, Pageable pageable);

	@Query(value = """
			select kb from KnowledgeBase kb
			where kb.integrationId = :integrationId
			  and exists (select g.id from KnowledgeBaseRoleGrant g
			              where g.knowledgeBaseId = kb.id and g.role in :roles and g.permission in :permissions)""",
			countQuery = """
					select count(kb) from KnowledgeBase kb
					where kb.integrationId = :integrationId
					  and exists (select g.id from KnowledgeBaseRoleGrant g
					              where g.knowledgeBaseId = kb.id and g.role in :roles and g.permission in :permissions)""")
	Page<KnowledgeBase> findVisibleToRoles(@Param("integrationId") UUID integrationId,
			@Param("roles") Collection<String> roles, @Param("permissions") Collection<Permission> permissions,
			Pageable pageable);

}
