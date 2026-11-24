package com.mimocodes.mimorag.ingestion.api;

import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.PageExtractionStore.PageDetail;
import com.mimocodes.mimorag.ingestion.application.PageExtractionStore.PageSummary;
import com.mimocodes.mimorag.ingestion.application.PageQueryService;
import com.mimocodes.mimorag.ingestion.application.PageQueryService.ExtractionSummary;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.web.CurrentAccess;
import com.mimocodes.mimorag.shared.web.PageQuery;
import com.mimocodes.mimorag.shared.web.PageResponse;

import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Integration plane: extraction results of a document's current version. */
@RestController
@RequestMapping("/api/v1/documents/{documentId}")
class DocumentPagesController {

	private final PageQueryService pages;

	DocumentPagesController(PageQueryService pages) {
		this.pages = pages;
	}

	/** Progress and page counts per class (DOCUMENT_UPLOAD or DOCUMENT_REVIEW). */
	@GetMapping("/extraction")
	ExtractionSummary extraction(@CurrentAccess AccessContext access, @PathVariable UUID documentId) {
		return pages.summary(access, documentId);
	}

	/** Pages without text (DOCUMENT_REVIEW). */
	@GetMapping("/pages")
	PageResponse<PageSummary> list(@CurrentAccess AccessContext access, @PathVariable UUID documentId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
		return PageResponse.from(pages.pages(access, documentId, PageQuery.of(page, size, Sort.unsorted())),
				summary -> summary);
	}

	/** One page with its trusted text, warnings and regions (DOCUMENT_REVIEW). */
	@GetMapping("/pages/{pageNumber}")
	PageDetail page(@CurrentAccess AccessContext access, @PathVariable UUID documentId,
			@PathVariable int pageNumber) {
		return pages.page(access, documentId, pageNumber);
	}

}
