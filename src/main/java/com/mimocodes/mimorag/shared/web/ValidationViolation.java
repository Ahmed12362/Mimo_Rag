package com.mimocodes.mimorag.shared.web;

/**
 * A single invalid input field. The rejected value is intentionally not echoed back, because it may contain
 * sensitive data or content that should not be reflected to clients or logs.
 */
public record ValidationViolation(String field, String message) {
}
