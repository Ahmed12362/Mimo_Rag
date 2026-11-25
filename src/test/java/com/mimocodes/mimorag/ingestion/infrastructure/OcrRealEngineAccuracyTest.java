package com.mimocodes.mimorag.ingestion.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.mimocodes.mimorag.ingestion.application.OcrProperties;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrLine;
import com.mimocodes.mimorag.ingestion.application.OcrProvider.OcrResult;
import com.mimocodes.mimorag.ingestion.application.PageRenderer.RenderSession;
import com.mimocodes.mimorag.ingestion.application.PageRenderer.RenderedImage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.util.unit.DataSize;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-engine accuracy gate for slice 2d (opt-in: {@code ./mvnw test -Pocr-it}). Every page of the 2a corpus is
 * rendered by {@link PdfBoxPageRenderer} at 300 DPI and recognised by the real OCR service through
 * {@link HttpOcrProvider}, then scored with the 2a metric (a port of {@code benchmarks/ocr/run_benchmark.py}:
 * {@code normalise}, {@code order_rows}, {@code cer}) against the 2a baseline {@code merged} variant.
 * <p>
 * Hard criteria (not to be weakened without review): per scan profile, mean CER at most 1.5 points above the
 * baseline; orientation {@code auto} never worse than {@code off} on upright pages; upside-down pages within 1 point
 * of the upright {@code clean300} pages. Results go to {@code target/ocr-it/}.
 * <p>
 * Configuration: {@code MIMORAG_OCR_BASE_URL} (default http://127.0.0.1:8090), {@code MIMORAG_OCR_TOKEN} or
 * {@code -Docr.it.token-file=...}, {@code -Docr.it.corpus=...}, {@code -Docr.it.baseline=...}.
 */
@Tag("ocr-it")
class OcrRealEngineAccuracyTest {

	static final double PROFILE_TOLERANCE = 0.015;

	static final double ORIENTATION_TOLERANCE = 0.01;

	/**
	 * Diagnostic only: {@code -Docr.it.png-dir=<dir>} sends pre-rendered {@code <file stem>.png} images (e.g. rendered
	 * by pdfium as in 2a) instead of PDFBox renderings, to separate rendering effects from the service. Results then
	 * go to {@code target/ocr-it/png-dir/}; the criteria are the same.
	 */
	private static final String PNG_DIR = System.getProperty("ocr.it.png-dir");

	private static final Path OUT = PNG_DIR == null ? Path.of("target/ocr-it") : Path.of("target/ocr-it/png-dir");

	private static final Pattern ARABIC = Pattern
		.compile("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]");

	private static final Pattern LATIN = Pattern.compile("[A-Za-z]");

	private static final Pattern SPACES = Pattern.compile("(?U)\\s+");

	private final JsonMapper json = JsonMapper.builder().build();

	record Entry(String file, String page, String script, String profile, List<String> lines) {
	}

	record Scored(String file, String profile, String orientation, double cer, int applied, long millis,
			List<String> rows) {
	}

	@Test
	void pdfBoxRenderingKeepsTheTwoABaselineAccuracy() throws Exception {
		Path corpus = Path.of(System.getProperty("ocr.it.corpus", "benchmarks/results/ocr/corpus"));
		Path baselineFile = Path.of(System.getProperty("ocr.it.baseline",
				"benchmarks/results/ocr/runs_hybrid/hybrid_agree50_gateen.jsonl"));
		assertThat(corpus.resolve("manifest.json")).as("2a corpus (benchmarks/ocr/generate_corpus.py)").exists();
		assertThat(baselineFile).as("2a baseline run").exists();

		Map<String, Double> baseline = baseline(baselineFile);
		List<Entry> manifest = json.readValue(corpus.resolve("manifest.json").toFile(), new TypeReference<List<Entry>>() {
		});
		metricPortReproducesTheTwoAScores(baselineFile, manifest);
		List<Entry> entries = manifest
			.stream()
			.filter(e -> baseline.containsKey(e.file()) || ("flip180".equals(e.profile()) && !"en".equals(e.script())))
			.toList();

		OcrProperties properties = properties();
		PdfBoxPageRenderer renderer = new PdfBoxPageRenderer(properties);
		HttpOcrProvider auto = new HttpOcrProvider(properties, json);
		HttpOcrProvider off = new HttpOcrProvider(withOrientationOff(properties), json);
		List<Scored> scored = new ArrayList<>();
		for (Entry entry : entries) {
			RenderedImage image;
			if (PNG_DIR != null) {
				String stem = Path.of(entry.file()).getFileName().toString().replace(".pdf", "");
				byte[] png = Files.readAllBytes(Path.of(PNG_DIR, stem + ".png"));
				int width = java.nio.ByteBuffer.wrap(png, 16, 4).getInt();
				int height = java.nio.ByteBuffer.wrap(png, 20, 4).getInt();
				image = new RenderedImage(png, width, height, 300, 300 / 72.0, 0, 0, false);
			}
			else {
				try (RenderSession session = renderer.open(corpus.resolve(entry.file()))) {
					image = session.page(1);
				}
			}
			assertThat(image.reducedDpi()).as(entry.file()).isFalse();
			scored.add(score(entry, auto, image, "auto"));
			if (!"flip180".equals(entry.profile())) {
				scored.add(score(entry, off, image, "off"));
			}
		}
		List<String> failures = evaluate(scored, baseline, entries);
		write(scored, baseline, failures);
		assertThat(failures).as("hard OCR accuracy criteria; see target/ocr-it/summary.md").isEmpty();
	}

	// ---- scoring (port of the 2a harness) -------------------------------------------------------------------------

	private Scored score(Entry entry, HttpOcrProvider provider, RenderedImage image, String orientation) {
		long started = System.nanoTime();
		OcrResult result = provider.recognize(image.png(), image.width(), image.height(),
				"ocr-it-" + entry.page() + "-" + entry.profile() + "-" + orientation);
		long millis = (System.nanoTime() - started) / 1_000_000;
		List<OcrLine> corrected = result.lines()
			.stream()
			.map(l -> inCorrectedFrame(l, result.orientation().applied(), image.width(), image.height()))
			.toList();
		List<String> rows = orderRows(corrected).stream().map(OcrRealEngineAccuracyTest::normalise).toList();
		String truth = entry.lines().stream().map(OcrRealEngineAccuracyTest::normalise).collect(Collectors.joining("\n"));
		return new Scored(entry.file(), entry.profile(), orientation, cer(truth, String.join("\n", rows)),
				result.orientation().applied(), millis, rows);
	}

	/**
	 * The service returns boxes in the submitted image's frame; 2a ordered rows in the upright (corrected) frame.
	 * Rotating the box back by the applied correction ({@code np.rot90}, counter-clockwise quarter turns) makes the
	 * reading order comparable.
	 */
	static OcrLine inCorrectedFrame(OcrLine line, int applied, int width, int height) {
		int turns = (4 - applied / 90) % 4;
		double x = line.box().x();
		double y = line.box().y();
		double w = line.box().width();
		double h = line.box().height();
		double frameWidth = width;
		double frameHeight = height;
		for (int i = 0; i < turns; i++) {
			// np.rot90 (counter-clockwise): (x, y) -> (y, W - x); the frame becomes H x W.
			double nx = y;
			double ny = frameWidth - (x + w);
			double nw = h;
			double nh = w;
			x = nx;
			y = ny;
			w = nw;
			h = nh;
			double swap = frameWidth;
			frameWidth = frameHeight;
			frameHeight = swap;
		}
		return new OcrLine(new com.mimocodes.mimorag.ingestion.application.OcrProvider.Box(x, y, w, h),
				line.direction(), line.confidence(), line.originalText(), line.proposedText(), line.recoveredTokens(),
				line.unplacedTokens(), line.possiblyMissingTokens(), line.flags(), line.row());
	}

	/** {@code run_benchmark.normalise}: NFKC, no tatweel, whitespace collapsed. */
	static String normalise(String text) {
		String nfkc = Normalizer.normalize(text, Normalizer.Form.NFKC).replace("ـ", "");
		return SPACES.matcher(nfkc).replaceAll(" ").strip();
	}

	/** {@code run_benchmark.order_rows} on the proposed text, with box centres as item positions. */
	static List<String> orderRows(List<OcrLine> lines) {
		if (lines.isEmpty()) {
			return List.of();
		}
		String letters = lines.stream().map(OcrLine::proposedText).collect(Collectors.joining());
		boolean rtl = ARABIC.matcher(letters).results().count() >= LATIN.matcher(letters).results().count();
		double[] heights = lines.stream().mapToDouble(l -> Math.max(1.0, l.box().height())).sorted().toArray();
		int n = heights.length;
		double median = n % 2 == 1 ? heights[n / 2] : (heights[n / 2 - 1] + heights[n / 2]) / 2;
		record Item(String text, double x, double y) {
		}
		List<Item> items = lines.stream()
			.map(l -> new Item(l.proposedText(), l.box().x() + l.box().width() / 2, l.box().y() + l.box().height() / 2))
			.sorted(Comparator.comparingDouble(Item::y))
			.toList();
		List<List<Item>> rows = new ArrayList<>();
		List<Double> rowY = new ArrayList<>();
		for (Item item : items) {
			int last = rows.size() - 1;
			if (last >= 0 && Math.abs(item.y() - rowY.get(last)) <= 0.5 * median) {
				rows.get(last).add(item);
				rowY.set(last, rows.get(last).stream().mapToDouble(Item::y).average().orElseThrow());
			}
			else {
				rows.add(new ArrayList<>(List.of(item)));
				rowY.add(item.y());
			}
		}
		Comparator<Item> order = Comparator.comparingDouble(Item::x);
		return rows.stream()
			.map(row -> row.stream()
				.sorted(rtl ? order.reversed() : order)
				.map(Item::text)
				.collect(Collectors.joining(" ")))
			.toList();
	}

	/** Levenshtein distance over code points divided by the truth length ({@code run_benchmark.cer}). */
	static double cer(String truth, String predicted) {
		int[] a = truth.codePoints().toArray();
		int[] b = predicted.codePoints().toArray();
		int[] previous = new int[b.length + 1];
		int[] current = new int[b.length + 1];
		for (int j = 0; j <= b.length; j++) {
			previous[j] = j;
		}
		for (int i = 1; i <= a.length; i++) {
			current[0] = i;
			for (int j = 1; j <= b.length; j++) {
				int cost = a[i - 1] == b[j - 1] ? 0 : 1;
				current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
			}
			int[] swap = previous;
			previous = current;
			current = swap;
		}
		return (double) previous[b.length] / Math.max(1, a.length);
	}

	/** The Java port of normalise + cer must give the stored 2a scores for the stored 2a rows. */
	private void metricPortReproducesTheTwoAScores(Path baselineFile, List<Entry> manifest) throws IOException {
		Map<String, Entry> byFile = manifest.stream().collect(Collectors.toMap(Entry::file, e -> e));
		int checked = 0;
		for (String line : Files.readAllLines(baselineFile, StandardCharsets.UTF_8)) {
			Map<String, Object> row = json.readValue(line, new TypeReference<Map<String, Object>>() {
			});
			if (!(row.get("file") instanceof String name) || !(row.get("merged") instanceof Map<?, ?> merged)) {
				continue;
			}
			String truth = byFile.get(name).lines().stream().map(OcrRealEngineAccuracyTest::normalise)
				.collect(Collectors.joining("\n"));
			@SuppressWarnings("unchecked")
			List<String> rows = (List<String>) merged.get("rows");
			assertThat(cer(truth, String.join("\n", rows))).as("metric port on " + name)
				.isCloseTo(((Number) merged.get("cer")).doubleValue(), org.assertj.core.api.Assertions.within(0.00051));
			checked++;
		}
		assertThat(checked).isPositive();
	}

	// ---- criteria and report --------------------------------------------------------------------------------------

	private static List<String> evaluate(List<Scored> scored, Map<String, Double> baseline, List<Entry> entries) {
		List<String> failures = new ArrayList<>();
		Map<String, Double> auto = meanByProfile(scored, "auto");
		Map<String, Double> off = meanByProfile(scored, "off");
		Map<String, Double> base = baselineByProfile(baseline, entries);
		base.forEach((profile, expected) -> {
			double actual = auto.getOrDefault(profile, Double.NaN);
			if (!(actual - expected <= PROFILE_TOLERANCE)) {
				failures.add("%s: CER %.4f vs baseline %.4f (+%.2f points > 1.5)".formatted(profile, actual, expected,
						(actual - expected) * 100));
			}
		});
		off.forEach((profile, withoutOrientation) -> {
			double withOrientation = auto.get(profile);
			if (withOrientation > withoutOrientation + 1e-9) {
				failures.add("%s: orientation auto %.4f is worse than off %.4f on upright pages".formatted(profile,
						withOrientation, withoutOrientation));
			}
		});
		if (auto.containsKey("flip180")) {
			double flipped = auto.get("flip180");
			double upright = auto.get("clean300");
			if (flipped > upright + ORIENTATION_TOLERANCE) {
				failures.add("flip180: CER %.4f vs upright clean300 %.4f (> 1 point)".formatted(flipped, upright));
			}
		}
		return failures;
	}

	private void write(List<Scored> scored, Map<String, Double> baseline, List<String> failures) throws IOException {
		Files.createDirectories(OUT);
		StringBuilder jsonl = new StringBuilder();
		for (Scored s : scored) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("file", s.file());
			row.put("profile", s.profile());
			row.put("orientation", s.orientation());
			row.put("cer", Math.round(s.cer() * 10000) / 10000.0);
			row.put("baselineCer", baseline.get(s.file()));
			row.put("orientationApplied", s.applied());
			row.put("millis", s.millis());
			row.put("rows", s.rows());
			jsonl.append(json.writeValueAsString(row)).append('\n');
		}
		Files.writeString(OUT.resolve("results.jsonl"), jsonl, StandardCharsets.UTF_8);

		Map<String, Double> auto = meanByProfile(scored, "auto");
		Map<String, Double> off = meanByProfile(scored, "off");
		Map<String, Double> base = new TreeMap<>();
		Map<String, List<Double>> perProfile = new HashMap<>();
		scored.stream()
			.filter(s -> baseline.containsKey(s.file()) && s.orientation().equals("auto"))
			.forEach(s -> perProfile.computeIfAbsent(s.profile(), k -> new ArrayList<>()).add(baseline.get(s.file())));
		perProfile.forEach((k, v) -> base.put(k, v.stream().mapToDouble(Double::doubleValue).average().orElseThrow()));
		StringBuilder md = new StringBuilder("# OCR accuracy gate (slice 2d, measured)\n\n");
		md.append("| Profile | Pages | 2a baseline CER | 2d CER (auto) | Δ points | 2d CER (orientation off) | ")
			.append("mean ms/page (auto) |\n|---|---|---|---|---|---|---|\n");
		for (String profile : new TreeMap<>(auto).keySet()) {
			List<Scored> rows = scored.stream()
				.filter(s -> s.profile().equals(profile) && s.orientation().equals("auto"))
				.toList();
			Double b = base.get(profile);
			md.append("| %s | %d | %s | %.2f%% | %s | %s | %.0f |\n".formatted(profile, rows.size(),
					b == null ? "—" : "%.2f%%".formatted(b * 100), auto.get(profile) * 100,
					b == null ? "—" : "%+.2f".formatted((auto.get(profile) - b) * 100),
					off.containsKey(profile) ? "%.2f%%".formatted(off.get(profile) * 100) : "—",
					rows.stream().mapToLong(Scored::millis).average().orElse(0)));
		}
		md.append("\nCriteria: per profile ≤ +1.5 points vs 2a; auto never worse than off on upright pages; ")
			.append("flip180 within 1 point of clean300.\n\n");
		md.append(failures.isEmpty() ? "**Result: PASS**\n" : "**Result: FAIL**\n\n- " + String.join("\n- ", failures) + "\n");
		Files.writeString(OUT.resolve("summary.md"), md, StandardCharsets.UTF_8);
	}

	private static Map<String, Double> meanByProfile(List<Scored> scored, String orientation) {
		return scored.stream()
			.filter(s -> s.orientation().equals(orientation))
			.collect(Collectors.groupingBy(Scored::profile, TreeMap::new, Collectors.averagingDouble(Scored::cer)));
	}

	private static Map<String, Double> baselineByProfile(Map<String, Double> baseline, List<Entry> entries) {
		return entries.stream()
			.filter(e -> baseline.containsKey(e.file()))
			.collect(Collectors.groupingBy(Entry::profile, TreeMap::new,
					Collectors.averagingDouble(e -> baseline.get(e.file()))));
	}

	/** Per file: the 2a {@code merged} CER (gated hybrid, agreement 0.5, English gate). */
	private Map<String, Double> baseline(Path file) throws IOException {
		Map<String, Double> cer = new HashMap<>();
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			Map<String, Object> row = json.readValue(line, new TypeReference<Map<String, Object>>() {
			});
			if (row.get("file") instanceof String name && row.get("merged") instanceof Map<?, ?> merged) {
				cer.put(name, ((Number) merged.get("cer")).doubleValue());
			}
		}
		return cer;
	}

	private static OcrProperties properties() throws IOException {
		String token = System.getenv("MIMORAG_OCR_TOKEN");
		String tokenFile = System.getProperty("ocr.it.token-file");
		if (tokenFile != null) {
			token = Files.readString(Path.of(tokenFile)).strip();
		}
		assertThat(token).as("MIMORAG_OCR_TOKEN or -Docr.it.token-file").isNotBlank();
		String base = System.getenv().getOrDefault("MIMORAG_OCR_BASE_URL", "http://127.0.0.1:8090");
		return new OcrProperties(true, URI.create(base), token, Duration.ofSeconds(5), Duration.ofSeconds(180),
				DataSize.ofMegabytes(5), 2000, 5000, 64, 300, 40_000_000L, DataSize.ofMegabytes(30), 4, 32, "auto", 3);
	}

	private static OcrProperties withOrientationOff(OcrProperties p) {
		return new OcrProperties(true, p.baseUrl(), p.token(), p.connectTimeout(), p.requestTimeout(),
				p.maxResponseSize(), p.maxLines(), p.maxLineChars(), p.maxTokensPerLine(), p.renderDpi(),
				p.maxRenderPixels(), p.maxImageSize(), p.regionMarginPoints(), p.minRegionPixels(), "off",
				p.maxPageEngineAttempts());
	}

}
