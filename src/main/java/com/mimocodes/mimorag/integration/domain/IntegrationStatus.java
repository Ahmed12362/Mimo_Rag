package com.mimocodes.mimorag.integration.domain;

public enum IntegrationStatus {

	/** Credentials of the integration are accepted. */
	ACTIVE,

	/** All credentials of the integration are rejected; data is retained. */
	SUSPENDED

}
