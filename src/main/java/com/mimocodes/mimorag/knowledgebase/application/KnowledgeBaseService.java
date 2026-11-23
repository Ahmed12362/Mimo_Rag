package com.mimocodes.mimorag.knowledgebase.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBase;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBasePolicies;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseRoleGrant;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import com.mimocodes.mimorag.shared.error.ConflictException;
import com.mimocodes.mimorag.shared.error.InvalidRequestException;
import com.mimocodes.mimorag.shared.error.OperationNotPermittedException;
import com.mimocodes.mimorag.shared.error.ResourceNotFoundException;
import com.mimocodes.mimorag.shared.web.ValidationViolation;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Knowledge Base use cases for the integration plane. The tenant always comes from the verified
 * {@link AccessContext}; IDs from another tenant behave exactly like non-existent IDs.
 */
@Service
public class KnowledgeBaseService {

	static final int MAX_GRANTS = 200;

	private static final Pattern ROLE_FORMAT = Pattern.compile("[^\\p{Cntrl}]{1,200}");

	private static final String RESOURCE = "knowledge-base";

	private final KnowledgeBaseRepository knowledgeBases;

	private final KnowledgeBaseRoleGrantRepository grants;

	private final AuditRecorder audit;

	private final Clock clock;

	KnowledgeBaseService(KnowledgeBaseRepository knowledgeBases, KnowledgeBaseRoleGrantRepository grants,
			AuditRecorder audit, Clock clock) {
		this.knowledgeBases = knowledgeBases;
		this.grants = grants;
		this.audit = audit;
		this.clock = clock;
	}

	public record CreateKnowledgeBaseCommand(String code, String name, String description,
			KnowledgeBasePolicies policies) {
	}

	/** Partial update; {@code null} fields are left unchanged. {@code expectedVersion} is mandatory. */
	public record UpdateKnowledgeBaseCommand(long expectedVersion, String name, String description,
			Boolean allowServiceSearch, Boolean requireDifferentSubmitter, KnowledgeBaseStatus status) {
	}

	public record RoleGrant(String role, Permission permission) {
	}

	@Transactional
	public KnowledgeBaseView create(AccessContext access, CreateKnowledgeBaseCommand command) {
		UUID integrationId = access.requireIntegrationId();
		if (!access.isIntegrationService() || !access.hasScope(Permission.KB_MANAGE)) {
			audit.recordIndependently(AuditEvent.denied("KNOWLEDGE_BASE_CREATE", RESOURCE, null));
			throw new OperationNotPermittedException(Permission.KB_MANAGE);
		}
		if (knowledgeBases.existsByIntegrationIdAndCode(integrationId, command.code())) {
			throw new ConflictException("A knowledge base with this code already exists.");
		}
		KnowledgeBase kb = KnowledgeBase.create(integrationId, command.code(), command.name(), command.description(),
				command.policies(), access.principalRef(), clock.instant());
		try {
			knowledgeBases.save(kb);
		}
		catch (DataIntegrityViolationException ex) {
			throw new ConflictException("A knowledge base with this code already exists.");
		}
		audit.record(AuditEvent.success("KNOWLEDGE_BASE_CREATED", RESOURCE, kb.getId())
			.withDetails(Map.of("code", kb.getCode())));
		return KnowledgeBaseView.of(kb, KnowledgeBaseAccessPolicy.effectivePermissions(access, Set.of()));
	}

	@Transactional(readOnly = true)
	public Page<KnowledgeBaseView> list(AccessContext access, Pageable pageable) {
		UUID integrationId = access.requireIntegrationId();
		if (access.isEndUser()) {
			Set<Permission> visibleWith = KnowledgeBaseAccessPolicy.visibilityPermissions(access);
			if (access.roles().isEmpty() || visibleWith.isEmpty()) {
				return Page.empty(pageable);
			}
			return knowledgeBases.findVisibleToRoles(integrationId, access.roles(), visibleWith, pageable)
				.map(kb -> KnowledgeBaseView.of(kb, permissionsOn(access, kb)));
		}
		Set<Permission> servicePermissions = KnowledgeBaseAccessPolicy.effectivePermissions(access, Set.of());
		if (servicePermissions.isEmpty()) {
			return Page.empty(pageable);
		}
		return knowledgeBases.findAllByIntegrationId(integrationId, pageable)
			.map(kb -> KnowledgeBaseView.of(kb, servicePermissions));
	}

	@Transactional(readOnly = true)
	public KnowledgeBaseView get(AccessContext access, UUID knowledgeBaseId) {
		KnowledgeBase kb = loadVisible(access, knowledgeBaseId);
		return KnowledgeBaseView.of(kb, permissionsOn(access, kb));
	}

	@Transactional
	public KnowledgeBaseView update(AccessContext access, UUID knowledgeBaseId, UpdateKnowledgeBaseCommand command) {
		KnowledgeBase kb = loadVisible(access, knowledgeBaseId);
		Set<Permission> permissions = requirePermission(access, kb, Permission.KB_MANAGE, "KNOWLEDGE_BASE_UPDATE");
		if (kb.getVersion() == null || kb.getVersion() != command.expectedVersion()) {
			throw new ConflictException(
					"The knowledge base was modified concurrently. Reload it and retry with the current version.");
		}
		Instant now = clock.instant();
		List<String> changed = new ArrayList<>();
		if (command.name() != null) {
			kb.rename(command.name(), now);
			changed.add("name");
		}
		if (command.description() != null) {
			kb.describe(command.description(), now);
			changed.add("description");
		}
		if (command.allowServiceSearch() != null || command.requireDifferentSubmitter() != null) {
			KnowledgeBasePolicies current = kb.policies();
			kb.applyPolicies(new KnowledgeBasePolicies(
					command.allowServiceSearch() != null ? command.allowServiceSearch() : current.allowServiceSearch(),
					command.requireDifferentSubmitter() != null ? command.requireDifferentSubmitter()
							: current.requireDifferentSubmitter()),
					now);
			changed.add("policies");
		}
		if (command.status() != null && command.status() != kb.getStatus()) {
			kb.changeStatus(command.status(), now);
			changed.add("status");
		}
		knowledgeBases.saveAndFlush(kb);
		audit.record(AuditEvent.success("KNOWLEDGE_BASE_UPDATED", RESOURCE, kb.getId())
			.withDetails(Map.of("changedFields", changed)));
		return KnowledgeBaseView.of(kb, permissions);
	}

	@Transactional(readOnly = true)
	public List<RoleGrant> listGrants(AccessContext access, UUID knowledgeBaseId) {
		KnowledgeBase kb = loadVisible(access, knowledgeBaseId);
		requirePermission(access, kb, Permission.KB_MANAGE, "KNOWLEDGE_BASE_GRANTS_READ");
		return grants.findAllByIntegrationIdAndKnowledgeBaseIdOrderByRoleAscPermissionAsc(kb.getIntegrationId(),
				kb.getId())
			.stream()
			.map(grant -> new RoleGrant(grant.getRole(), grant.getPermission()))
			.toList();
	}

	@Transactional
	public List<RoleGrant> replaceGrants(AccessContext access, UUID knowledgeBaseId, List<RoleGrant> requested) {
		KnowledgeBase kb = loadVisible(access, knowledgeBaseId);
		requirePermission(access, kb, Permission.KB_MANAGE, "KNOWLEDGE_BASE_GRANTS_REPLACE");
		Set<RoleGrant> normalized = validateGrants(requested);
		Instant now = clock.instant();
		grants.deleteAllForKnowledgeBase(kb.getIntegrationId(), kb.getId());
		normalized.forEach(grant -> grants.save(new KnowledgeBaseRoleGrant(kb, grant.role(), grant.permission(), now)));
		audit.record(AuditEvent.success("KNOWLEDGE_BASE_GRANTS_REPLACED", RESOURCE, kb.getId())
			.withDetails(Map.of("grantCount", normalized.size())));
		return List.copyOf(normalized);
	}

	private KnowledgeBase loadVisible(AccessContext access, UUID knowledgeBaseId) {
		UUID integrationId = access.requireIntegrationId();
		KnowledgeBase kb = knowledgeBases.findByIdAndIntegrationId(knowledgeBaseId, integrationId)
			.orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
		if (permissionsOn(access, kb).isEmpty()) {
			throw new ResourceNotFoundException(RESOURCE);
		}
		return kb;
	}

	private Set<Permission> requirePermission(AccessContext access, KnowledgeBase kb, Permission required,
			String action) {
		Set<Permission> permissions = permissionsOn(access, kb);
		if (!permissions.contains(required)) {
			audit.recordIndependently(AuditEvent.denied(action, RESOURCE, kb.getId()));
			throw new OperationNotPermittedException(required);
		}
		return permissions;
	}

	private Set<Permission> permissionsOn(AccessContext access, KnowledgeBase kb) {
		if (!access.isEndUser()) {
			return KnowledgeBaseAccessPolicy.effectivePermissions(access, Set.of());
		}
		if (access.roles().isEmpty()) {
			return Set.of();
		}
		List<Permission> granted = grants
			.findAllByIntegrationIdAndKnowledgeBaseIdAndRoleIn(kb.getIntegrationId(), kb.getId(), access.roles())
			.stream()
			.map(KnowledgeBaseRoleGrant::getPermission)
			.toList();
		return KnowledgeBaseAccessPolicy.effectivePermissions(access, granted);
	}

	private static Set<RoleGrant> validateGrants(List<RoleGrant> requested) {
		if (requested == null) {
			throw new InvalidRequestException("grants", "must not be null");
		}
		if (requested.size() > MAX_GRANTS) {
			throw new InvalidRequestException("grants", "must contain at most " + MAX_GRANTS + " entries");
		}
		List<ValidationViolation> violations = new ArrayList<>();
		Set<RoleGrant> normalized = new LinkedHashSet<>();
		for (int i = 0; i < requested.size(); i++) {
			RoleGrant grant = requested.get(i);
			if (grant == null || grant.role() == null || !ROLE_FORMAT.matcher(grant.role()).matches()
					|| grant.role().isBlank()) {
				violations.add(new ValidationViolation("grants[" + i + "].role",
						"must be 1-200 characters without control characters"));
				continue;
			}
			if (grant.permission() == null || !Permission.KNOWLEDGE_BASE_PERMISSIONS.contains(grant.permission())) {
				violations.add(new ValidationViolation("grants[" + i + "].permission",
						"must be one of " + Permission.KNOWLEDGE_BASE_PERMISSIONS));
				continue;
			}
			normalized.add(grant);
		}
		if (!violations.isEmpty()) {
			throw new InvalidRequestException(violations);
		}
		return normalized;
	}

}
