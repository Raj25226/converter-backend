package com.converter.service;

import com.converter.entity.Conversion;
import com.converter.repository.ConversionRepository;
import com.converter.repository.EmailTokenRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Service
@Slf4j
public class FileCleanupService {
    @org.springframework.beans.factory.annotation.Value("${file.history-retention-days}")
    private long historyRetentionDays;
    @org.springframework.beans.factory.annotation.Value("${file.cleanup-batch-size:100}")
    private int batchSize = 100;
    @org.springframework.beans.factory.annotation.Value("${file.cleanup-max-batches:10}")
    private int maxBatches = 10;
    @org.springframework.beans.factory.annotation.Value("${file.orphan-grace-minutes}")
    private long orphanGraceMinutes;


    private static final List<String> KNOWN_EXTENSIONS =
            List.of("pdf", "docx", "xlsx", "pptx", "png", "jpg", "jpeg", "webp", "mp3", "gif", "zip", "gz");

    private final ConversionRepository conversionRepository;
    private final EmailTokenRepository emailTokenRepository;
    private final Path outputPath;
    @org.springframework.beans.factory.annotation.Value("${file.expiry-minutes}")
    private int expiryMinutes;

    public FileCleanupService(ConversionRepository conversionRepository,
                              EmailTokenRepository emailTokenRepository,
                              @Qualifier("outputPath") Path outputPath) {
        this.conversionRepository = conversionRepository;
        this.emailTokenRepository = emailTokenRepository;
        this.outputPath           = outputPath;
    }

    /**
     * Runs every 60 seconds.
     * Deletes expired files, retaining history for 30 days.
     */
    @Scheduled(fixedDelayString = "${file.cleanup-interval-ms}")
    public void cleanupExpiredFiles() {
        Instant now = Instant.now();
        if (batchSize < 1 || maxBatches < 1) throw new IllegalStateException("Cleanup batch settings must be positive.");
        var pageable = org.springframework.data.domain.PageRequest.of(0, batchSize);
        long after = 0; int filesDeleted = 0, deletedRecords = 0;
        for (int batch = 0; batch < maxBatches; batch++) {
            var expired = conversionRepository.findExpiredBatch(now, after, pageable);
            if (expired.isEmpty()) break;
            var completed = new java.util.ArrayList<Long>();
            for (Conversion conversion : expired) {
                after = conversion.getId(); boolean removed = true;
                for (String extension : KNOWN_EXTENSIONS) {
                    try {
                        if (Files.deleteIfExists(outputPath.resolve(conversion.getFileId() + "." + extension))) filesDeleted++;
                    } catch (IOException failure) {
                        removed = false; log.warn("Could not delete expired file for conversion {}", conversion.getId(), failure);
                    }
                }
                if (removed) completed.add(conversion.getId());
            }
            // Each repository update commits separately; filesystem IO holds no database transaction.
            if (!completed.isEmpty()) conversionRepository.clearExpiredFiles(completed, now);
            if (expired.size() < batchSize) break;
        }
        Instant retention = now.minus(historyRetentionDays, java.time.temporal.ChronoUnit.DAYS);
        for (int batch = 0; batch < maxBatches; batch++) {
            var ids = conversionRepository.findRetiredBatch(retention, pageable);
            if (ids.isEmpty()) break;
            deletedRecords += conversionRepository.deleteRetiredBatch(ids, retention);
            if (ids.size() < batchSize) break;
        }
        log.info("File cleanup complete: deleted {} files and {} history records", filesDeleted, deletedRecords);
    }

    // Reclaim orphaned outputs and work directories after expiry plus a processing grace period.
    @Scheduled(fixedDelayString = "${file.orphan-cleanup-interval-ms}")
    public void cleanupOrphans() {
        Instant cutoff = Instant.now().minus(expiryMinutes + orphanGraceMinutes, java.time.temporal.ChronoUnit.MINUTES);
        try (var entries = Files.list(outputPath)) {
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (!(name.matches("[a-f0-9]{32}\\.[a-zA-Z0-9]+") || (name.startsWith("office-") || name.startsWith("work-")))) continue;
                if (!Files.getLastModifiedTime(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(cutoff)) continue;
                try (var paths = Files.walk(entry)) {
                    for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                } catch (IOException e) { log.warn("Could not reclaim orphaned output", e); }
            }
        } catch (IOException e) { log.warn("Orphan cleanup failed", e); }
    }

    /**
     * Runs every hour.
     * Removes expired email tokens (verification + password reset) to keep the table clean.
     */
    @Scheduled(fixedDelayString = "${auth.token-cleanup-interval-ms}")
    @Transactional
    public void cleanupExpiredTokens() {
        log.debug("Token cleanup: starting");
        emailTokenRepository.deleteExpiredTokens(Instant.now());
        log.info("Token cleanup: expired email tokens removed");
    }
}
