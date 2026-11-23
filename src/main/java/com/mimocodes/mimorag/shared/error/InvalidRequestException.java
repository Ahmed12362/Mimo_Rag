package com.mimocodes.mimorag.shared.error;

import java.util.List;

import com.mimocodes.mimorag.shared.web.ValidationViolation;

/** Business-level validation failure detected after boundary validation (e.g. cross-field or policy rules). */
public class InvalidRequestException extends RuntimeException {

	private final List<ValidationViolation> violations;

	public InvalidRequestException(String field, String message) {
		this(List.of(new ValidationViolation(field, message)));
	}

	public InvalidRequestException(List<ValidationViolation> violations) {
		super("Invalid request");
		this.violations = List.copyOf(violations);
	}

	public List<ValidationViolation> getViolations() {
		return violations;
	}

}
