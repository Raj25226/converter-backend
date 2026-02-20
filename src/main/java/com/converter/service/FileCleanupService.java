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

    private static final List<String> KNOWN_EXTENSIONS =
            List.of("pdf", "docx", "xlsx", "pptx", "png", "jpg", "jpeg", "webp", "mp3", "gif", "zip", "gz");

    private final ConversionRepository conversionRepository;
    private final EmailTokenRepository emailTokenRepository;
    private final Path outputPath;

    public FileCleanupService(ConversionRepository conversionRepository,
                              EmailTokenRepository emailTokenRepository,
                              @Qualifier("outputPath") Path outputPath) {
        this.conversionRepository = conversionRepository;
        this.emailTokenRepository = emailTokenRepository;
        this.outputPath           = outputPath;
    }

    /**
     * Runs every 60 seconds.
     * Finds all expired conversions, deletes their physical files, then deletes the DB records.
     */
    @Scheduled(fixedDelayString = "${file.cleanup-interval-ms:60000}")
    @Transactional
    public void cleanupExpiredFiles() {
        Instant now = Instant.now();
        List<Conversion> expired = conversionRepository.findByExpiresAtBefore(now);

        if (expired.isEmpty()) {
            log.debug("File cleanup: no expired files found");
            return;
        }

        log.info("File cleanup: found {} expired conversion records", expired.size());

        int filesDeleted = 0;
        int filesNotFound = 0;

        for (Conversion conv : expired) {
            if (conv.getFileId() == null) continue;

            for (String ext : KNOWN_EXTENSIONS) {
                Path candidate = outputPath.resolve(conv.getFileId() + "." + ext);
                try {
                    if (Files.deleteIfExists(candidate)) {
                        filesDeleted++;
                        log.debug("Deleted expired file: {}", candidate.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Could not delete expired file [{}]: {}", candidate.getFileName(), e.getMessage());
                }
            }
        }

        int deletedRecords = conversionRepository.deleteExpiredConversions(now);
        log.info("File cleanup complete: deleted {} physical files, {} DB records", filesDeleted, deletedRecords);
    }

    /**
     * Runs every hour.
     * Removes expired email tokens (verification + password reset) to keep the table clean.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredTokens() {
        log.debug("Token cleanup: starting");
        emailTokenRepository.deleteExpiredTokens(Instant.now());
        log.info("Token cleanup: expired email tokens removed");
    }
}