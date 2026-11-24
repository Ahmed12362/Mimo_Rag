package com.mimocodes.mimorag.ingestion.api;

import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.DocumentQueryService;
import com.mimocodes.mimorag.ingestion.application.DocumentViews.IngestionJobView;
import com.mimocodes.mimorag.shared.access.AccessContext;
import com.mimocodes.mimorag.shared.web.CurrentAccess;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Integration plane: status of document processing jobs, and manual retry of failed ones. */
@RestController
@RequestMapping("/api/v1/ingestion-jobs")
class IngestionJobController {

	private final DocumentQueryService queries;

	IngestionJobController(DocumentQueryService queries) {
		this.queries = queries;
	}

	@GetMapping("/{jobId}")
	IngestionJobView get(@CurrentAccess AccessContext access, @PathVariable UUID jobId) {
		return queries.job(access, jobId);
	}

	/** Safe to repeat: a job that is already queued or running is returned unchanged. */
	@PostMapping("/{jobId}/retry")
	ResponseEntity<IngestionJobView> retry(@CurrentAccess AccessContext access, @PathVariable UUID jobId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(queries.retry(access, jobId));
	}

}
