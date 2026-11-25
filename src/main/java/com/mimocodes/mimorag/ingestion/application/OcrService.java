package com.mimocodes.mimorag.ingestion.application;

import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.DocumentStorage.StoredObjectMissingException;
import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrConfigurationException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrEngineException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrLine;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrPageRejectedException;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrResult;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrUnavailableException;
import com.mimocodes.mimorag.ingestion.application.OcrResponseValidator.InvalidOcrResponseException;
import com.mimocodes.mimorag.ingestion.application.PageOcrStore.OcrCandidate;
import com.mimocodes.mimorag.ingestion.application.PageOcrStore.PageOcrResult;
import com.mimocodes.mimorag.ingestion.application.PageRenderer.RenderSession;
import com.mimocodes.mimorag.ingestion.application.PageRenderer.RenderedImage;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersion;
import com.mimocodes.mimorag.ingestion.domain.DocumentVersionStatus;
import com.mimocodes.mimorag.ingestion.domain.ExtractionRun;
import com.mimocodes.mimorag.ingestion.domain.OcrRun;
import com.mimocodes.mimorag.jobs.application.JobFailure;
import com.mimocodes.mimorag.jobs.application.JobHandler.JobExecution;
import com.mimocodes.mimorag.shared.audit.AuditEvent;
import com.mimocodes.mimorag.shared.audit.AuditRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OCR of the pages native extraction could not read (docs/adr/0014-ocr-service.md).
 * <ul>
 * <li>{@code OCR_REQUIRED} pages are rendered whole; {@code MIXED} pages only in their stored image regions, and OCR
 * lines that overlap native text lines are dropped, so nothing is read twice.</li>
 * <li>One run per job; every page is committed on its own with the run's counters, and a resumed job continues
 * after the stored pages (rows are insert-only and unique per run and page).</li>
 * <li>No database transaction is open while a page is rendered or recognised.</li>
 * <li>The result is unreviewed machine output; only human review (M3) can turn it into approved content.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "mimorag.ocr.enabled", havingValue = "true")
public class OcrService {

	public static final String OCR_DOCUMENT = "OCR_DOCUMENT";

	/** An OCR line overlapping a native text line by at least this share of its own area is a duplicate. */
	static final double DUPLICATE_OVERLAP = 0.5;

	private static final Logger log = LoggerFactory.getLogger(OcrService.class);

	private final OcrRunRepository runs;

	private final ExtractionRunRepository extractionRuns;

	private final DocumentVersionRepository versions;

	private final PageOcrStore pages;

	private final DocumentStorage storage;

	private final PageRenderer renderer;

	private final OcrProvider ocr;

	private final OcrProperties properties;

	private final AuditRecorder audit;

	private final Clock clock;

	private final MeterRegistry meters;

	private final TransactionTemplate transaction;

	OcrService(OcrRunRepository runs, ExtractionRunRepository extractionRuns, DocumentVersionRepository versions,
			PageOcrStore pages, DocumentStorage storage, PageRenderer renderer, OcrProvider ocr,
			OcrProperties properties, AuditRecorder audit, Clock clock, MeterRegistry meters,
			PlatformTransactionManager transactionManager) {
		this.runs = runs;
		this.extractionRuns = extractionRuns;
		this.versions = versions;
		this.pages = pages;
		this.storage = storage;
		this.renderer = renderer;
		this.ocr = ocr;
		this.properties = properties;
		this.audit = audit;
		this.clock = clock;
		this.meters = meters;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public void ocr(JobExecution execution, UUID versionId) {
		UUID integrationId = execution.access().requireIntegrationId();
		OcrRun run = openRun(execution, versionId);
		if (run.getStatus() != OcrRun.Status.COMPLETED) {
			List<OcrCandidate> todo = transaction.execute(status -> {
				Set<Integer> stored = pages.storedPages(integrationId, run.getId());
				return pages.candidates(integrationId, run.getExtractionRunId())
					.stream()
					.filter(candidate -> !stored.contains(candidate.pageNumber()))
					.toList();
			});
			if (!todo.isEmpty()) {
				String storageKey = transaction.execute(status -> load(integrationId, versionId).getStorageKey());
				recognisePages(execution, integrationId, run.getId(), storageKey, todo);
			}
		}
		finish(integrationId, run.getId(), versionId);
	}

	/** Marks the job's run failed (called when the job ends without success). */
	public void failRun(UUID integrationId, UUID jobId, String code) {
		runs.findByIntegrationIdAndJobId(integrationId, jobId).ifPresent(run -> {
			run.fail(code, clock.instant());
			runs.saveAndFlush(run);
		});
	}

	// ---- run lifecycle --------------------------------------------------------------------------------------------

	private OcrRun openRun(JobExecution execution, UUID versionId) {
		UUID integrationId = execution.access().requireIntegrationId();
		try {
			return transaction.execute(status -> {
				DocumentVersion version = load(integrationId, versionId);
				if (version.getStatus() == DocumentVersionStatus.AWAITING_OCR) {
					version.startOcr(clock.instant());
					versions.saveAndFlush(version);
				}
				ExtractionRun extraction = extractionRuns
					.findFirstByIntegrationIdAndDocumentVersionIdOrderByStartedAtDesc(integrationId, versionId)
					.filter(r -> r.getStatus() == ExtractionRun.Status.COMPLETED)
					.orElseThrow(() -> JobFailure.permanent("EXTRACTION_NOT_COMPLETED"));
				OcrRun run = runs.findByIntegrationIdAndJobId(integrationId, execution.jobId())
					.orElseGet(() -> OcrRun.start(version, extraction.getId(), execution.jobId(),
							pages.candidates(integrationId, extraction.getId()).size(), clock.instant()));
				run.resume(clock.instant());
				return runs.saveAndFlush(run);
			});
		}
		catch (DataIntegrityViolationException concurrentStart) {
			return transaction.execute(status -> runs.findByIntegrationIdAndJobId(integrationId, execution.jobId())
				.orElseThrow(() -> concurrentStart));
		}
	}

	private void finish(UUID integrationId, UUID runId, UUID versionId) {
		transaction.executeWithoutResult(status -> {
			OcrRun run = runs.findByIdAndIntegrationId(runId, integrationId).orElseThrow();
			int[] counts = pages.counts(integrationId, runId);
			run.recordProgress(counts[0], counts[1], clock.instant());
			if (run.getStatus() != OcrRun.Status.COMPLETED) {
				run.complete(clock.instant());
			}
			runs.saveAndFlush(run);
			DocumentVersion version = load(integrationId, versionId);
			if (version.getStatus() == DocumentVersionStatus.OCR_RUNNING) {
				version.finishOcr(clock.instant());
				versions.saveAndFlush(version);
				audit.record(AuditEvent.success("DOCUMENT_OCR_COMPLETED", "document", version.getDocumentId())
					.withDetails(Map.of("versionId", versionId.toString(), "ocrRunId", runId.toString(), "pages",
							run.getPagesDone(), "pagesFailed", run.getPagesFailed())));
			}
		});
	}

	// ---- pages ----------------------------------------------------------------------------------------------------

	private void recognisePages(JobExecution execution, UUID integrationId, UUID runId, String storageKey,
			List<OcrCandidate> todo) {
		try {
			storage.withLocalFile(storageKey, path -> {
				try (RenderSession session = renderer.open(path)) {
					for (OcrCandidate candidate : todo) {
						PageOcrResult result = recognisePage(session, candidate, execution);
						transaction.executeWithoutResult(status -> {
							pages.insert(integrationId, runId, result, clock.instant());
							OcrRun run = runs.findByIdAndIntegrationId(runId, integrationId).orElseThrow();
							int[] counts = pages.counts(integrationId, runId);
							run.recordProgress(counts[0], counts[1], clock.instant());
							runs.saveAndFlush(run);
						});
					}
				}
				return null;
			});
		}
		catch (StoredObjectMissingException missing) {
			throw JobFailure.permanent("STORED_FILE_MISSING");
		}
	}

	private PageOcrResult recognisePage(RenderSession session, OcrCandidate candidate, JobExecution execution) {
		long started = System.nanoTime();
		String mode = candidate.mixed() ? "REGIONS" : "PAGE";
		String requestBase = requestId(execution, candidate.pageNumber());
		try {
			PageOcrResult result = candidate.mixed() ? recogniseRegions(session, candidate, requestBase, started)
					: recogniseWholePage(session, candidate, requestBase, started);
			meters.timer("mimorag.ingestion.ocr.page", "mode", mode, "outcome", "completed")
				.record(java.time.Duration.ofNanos(System.nanoTime() - started));
			return result;
		}
		catch (OcrPageRejectedException rejected) {
			return failedPage(candidate, mode, rejected.getCode(), started);
		}
		catch (InvalidOcrResponseException invalid) {
			log.warn("OCR response for page {} rejected: {}", candidate.pageNumber(), invalid.getMessage());
			return failedPage(candidate, mode, "OCR_RESPONSE_INVALID", started);
		}
		catch (OcrEngineException engine) {
			return failedPage(candidate, mode, engine.getCode(), started);
		}
		catch (UncheckedIOException | IllegalArgumentException renderFailure) {
			log.warn("Page {} could not be rendered for OCR", candidate.pageNumber(), renderFailure);
			return failedPage(candidate, mode, "PAGE_RENDER_ERROR", started);
		}
		catch (OcrUnavailableException unavailable) {
			throw JobFailure.transientFailure(unavailable.getCode(), unavailable);
		}
		catch (OcrConfigurationException misconfigured) {
			throw JobFailure.permanent("OCR_CONFIGURATION_ERROR");
		}
	}

	private PageOcrResult recogniseWholePage(RenderSession session, OcrCandidate candidate, String requestId,
			long started) {
		RenderedImage image = session.page(candidate.pageNumber());
		OcrResult result = callWithEngineRetries(image, requestId);
		Accumulator page = new Accumulator(image.dpi(), image.width(), image.height());
		page.add(result, image, null, List.of());
		return page.result(candidate.pageNumber(), "PAGE", started);
	}

	private PageOcrResult recogniseRegions(RenderSession session, OcrCandidate candidate, String requestBase,
			long started) {
		Accumulator page = new Accumulator(properties.renderDpi(), 0, 0);
		int index = 0;
		for (Region region : candidate.images()) {
			double scale = properties.renderDpi() / 72.0;
			if (region.width() * scale < properties.minRegionPixels() || region.height() * scale < properties.minRegionPixels()) {
				continue;
			}
			RenderedImage image = session.region(candidate.pageNumber(), region, properties.regionMarginPoints());
			OcrResult result = callWithEngineRetries(image, requestBase + "-r" + index);
			page.add(result, image, index, candidate.nativeLines());
			index++;
		}
		if (index == 0) {
			page.warn("NO_OCR_REGIONS");
		}
		return page.result(candidate.pageNumber(), "REGIONS", started);
	}

	private OcrResult callWithEngineRetries(RenderedImage image, String requestId) {
		OcrEngineException last = null;
		for (int attempt = 1; attempt <= properties.maxPageEngineAttempts(); attempt++) {
			try {
				return ocr.recognize(image.png(), image.width(), image.height(), requestId);
			}
			catch (OcrEngineException ex) {
				last = ex;
			}
		}
		throw last;
	}

	private PageOcrResult failedPage(OcrCandidate candidate, String mode, String code, long startedNanos) {
		meters.counter("mimorag.ingestion.ocr.page.failed", "code", code).increment();
		return PageOcrResult.failed(candidate.pageNumber(), mode, properties.renderDpi(), code,
				(System.nanoTime() - startedNanos) / 1_000_000);
	}

	private static String requestId(JobExecution execution, int pageNumber) {
		String correlation = execution.access().correlationId();
		String base = correlation != null && correlation.matches("[A-Za-z0-9._-]{8,48}") ? correlation
				: execution.jobId().toString();
		return base + "-p" + pageNumber;
	}

	private DocumentVersion load(UUID integrationId, UUID versionId) {
		return versions.findByIdAndIntegrationId(versionId, integrationId)
			.orElseThrow(() -> JobFailure.permanent("DOCUMENT_VERSION_NOT_FOUND"));
	}

	/** Collects one page's lines (from the whole page or several regions) and builds the stored result. */
	private static final class Accumulator {

		private final List<Map<String, Object>> lines = new ArrayList<>();

		private final List<Map<String, Object>> regions = new ArrayList<>();

		private final Set<String> flags = new LinkedHashSet<>();

		private final Set<String> warnings = new LinkedHashSet<>();

		private final Map<String, Object> timings = new HashMap<>();

		private Map<String, Object> engine = Map.of();

		private final int dpi;

		private int width;

		private int height;

		private int orientation;

		private int dropped;

		private int recovered;

		private int unplaced;

		private int possiblyMissing;

		private final StringBuilder original = new StringBuilder();

		private final StringBuilder proposed = new StringBuilder();

		Accumulator(int dpi, int width, int height) {
			this.dpi = dpi;
			this.width = width;
			this.height = height;
		}

		void add(OcrResult result, RenderedImage image, Integer regionIndex, List<Region> nativeLines) {
			engine = result.engine();
			if (orientation == 0) {
				orientation = result.orientation().applied();
			}
			warnings.addAll(result.warnings());
			if (image.reducedDpi()) {
				warnings.add("RENDERED_AT_REDUCED_DPI");
			}
			result.timings().forEach((key, value) -> timings.merge(key, value, (a, b) -> (Integer) a + (Integer) b));
			if (regionIndex != null) {
				regions.add(Map.of("index", regionIndex, "x", image.offsetX(), "y", image.offsetY(), "width",
						image.width() / image.scale(), "height", image.height() / image.scale(), "imageWidth",
						image.width(), "imageHeight", image.height()));
				width = Math.max(width, image.width());
				height = Math.max(height, image.height());
			}
			int currentRow = -1;
			StringBuilder rowOriginal = new StringBuilder();
			StringBuilder rowProposed = new StringBuilder();
			for (OcrLine line : result.lines()) {
				Region box = new Region(image.offsetX() + line.box().x() / image.scale(),
						image.offsetY() + line.box().y() / image.scale(), line.box().width() / image.scale(),
						line.box().height() / image.scale());
				if (overlapsNativeText(box, nativeLines)) {
					dropped++;
					continue;
				}
				if (line.row() != currentRow && currentRow != -1) {
					appendRow(rowOriginal, rowProposed);
				}
				currentRow = line.row();
				separate(rowOriginal, line.originalText(), " ");
				separate(rowProposed, line.proposedText(), " ");
				flags.addAll(line.flags());
				recovered += line.recoveredTokens().size();
				unplaced += line.unplacedTokens().size();
				possiblyMissing += line.possiblyMissingTokens().size();
				Map<String, Object> stored = new LinkedHashMap<>();
				stored.put("box", box);
				stored.put("region", regionIndex);
				stored.put("row", line.row());
				stored.put("direction", line.direction());
				stored.put("confidence", line.confidence());
				stored.put("originalText", line.originalText());
				stored.put("proposedText", line.proposedText());
				stored.put("recoveredTokens", line.recoveredTokens());
				stored.put("unplacedTokens", line.unplacedTokens());
				stored.put("possiblyMissingTokens", line.possiblyMissingTokens());
				stored.put("flags", line.flags());
				lines.add(stored);
			}
			appendRow(rowOriginal, rowProposed);
		}

		void warn(String warning) {
			warnings.add(warning);
		}

		private void appendRow(StringBuilder rowOriginal, StringBuilder rowProposed) {
			if (rowOriginal.isEmpty() && rowProposed.isEmpty()) {
				return;
			}
			separate(original, rowOriginal.toString(), "\n");
			separate(proposed, rowProposed.toString(), "\n");
			rowOriginal.setLength(0);
			rowProposed.setLength(0);
		}

		PageOcrResult result(int pageNumber, String mode, long startedNanos) {
			double mean = lines.stream().mapToDouble(l -> (Double) l.get("confidence")).average().orElse(0);
			int low = (int) lines.stream().filter(l -> ((List<?>) l.get("flags")).contains("LOW_CONFIDENCE")).count();
			return new PageOcrResult(pageNumber, true, mode, dpi, width, height, orientation, original.toString(),
					proposed.toString(), lines, regions, mean, low, recovered, unplaced, possiblyMissing, dropped, flags,
					warnings, null, engine, timings, (System.nanoTime() - startedNanos) / 1_000_000);
		}

		private static void separate(StringBuilder target, String text, String separator) {
			if (text.isEmpty()) {
				return;
			}
			if (!target.isEmpty()) {
				target.append(separator);
			}
			target.append(text);
		}

		private static boolean overlapsNativeText(Region box, List<Region> nativeLines) {
			double area = box.area();
			if (area <= 0) {
				return false;
			}
			for (Region nativeLine : nativeLines) {
				double ix = Math.min(box.x() + box.width(), nativeLine.x() + nativeLine.width())
						- Math.max(box.x(), nativeLine.x());
				double iy = Math.min(box.y() + box.height(), nativeLine.y() + nativeLine.height())
						- Math.max(box.y(), nativeLine.y());
				if (ix > 0 && iy > 0 && ix * iy >= DUPLICATE_OVERLAP * area) {
					return true;
				}
			}
			return false;
		}

	}

}
