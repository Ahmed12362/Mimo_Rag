package com.mimocodes.mimorag.shared.web;

import com.mimocodes.mimorag.shared.error.InvalidRequestException;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * Validated pagination input. Sorting is fixed per endpoint (not client-controlled) to keep queries index-backed and
 * to avoid exposing internal property names.
 */
public final class PageQuery {

	public static final int DEFAULT_SIZE = 20;

	public static final int MAX_SIZE = 100;

	private PageQuery() {
	}

	public static PageRequest of(int page, int size, Sort sort) {
		if (page < 0) {
			throw new InvalidRequestException("page", "must be greater than or equal to 0");
		}
		if (size < 1 || size > MAX_SIZE) {
			throw new InvalidRequestException("size", "must be between 1 and " + MAX_SIZE);
		}
		return PageRequest.of(page, size, sort);
	}

}
