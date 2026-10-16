package com.converter.controller;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.TrackConversionRequest;
import com.converter.dto.TrackVisitRequest;
import com.converter.service.AnalyticsService;
import com.converter.service.QuotaService;
import com.converter.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {
    @Value("${analytics.requests-per-window}")
    private int requestLimit;

    private final AnalyticsService analyticsService;
    private final QuotaService quotaService;
    private final IpUtil ipUtil;

    @GetMapping("/stats")
    public ResponseEntity<AnalyticsStatsResponse> getStats() {
        log.debug("Analytics stats requested");
        return ResponseEntity.ok(analyticsService.getStats());
    }

    @PostMapping("/track-visit")
    public ResponseEntity<ApiResponse> trackVisit(
            @Valid @RequestBody TrackVisitRequest req, HttpServletRequest httpReq) {
        log.debug(
                "Track visit: session=[{}], page=[{}], ip=[{}]",
                req.getSessionId(),
                req.getPage(),
                httpReq.getRemoteAddr());
        quotaService.reserveRequest(ipUtil.getClientIp(httpReq), "visit", requestLimit);
        analyticsService.trackVisit(req.getSessionId());
        return ResponseEntity.ok(ApiResponse.success("Visit tracked"));
    }

    @PostMapping("/track-conversion")
    public ResponseEntity<ApiResponse> trackConversion(
            @Valid @RequestBody TrackConversionRequest req) {
        log.debug(
                "Frontend conversion event received for type=[{}] (not double-counted)",
                req.getConverterType());
        return ResponseEntity.ok(ApiResponse.success("Conversion event received"));
    }
}
