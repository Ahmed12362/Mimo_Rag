package com.mimocodes.mimorag.ingestion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.mimocodes.mimorag.support.ApiTestClient;
import com.mimocodes.mimorag.support.PostgresIntegrationTest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Worker-layout benchmark for slice 2d (opt-in: {@code ./mvnw test -Pocr-bench}): one 50-page scan plus three
 * concurrent 5-page uploads from other integrations, through the real application (upload, extraction, rendering,
 * OCR job) against a real OCR container. Measures OCR pages per minute, the container's memory and CPU
 * ({@code docker stats}, sampled every 2 s), and API latency while OCR runs (an in-process request every 250 ms).
 * <p>
 * Options: {@code -Docr.bench.base-url=http://127.0.0.1:8090}, {@code -Docr.bench.token-file=...},
 * {@code -Docr.bench.container=<docker name>}, {@code -Docr.bench.label=1x4}, and the application side, e.g.
 * {@code -Dmimorag.jobs.workers.OCR_DOCUMENT=2}. Results: {@code target/ocr-bench/<label>.json}.
 */
@Tag("ocr-bench")
class OcrThroughputBenchmarkTest extends PostgresIntegrationTest {

	private static final Path CORPUS = Path.of(System.getProperty("ocr.bench.corpus",
			"benchmarks/results/ocr/corpus"));

	private static final Duration TIMEOUT = Duration.ofMinutes(30);

	private static final Pattern MEMORY = Pattern.compile("([\\d.]+)\\s*([KMG]i?B)");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JsonMapper jsonMapper;

	record Upload(String key, String jobId, int pages) {
	}

	@Test
	void fiftyPageScanAndThreeConcurrentUploads() throws Exception {
		String label = System.getProperty("ocr.bench.label", "run");
		String container = System.getProperty("ocr.bench.container");
		ApiTestClient api = new ApiTestClient(mockMvc, jsonMapper);
		List<Path> pages;
		try (Stream<Path> files = Files.list(CORPUS.resolve("scanned"))) {
			pages = files.filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
		}
		assertThat(pages).as("2a scanned corpus").isNotEmpty();

		List<String[]> tenants = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			UUID integration = api.createIntegration(ApiTestClient.uniqueCode("bench"));
			String key = api.issueKey(integration, "KB_READ", "KB_MANAGE", "DOCUMENT_UPLOAD", "DOCUMENT_REVIEW")
				.plaintext();
			tenants.add(new String[] { key, api.createKnowledgeBase(key, ApiTestClient.uniqueCode("kb")).toString() });
		}
		byte[] big = merge(pages, 0, 50);
		List<byte[]> small = List.of(merge(pages, 7, 5), merge(pages, 19, 5), merge(pages, 31, 5));

		List<Long> apiLatencies = new CopyOnWriteArrayList<>();
		List<double[]> stats = new CopyOnWriteArrayList<>();
		AtomicBoolean running = new AtomicBoolean(true);
		ExecutorService background = Executors.newFixedThreadPool(5);
		background.submit(() -> sampleDockerStats(container, running, stats));

		Instant started = Instant.now();
		List<Future<Upload>> uploads = new ArrayList<>();
		uploads.add(background.submit(() -> upload(api, tenants.get(0), big, 50)));
		for (int i = 0; i < 3; i++) {
			int index = i;
			uploads.add(background.submit(() -> upload(api, tenants.get(index + 1), small.get(index), 5)));
		}
		List<Upload> ocrJobs = new ArrayList<>();
		for (Future<Upload> upload : uploads) {
			ocrJobs.add(upload.get(10, TimeUnit.MINUTES));
		}
		String probeKey = tenants.get(1)[0];
		String probeKb = tenants.get(1)[1];
		background.submit(() -> probeApi(api, probeKey, probeKb, running, apiLatencies));

		Map<String, Object> jobs = new LinkedHashMap<>();
		Instant firstOcrStart = null;
		Instant lastOcrFinish = null;
		int pagesDone = 0;
		for (Upload job : ocrJobs) {
			JsonNode done = api.awaitJob(job.key(), job.jobId(), TIMEOUT);
			assertThat(done.get("status").asString()).as(done.toString()).isEqualTo("SUCCEEDED");
			Instant start = Instant.parse(done.get("startedAt").asString());
			Instant finish = Instant.parse(done.get("finishedAt").asString());
			firstOcrStart = firstOcrStart == null || start.isBefore(firstOcrStart) ? start : firstOcrStart;
			lastOcrFinish = lastOcrFinish == null || finish.isAfter(lastOcrFinish) ? finish : lastOcrFinish;
			pagesDone += done.get("progress").get("pagesDone").asInt();
			jobs.put(job.jobId(), Map.of("pages", job.pages(), "seconds",
					Duration.between(start, finish).toMillis() / 1000.0));
		}
		running.set(false);
		background.shutdownNow();

		double minutes = Duration.between(firstOcrStart, lastOcrFinish).toMillis() / 60000.0;
		List<Long> sorted = new ArrayList<>(apiLatencies);
		Collections.sort(sorted);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("label", label);
		result.put("measured", true);
		result.put("ocrPages", pagesDone);
		result.put("ocrMinutes", Math.round(minutes * 100) / 100.0);
		result.put("pagesPerMinute", Math.round(pagesDone / minutes * 10) / 10.0);
		result.put("wallSecondsFromFirstUpload", Duration.between(started, lastOcrFinish).toSeconds());
		result.put("peakMemoryMiB", stats.stream().mapToDouble(s -> s[0]).max().orElse(-1));
		result.put("meanCpuPercent", Math.round(stats.stream().mapToDouble(s -> s[1]).average().orElse(-1)));
		result.put("statsSamples", stats.size());
		result.put("apiRequests", sorted.size());
		result.put("apiP50Ms", percentile(sorted, 0.50));
		result.put("apiP95Ms", percentile(sorted, 0.95));
		result.put("apiMaxMs", sorted.isEmpty() ? -1 : sorted.get(sorted.size() - 1));
		result.put("jobs", jobs);
		result.put("appOcrWorkers", System.getProperty("mimorag.jobs.workers.OCR_DOCUMENT", "1"));
		Path out = Path.of("target/ocr-bench");
		Files.createDirectories(out);
		Files.writeString(out.resolve(label + ".json"),
				jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
	}

	private Upload upload(ApiTestClient api, String[] tenant, byte[] pdf, int pages) throws Exception {
		JsonNode accepted = api.uploadAccepted(tenant[0], UUID.fromString(tenant[1]), pdf);
		JsonNode extraction = api.awaitJob(tenant[0], accepted.get("jobId").asString(), TIMEOUT);
		assertThat(extraction.get("nextJobId").isNull()).as(extraction.toString()).isFalse();
		return new Upload(tenant[0], extraction.get("nextJobId").asString(), pages);
	}

	private static byte[] merge(List<Path> pages, int offset, int count) throws IOException {
		List<PDDocument> sources = new ArrayList<>();
		try (PDDocument target = new PDDocument()) {
			for (int i = 0; i < count; i++) {
				PDDocument source = Loader.loadPDF(pages.get((offset + i) % pages.size()).toFile());
				sources.add(source);
				target.importPage(source.getPage(0));
			}
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			target.save(bytes);
			return bytes.toByteArray();
		}
		finally {
			for (PDDocument source : sources) {
				source.close();
			}
		}
	}

	private static void probeApi(ApiTestClient api, String key, String kb, AtomicBoolean running, List<Long> out) {
		while (running.get()) {
			long t = System.nanoTime();
			try {
				api.readJson(api.withKey(get("/api/v1/knowledge-bases/{id}/documents", kb), key), 200);
				out.add((System.nanoTime() - t) / 1_000_000);
				Thread.sleep(250);
			}
			catch (InterruptedException ex) {
				return;
			}
			catch (Exception ex) {
				out.add(Long.MAX_VALUE / 2);
			}
		}
	}

	/** [memory MiB, CPU %] every 2 s from {@code docker stats} (measured, not estimated). */
	private static void sampleDockerStats(String container, AtomicBoolean running, List<double[]> out) {
		if (container == null) {
			return;
		}
		while (running.get()) {
			try {
				Process process = new ProcessBuilder("docker", "stats", "--no-stream", "--format",
						"{{.MemUsage}}|{{.CPUPerc}}", container)
					.redirectErrorStream(true)
					.start();
				String line = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
				process.waitFor(10, TimeUnit.SECONDS);
				String[] parts = line.split("\\|");
				Matcher m = MEMORY.matcher(parts[0]);
				if (parts.length == 2 && m.find()) {
					double value = Double.parseDouble(m.group(1));
					double mib = switch (m.group(2)) {
						case "GiB", "GB" -> value * 1024;
						case "KiB", "KB" -> value / 1024;
						default -> value;
					};
					out.add(new double[] { mib, Double.parseDouble(parts[1].replace("%", "").strip()) });
				}
				Thread.sleep(2000);
			}
			catch (InterruptedException ex) {
				return;
			}
			catch (Exception ex) {
				// a missed sample is not a failure
			}
		}
	}

	private static long percentile(List<Long> sorted, double p) {
		if (sorted.isEmpty()) {
			return -1;
		}
		return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
	}

}
