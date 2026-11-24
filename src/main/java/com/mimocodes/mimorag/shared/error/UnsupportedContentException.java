package com.mimocodes.mimorag.shared.error;

/** The uploaded content is not of a supported type, judged by its bytes (never by file name or declared type). */
public class UnsupportedContentException extends RuntimeException {

	public UnsupportedContentException(String message) {
		super(message);
	}

}
