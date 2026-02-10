package com.converter.controller;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.dto.ApiResponse;
import com.converter.dto.TrackConversionRequest;
import com.converter.dto.TrackVisitRequest;
import com.converter.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@Slf4j
@RequestMapping("/api/analytics")
public class AnalyticsController {

    @Autowired
    private AnalyticsService analyticsService;

    @GetMapping("/stats")
    public ResponseEntity<AnalyticsStatsResponse> getStats() {
        log.info("Fetching analytics stats");
        AnalyticsStatsResponse stats = analyticsService.getStats();
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/track-visit")
    public ResponseEntity<ApiResponse> trackVisit(
            @Valid @RequestBody TrackVisitRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        analyticsService.trackVisit(request.getPage(), ipAddress, userAgent);

        return ResponseEntity.ok(ApiResponse.success("Visit tracked"));
    }

    @PostMapping("/track-conversion")
    public ResponseEntity<ApiResponse> trackConversion(
            @Valid @RequestBody TrackConversionRequest request,
            HttpServletRequest httpRequest) {

        String ipAddress = getClientIpAddress(httpRequest);
        String userAgent = httpRequest.getHeader("User-Agent");

        analyticsService.trackConversion(request.getConverterType(), ipAddress, userAgent);

        return ResponseEntity.ok(ApiResponse.success("Conversion tracked"));
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        return request.getRemoteAddr();
    }
}