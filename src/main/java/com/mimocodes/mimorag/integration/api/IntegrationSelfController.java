package com.mimocodes.mimorag.integration.api;

import com.mimocodes.mimorag.integration.application.IntegrationAdminService;
import com.mimocodes.mimorag.integration.application.IntegrationView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.web.CurrentAccess;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration plane: the calling integration's own record. The tenant comes from the credential, never the URL. */
@RestController
class IntegrationSelfController {

	private final IntegrationAdminService integrations;

	IntegrationSelfController(IntegrationAdminService integrations) {
		this.integrations = integrations;
	}

	@GetMapping("/api/v1/integration")
	IntegrationView own(@CurrentAccess AccessContext access) {
		return integrations.getOwn(access);
	}

}
