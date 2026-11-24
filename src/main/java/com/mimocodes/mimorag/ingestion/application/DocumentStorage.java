package com.mimocodes.mimorag.ingestion.application;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Storage for original files (decision D-23). The local implementation can be replaced by an S3-compatible one
 * without changing the pipeline: callers never build paths and only use opaque keys.
 * <p>
 * Uploads arrive in two steps: {@link #receive} streams the body into a private temporary area (never fully into
 * memory) while computing its hash; {@link #store} then moves it to its final key, or {@link #discard} drops it.
 */
public interface DocumentStorage {

	/**
	 * A received upload that is not yet stored under a key.
	 *
	 * @param handle opaque temporary handle
	 * @param sha256 lowercase hex SHA-256 of the content
	 * @param startsLikePdf whether the PDF signature {@code %PDF-} appears within the first 1024 bytes
	 */
	record IncomingFile(String handle, String sha256, long sizeBytes, boolean startsLikePdf) {
	}

	/**
	 * Streams the content to temporary storage.
	 *
	 * @throws com.mimocodes.mimorag.shared.error.PayloadTooLargeException if more than {@code maxBytes} arrive
	 * (nothing is kept)
	 */
	IncomingFile receive(InputStream content, long maxBytes);

	/** Moves a received file to its final key; the stored object is never modified afterwards. */
	void store(IncomingFile file, String key);

	/** Drops a received file that will not be stored. Safe to call more than once. */
	void discard(IncomingFile file);

	/** Removes a stored object (compensation when the database transaction did not commit). */
	void delete(String key);

	/**
	 * Gives {@code work} a local, read-only file with the object's content.
	 *
	 * @throws StoredObjectMissingException if no object exists for the key
	 */
	<T> T withLocalFile(String key, Function<Path, T> work);

	/** The storage key for the original file of a document version. Built only from identifiers. */
	static String originalKey(java.util.UUID integrationId, java.util.UUID documentId, java.util.UUID versionId) {
		return integrationId + "/" + documentId + "/" + versionId + "/original.pdf";
	}

	/** No object is stored under the requested key. */
	class StoredObjectMissingException extends RuntimeException {

		public StoredObjectMissingException(String message) {
			super(message);
		}

	}

}
