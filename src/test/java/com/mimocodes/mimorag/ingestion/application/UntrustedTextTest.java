package com.mimocodes.mimorag.ingestion.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UntrustedTextTest {

	@Test
	void keepsOnlyTheLastPathSegment() {
		assertThat(UntrustedText.filename("../../etc/passwd")).isEqualTo("passwd");
		assertThat(UntrustedText.filename("C:\\Users\\hr\\policy.pdf")).isEqualTo("policy.pdf");
	}

	@Test
	void removesControlBidiAndZeroWidthCharacters() {
		// U+202E RIGHT-TO-LEFT OVERRIDE is a classic way to disguise "fdp.exe" as "exe.pdf".
		assertThat(UntrustedText.filename("invoice\u202Efdp.exe")).isEqualTo("invoicefdp.exe");
		assertThat(UntrustedText.filename("a\u200Bb\u0000c\n.pdf")).isEqualTo("abc.pdf");
	}

	@Test
	void keepsArabicNamesAndNormalisesThemToNfc() {
		assertThat(UntrustedText.filename("سياسة الإجازات.pdf")).isEqualTo("سياسة الإجازات.pdf");
	}

	@Test
	void fallsBackToADefaultName() {
		assertThat(UntrustedText.filename(null)).isEqualTo(UntrustedText.DEFAULT_FILENAME);
		assertThat(UntrustedText.filename("   ")).isEqualTo(UntrustedText.DEFAULT_FILENAME);
		assertThat(UntrustedText.filename("dir/..")).isEqualTo(UntrustedText.DEFAULT_FILENAME);
	}

	@Test
	void limitsLength() {
		assertThat(UntrustedText.filename("x".repeat(400) + ".pdf")).hasSize(255);
		assertThat(UntrustedText.title("t".repeat(300), "f.pdf")).hasSize(200);
	}

	@Test
	void titleDefaultsToTheFileNameWithoutExtension() {
		assertThat(UntrustedText.title(null, "HR Policies 2026.pdf")).isEqualTo("HR Policies 2026");
		assertThat(UntrustedText.title("  Leave   policy ", "x.pdf")).isEqualTo("Leave policy");
	}

}
