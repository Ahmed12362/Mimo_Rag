package com.mimocodes.mimorag.ingestion.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.DocumentQueryService;
import com.mimocodes.mimorag.ingestion.application.DocumentUploadService;
import com.mimocodes.mimorag.ingestion.application.DocumentUploadService.UploadCommand;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.DocumentDetails;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.DocumentSummary;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.UploadResult;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.web.CurrentAccess;
import com.mimocodes.mimorag.shared.web.PageQuery;
import com.mimocodes.mimorag.shared.web.PageResponse;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Integration plane: upload PDFs into a Knowledge Base and read documents. Uploads return immediately (202); the
 * PDF is processed by a background job whose status is at {@code Location}.
 */
@RestController
class DocumentController {

	private final DocumentUploadService uploads;

	private final DocumentQueryService queries;

	DocumentController(DocumentUploadService uploads, DocumentQueryService queries) {
		this.uploads = uploads;
		this.queries = queries;
	}

	@PostMapping(path = "/api/v1/knowledge-bases/{knowledgeBaseId}/documents",
			consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<UploadResult> upload(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId,
			@RequestPart("file") MultipartFile file, @RequestParam(name = "title", required = false) String title,
			@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
		UploadResult result;
		try (InputStream content = file.getInputStream()) {
			result = uploads.upload(access,
					new UploadCommand(knowledgeBaseId, file.getOriginalFilename(), title, idempotencyKey, content));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (result.outcome() == UploadResult.Outcome.DUPLICATE) {
			return ResponseEntity.ok(result);
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED)
			.location(URI.create("/api/v1/ingestion-jobs/" + result.jobId()))
			.body(result);
	}

	@GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/documents")
	PageResponse<DocumentSummary> list(@CurrentAccess AccessContext access, @PathVariable UUID knowledgeBaseId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
		return PageResponse.from(
				queries.list(access, knowledgeBaseId, PageQuery.of(page, size, Sort.by("createdAt", "id"))),
				view -> view);
	}

	@GetMapping("/api/v1/documents/{documentId}")
	DocumentDetails get(@CurrentAccess AccessContext access, @PathVariable UUID documentId) {
		return queries.get(access, documentId);
	}

}
