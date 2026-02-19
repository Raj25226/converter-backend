package com.converter.controller;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.TrackConversionRequest;
import com.converter.dto.TrackVisitRequest;
import com.converter.service.AnalyticsService;
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

    // GET /api/analytics/stats
    @GetMapping("/stats")
    public ResponseEntity<AnalyticsStatsResponse> getStats() {
        return ResponseEntity.ok(analyticsService.getStats());
    }

    // POST /api/analytics/track-visit
    @PostMapping("/track-visit")
    public ResponseEntity<ApiResponse> trackVisit(@Valid @RequestBody TrackVisitRequest req) {
        analyticsService.trackVisit(req.getSessionId());
        return ResponseEntity.ok(ApiResponse.success("Visit tracked"));
    }

    // POST /api/analytics/track-conversion
    @PostMapping("/track-conversion")
    public ResponseEntity<ApiResponse> trackConversion(
            @Valid @RequestBody TrackConversionRequest req) {
        analyticsService.trackConversion(req.getConverterType());
        return ResponseEntity.ok(ApiResponse.success("Conversion tracked"));
    }
}