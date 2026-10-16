package com.converter.controller;

import com.converter.dto.ApiResponse;
import com.converter.dto.ConversionHistoryResponse;
import com.converter.entity.Conversion;
import com.converter.service.ConversionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Path;

@RestController
@Slf4j
public class FileController {

    private final ConversionService conversionService;
    private final Path outputPath;

    // ✅ Explicit constructor — @Qualifier does NOT work with @RequiredArgsConstructor on fields
    public FileController(ConversionService conversionService,
                          @Qualifier("outputPath") Path outputPath) {
        this.conversionService = conversionService;
        this.outputPath        = outputPath;
    }

    // GET /api/download/:fileId — public, no auth required
    @GetMapping("/api/download/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId) {
        log.info("Download request for fileId=[{}]", fileId);

        // Basic validation — reject anything that looks like path traversal
        if (fileId == null || !fileId.matches("[a-f0-9]{32}")) {
            log.warn("Download rejected: invalid fileId=[{}]", fileId);
            return ResponseEntity.badRequest().build();
        }

        // Validate DB record and check expiry
        Conversion conv = conversionService.getConversionForDownload(fileId);

        // Determine extension from stored file name
        String convertedName = conv.getConvertedFileName();
        String ext = "";
        if (convertedName != null && convertedName.contains(".")) {
            ext = convertedName.substring(convertedName.lastIndexOf('.') + 1).toLowerCase();
        }

        // Try primary extension first
        File file = null;
        if (!ext.isEmpty()) {
            File candidate = outputPath.resolve(fileId + "." + ext).toFile();
            if (java.nio.file.Files.isRegularFile(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                file = candidate;
                log.debug("Found file by primary extension: {}.{}", fileId, ext);
            }
        }

        // Fallback: scan all known extensions
        if (file == null) {
            for (String tryExt : new String[]{"pdf","docx","xlsx","pptx","png","jpg","webp","mp3","gif","zip","gz"}) {
                File candidate = outputPath.resolve(fileId + "." + tryExt).toFile();
                if (java.nio.file.Files.isRegularFile(candidate.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    file = candidate;
                    ext  = tryExt;
                    log.debug("Found file by fallback extension: {}.{}", fileId, tryExt);
                    break;
                }
            }
        }

        if (file == null || !file.exists()) {
            log.warn("Download failed: physical file missing on disk for fileId=[{}]", fileId);
            return ResponseEntity.notFound().build();
        }

        String contentType  = resolveContentType(ext);
        String safeFilename = sanitizeFilename(convertedName != null ? convertedName : fileId);

        log.info("Download serving: fileId=[{}], name=[{}], size=[{}] bytes, type=[{}]",
                fileId, safeFilename, file.length(), contentType);

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment().filename(safeFilename, java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(file.length())
                .body(new FileSystemResource(file));
    }

    // GET /api/user/history — requires authentication
    @GetMapping("/api/user/history")
    public ResponseEntity<ConversionHistoryResponse> getHistory(Authentication auth, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) auth.getPrincipal();
        log.debug("Conversion history requested for userId=[{}]", userId);
        return ResponseEntity.ok(conversionService.getUserHistory(userId, page, size));
    }

    // DELETE /api/user/history/:id — requires authentication
    @DeleteMapping("/api/user/history/{id}")
    public ResponseEntity<ApiResponse> deleteHistory(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        log.info("Delete history record: conversionId=[{}] by userId=[{}]", id, userId);
        conversionService.deleteConversionRecord(id, userId);
        return ResponseEntity.ok(ApiResponse.success("Conversion record deleted successfully"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveContentType(String ext) {
        if (ext == null) return "application/octet-stream";
        return switch (ext.toLowerCase()) {
            case "pdf"        -> "application/pdf";
            case "docx"       -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx"       -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx"       -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png"        -> "image/png";
            case "jpg","jpeg" -> "image/jpeg";
            case "webp"       -> "image/webp";
            case "gif"        -> "image/gif";
            case "mp3"        -> "audio/mpeg";
            case "zip"        -> "application/zip";
            case "gz"         -> "application/gzip";
            default           -> "application/octet-stream";
        };
    }

    /** Remove characters that could cause issues in Content-Disposition header. */
    private String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "download";
        return org.apache.commons.io.FilenameUtils.getName(name).replaceAll("[\\p{Cntrl}]", "_");
    }
}