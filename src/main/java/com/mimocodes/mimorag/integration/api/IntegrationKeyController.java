package com.mimocodes.mimorag.integration.api;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.mimocodes.mimorag.integration.application.ApiKeyView;
import com.mimocodes.mimorag.integration.application.IssuedApiKey;
import com.mimocodes.mimorag.integration.application.SelfServiceKeyService;
import com.mimocodes.mimorag.integration.application.SelfServiceKeyService.CreateKeyCommand;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.access.Permission;
import com.mimocodes.mimorag.shared.web.CurrentAccess;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Integration plane: the integration manages its own API keys (requires the KEY_ROTATE scope). A bootstrap key may
 * call {@code POST} exactly once, which also revokes the bootstrap key.
 */
@RestController
@RequestMapping(IntegrationKeyController.PATH)
class IntegrationKeyController {

	static final String PATH = "/api/v1/integration/api-keys";

	private final SelfServiceKeyService keys;

	IntegrationKeyController(SelfServiceKeyService keys) {
		this.keys = keys;
	}

	/**
	 * @param validityDays 1–365, default 90
	 * @param knowledgeBaseIds optional: restrict the key to these Knowledge Bases
	 */
	record CreateKeyRequest(@NotBlank @Size(max = 100) String name,
			@NotEmpty @Size(max = 10) Set<@NotNull Permission> scopes, Integer validityDays,
			@Size(max = 100) Set<@NotNull UUID> knowledgeBaseIds) {
	}

	@PostMapping
	ResponseEntity<IssuedApiKey> create(@CurrentAccess AccessContext access,
			@Valid @RequestBody CreateKeyRequest request) {
		IssuedApiKey issued = keys.create(access, new CreateKeyCommand(request.name(), request.scopes(),
				request.validityDays(), request.knowledgeBaseIds()));
		return ResponseEntity.created(URI.create(PATH + "/" + issued.key().keyId())).body(issued);
	}

	@GetMapping
	List<ApiKeyView> list(@CurrentAccess AccessContext access) {
		return keys.list(access);
	}

	@DeleteMapping("/{keyId}")
	ApiKeyView revoke(@CurrentAccess AccessContext access, @PathVariable String keyId) {
		return keys.revoke(access, keyId);
	}

}
