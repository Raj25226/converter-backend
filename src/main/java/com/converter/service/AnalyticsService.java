package com.converter.service;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.entity.Analytics;
import com.converter.entity.ToolStat;
import com.converter.repository.AnalyticsRepository;
import com.converter.repository.ToolStatRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsService {

    private final AnalyticsRepository analyticsRepository;
    private final ToolStatRepository toolStatRepository;
    private final StringRedisTemplate redisTemplate;

    private static final String SESSION_PREFIX = "session:";
    private static final Duration SESSION_TTL = Duration.ofMinutes(30);

    // All supported converter types
    private static final List<String> ALL_TOOLS = Arrays.asList(
            "pdf-to-word", "word-to-pdf", "pdf-to-excel", "excel-to-pdf", "ppt-to-pdf",
            "jpg-to-png", "png-to-jpg", "jpg-to-webp", "webp-to-png", "heic-to-jpg",
            "mp4-to-mp3", "mp4-to-gif", "wav-to-mp3",
            "zip-extract", "file-compress"
    );

    @PostConstruct
    public void initializeData() {
        // Ensure analytics row exists
        if (!analyticsRepository.existsById(1L)) {
            Analytics analytics = new Analytics();
            analyticsRepository.save(analytics);
            log.info("Analytics row initialized");
        }

        // Ensure all tool stat rows exist
        ALL_TOOLS.forEach(tool -> {
            if (!toolStatRepository.existsById(tool)) {
                toolStatRepository.save(new ToolStat(tool));
            }
        });
        log.info("Tool stats initialized for {} tools", ALL_TOOLS.size());
    }

    // ─── Track Visit ─────────────────────────────────────────────────────────

    @Async
    @Transactional
    public void trackVisit(String sessionId) {
        try {
            String key = SESSION_PREFIX + sessionId;
            Boolean isNew = redisTemplate.opsForValue().setIfAbsent(key, "1", SESSION_TTL);

            analyticsRepository.incrementTotalVisits();

            if (Boolean.TRUE.equals(isNew)) {
                analyticsRepository.incrementUniqueVisitors();
            }
        } catch (Exception e) {
            log.error("Error tracking visit: {}", e.getMessage());
        }
    }

    // ─── Track Conversion ────────────────────────────────────────────────────

    @Async
    @Transactional
    public void trackConversion(String converterType) {
        try {
            analyticsRepository.incrementTotalConversions();

            // Increment per-tool stat (upsert)
            int updated = toolStatRepository.incrementCount(converterType);
            if (updated == 0) {
                ToolStat stat = new ToolStat(converterType);
                stat.setCount(1);
                toolStatRepository.save(stat);
            }
        } catch (Exception e) {
            log.error("Error tracking conversion for {}: {}", converterType, e.getMessage());
        }
    }

    // ─── Get Stats ───────────────────────────────────────────────────────────

    public AnalyticsStatsResponse getStats() {
        try {
            Analytics analytics = analyticsRepository.findById(1L)
                    .orElse(new Analytics());

            long visits = analytics.getTotalVisits();
            long conversions = analytics.getTotalConversions();
            long unique = analytics.getUniqueVisitors();

            double rate = visits > 0
                    ? Math.round((conversions * 100.0 / visits) * 10.0) / 10.0
                    : 0.0;

            return AnalyticsStatsResponse.success(visits, conversions, unique, rate);
        } catch (Exception e) {
            log.error("Error fetching analytics stats: {}", e.getMessage());
            return AnalyticsStatsResponse.success(0, 0, 0, 0.0);
        }
    }

    public List<ToolStat> getToolStats() {
        return toolStatRepository.findAllByOrderByCountDesc();
    }
}