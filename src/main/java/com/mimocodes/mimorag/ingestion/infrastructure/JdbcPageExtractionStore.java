package com.mimocodes.mimorag.ingestion.infrastructure;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mimocodes.mimorag.ingestion.application.NativePage.Region;
import com.mimocodes.mimorag.ingestion.application.PageAnalysis;
import com.mimocodes.mimorag.ingestion.application.PageAnalysis.LineBox;
import com.mimocodes.mimorag.ingestion.application.PageExtractionStore;
import com.mimocodes.mimorag.ingestion.domain.PageClass;
import com.mimocodes.mimorag.ingestion.domain.PageWarning;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC implementation of {@link PageExtractionStore} (jsonb and array columns, insert-only). */
@Repository
class JdbcPageExtractionStore implements PageExtractionStore {

	private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
	};

	private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
	};

	private static final TypeReference<List<Region>> REGIONS = new TypeReference<>() {
	};

	private final JdbcTemplate jdbc;

	private final JsonMapper json;

	JdbcPageExtractionStore(JdbcTemplate jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public int insert(UUID integrationId, UUID runId, List<PageAnalysis> pages, Instant now) {
		int inserted = 0;
		for (PageAnalysis page : pages) {
			inserted += jdbc.update("""
					insert into page_extraction (id, integration_id, extraction_run_id, page_number, page_class,
					    native_text, native_text_trusted, result_text, scripts, warnings, quality, lines, images,
					    image_coverage, page_width, page_height, rotation, duration_ms, created_at)
					values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?)
					on conflict (extraction_run_id, page_number) do nothing""", UUID.randomUUID(), integrationId,
					runId, page.pageNumber(), page.pageClass().name(), page.nativeText(), page.nativeTextTrusted(),
					page.resultText(), page.scripts().toArray(String[]::new),
					page.warnings().stream().map(Enum::name).sorted().toArray(String[]::new), qualityJson(page),
					linesJson(page), json.writeValueAsString(page.images()), (float) page.imageCoverage(),
					(float) page.width(), (float) page.height(), page.rotation(),
					(int) Math.min(Integer.MAX_VALUE, page.durationMillis()), Timestamp.from(now));
		}
		return inserted;
	}

	@Override
	@Transactional(readOnly = true)
	public int highestPage(UUID integrationId, UUID runId) {
		Integer highest = jdbc.queryForObject("""
				select coalesce(max(page_number), 0) from page_extraction
				where integration_id = ? and extraction_run_id = ?""", Integer.class, integrationId, runId);
		return highest == null ? 0 : highest;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<PageClass, Integer> countByClass(UUID integrationId, UUID runId) {
		Map<PageClass, Integer> counts = new EnumMap<>(PageClass.class);
		jdbc.query("""
				select page_class, count(*) from page_extraction
				where integration_id = ? and extraction_run_id = ? group by page_class""",
				(ResultSet rs) -> {
					counts.put(PageClass.valueOf(rs.getString(1)), rs.getInt(2));
				}, integrationId, runId);
		return counts;
	}

	@Override
	@Transactional(readOnly = true)
	public Page<PageSummary> list(UUID integrationId, UUID runId, Pageable pageable) {
		Long total = jdbc.queryForObject(
				"select count(*) from page_extraction where integration_id = ? and extraction_run_id = ?", Long.class,
				integrationId, runId);
		List<PageSummary> rows = jdbc.query("""
				select page_number, page_class, scripts, warnings, image_coverage, result_text is not null as has_text,
				       coalesce((quality ->> 'nonSpaceChars')::int, 0) as characters
				from page_extraction where integration_id = ? and extraction_run_id = ?
				order by page_number limit ? offset ?""", (rs, row) -> new PageSummary(rs.getInt("page_number"),
				PageClass.valueOf(rs.getString("page_class")), strings(rs.getArray("scripts")),
				warnings(rs.getArray("warnings")), rs.getInt("characters"), rs.getBoolean("has_text"),
				rs.getDouble("image_coverage")), integrationId, runId, pageable.getPageSize(), pageable.getOffset());
		return new PageImpl<>(rows, pageable, total == null ? 0 : total);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<PageDetail> find(UUID integrationId, UUID runId, int pageNumber) {
		return jdbc.query("""
				select page_number, page_class, result_text, native_text_trusted, scripts, warnings, quality::text,
				       lines::text, images::text, image_coverage, page_width, page_height, rotation, duration_ms
				from page_extraction where integration_id = ? and extraction_run_id = ? and page_number = ?""",
				(rs, row) -> new PageDetail(rs.getInt("page_number"), PageClass.valueOf(rs.getString("page_class")),
						rs.getString("result_text"), rs.getBoolean("native_text_trusted"),
						strings(rs.getArray("scripts")), warnings(rs.getArray("warnings")),
						json.readValue(rs.getString("quality"), MAP), json.readValue(rs.getString("lines"), LIST_OF_MAPS),
						json.readValue(rs.getString("images"), REGIONS), rs.getDouble("image_coverage"),
						rs.getDouble("page_width"), rs.getDouble("page_height"), rs.getInt("rotation"),
						rs.getInt("duration_ms"), null),
				integrationId, runId, pageNumber)
			.stream()
			.findFirst();
	}

	private String qualityJson(PageAnalysis page) {
		Map<String, Object> quality = new LinkedHashMap<>(json.convertValue(page.quality(), MAP));
		quality.put("unreliableReasons", page.unreliableReasons());
		return json.writeValueAsString(quality);
	}

	private String linesJson(PageAnalysis page) {
		List<Map<String, Object>> lines = new ArrayList<>();
		for (LineBox line : page.lines()) {
			Map<String, Object> entry = new LinkedHashMap<>();
			entry.put("box", line.box());
			entry.put("direction", line.direction().name());
			entry.put("start", line.start());
			entry.put("end", line.end());
			entry.put("mixedDirection", line.mixedDirection());
			lines.add(entry);
		}
		return json.writeValueAsString(lines);
	}

	private static Set<String> strings(Array array) throws SQLException {
		return array == null ? Set.of() : new LinkedHashSet<>(Arrays.asList((String[]) array.getArray()));
	}

	private static Set<PageWarning> warnings(Array array) throws SQLException {
		Set<PageWarning> warnings = new LinkedHashSet<>();
		for (String warning : strings(array)) {
			warnings.add(PageWarning.valueOf(warning));
		}
		return warnings;
	}

}
