package com.mimocodes.mimorag.knowledgebase.application;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseRoleGrant;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface KnowledgeBaseRoleGrantRepository extends Repository<KnowledgeBaseRoleGrant, UUID> {

	KnowledgeBaseRoleGrant save(KnowledgeBaseRoleGrant grant);

	List<KnowledgeBaseRoleGrant> findAllByIntegrationIdAndKnowledgeBaseIdOrderByRoleAscPermissionAsc(
			UUID integrationId, UUID knowledgeBaseId);

	List<KnowledgeBaseRoleGrant> findAllByIntegrationIdAndKnowledgeBaseIdAndRoleIn(UUID integrationId,
			UUID knowledgeBaseId, Collection<String> roles);

	/** Bulk delete executed immediately, so a replacement set can be inserted in the same transaction. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from KnowledgeBaseRoleGrant g where g.integrationId = :integrationId and g.knowledgeBaseId = :kbId")
	int deleteAllForKnowledgeBase(@Param("integrationId") UUID integrationId, @Param("kbId") UUID knowledgeBaseId);

}
