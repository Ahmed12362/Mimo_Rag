package com.mimocodes.mimorag.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Test PDFs generated at run time with PDFBox (decision: no binary fixtures in the repository). {@code marker} makes
 * the content, and therefore the SHA-256, unique per call site, so tests do not collide on duplicate detection.
 */
public final class TestPdfs {

	private TestPdfs() {
	}

	/** A valid PDF with {@code pages} pages, each with one line of text. */
	public static byte[] withPages(int pages, String marker) {
		try (PDDocument document = new PDDocument()) {
			for (int i = 1; i <= pages; i++) {
				PDPage page = new PDPage();
				document.addPage(page);
				if (i <= 3) {
					try (PDPageContentStream text = new PDPageContentStream(document, page)) {
						text.beginText();
						text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
						text.newLineAtOffset(72, 720);
						text.showText("Page " + i + " " + marker);
						text.endText();
					}
				}
			}
			document.getDocumentInformation().setSubject(marker);
			return save(document);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** Encrypted with a user password: cannot be opened without it. */
	public static byte[] passwordProtected(String marker) {
		return encrypted(marker, "owner-secret", "user-secret");
	}

	/** Encrypted with only an owner password: opens without a password, but restricts permissions (still rejected). */
	public static byte[] ownerPasswordOnly(String marker) {
		return encrypted(marker, "owner-secret", "");
	}

	/** A file that starts like a PDF but is not one. */
	public static byte[] corrupt(String marker) {
		return ("%PDF-1.7\n%garbage " + marker + "\n1 0 obj << /Type /Catalog /Pages 9 0 R >>\nnot a pdf at all\n")
			.getBytes(StandardCharsets.US_ASCII);
	}

	/** A structurally valid PDF without any page. */
	public static byte[] withoutPages(String marker) {
		try (PDDocument document = new PDDocument()) {
			document.getDocumentInformation().setSubject(marker);
			return save(document);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static byte[] encrypted(String marker, String ownerPassword, String userPassword) {
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.getDocumentInformation().setSubject(marker);
			StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword,
					new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			return save(document);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

}
