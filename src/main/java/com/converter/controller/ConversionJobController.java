package com.converter.controller;

import com.converter.dto.ConversionJobResponse;
import com.converter.service.ConversionJobService;
import com.converter.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class ConversionJobController {
    private final ConversionJobService conversionJobService;
    private final IpUtil ipUtil;

    @PostMapping(value = "/{tool}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ConversionJobResponse> submit(
            @PathVariable String tool,
            @RequestParam("file") MultipartFile file,
            @RequestParam String requestId,
            @RequestParam(required = false) String sessionId,
            Authentication auth,
            HttpServletRequest request)
            throws IOException {
        return ResponseEntity.accepted()
                .cacheControl(CacheControl.noStore())
                .body(
                        conversionJobService.submit(
                                tool,
                                file,
                                requestId,
                                userId(auth),
                                sessionId,
                                ipUtil.getClientIp(request)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ConversionJobResponse> get(@PathVariable String id, Authentication auth) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(conversionJobService.get(id, userId(auth)));
    }

    @GetMapping("/request/{requestId}")
    public ResponseEntity<ConversionJobResponse> byRequest(
            @PathVariable String requestId, Authentication auth, HttpServletRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(
                        conversionJobService.byRequest(
                                requestId, userId(auth), ipUtil.getClientIp(request)));
    }

    @GetMapping
    public ResponseEntity<List<ConversionJobResponse>> recent(
            @RequestParam String tool, Authentication auth) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(conversionJobService.recent(userId(auth), tool));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ConversionJobResponse> cancel(
            @PathVariable String id, Authentication auth) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(conversionJobService.cancel(id, userId(auth)));
    }

    private Long userId(Authentication auth) {
        return auth != null && auth.getPrincipal() instanceof Long id ? id : null;
    }
}
