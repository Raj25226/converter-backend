package com.converter.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Service
public class FileCleanupService {

    private static final Logger logger = LoggerFactory.getLogger(FileCleanupService.class);

    @Autowired
    private Path fileStorageLocation;

    @Value("${file.cleanup-delay}")
    private long cleanupDelay;

    @Scheduled(fixedRate = 300000)
    public void cleanupOldFiles() {
        logger.info("Starting file cleanup task");

        File uploadDir = fileStorageLocation.toFile();
        if (!uploadDir.exists()) {
            return;
        }

        File[] files = uploadDir.listFiles();
        if (files == null) {
            return;
        }

        long currentTime = Instant.now().toEpochMilli();
        int deletedCount = 0;

        for (File file : files) {
            long fileAge = currentTime - file.lastModified();

            if (fileAge > cleanupDelay) {
                try {
                    Files.delete(file.toPath());
                    deletedCount++;
                    logger.debug("Deleted old file: {}", file.getName());
                } catch (IOException e) {
                    logger.error("Failed to delete file: {}", file.getName(), e);
                }
            }
        }

        if (deletedCount > 0) {
            logger.info("Cleanup completed. Deleted {} files", deletedCount);
        }
    }
}