package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage;
import com.mimocodes.mimorag.ingestion.application.DocumentStorage.IncomingFile;
import com.mimocodes.mimorag.ingestion.application.DocumentStorage.StoredObjectMissingException;
import com.mimocodes.mimorag.ingestion.application.IngestionProperties;
import com.mimocodes.mimorag.shared.error.PayloadTooLargeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalDocumentStorageTest {

	@TempDir
	Path root;

	@Test
	void receiveStreamsToTempAndReportsHashSizeAndPdfSignature() throws Exception {
		LocalDocumentStorage storage = storage();
		byte[] content = "%PDF-1.7 hello".getBytes(StandardCharsets.US_ASCII);

		IncomingFile incoming = storage.receive(new ByteArrayInputStream(content), 1024);

		assertThat(incoming.sizeBytes()).isEqualTo(content.length);
		assertThat(incoming.sha256()).isEqualTo(sha256(content));
		assertThat(incoming.startsLikePdf()).isTrue();
		assertThat(tempFiles()).hasSize(1);
	}

	@Test
	void pdfSignatureIsDetectedWithinTheFirstKilobyteOnly() {
		LocalDocumentStorage storage = storage();
		byte[] lateSignature = new byte[2000];
		System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, lateSignature, 1500, 5);
		byte[] earlySignature = new byte[2000];
		System.arraycopy("%PDF-".getBytes(StandardCharsets.US_ASCII), 0, earlySignature, 500, 5);

		assertThat(storage.receive(new ByteArrayInputStream(lateSignature), 4096).startsLikePdf()).isFalse();
		assertThat(storage.receive(new ByteArrayInputStream(earlySignature), 4096).startsLikePdf()).isTrue();
	}

	@Test
	void oversizedUploadIsRejectedAndNothingIsKept() throws IOException {
		LocalDocumentStorage storage = storage();

		assertThatThrownBy(() -> storage.receive(new ByteArrayInputStream(new byte[2048]), 1024))
			.isInstanceOf(PayloadTooLargeException.class);
		assertThat(tempFiles()).isEmpty();
	}

	@Test
	void storeMovesTheFileToItsKeyAndMakesItReadOnly() throws IOException {
		LocalDocumentStorage storage = storage();
		IncomingFile incoming = storage.receive(new ByteArrayInputStream("%PDF-x".getBytes()), 1024);
		String key = DocumentStorage.originalKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		storage.store(incoming, key);

		Path stored = root.resolve(key);
		assertThat(stored).exists().hasContent("%PDF-x");
		assertThat(Files.isWritable(stored)).isFalse();
		assertThat(tempFiles()).isEmpty();
		Path local = storage.withLocalFile(key, path -> path);
		assertThat(local).isEqualTo(stored.toAbsolutePath().normalize());
	}

	@Test
	void deleteRemovesAStoredObject() {
		LocalDocumentStorage storage = storage();
		IncomingFile incoming = storage.receive(new ByteArrayInputStream("%PDF-x".getBytes()), 1024);
		String key = DocumentStorage.originalKey(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		storage.store(incoming, key);

		storage.delete(key);

		assertThat(root.resolve(key)).doesNotExist();
		assertThatThrownBy(() -> storage.withLocalFile(key, path -> path))
			.isInstanceOf(StoredObjectMissingException.class);
	}

	@Test
	void discardRemovesTheTemporaryFileAndIsIdempotent() throws IOException {
		LocalDocumentStorage storage = storage();
		IncomingFile incoming = storage.receive(new ByteArrayInputStream("abc".getBytes()), 1024);

		storage.discard(incoming);
		storage.discard(incoming);

		assertThat(tempFiles()).isEmpty();
	}

	@Test
	void keysOutsideTheStrictFormatAreRefused() {
		LocalDocumentStorage storage = storage();
		UUID id = UUID.randomUUID();

		for (String key : new String[] { "../etc/passwd", id + "/" + id + "/../../x/original.pdf",
				id + "/" + id + "/" + id + "/other.pdf", "tmp/" + id + "/" + id + "/original.pdf",
				"C:/" + id + "/" + id + "/original.pdf", id.toString().toUpperCase() + "/" + id + "/" + id + "/original.pdf" }) {
			assertThatThrownBy(() -> storage.resolve(key)).as(key).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void forgedTemporaryHandlesAreRefused() {
		LocalDocumentStorage storage = storage();

		assertThatThrownBy(() -> storage.discard(new IncomingFile("../../secret", "0".repeat(64), 1, true)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void staleTemporaryFilesAreRemovedAtStartup() throws IOException {
		Path temp = Files.createDirectories(root.resolve("tmp"));
		Path stale = Files.writeString(temp.resolve("upload-1.part"), "old");
		Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(Duration.ofHours(2))));
		Path fresh = Files.writeString(temp.resolve("upload-2.part"), "new");

		storage();

		assertThat(stale).doesNotExist();
		assertThat(fresh).exists();
	}

	private LocalDocumentStorage storage() {
		return new LocalDocumentStorage(new IngestionProperties(root, DataSize.ofMegabytes(50), 500, 20,
				DataSize.ofGigabytes(2), Duration.ofSeconds(30), Duration.ofSeconds(60), Duration.ofSeconds(30), 20));
	}

	private Stream<Path> tempFilesStream() throws IOException {
		return Files.list(root.resolve("tmp"));
	}

	private java.util.List<Path> tempFiles() throws IOException {
		try (Stream<Path> files = tempFilesStream()) {
			return files.toList();
		}
	}

	private static String sha256(byte[] content) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

}
