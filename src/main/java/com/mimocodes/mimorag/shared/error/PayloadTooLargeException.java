package com.mimocodes.mimorag.shared.error;

/** The request body exceeds a configured size limit. */
public class PayloadTooLargeException extends RuntimeException {

	public PayloadTooLargeException(String message) {
		super(message);
	}

}
