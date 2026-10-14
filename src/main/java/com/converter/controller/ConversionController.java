package com.converter.controller;

import com.converter.dto.ConversionResponse;
import com.converter.service.ConversionService;
import com.converter.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/convert")
@RequiredArgsConstructor
@Slf4j
public class ConversionController {

    private final ConversionService conversionService;
    private final IpUtil ipUtil;

    // ─── Documents ───────────────────────────────────────────────────────────

    @PostMapping("/pdf-to-word")
    public ResponseEntity<ConversionResponse> pdfToWord(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.pdfToWord(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/word-to-pdf")
    public ResponseEntity<ConversionResponse> wordToPdf(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.wordToPdf(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/pdf-to-excel")
    public ResponseEntity<ConversionResponse> pdfToExcel(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.pdfToExcel(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/excel-to-pdf")
    public ResponseEntity<ConversionResponse> excelToPdf(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.excelToPdf(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/ppt-to-pdf")
    public ResponseEntity<ConversionResponse> pptToPdf(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.pptToPdf(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    // ─── Images ──────────────────────────────────────────────────────────────

    @PostMapping("/jpg-to-png")
    public ResponseEntity<ConversionResponse> jpgToPng(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.jpgToPng(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/png-to-jpg")
    public ResponseEntity<ConversionResponse> pngToJpg(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.pngToJpg(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/jpg-to-webp")
    public ResponseEntity<ConversionResponse> jpgToWebp(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.jpgToWebp(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/webp-to-png")
    public ResponseEntity<ConversionResponse> webpToPng(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.webpToPng(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/heic-to-jpg")
    public ResponseEntity<ConversionResponse> heicToJpg(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.heicToJpg(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    // ─── Media ───────────────────────────────────────────────────────────────

    @PostMapping("/mp4-to-mp3")
    public ResponseEntity<ConversionResponse> mp4ToMp3(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.mp4ToMp3(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/mp4-to-gif")
    public ResponseEntity<ConversionResponse> mp4ToGif(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.mp4ToGif(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/wav-to-mp3")
    public ResponseEntity<ConversionResponse> wavToMp3(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.wavToMp3(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    // ─── Utilities ───────────────────────────────────────────────────────────

    @PostMapping("/zip-extract")
    public ResponseEntity<ConversionResponse> zipExtract(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.zipExtract(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    @PostMapping("/file-compress")
    public ResponseEntity<ConversionResponse> fileCompress(
            @RequestParam("file") MultipartFile file,
            Authentication auth,
            @RequestParam(required = false) String sessionId,
            HttpServletRequest req) {
        return ok(conversionService.fileCompress(file, userId(auth), sessionId, ipUtil.getClientIp(req)));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<ConversionResponse> ok(ConversionResponse r) {
        return ResponseEntity.ok(r);
    }

    private Long userId(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
}