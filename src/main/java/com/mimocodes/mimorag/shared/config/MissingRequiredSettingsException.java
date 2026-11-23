package com.mimocodes.mimorag.shared.config;

import java.util.List;

public class MissingRequiredSettingsException extends IllegalStateException {

	private final List<String> missingSettings;

	public MissingRequiredSettingsException(List<String> missingSettings) {
		super("Missing required settings: " + String.join(", ", missingSettings));
		this.missingSettings = List.copyOf(missingSettings);
	}

	public List<String> getMissingSettings() {
		return missingSettings;
	}

}
