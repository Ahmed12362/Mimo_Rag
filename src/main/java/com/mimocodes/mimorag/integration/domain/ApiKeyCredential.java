package com.mimocodes.mimorag.integration.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Format, generation and verification of integration API keys: {@code mrk_<keyId>_<secret>}.
 * <ul>
 * <li>{@code keyId}: 24 lowercase hex characters (96 random bits), public, used to look the key up and in audit.</li>
 * <li>{@code secret}: 43 base64url characters (256 random bits). Only its SHA-256 is stored. A fast hash is
 * appropriate because the secret is high-entropy random data, not a human password.</li>
 * </ul>
 */
public final class ApiKeyCredential {

	public static final String PREFIX = "mrk";

	private static final int KEY_ID_BYTES = 12;

	private static final int SECRET_BYTES = 32;

	private static final int MAX_PRESENTED_LENGTH = 128;

	private static final Pattern KEY_ID_FORMAT = Pattern.compile("[a-f0-9]{24}");

	private static final Pattern SECRET_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

	private static final SecureRandom RANDOM = new SecureRandom();

	private ApiKeyCredential() {
	}

	/** A freshly generated key. {@code plaintext} must be shown to the caller once and never stored. */
	public record Generated(String keyId, String plaintext, String secretHash, String last4) {

		@Override
		public String toString() {
			return "Generated[keyId=" + keyId + ", last4=" + last4 + "]";
		}

	}

	/** A syntactically valid key presented by a caller. */
	public record Presented(String keyId, String secret) {

		@Override
		public String toString() {
			return "Presented[keyId=" + keyId + "]";
		}

	}

	public static Generated generate() {
		byte[] idBytes = new byte[KEY_ID_BYTES];
		byte[] secretBytes = new byte[SECRET_BYTES];
		RANDOM.nextBytes(idBytes);
		RANDOM.nextBytes(secretBytes);
		String keyId = HexFormat.of().formatHex(idBytes);
		String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
		String plaintext = PREFIX + "_" + keyId + "_" + secret;
		return new Generated(keyId, plaintext, hashSecret(secret), secret.substring(secret.length() - 4));
	}

	public static Optional<Presented> parse(String presented) {
		if (presented == null || presented.length() > MAX_PRESENTED_LENGTH) {
			return Optional.empty();
		}
		String[] parts = presented.split("_", 3);
		if (parts.length != 3 || !PREFIX.equals(parts[0]) || !KEY_ID_FORMAT.matcher(parts[1]).matches()
				|| !SECRET_FORMAT.matcher(parts[2]).matches()) {
			return Optional.empty();
		}
		return Optional.of(new Presented(parts[1], parts[2]));
	}

	public static String hashSecret(String secret) {
		return HexFormat.of().formatHex(sha256(secret.getBytes(StandardCharsets.UTF_8)));
	}

	/** Constant-time comparison of a presented secret against a stored hash. */
	public static boolean secretMatches(String secret, String storedHashHex) {
		byte[] presentedHash = sha256(secret.getBytes(StandardCharsets.UTF_8));
		byte[] storedHash = storedHashHex == null ? new byte[32] : HexFormat.of().parseHex(storedHashHex.trim());
		return MessageDigest.isEqual(presentedHash, storedHash);
	}

	private static byte[] sha256(byte[] input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is required by every Java runtime", ex);
		}
	}

}
