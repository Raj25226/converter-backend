package com.converter.service;

import com.converter.dto.ConversionHistoryResponse;
import com.converter.dto.ConversionResponse;
import com.converter.entity.Conversion;
import com.converter.entity.User;
import com.converter.exception.AppException;
import com.converter.exception.ConversionException;
import com.converter.exception.RateLimitException;
import com.converter.repository.ConversionRepository;
import com.converter.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversionService {

    private final FileConversionService fileConversionService;
    private final ConversionRepository conversionRepository;
    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;

    @Value("${app.backend-download-url}")
    private String downloadBaseUrl;

    @Value("${file.expiry-minutes:30}")
    private int expiryMinutes;

    @Value("${conversion.guest-daily-limit:2}")
    private int guestDailyLimit;

    @Value("${conversion.user-daily-limit:10}")
    private int userDailyLimit;

    // ─── Public convert methods (per converter type) ─────────────────────────

    public ConversionResponse pdfToWord(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "pdf-to-word",
                f -> fileConversionService.pdfToWord(f));
    }

    public ConversionResponse wordToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "word-to-pdf",
                f -> fileConversionService.wordToPdf(f));
    }

    public ConversionResponse pdfToExcel(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "pdf-to-excel",
                f -> fileConversionService.pdfToExcel(f));
    }

    public ConversionResponse excelToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "excel-to-pdf",
                f -> fileConversionService.excelToPdf(f));
    }

    public ConversionResponse pptToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "ppt-to-pdf",
                f -> fileConversionService.pptToPdf(f));
    }

    public ConversionResponse jpgToPng(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "jpg-to-png",
                f -> fileConversionService.jpgToPng(f));
    }

    public ConversionResponse pngToJpg(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "png-to-jpg",
                f -> fileConversionService.pngToJpg(f));
    }

    public ConversionResponse jpgToWebp(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "jpg-to-webp",
                f -> fileConversionService.jpgToWebp(f));
    }

    public ConversionResponse webpToPng(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "webp-to-png",
                f -> fileConversionService.webpToPng(f));
    }

    public ConversionResponse heicToJpg(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "heic-to-jpg",
                f -> fileConversionService.heicToJpg(f));
    }

    public ConversionResponse mp4ToMp3(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "mp4-to-mp3",
                f -> fileConversionService.mp4ToMp3(f));
    }

    public ConversionResponse mp4ToGif(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "mp4-to-gif",
                f -> fileConversionService.mp4ToGif(f));
    }

    public ConversionResponse wavToMp3(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "wav-to-mp3",
                f -> fileConversionService.wavToMp3(f));
    }

    public ConversionResponse zipExtract(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "zip-extract",
                f -> fileConversionService.zipExtract(f));
    }

    public ConversionResponse fileCompress(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "file-compress",
                f -> fileConversionService.fileCompress(f));
    }

    // ─── Core conversion orchestrator ────────────────────────────────────────

    @Transactional
    public ConversionResponse doConvert(MultipartFile file,
                                        Long userId,
                                        String sessionId,
                                        String ip,
                                        String converterType,
                                        ConverterFn converter) {
        // Validate file exists
        if (file == null || file.isEmpty()) {
            throw new ConversionException("No file uploaded. Please select a file and try again.");
        }

        // Check size (50MB)
        if (file.getSize() > 50L * 1024 * 1024) {
            throw new ConversionException("File exceeds the 50MB limit.");
        }

        // Rate limiting
        checkRateLimit(userId, sessionId);

        // Perform conversion
        FileConversionService.ConvertedFile result;
        try {
            result = converter.apply(file);
        } catch (ConversionException ce) {
            throw ce; // Re-throw as-is with user message
        } catch (IOException ioe) {
            log.error("IO error during {} conversion", converterType, ioe);
            throw new ConversionException("Conversion failed due to an I/O error: " + ioe.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during {} conversion", converterType, e);
            throw new ConversionException("An unexpected error occurred during conversion. Please try again.");
        }

        // Verify output file was actually created
        if (!Files.exists(result.path()) || result.path().toFile().length() == 0) {
            throw new ConversionException("Conversion produced an empty output file. Please try again.");
        }

        // Save conversion record
        Instant expiresAt = Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES);
        Conversion record = new Conversion();
        record.setConverterType(converterType);
        record.setOriginalFileName(file.getOriginalFilename());
        record.setConvertedFileName(result.outputName());
        record.setFileId(result.fileId());
        record.setFileSize(result.path().toFile().length());
        record.setSessionId(sessionId);
        record.setExpiresAt(expiresAt);
        record.setIpAddress(ip);

        // Attach user if authenticated
        if (userId != null) {
            userRepository.findById(userId).ifPresent(record::setUser);
        }
        conversionRepository.save(record);

        // Track analytics (async, non-blocking)
        analyticsService.trackConversion(converterType);

        log.info("Conversion [{}] completed: {} → {} ({} bytes)",
                converterType, file.getOriginalFilename(),
                result.outputName(), result.path().toFile().length());

        return ConversionResponse.success(
                downloadBaseUrl + result.fileId(),
                result.outputName(),
                expiresAt,
                result.path().toFile().length()
        );
    }

    // ─── Rate Limiting ───────────────────────────────────────────────────────

    private void checkRateLimit(Long userId, String sessionId) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        if (userId != null) {
            long count = conversionRepository.countTodayByUserId(userId, startOfDay);
            if (count >= userDailyLimit) {
                throw new RateLimitException(
                        "You've reached your daily limit of " + userDailyLimit +
                                " conversions. Limit resets at midnight UTC.");
            }
        } else if (sessionId != null) {
            long count = conversionRepository.countTodayBySessionId(sessionId, startOfDay);
            if (count >= guestDailyLimit) {
                throw new RateLimitException(
                        "Guest accounts are limited to " + guestDailyLimit +
                                " conversions per day. Sign up for free to get " +
                                userDailyLimit + " conversions/day!");
            }
        }
    }

    // ─── Download ────────────────────────────────────────────────────────────

    public Conversion getConversionForDownload(String fileId) {
        Conversion conv = conversionRepository.findByFileId(fileId)
                .orElseThrow(() -> new AppException("File not found or has expired",
                        HttpStatus.NOT_FOUND));

        if (conv.getExpiresAt().isBefore(Instant.now())) {
            throw new AppException(
                    "This file has expired and been deleted. Files are automatically " +
                            "deleted " + expiryMinutes + " minutes after conversion.",
                    HttpStatus.GONE);
        }

        return conv;
    }

    // ─── User History ────────────────────────────────────────────────────────

    public ConversionHistoryResponse getUserHistory(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        List<Conversion> conversions = conversionRepository.findRecentByUserId(userId);

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        long usedToday = conversionRepository.countTodayByUserId(userId, startOfDay);

        List<ConversionHistoryResponse.ConversionItem> items = conversions.stream()
                .map(c -> ConversionHistoryResponse.fromConversion(c, downloadBaseUrl))
                .collect(Collectors.toList());

        return ConversionHistoryResponse.builder()
                .status("success")
                .conversions(items)
                .usedToday((int) usedToday)
                .dailyLimit(userDailyLimit)
                .remaining(Math.max(0, userDailyLimit - (int) usedToday))
                .build();
    }

    @Transactional
    public void deleteConversionRecord(Long conversionId, Long userId) {
        Conversion conv = conversionRepository.findById(conversionId)
                .orElseThrow(() -> new AppException("Conversion not found", HttpStatus.NOT_FOUND));

        if (!conv.getUser().getId().equals(userId)) {
            throw new AppException("Forbidden", HttpStatus.FORBIDDEN);
        }

        // Delete the physical file too
        try {
            Files.deleteIfExists(conv.getExpiresAt().isBefore(Instant.now())
                    ? null // already cleaned up
                    : java.nio.file.Path.of(conv.getFileId()));
        } catch (Exception ignored) {}

        conversionRepository.delete(conv);
    }

    // ─── Functional Interface ────────────────────────────────────────────────

    @FunctionalInterface
    public interface ConverterFn {
        FileConversionService.ConvertedFile apply(MultipartFile file) throws IOException;
    }
}