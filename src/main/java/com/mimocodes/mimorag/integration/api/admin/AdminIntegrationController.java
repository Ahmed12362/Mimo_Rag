package com.mimocodes.mimorag.integration.api.admin;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.mimocodes.mimorag.integration.api.admin.AdminIntegrationRequests.ChangeStatusRequest;
import com.mimocodes.mimorag.integration.api.admin.AdminIntegrationRequests.AllowedScopesRequest;
import com.mimocodes.mimorag.integration.api.admin.AdminIntegrationRequests.IssueBootstrapKeyRequest;
import com.mimocodes.mimorag.integration.api.admin.AdminIntegrationRequests.RegisterIntegrationRequest;
import com.mimocodes.mimorag.integration.api.admin.AdminIntegrationRequests.TrustConfigurationRequest;
import com.mimocodes.mimorag.integration.application.ApiKeyService;
import com.mimocodes.mimorag.integration.application.ApiKeyView;
import com.mimocodes.mimorag.integration.application.IntegrationAdminService;
import com.mimocodes.mimorag.integration.application.IntegrationAdminService.RegisterIntegrationCommand;
import com.mimocodes.mimorag.integration.application.IntegrationView;
import com.mimocodes.mimorag.integration.application.IssuedApiKey;
import com.mimocodes.mimorag.integration.application.TrustConfigurationService;
import com.mimocodes.mimorag.integration.application.TrustConfigurationView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.web.CurrentAccess;
import com.mimocodes.mimorag.shared.web.PageQuery;
import com.mimocodes.mimorag.shared.web.PageResponse;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Platform-admin plane: integration registration, credentials and user-assertion trust configuration. */
@RestController
@RequestMapping("/api/v1/admin/integrations")
class AdminIntegrationController {

	private final IntegrationAdminService integrations;

	private final ApiKeyService apiKeys;

	private final TrustConfigurationService trustConfigurations;

	AdminIntegrationController(IntegrationAdminService integrations, ApiKeyService apiKeys,
			TrustConfigurationService trustConfigurations) {
		this.integrations = integrations;
		this.apiKeys = apiKeys;
		this.trustConfigurations = trustConfigurations;
	}

	@PostMapping
	ResponseEntity<IntegrationView> register(@CurrentAccess AccessContext access,
			@Valid @RequestBody RegisterIntegrationRequest request) {
		IntegrationView created = integrations.register(access,
				new RegisterIntegrationCommand(request.code(), request.name(), request.description(),
						request.allowedScopes()));
		return ResponseEntity.created(URI.create("/api/v1/admin/integrations/" + created.id())).body(created);
	}

	@GetMapping
	PageResponse<IntegrationView> list(@CurrentAccess AccessContext access,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
		return PageResponse.from(
				integrations.list(access, PageQuery.of(page, size, Sort.by("createdAt", "id"))), view -> view);
	}

	@GetMapping("/{integrationId}")
	IntegrationView get(@CurrentAccess AccessContext access, @PathVariable UUID integrationId) {
		return integrations.get(access, integrationId);
	}

	@PatchMapping("/{integrationId}/status")
	IntegrationView changeStatus(@CurrentAccess AccessContext access, @PathVariable UUID integrationId,
			@Valid @RequestBody ChangeStatusRequest request) {
		return integrations.changeStatus(access, integrationId, request.status());
	}

	@PutMapping("/{integrationId}/allowed-scopes")
	IntegrationView changeAllowedScopes(@CurrentAccess AccessContext access, @PathVariable UUID integrationId,
			@Valid @RequestBody AllowedScopesRequest request) {
		return integrations.changeAllowedScopes(access, integrationId, request.allowedScopes());
	}

	/**
	 * Hands an integration over: the returned key can only create the integration's first own key (scope
	 * KEY_ROTATE, 24 hours, single use). Admins cannot create keys that reach tenant content.
	 */
	@PostMapping("/{integrationId}/bootstrap-keys")
	ResponseEntity<IssuedApiKey> issueBootstrapKey(@CurrentAccess AccessContext access,
			@PathVariable UUID integrationId, @Valid @RequestBody IssueBootstrapKeyRequest request) {
		IssuedApiKey issued = apiKeys.issueBootstrap(access, integrationId, request.name());
		return ResponseEntity
			.created(URI.create("/api/v1/admin/integrations/" + integrationId + "/api-keys/" + issued.key().keyId()))
			.body(issued);
	}

	@GetMapping("/{integrationId}/api-keys")
	List<ApiKeyView> listApiKeys(@CurrentAccess AccessContext access, @PathVariable UUID integrationId) {
		return apiKeys.list(access, integrationId);
	}

	@DeleteMapping("/{integrationId}/api-keys/{keyId}")
	ApiKeyView revokeApiKey(@CurrentAccess AccessContext access, @PathVariable UUID integrationId,
			@PathVariable String keyId) {
		return apiKeys.revoke(access, integrationId, keyId);
	}

	@PutMapping("/{integrationId}/trust-config")
	TrustConfigurationView replaceTrustConfiguration(@CurrentAccess AccessContext access,
			@PathVariable UUID integrationId, @Valid @RequestBody TrustConfigurationRequest request) {
		return trustConfigurations.replace(access, integrationId, request.toSettings());
	}

	@GetMapping("/{integrationId}/trust-config")
	TrustConfigurationView getTrustConfiguration(@CurrentAccess AccessContext access,
			@PathVariable UUID integrationId) {
		return trustConfigurations.get(access, integrationId);
	}

}
