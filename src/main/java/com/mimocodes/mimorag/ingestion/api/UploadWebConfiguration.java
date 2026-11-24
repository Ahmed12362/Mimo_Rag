package com.mimocodes.mimorag.ingestion.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.servlet.MultipartConfigElement;

import com.mimocodes.mimorag.ingestion.application.IngestionProperties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

/**
 * Multipart limits derived from the ingestion settings (replaces Spring Boot's 1 MB default). Parts are written to
 * disk from the first byte ({@code fileSizeThreshold = 0}), so upload size never translates into heap use; oversized
 * requests are rejected while being read (413).
 */
@Configuration(proxyBeanMethods = false)
class UploadWebConfiguration {

	/** Room for the other multipart fields (title) next to the file. */
	private static final long FORM_OVERHEAD_BYTES = DataSize.ofKilobytes(64).toBytes();

	@Bean
	MultipartConfigElement multipartConfigElement(IngestionProperties properties) {
		Path spool = properties.storageRoot().toAbsolutePath().normalize().resolve("multipart-spool");
		try {
			Files.createDirectories(spool);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot create the multipart spool directory " + spool, ex);
		}
		long maxFile = properties.maxFileSize().toBytes();
		return new MultipartConfigElement(spool.toString(), maxFile, maxFile + FORM_OVERHEAD_BYTES, 0);
	}

}
