package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage;
import com.mimocodes.mimorag.ingestion.application.IngestionProperties;
import com.mimocodes.mimorag.shared.error.PayloadTooLargeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Component;

/**
 * Stores originals on the local file system under {@code mimorag.ingestion.storage-root} (decision D-23).
 * <ul>
 * <li>Keys are accepted only in the exact form {@code <uuid>/<uuid>/<uuid>/original.pdf} and must resolve inside the
 * root, so no client input can ever influence a path.</li>
 * <li>Uploads are streamed into {@code <root>/tmp}, then moved atomically into place (same file system) and made
 * read-only.</li>
 * <li>Temporary files older than an hour (from a crash) are removed at startup.</li>
 * </ul>
 */
@Component
class LocalDocumentStorage implements DocumentStorage {

	private static final Logger log = LoggerFactory.getLogger(LocalDocumentStorage.class);

	static final Pattern KEY = Pattern.compile(
			"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}){2}/original\\.pdf");

	private static final Pattern TEMP_HANDLE = Pattern.compile("upload-[0-9]+\\.part");

	private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private static final int SIGNATURE_WINDOW = 1024;

	private static final Duration STALE_TEMP_AGE = Duration.ofHours(1);

	private final Path root;

	private final Path temp;

	LocalDocumentStorage(IngestionProperties properties) {
		this.root = properties.storageRoot().toAbsolutePath().normalize();
		this.temp = root.resolve("tmp");
		try {
			Files.createDirectories(temp);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create document storage at " + root, ex);
		}
		removeStaleTempFiles();
		log.info("Document storage root: {}", root);
	}

	@Override
	public IncomingFile receive(InputStream content, long maxBytes) {
		Path file;
		try {
			file = Files.createTempFile(temp, "upload-", ".part");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		try (OutputStream out = Files.newOutputStream(file)) {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] head = new byte[SIGNATURE_WINDOW];
			int headLength = 0;
			long size = 0;
			byte[] buffer = new byte[64 * 1024];
			for (int read = content.read(buffer); read != -1; read = content.read(buffer)) {
				size += read;
				if (size > maxBytes) {
					throw new PayloadTooLargeException(
							"The file exceeds the maximum upload size of " + maxBytes + " bytes.");
				}
				if (headLength < SIGNATURE_WINDOW) {
					int copy = Math.min(read, SIGNATURE_WINDOW - headLength);
					System.arraycopy(buffer, 0, head, headLength, copy);
					headLength += copy;
				}
				digest.update(buffer, 0, read);
				out.write(buffer, 0, read);
			}
			return new IncomingFile(file.getFileName().toString(), HexFormat.of().formatHex(digest.digest()), size,
					contains(head, headLength, PDF_SIGNATURE));
		}
		catch (IOException ex) {
			deleteQuietly(file);
			throw new UncheckedIOException(ex);
		}
		catch (NoSuchAlgorithmException ex) {
			deleteQuietly(file);
			throw new IllegalStateException(ex);
		}
		catch (RuntimeException ex) {
			deleteQuietly(file);
			throw ex;
		}
	}

	@Override
	public void store(IncomingFile file, String key) {
		Path source = tempPath(file);
		Path target = resolve(key);
		try {
			Files.createDirectories(target.getParent());
			try {
				Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
			}
			catch (AtomicMoveNotSupportedException ex) {
				Files.move(source, target);
			}
			if (!target.toFile().setReadOnly()) {
				log.warn("Could not mark stored object read-only: {}", key);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@Override
	public void discard(IncomingFile file) {
		deleteQuietly(tempPath(file));
	}

	@Override
	public void delete(String key) {
		Path target = resolve(key);
		if (target.toFile().exists() && !target.toFile().setWritable(true)) {
			log.warn("Could not make stored object writable before deleting it: {}", key);
		}
		deleteQuietly(target);
	}

	@Override
	public <T> T withLocalFile(String key, Function<Path, T> work) {
		Path target = resolve(key);
		if (!Files.isRegularFile(target)) {
			throw new StoredObjectMissingException("No stored object for the requested key");
		}
		return work.apply(target);
	}

	/** Resolves a key inside the root; anything else is a programming error, never client input. */
	Path resolve(String key) {
		if (key == null || !KEY.matcher(key).matches()) {
			throw new IllegalArgumentException("Invalid storage key");
		}
		Path resolved = root.resolve(key).normalize();
		if (!resolved.startsWith(root) || resolved.startsWith(temp)) {
			throw new IllegalArgumentException("Storage key escapes the storage root");
		}
		return resolved;
	}

	private Path tempPath(IncomingFile file) {
		if (!TEMP_HANDLE.matcher(file.handle()).matches()) {
			throw new IllegalArgumentException("Invalid temporary handle");
		}
		return temp.resolve(file.handle());
	}

	private void removeStaleTempFiles() {
		FileTime cutoff = FileTime.from(Instant.now().minus(STALE_TEMP_AGE));
		try (DirectoryStream<Path> files = Files.newDirectoryStream(temp, "upload-*.part")) {
			for (Path file : files) {
				if (Files.getLastModifiedTime(file).compareTo(cutoff) < 0) {
					deleteQuietly(file);
				}
			}
		}
		catch (IOException ex) {
			log.warn("Could not clean temporary uploads in {}", temp, ex);
		}
	}

	private static boolean contains(byte[] data, int length, byte[] pattern) {
		outer: for (int i = 0; i + pattern.length <= length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static void deleteQuietly(Path file) {
		try {
			Files.deleteIfExists(file);
		}
		catch (IOException ex) {
			log.warn("Could not delete {}", file.getFileName(), ex);
		}
	}

}
