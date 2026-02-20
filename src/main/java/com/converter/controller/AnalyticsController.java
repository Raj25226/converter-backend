package com.converter.controller;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.TrackConversionRequest;
import com.converter.dto.TrackVisitRequest;
import com.converter.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Slf4j
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/stats")
    public ResponseEntity<AnalyticsStatsResponse> getStats() {
        log.debug("Analytics stats requested");
        return ResponseEntity.ok(analyticsService.getStats());
    }

    @PostMapping("/track-visit")
    public ResponseEntity<ApiResponse> trackVisit(
            @Valid @RequestBody TrackVisitRequest req,
            HttpServletRequest httpReq) {
        log.debug("Track visit: session=[{}], page=[{}], ip=[{}]",
                req.getSessionId(), req.getPage(), httpReq.getRemoteAddr());
        analyticsService.trackVisit(req.getSessionId());
        return ResponseEntity.ok(ApiResponse.success("Visit tracked"));
    }

    @PostMapping("/track-conversion")
    public ResponseEntity<ApiResponse> trackConversion(
            @Valid @RequestBody TrackConversionRequest req) {
        log.debug("Frontend conversion event received for type=[{}] (not double-counted)", req.getConverterType());
        return ResponseEntity.ok(ApiResponse.success("Conversion event received"));
    }
}