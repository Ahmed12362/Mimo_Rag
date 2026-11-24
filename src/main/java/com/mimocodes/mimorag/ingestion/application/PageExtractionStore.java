package com.mimocodes.mimorag.ingestion.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Read-only page results. Rows are only ever inserted (the runtime role has no UPDATE or DELETE on the table), and a
 * page already stored for a run is never stored again.
 */
public interface PageExtractionStore {

	/**
	 * Inserts the pages inside the caller's transaction (mandatory), skipping pages the run already has.
	 *
	 * @return how many rows were actually inserted
	 */
	int insert(UUID integrationId, UUID runId, List<PageAnalysis> pages, Instant now);

	/** Highest page number stored for the run, 0 when none. */
	int highestPage(UUID integrationId, UUID runId);

	Map<PageClass, Integer> countByClass(UUID integrationId, UUID runId);

	Page<PageSummary> list(UUID integrationId, UUID runId, Pageable pageable);

	Optional<PageDetail> find(UUID integrationId, UUID runId, int pageNumber);

	/** A page in a listing: no text. */
	record PageSummary(int pageNumber, PageClass pageClass, Set<String> scripts, Set<PageWarning> warnings,
			int characters, boolean hasText, double imageCoverage) {
	}

	/**
	 * One page. {@code text} is the trusted result only; untrusted native text is never returned.
	 *
	 * @param quality quality signals and, for untrusted text, the reasons
	 */
	record PageDetail(int pageNumber, PageClass pageClass, String text, boolean nativeTextTrusted,
			Set<String> scripts, Set<PageWarning> warnings, Map<String, Object> quality, List<Map<String, Object>> lines,
			List<Region> images, double imageCoverage, double width, double height, int rotation, int durationMillis) {
	}

}
