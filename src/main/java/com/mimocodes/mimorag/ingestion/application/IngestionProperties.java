package com.mimocodes.mimorag.ingestion.application;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Upload and storage limits ({@code mimorag.ingestion.*}; environment variables such as
 * {@code MIMORAG_INGESTION_STORAGE_ROOT} override them). Defaults follow decisions D-23 and D-29.
 *
 * @param storageRoot where originals are stored; outside the repository in real use (e.g. {@code D:\mimorag-data})
 * @param maxFileSize largest accepted upload
 * @param maxPages largest accepted page count (checked by the worker)
 * @param maxUnfinishedJobsPerIntegration uploads waiting or running per integration before new uploads get 429
 * @param storageQuotaPerIntegration total bytes of stored originals per integration
 * @param queueFullRetryAfter {@code Retry-After} sent with 429
 * @param validationTimeout longest time the worker may spend checking one PDF's structure
 * @param pageTimeout longest time native extraction may spend on one page (the page is then marked failed)
 * @param extractionBatchSize pages committed per transaction during extraction (what a crash can lose at most)
 */
@ConfigurationProperties("mimorag.ingestion")
public record IngestionProperties(@DefaultValue("data/storage") Path storageRoot,
		@DefaultValue("50MB") DataSize maxFileSize, @DefaultValue("500") int maxPages,
		@DefaultValue("20") int maxUnfinishedJobsPerIntegration, @DefaultValue("2GB") DataSize storageQuotaPerIntegration,
		@DefaultValue("PT30S") Duration queueFullRetryAfter, @DefaultValue("PT60S") Duration validationTimeout,
		@DefaultValue("PT30S") Duration pageTimeout, @DefaultValue("20") int extractionBatchSize) {

	public IngestionProperties {
		if (maxFileSize.toBytes() < 1024) {
			throw new IllegalArgumentException("mimorag.ingestion.max-file-size must be at least 1KB");
		}
		if (maxPages < 1) {
			throw new IllegalArgumentException("mimorag.ingestion.max-pages must be at least 1");
		}
		if (extractionBatchSize < 1 || extractionBatchSize > 500) {
			throw new IllegalArgumentException("mimorag.ingestion.extraction-batch-size must be between 1 and 500");
		}
		if (maxUnfinishedJobsPerIntegration < 1) {
			throw new IllegalArgumentException("mimorag.ingestion.max-unfinished-jobs-per-integration must be >= 1");
		}
	}

}
