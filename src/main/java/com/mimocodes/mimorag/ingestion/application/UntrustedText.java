package com.mimocodes.mimorag.ingestion.application;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Cleans short, client-supplied labels (file names, titles) before they are stored. They are metadata only: never
 * used to build paths, and never interpreted. Control characters and format characters (which include bidi
 * overrides and zero-width characters, a common way to disguise names) are removed.
 */
final class UntrustedText {

	static final String DEFAULT_FILENAME = "document.pdf";

	private static final Pattern INVISIBLE = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Co}\\p{Cn}]");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private UntrustedText() {
	}

	/** The last path segment of a client file name, cleaned and at most 255 characters. */
	static String filename(String original) {
		if (original == null) {
			return DEFAULT_FILENAME;
		}
		String name = original;
		int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
		if (separator >= 0) {
			name = name.substring(separator + 1);
		}
		name = clean(name, 255);
		if (name.isEmpty() || name.equals(".") || name.equals("..")) {
			return DEFAULT_FILENAME;
		}
		return name;
	}

	/** A display title: the given one, or the file name without its extension. At most 200 characters. */
	static String title(String requested, String filename) {
		String title = requested == null ? "" : clean(requested, 200);
		if (!title.isEmpty()) {
			return title;
		}
		int dot = filename.lastIndexOf('.');
		String base = dot > 0 ? filename.substring(0, dot) : filename;
		return clean(base, 200).isEmpty() ? "Untitled document" : clean(base, 200);
	}

	private static String clean(String value, int maxLength) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
		normalized = INVISIBLE.matcher(normalized).replaceAll("");
		normalized = WHITESPACE.matcher(normalized).replaceAll(" ").strip();
		if (normalized.codePointCount(0, normalized.length()) > maxLength) {
			normalized = normalized.substring(0, normalized.offsetByCodePoints(0, maxLength)).strip();
		}
		return normalized;
	}

}
