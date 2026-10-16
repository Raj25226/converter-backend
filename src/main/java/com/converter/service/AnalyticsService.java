package com.converter.service;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.entity.Analytics;
import com.converter.entity.ToolStat;
import com.converter.repository.AnalyticsRepository;
import com.converter.repository.ToolStatRepository;
import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {
    @Value("${analytics.max-tracked-sessions}")
    private int maxTrackedSessions;

    private final AnalyticsRepository analyticsRepository;
    private final ToolStatRepository toolStatRepository;
    // ✅ Inject the writer bean — NOT a self-call, so Spring's @Transactional proxy fires correctly
    private final AnalyticsWriter analyticsWriter;

    /**
     * In-memory session deduplication (replaces Redis). Thread-safe. Resets on process restart —
     * acceptable for single-instance deployments.
     */
    private final ConcurrentHashMap<String, Boolean> seenSessions = new ConcurrentHashMap<>();

    private static final List<String> ALL_TOOLS =
            Arrays.asList(
                    "pdf-to-word",
                    "word-to-pdf",
                    "pdf-to-excel",
                    "excel-to-pdf",
                    "ppt-to-pdf",
                    "jpg-to-png",
                    "png-to-jpg",
                    "jpg-to-webp",
                    "webp-to-png",
                    "heic-to-jpg",
                    "mp4-to-mp3",
                    "mp4-to-gif",
                    "wav-to-mp3",
                    "zip-extract",
                    "file-compress");

    @PostConstruct
    @Transactional
    public void initializeData() {
        log.info("Initializing analytics data...");

        if (!analyticsRepository.existsById(1L)) {
            analyticsRepository.save(new Analytics());
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
        log.info(
                "Tool stats initialized: {} tools total, {} newly created",
                ALL_TOOLS.size(),
                created);
    }

    @Scheduled(fixedDelayString = "${analytics.session-cleanup-interval-ms}")
    public void clearVisitorCache() {
        seenSessions.clear();
    }

    // ─── Track Visit ──────────────────────────────────────────────────────────

    /**
     * Called from AnalyticsController on every page view. @Async = fire-and-forget, never blocks
     * the HTTP response. Delegates DB writes to AnalyticsWriter so @Transactional proxy is not
     * bypassed.
     */
    @Async
    public void trackVisit(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("trackVisit skipped: null or blank sessionId");
            return;
        }
        try {
            if (seenSessions.size() >= maxTrackedSessions) seenSessions.clear();
            boolean isNewSession = seenSessions.putIfAbsent(sessionId, Boolean.TRUE) == null;
            analyticsWriter.writeVisit(isNewSession);
            if (isNewSession) {
                log.debug("New unique visitor recorded for session=[{}]", sessionId);
            } else {
                log.debug("Returning visitor, session=[{}]", sessionId);
            }
        } catch (Exception e) {
            log.error("Failed to track visit for session=[{}]: {}", sessionId, e.getMessage(), e);
        }
    }

    // ─── Track Conversion ────────────────────────────────────────────────────

    /**
     * Called ONLY from ConversionService.doConvert() after a successful conversion. ⚠️ NOT called
     * from AnalyticsController — that would double-count.
     */
    @Async
    public void trackConversion(String converterType) {
        if (converterType == null || converterType.isBlank()) {
            log.warn("trackConversion called with null/blank converterType");
            return;
        }
        try {
            analyticsWriter.writeConversion(converterType);
            log.debug("Conversion tracked for type=[{}]", converterType);
        } catch (Exception e) {
            log.error(
                    "Failed to track conversion for type=[{}]: {}",
                    converterType,
                    e.getMessage(),
                    e);
        }
    }

    // ─── Get Stats ────────────────────────────────────────────────────────────

    public AnalyticsStatsResponse getStats() {
        try {
            Analytics analytics = analyticsRepository.findById(1L).orElse(new Analytics());
            long visits = analytics.getTotalVisits();
            long conversions = analytics.getTotalConversions();
            long unique = analytics.getUniqueVisitors();
            double rate =
                    visits > 0 ? Math.round((conversions * 100.0 / visits) * 10.0) / 10.0 : 0.0;

            log.debug(
                    "Stats fetched: visits={}, conversions={}, unique={}, rate={}%",
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

    // ─── Inner Writer Component ───────────────────────────────────────────────

    /**
     * Separate Spring component that owns the @Transactional DB writes.
     *
     * <p>WHY: When AnalyticsService calls its own @Transactional method (self-call), Spring's AOP
     * proxy is bypassed — @Transactional is silently ignored and Hibernate throws
     * "TransactionRequiredException: Executing an update/delete query".
     *
     * <p>By delegating to THIS separate bean, the call goes through Spring's proxy
     * and @Transactional works correctly every time.
     */
    @Component
    @RequiredArgsConstructor
    @Slf4j
    public static class AnalyticsWriter {

        private final AnalyticsRepository analyticsRepository;
        private final ToolStatRepository toolStatRepository;

        @Transactional
        public void writeVisit(boolean isNewUniqueVisitor) {
            analyticsRepository.incrementTotalVisits();
            log.debug("DB: total_visits incremented");

            if (isNewUniqueVisitor) {
                analyticsRepository.incrementUniqueVisitors();
                log.debug("DB: unique_visitors incremented");
            }
        }

        @Transactional
        public void writeConversion(String converterType) {
            analyticsRepository.incrementTotalConversions();
            log.debug("DB: total_conversions incremented");

            int updated = toolStatRepository.incrementCount(converterType);
            if (updated == 0) {
                log.warn("Tool stat row missing for [{}], inserting on the fly", converterType);
                ToolStat stat = new ToolStat(converterType);
                stat.setCount(1);
                toolStatRepository.save(stat);
            } else {
                log.debug("DB: tool_stats[{}] incremented", converterType);
            }
        }
    }
}
