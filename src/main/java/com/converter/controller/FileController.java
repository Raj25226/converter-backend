package com.converter.controller;

import com.converter.dto.ConversionHistoryResponse;
import com.converter.entity.Conversion;
import com.converter.service.ConversionService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final ConversionService conversionService;
    private final @Qualifier("outputPath") Path outputPath;

    // GET /api/download/:fileId
    @GetMapping("/api/download/{fileId}")
    public ResponseEntity<Resource> download(@PathVariable String fileId) {
        // Validate against DB (checks expiry)
        Conversion conv = conversionService.getConversionForDownload(fileId);

        // Find the actual file on disk
        String fileName = conv.getConvertedFileName();
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1) : "";

        File file = null;
        if (!ext.isEmpty()) {
            file = outputPath.resolve(fileId + "." + ext).toFile();
        }

        // Fallback: scan for any file with this UUID
        if (file == null || !file.exists()) {
            for (String candidate : new String[]{"pdf","docx","xlsx","png","jpg","webp","mp3","gif","zip","gz"}) {
                File f = outputPath.resolve(fileId + "." + candidate).toFile();
                if (f.exists()) { file = f; break; }
            }
        }

        if (file == null || !file.exists()) {
            log.warn("File not found on disk for fileId: {}", fileId);
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        String contentType = resolveContentType(ext);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + conv.getConvertedFileName() + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(file.length())
                .body(resource);
    }

    // GET /api/user/history (🔒 auth required)
    @GetMapping("/api/user/history")
    public ResponseEntity<ConversionHistoryResponse> getHistory(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(conversionService.getUserHistory(userId));
    }

    // DELETE /api/user/history/:id (🔒 auth required)
    @DeleteMapping("/api/user/history/{id}")
    public ResponseEntity<Void> deleteHistory(@PathVariable Long id, Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        conversionService.deleteConversionRecord(id, userId);
        return ResponseEntity.noContent().build();
    }

    private String resolveContentType(String ext) {
        return switch (ext.toLowerCase()) {
            case "pdf"  -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png"  -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif"  -> "image/gif";
            case "mp3"  -> "audio/mpeg";
            case "zip"  -> "application/zip";
            case "gz"   -> "application/gzip";
            default     -> "application/octet-stream";
        };
    }
}