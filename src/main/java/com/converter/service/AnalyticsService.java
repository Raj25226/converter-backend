package com.converter.service;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.entity.Analytics;
import com.converter.entity.ToolStat;
import com.converter.repository.AnalyticsRepository;
import com.converter.repository.ToolStatRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final ToolStatRepository toolStatRepository;

    // In-memory session tracking (replaces Redis). Stores sessionId -> true.
    // ConcurrentHashMap is thread-safe. Sessions persist for the lifetime of the process.
    // This is sufficient for single-instance deployments. For multi-instance, use a DB table.
    private final ConcurrentHashMap<String, Boolean> seenSessions = new ConcurrentHashMap<>();

    private static final List<String> ALL_TOOLS = Arrays.asList(
            "pdf-to-word", "word-to-pdf", "pdf-to-excel", "excel-to-pdf", "ppt-to-pdf",
            "jpg-to-png", "png-to-jpg", "jpg-to-webp", "webp-to-png", "heic-to-jpg",
            "mp4-to-mp3", "mp4-to-gif", "wav-to-mp3",
            "zip-extract", "file-compress"
    );

    @PostConstruct
    @Transactional
    public void initializeData() {
        log.info("Initializing analytics data...");

        if (!analyticsRepository.existsById(1L)) {
            Analytics analytics = new Analytics();
            analyticsRepository.save(analytics);
            log.info("Analytics row created (id=1)");
        } else {
            log.info("Analytics row already exists");
        }

        int created = 0;
        for (String tool : ALL_TOOLS) {
            if (!toolStatRepository.existsById(tool)) {
                toolStatRepository.save(new ToolStat(tool));
                created++;
            }
        }
        log.info("Tool stats initialized: {} tools total, {} newly created", ALL_TOOLS.size(), created);
    }

    // ─── Track Visit ─────────────────────────────────────────────────────────
    // @Async = fire and forget, does NOT block the HTTP response.
    // Split into two methods so @Async and @Transactional are on different proxy calls.

    @Async
    public void trackVisit(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("trackVisit skipped: null or blank sessionId");
            return;
        }
        try {
            doTrackVisit(sessionId);
        } catch (Exception e) {
            log.error("Failed to track visit for session [{}]: {}", sessionId, e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doTrackVisit(String sessionId) {
        analyticsRepository.incrementTotalVisits();
        log.debug("Total visits incremented for session [{}]", sessionId);

        // putIfAbsent returns null if key was absent (i.e. new session)
        Boolean alreadySeen = seenSessions.putIfAbsent(sessionId, Boolean.TRUE);
        if (alreadySeen == null) {
            analyticsRepository.incrementUniqueVisitors();
            log.debug("Unique visitor incremented for new session [{}]", sessionId);
        } else {
            log.debug("Session [{}] already counted as unique visitor", sessionId);
        }
    }

    // ─── Track Conversion ────────────────────────────────────────────────────
    // Called ONLY from ConversionService.doConvert() — NOT from the frontend analytics endpoint.
    // The AnalyticsController.trackConversion() is for page-level frontend tracking only
    // and should NOT call this method to avoid double-counting.

    @Async
    public void trackConversion(String converterType) {
        if (converterType == null || converterType.isBlank()) {
            log.warn("trackConversion called with null/blank converterType");
            return;
        }
        try {
            doTrackConversion(converterType);
        } catch (Exception e) {
            log.error("Failed to track conversion for type [{}]: {}", converterType, e.getMessage(), e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void doTrackConversion(String converterType) {
        analyticsRepository.incrementTotalConversions();
        log.debug("Total conversions incremented for type [{}]", converterType);

        int updated = toolStatRepository.incrementCount(converterType);
        if (updated == 0) {
            // Tool not seeded yet (shouldn't normally happen after @PostConstruct)
            log.warn("Tool stat not found for [{}], creating new entry", converterType);
            ToolStat stat = new ToolStat(converterType);
            stat.setCount(1);
            toolStatRepository.save(stat);
        } else {
            log.debug("Tool stat incremented for [{}]", converterType);
        }
    }

    // ─── Get Stats ───────────────────────────────────────────────────────────

    public AnalyticsStatsResponse getStats() {
        try {
            Analytics analytics = analyticsRepository.findById(1L).orElse(new Analytics());

            long visits      = analytics.getTotalVisits();
            long conversions = analytics.getTotalConversions();
            long unique      = analytics.getUniqueVisitors();
            double rate = visits > 0
                    ? Math.round((conversions * 100.0 / visits) * 10.0) / 10.0
                    : 0.0;

            log.debug("Stats fetched: visits={}, conversions={}, unique={}, rate={}%",
                    visits, conversions, unique, rate);

            return AnalyticsStatsResponse.success(visits, conversions, unique, rate);
        } catch (Exception e) {
            log.error("Error fetching analytics stats: {}", e.getMessage(), e);
            return AnalyticsStatsResponse.success(0, 0, 0, 0.0);
        }
    }

    public List<ToolStat> getToolStats() {
        List<ToolStat> stats = toolStatRepository.findAllByOrderByCountDesc();
        log.debug("Fetched {} tool stats", stats.size());
        return stats;
    }
}