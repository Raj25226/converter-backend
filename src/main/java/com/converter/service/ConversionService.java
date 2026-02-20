package com.converter.service;

import com.converter.dto.ConversionHistoryResponse;
import com.converter.dto.ConversionResponse;
import com.converter.entity.Conversion;
import com.converter.exception.AppException;
import com.converter.exception.ConversionException;
import com.converter.exception.RateLimitException;
import com.converter.repository.ConversionRepository;
import com.converter.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ConversionService {

    private final FileConversionService fileConversionService;
    private final ConversionRepository conversionRepository;
    private final UserRepository userRepository;
    private final AnalyticsService analyticsService;
    private final Path outputPath;

    @Value("${app.backend-download-url}")
    private String downloadBaseUrl;

    @Value("${file.expiry-minutes:30}")
    private int expiryMinutes;

    @Value("${conversion.guest-daily-limit:2}")
    private int guestDailyLimit;

    @Value("${conversion.user-daily-limit:10}")
    private int userDailyLimit;

    // Explicit constructor because @Qualifier on fields doesn't work with @RequiredArgsConstructor
    public ConversionService(FileConversionService fileConversionService,
                             ConversionRepository conversionRepository,
                             UserRepository userRepository,
                             AnalyticsService analyticsService,
                             @Qualifier("outputPath") Path outputPath) {
        this.fileConversionService = fileConversionService;
        this.conversionRepository  = conversionRepository;
        this.userRepository        = userRepository;
        this.analyticsService      = analyticsService;
        this.outputPath            = outputPath;
    }

    // ─── Public convert methods ───────────────────────────────────────────────

    public ConversionResponse pdfToWord(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "pdf-to-word", fileConversionService::pdfToWord);
    }

    public ConversionResponse wordToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "word-to-pdf", fileConversionService::wordToPdf);
    }

    public ConversionResponse pdfToExcel(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "pdf-to-excel", fileConversionService::pdfToExcel);
    }

    public ConversionResponse excelToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "excel-to-pdf", fileConversionService::excelToPdf);
    }

    public ConversionResponse pptToPdf(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "ppt-to-pdf", fileConversionService::pptToPdf);
    }

    public ConversionResponse jpgToPng(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "jpg-to-png", fileConversionService::jpgToPng);
    }

    public ConversionResponse pngToJpg(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "png-to-jpg", fileConversionService::pngToJpg);
    }

    public ConversionResponse jpgToWebp(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "jpg-to-webp", fileConversionService::jpgToWebp);
    }

    public ConversionResponse webpToPng(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "webp-to-png", fileConversionService::webpToPng);
    }

    public ConversionResponse heicToJpg(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "heic-to-jpg", fileConversionService::heicToJpg);
    }

    public ConversionResponse mp4ToMp3(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "mp4-to-mp3", fileConversionService::mp4ToMp3);
    }

    public ConversionResponse mp4ToGif(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "mp4-to-gif", fileConversionService::mp4ToGif);
    }

    public ConversionResponse wavToMp3(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "wav-to-mp3", fileConversionService::wavToMp3);
    }

    public ConversionResponse zipExtract(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "zip-extract", fileConversionService::zipExtract);
    }

    public ConversionResponse fileCompress(MultipartFile file, Long userId, String sessionId, String ip) {
        return doConvert(file, userId, sessionId, ip, "file-compress", fileConversionService::fileCompress);
    }

    // ─── Core conversion orchestrator ────────────────────────────────────────

    @Transactional
    public ConversionResponse doConvert(MultipartFile file,
                                        Long userId,
                                        String sessionId,
                                        String ip,
                                        String converterType,
                                        ConverterFn converter) {

        log.info("Conversion request: type=[{}], user=[{}], session=[{}], ip=[{}], file=[{}], size=[{}]",
                converterType, userId != null ? userId : "guest", sessionId, ip,
                file != null ? file.getOriginalFilename() : "null",
                file != null ? file.getSize() : 0);

        if (file == null || file.isEmpty()) {
            log.warn("Conversion rejected: no file uploaded for type=[{}]", converterType);
            throw new ConversionException("No file uploaded. Please select a file and try again.");
        }

        if (file.getSize() > 50L * 1024 * 1024) {
            log.warn("Conversion rejected: file too large ({} bytes) for type=[{}]",
                    file.getSize(), converterType);
            throw new ConversionException("File size exceeds the 50MB limit.");
        }

        checkRateLimit(userId, sessionId);

        long startMs = System.currentTimeMillis();

        FileConversionService.ConvertedFile result;
        try {
            result = converter.apply(file);
        } catch (ConversionException ce) {
            log.warn("Conversion failed [{}]: {}", converterType, ce.getMessage());
            throw ce;
        } catch (IOException ioe) {
            log.error("IO error during [{}] conversion of file [{}]",
                    converterType, file.getOriginalFilename(), ioe);
            throw new ConversionException("Conversion failed due to an I/O error: " + ioe.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error during [{}] conversion of file [{}]",
                    converterType, file.getOriginalFilename(), e);
            throw new ConversionException("An unexpected error occurred during conversion. Please try again.");
        }

        if (!Files.exists(result.path()) || result.path().toFile().length() == 0) {
            log.error("Conversion produced empty output for type=[{}], file=[{}]",
                    converterType, file.getOriginalFilename());
            throw new ConversionException("Conversion produced an empty output file. The input file may be corrupted.");
        }

        long durationMs = System.currentTimeMillis() - startMs;
        long outputSize = result.path().toFile().length();

        // Persist conversion record
        Instant expiresAt = Instant.now().plus(expiryMinutes, ChronoUnit.MINUTES);
        Conversion record = new Conversion();
        record.setConverterType(converterType);
        record.setOriginalFileName(file.getOriginalFilename());
        record.setConvertedFileName(result.outputName());
        record.setFileId(result.fileId());
        record.setFileSize(outputSize);
        record.setSessionId(sessionId);
        record.setExpiresAt(expiresAt);
        record.setIpAddress(ip);

        if (userId != null) {
            userRepository.findById(userId).ifPresentOrElse(
                    record::setUser,
                    () -> log.warn("User id=[{}] not found when saving conversion record", userId)
            );
        }
        conversionRepository.save(record);

        // ✅ Track analytics ONCE — only here, never in AnalyticsController.trackConversion()
        analyticsService.trackConversion(converterType);

        log.info("Conversion SUCCESS: type=[{}], file=[{}] → [{}], size=[{}] bytes, duration=[{}ms]",
                converterType, file.getOriginalFilename(), result.outputName(), outputSize, durationMs);

        return ConversionResponse.success(
                downloadBaseUrl + result.fileId(),
                result.outputName(),
                expiresAt,
                outputSize
        );
    }

    // ─── Rate Limiting ───────────────────────────────────────────────────────

    private void checkRateLimit(Long userId, String sessionId) {
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();

        if (userId != null) {
            long count = conversionRepository.countTodayByUserId(userId, startOfDay);
            log.debug("Rate limit check: user=[{}] used=[{}] limit=[{}]", userId, count, userDailyLimit);
            if (count >= userDailyLimit) {
                log.warn("Rate limit exceeded for user=[{}]: count=[{}]", userId, count);
                throw new RateLimitException(
                        "You've reached your daily limit of " + userDailyLimit +
                                " conversions. Limit resets at midnight UTC.");
            }
        } else if (sessionId != null && !sessionId.isBlank()) {
            long count = conversionRepository.countTodayBySessionId(sessionId, startOfDay);
            log.debug("Rate limit check: session=[{}] used=[{}] limit=[{}]", sessionId, count, guestDailyLimit);
            if (count >= guestDailyLimit) {
                log.warn("Rate limit exceeded for guest session=[{}]: count=[{}]", sessionId, count);
                throw new RateLimitException(
                        "Guest accounts are limited to " + guestDailyLimit +
                                " conversions per day. Sign up free to get " + userDailyLimit + "/day!");
            }
        } else {
            log.debug("Rate limit check skipped: no userId or sessionId provided");
        }
    }

    // ─── Download ────────────────────────────────────────────────────────────

    public Conversion getConversionForDownload(String fileId) {
        log.debug("Download requested for fileId=[{}]", fileId);

        Conversion conv = conversionRepository.findByFileId(fileId)
                .orElseThrow(() -> {
                    log.warn("Download failed: fileId=[{}] not found in DB", fileId);
                    return new AppException("File not found or has already expired.", HttpStatus.NOT_FOUND);
                });

        if (conv.getExpiresAt().isBefore(Instant.now())) {
            log.info("Download rejected: fileId=[{}] expired at [{}]", fileId, conv.getExpiresAt());
            throw new AppException(
                    "This file has expired and been deleted. Files are automatically deleted " +
                            expiryMinutes + " minutes after conversion. Please convert again.",
                    HttpStatus.GONE);
        }

        log.debug("Download authorized for fileId=[{}], expires=[{}]", fileId, conv.getExpiresAt());
        return conv;
    }

    // ─── User History ────────────────────────────────────────────────────────

    public ConversionHistoryResponse getUserHistory(Long userId) {
        log.debug("Fetching conversion history for user=[{}]", userId);

        userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        List<Conversion> conversions = conversionRepository.findRecentByUserId(userId);

        Instant startOfDay = LocalDate.now(ZoneOffset.UTC)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        long usedToday = conversionRepository.countTodayByUserId(userId, startOfDay);

        log.debug("User=[{}] history: {} total records, {} used today", userId, conversions.size(), usedToday);

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
        log.info("Delete conversion record request: conversionId=[{}], userId=[{}]", conversionId, userId);

        Conversion conv = conversionRepository.findById(conversionId)
                .orElseThrow(() -> new AppException("Conversion not found", HttpStatus.NOT_FOUND));

        if (conv.getUser() == null || !conv.getUser().getId().equals(userId)) {
            log.warn("Forbidden delete attempt: conversionId=[{}] does not belong to userId=[{}]",
                    conversionId, userId);
            throw new AppException("You don't have permission to delete this record.", HttpStatus.FORBIDDEN);
        }

        if (conv.getFileId() != null && !conv.getExpiresAt().isBefore(Instant.now())) {
            List<String> exts = List.of("pdf","docx","xlsx","pptx","png","jpg","jpeg","webp","mp3","gif","zip","gz");
            for (String ext : exts) {
                try {
                    Path candidate = outputPath.resolve(conv.getFileId() + "." + ext);
                    if (Files.deleteIfExists(candidate)) {
                        log.debug("Deleted physical file: {}", candidate.getFileName());
                    }
                } catch (IOException e) {
                    log.warn("Could not delete file [{}.{}]: {}", conv.getFileId(), ext, e.getMessage());
                }
            }
        }

        conversionRepository.delete(conv);
        log.info("Conversion record deleted: conversionId=[{}] for userId=[{}]", conversionId, userId);
    }

    // ─── Functional Interface ─────────────────────────────────────────────────

    @FunctionalInterface
    public interface ConverterFn {
        FileConversionService.ConvertedFile apply(MultipartFile file) throws IOException;
    }
}