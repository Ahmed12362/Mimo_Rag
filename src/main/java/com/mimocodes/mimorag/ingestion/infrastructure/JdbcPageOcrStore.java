package com.mimocodes.mimorag.ingestion.infrastructure;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.PageOcrStore;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of {@link PageOcrStore} (jsonb and array columns, insert-only). */
@Repository
class JdbcPageOcrStore implements PageOcrStore {

	private static final TypeReference<List<Region>> REGIONS = new TypeReference<>() {
	};

	private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
	};

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	JdbcPageOcrStore(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	@Transactional(readOnly = true)
	public List<OcrCandidate> candidates(UUID integrationId, UUID extractionRunId) {
		return jdbc.query("""
				select page_number, page_class, images::text, lines::text from page_extraction
				where integration_id = ? and extraction_run_id = ? and page_class in ('OCR_REQUIRED', 'MIXED')
				order by page_number""", (rs, row) -> {
			List<Region> nativeLines = new ArrayList<>();
			for (Map<String, Object> line : json.readValue(rs.getString(4), LIST_OF_MAPS)) {
				nativeLines.add(json.convertValue(line.get("box"), Region.class));
			}
			return new OcrCandidate(rs.getInt(1), "MIXED".equals(rs.getString(2)),
					json.readValue(rs.getString(3), REGIONS), nativeLines);
		}, integrationId, extractionRunId);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<Integer> storedPages(UUID integrationId, UUID ocrRunId) {
		return new HashSet<>(jdbc.queryForList(
				"select page_number from page_ocr where integration_id = ? and ocr_run_id = ?", Integer.class,
				integrationId, ocrRunId));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int insert(UUID integrationId, UUID ocrRunId, PageOcrResult page, Instant now) {
		return jdbc.update("""
				insert into page_ocr (id, integration_id, ocr_run_id, page_number, status, mode, render_dpi, image_width,
				    image_height, orientation_applied, original_text, proposed_text, lines, regions, mean_confidence,
				    low_confidence_lines, recovered_tokens, unplaced_tokens, possibly_missing_tokens,
				    dropped_overlapping, flags, warnings, error_code, engine, timings, duration_ms, created_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb,
				        ?, ?)
				on conflict (ocr_run_id, page_number) do nothing""", UUID.randomUUID(), integrationId, ocrRunId,
				page.pageNumber(), page.completed() ? "COMPLETED" : "FAILED", page.mode(), page.renderDpi(),
				page.imageWidth(), page.imageHeight(), page.orientationApplied(), page.originalText(),
				page.proposedText(), json.writeValueAsString(page.lines()), json.writeValueAsString(page.regions()),
				(float) page.meanConfidence(), page.lowConfidenceLines(), page.recoveredTokens(), page.unplacedTokens(),
				page.possiblyMissingTokens(), page.droppedOverlapping(), page.flags().stream().sorted().toArray(String[]::new),
				page.warnings().stream().sorted().toArray(String[]::new), page.errorCode(),
				json.writeValueAsString(page.engine()), json.writeValueAsString(page.timings()),
				(int) Math.min(Integer.MAX_VALUE, page.durationMillis()), Timestamp.from(now));
	}

	@Override
	@Transactional(readOnly = true)
	public int[] counts(UUID integrationId, UUID ocrRunId) {
		return jdbc.queryForObject("""
				select count(*), count(*) filter (where status = 'FAILED') from page_ocr
				where integration_id = ? and ocr_run_id = ?""",
				(rs, row) -> new int[] { rs.getInt(1), rs.getInt(2) }, integrationId, ocrRunId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Map<String, Object>> find(UUID integrationId, UUID ocrRunId, int pageNumber) {
		return jdbc.query("""
				select status, mode, render_dpi, image_width, image_height, orientation_applied, original_text,
				       proposed_text, lines::text, regions::text, mean_confidence, low_confidence_lines, recovered_tokens,
				       unplaced_tokens, possibly_missing_tokens, dropped_overlapping, flags, warnings, error_code,
				       engine::text, timings::text, duration_ms
				from page_ocr where integration_id = ? and ocr_run_id = ? and page_number = ?""", (rs, row) -> {
			Map<String, Object> page = new LinkedHashMap<>();
			page.put("status", rs.getString("status"));
			page.put("reviewState", "UNREVIEWED");
			page.put("mode", rs.getString("mode"));
			page.put("renderDpi", rs.getInt("render_dpi"));
			page.put("imageWidth", rs.getInt("image_width"));
			page.put("imageHeight", rs.getInt("image_height"));
			page.put("orientationApplied", rs.getInt("orientation_applied"));
			page.put("originalText", rs.getString("original_text"));
			page.put("proposedText", rs.getString("proposed_text"));
			page.put("meanConfidence", rs.getDouble("mean_confidence"));
			page.put("lowConfidenceLines", rs.getInt("low_confidence_lines"));
			page.put("recoveredTokens", rs.getInt("recovered_tokens"));
			page.put("unplacedTokens", rs.getInt("unplaced_tokens"));
			page.put("possiblyMissingTokens", rs.getInt("possibly_missing_tokens"));
			page.put("droppedOverlappingLines", rs.getInt("dropped_overlapping"));
			page.put("flags", strings(rs.getArray("flags")));
			page.put("warnings", strings(rs.getArray("warnings")));
			page.put("errorCode", rs.getString("error_code"));
			page.put("lines", json.readValue(rs.getString("lines"), LIST_OF_MAPS));
			page.put("regions", json.readValue(rs.getString("regions"), LIST_OF_MAPS));
			page.put("engine", json.readValue(rs.getString("engine"), MAP));
			page.put("timings", json.readValue(rs.getString("timings"), MAP));
			page.put("durationMillis", rs.getInt("duration_ms"));
			return page;
		}, integrationId, ocrRunId, pageNumber).stream().findFirst();
	}

	private static List<String> strings(Array array) throws SQLException {
		return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
	}

}
