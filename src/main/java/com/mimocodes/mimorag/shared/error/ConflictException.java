package com.mimocodes.mimorag.shared.error;

/** The request conflicts with the current state (duplicate code, stale version, invalid state transition). */
public class ConflictException extends RuntimeException {

	public ConflictException(String message) {
		super(message);
	}

}
