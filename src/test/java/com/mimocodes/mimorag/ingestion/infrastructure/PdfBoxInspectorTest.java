package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.mimocodes.mimorag.ingestion.application.PdfInspector.PdfRejectedException;
import com.mimocodes.mimorag.support.TestPdfs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PdfBoxInspectorTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	private final PdfBoxInspector inspector = new PdfBoxInspector();

	@TempDir
	Path dir;

	@AfterEach
	void shutDown() {
		inspector.destroy();
	}

	@Test
	void countsPagesOfAValidPdf() throws IOException {
		assertThat(inspector.countPages(write(TestPdfs.withPages(3, "valid")), 500, TIMEOUT)).isEqualTo(3);
	}

	@Test
	void acceptsExactlyTheMaximumPageCount() throws IOException {
		assertThat(inspector.countPages(write(TestPdfs.withPages(5, "limit")), 5, TIMEOUT)).isEqualTo(5);
	}

	@Test
	void rejectsTooManyPages() throws IOException {
		assertRejected(write(TestPdfs.withPages(6, "many")), 5, PdfRejectedException.TOO_MANY_PAGES);
	}

	@Test
	void rejectsPasswordProtectedPdf() throws IOException {
		assertRejected(write(TestPdfs.passwordProtected("locked")), 500, PdfRejectedException.ENCRYPTED);
	}

	/** D-27: also rejected when it opens without a password (owner password only). */
	@Test
	void rejectsOwnerPasswordOnlyPdf() throws IOException {
		assertRejected(write(TestPdfs.ownerPasswordOnly("owner")), 500, PdfRejectedException.ENCRYPTED);
	}

	@Test
	void rejectsCorruptPdf() throws IOException {
		assertRejected(write(TestPdfs.corrupt("broken")), 500, PdfRejectedException.CORRUPT);
	}

	@Test
	void rejectsPdfWithoutPages() throws IOException {
		assertRejected(write(TestPdfs.withoutPages("empty")), 500, PdfRejectedException.EMPTY);
	}

	@Test
	void rejectsArbitraryBytes() throws IOException {
		assertRejected(write(new byte[] { 1, 2, 3, 4, 5 }), 500, PdfRejectedException.CORRUPT);
	}

	private void assertRejected(Path pdf, int maxPages, String code) {
		assertThatThrownBy(() -> inspector.countPages(pdf, maxPages, TIMEOUT))
			.isInstanceOfSatisfying(PdfRejectedException.class, ex -> assertThat(ex.getCode()).isEqualTo(code));
	}

	private Path write(byte[] content) throws IOException {
		Path file = Files.createTempFile(dir, "test-", ".pdf");
		Files.write(file, content);
		return file;
	}

}
