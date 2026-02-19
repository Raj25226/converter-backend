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

    private final ConversionRepository conversionRepository;
    private final EmailTokenRepository emailTokenRepository;
    private final Path outputPath;

    public FileCleanupService(ConversionRepository conversionRepository,
                              EmailTokenRepository emailTokenRepository,
                              @Qualifier("outputPath") Path outputPath) {
        this.conversionRepository = conversionRepository;
        this.emailTokenRepository = emailTokenRepository;
        this.outputPath = outputPath;
    }

    /**
     * Run every 60 seconds: delete expired conversion files and DB records.
     */
    @Scheduled(fixedDelayString = "${file.cleanup-interval-ms:60000}")
    @Transactional
    public void cleanupExpiredFiles() {
        Instant now = Instant.now();

        List<Conversion> expired = conversionRepository.findByExpiresAtBefore(now);
        if (expired.isEmpty()) return;

        int fileCount = 0;
        int recordCount = 0;

        for (Conversion conv : expired) {
            // Delete physical file
            if (conv.getFileId() != null) {
                Path file = outputPath.resolve(conv.getFileId() + ".*");
                // Try common extensions
                for (String ext : List.of("pdf","docx","xlsx","pptx","png","jpg","webp","mp3","gif","zip","gz")) {
                    try {
                        Path candidate = outputPath.resolve(conv.getFileId() + "." + ext);
                        if (Files.deleteIfExists(candidate)) {
                            fileCount++;
                            log.debug("Deleted expired file: {}", candidate.getFileName());
                        }
                    } catch (IOException e) {
                        log.warn("Could not delete file {}: {}", conv.getFileId(), e.getMessage());
                    }
                }
            }
            recordCount++;
        }

        int deletedRecords = conversionRepository.deleteExpiredConversions(now);

        if (recordCount > 0) {
            log.info("Cleanup: deleted {} files, {} DB records", fileCount, deletedRecords);
        }
    }

    /**
     * Run once per hour: clean expired email tokens.
     */
    @Scheduled(fixedDelay = 3_600_000)
    @Transactional
    public void cleanupExpiredTokens() {
        emailTokenRepository.deleteExpiredTokens(Instant.now());
        log.debug("Cleaned up expired email tokens");
    }
}