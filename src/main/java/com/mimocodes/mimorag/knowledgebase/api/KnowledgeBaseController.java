package com.mimocodes.mimorag.knowledgebase.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService.CreateKnowledgeBaseCommand;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService.RoleGrant;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseService.UpdateKnowledgeBaseCommand;
import com.mimocodes.mimorag.knowledgebase.application.KnowledgeBaseView;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBasePolicies;
import com.mimocodes.mimorag.knowledgebase.domain.KnowledgeBaseStatus;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.web.CurrentAccess;
import com.mimocodes.mimorag.shared.web.PageQuery;
import com.mimocodes.mimorag.shared.web.PageResponse;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integration plane: Knowledge Bases of the calling integration. There is no integration id in the path; the
 * tenant is derived from the verified credential only.
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
class KnowledgeBaseController {

	private final KnowledgeBaseService knowledgeBases;

	KnowledgeBaseController(KnowledgeBaseService knowledgeBases) {
		this.knowledgeBases = knowledgeBases;
	}

	record CreateKnowledgeBaseRequest(
			@NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,63}$", message = "must be 2-64 lowercase letters, digits or hyphens, starting with a letter") String code,
			@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description, Boolean allowServiceSearch,
			Boolean requireDifferentSubmitter) {
	}

	record UpdateKnowledgeBaseRequest(@NotNull Long version, @Size(min = 1, max = 200) String name,
			@Size(max = 2000) String description, Boolean allowServiceSearch, Boolean requireDifferentSubmitter,
			KnowledgeBaseStatus status) {
	}

	record RoleGrantRequest(@NotBlank @Size(max = 200) String role, @NotNull Permission permission) {
	}

	record ReplaceGrantsRequest(@NotNull @Size(max = 200) List<@Valid @NotNull RoleGrantRequest> grants) {
	}

	record GrantsResponse(List<RoleGrant> grants) {
	}

	@PostMapping
	ResponseEntity<KnowledgeBaseView> create(@CurrentAccess AccessContext access,
			@Valid @RequestBody CreateKnowledgeBaseRequest request) {
		KnowledgeBaseView created = knowledgeBases.create(access,
				new CreateKnowledgeBaseCommand(request.code(), request.name(), request.description(),
						new KnowledgeBasePolicies(Boolean.TRUE.equals(request.allowServiceSearch()),
								Boolean.TRUE.equals(request.requireDifferentSubmitter()))));
		return ResponseEntity.created(URI.create("/api/v1/knowledge-bases/" + created.id())).body(created);
	}

	@GetMapping
	PageResponse<KnowledgeBaseView> list(@CurrentAccess AccessContext access,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
		return PageResponse.from(knowledgeBases.list(access, PageQuery.of(page, size, Sort.by("createdAt", "id"))),
				view -> view);
	}

	@GetMapping("/{knowledgeBaseId}")
	KnowledgeBaseView get(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId) {
		return knowledgeBases.get(access, knowledgeBaseId);
	}

	@PatchMapping("/{knowledgeBaseId}")
	KnowledgeBaseView update(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId,
			@Valid @RequestBody UpdateKnowledgeBaseRequest request) {
		return knowledgeBases.update(access, knowledgeBaseId,
				new UpdateKnowledgeBaseCommand(request.version(), request.name(), request.description(),
						request.allowServiceSearch(), request.requireDifferentSubmitter(), request.status()));
	}

	@GetMapping("/{knowledgeBaseId}/role-grants")
	GrantsResponse grants(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId) {
		return new GrantsResponse(knowledgeBases.listGrants(access, knowledgeBaseId));
	}

	@PutMapping("/{knowledgeBaseId}/role-grants")
	GrantsResponse replaceGrants(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId,
			@Valid @RequestBody ReplaceGrantsRequest request) {
		List<RoleGrant> grants = request.grants()
			.stream()
			.map(grant -> new RoleGrant(grant.role(), grant.permission()))
			.toList();
		return new GrantsResponse(knowledgeBases.replaceGrants(access, knowledgeBaseId, grants));
	}

}
