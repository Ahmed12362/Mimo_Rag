package com.mimocodes.mimorag.shared.error;

import com.mimocodes.mimorag.shared.access.Permission;

/**
 * The caller can see the resource but lacks the permission for this operation. Use only when revealing existence is
 * acceptable; otherwise throw {@link ResourceNotFoundException}.
 */
public class OperationNotPermittedException extends RuntimeException {

	private final Permission requiredPermission;

	public OperationNotPermittedException(Permission requiredPermission) {
		super("Operation requires permission " + requiredPermission);
		this.requiredPermission = requiredPermission;
	}

	public OperationNotPermittedException(String message) {
		super(message);
		this.requiredPermission = null;
	}

	public Permission getRequiredPermission() {
		return requiredPermission;
	}

}
