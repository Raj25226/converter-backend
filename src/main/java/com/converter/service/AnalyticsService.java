package com.converter.service;

import com.converter.dto.AnalyticsStatsResponse;
import com.converter.entity.Analytics;
import com.converter.repository.AnalyticsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);

    @Autowired
    private AnalyticsRepository analyticsRepository;

    public AnalyticsStatsResponse getStats() {
        try {
            long totalVisits = analyticsRepository.countVisits();
            long totalConversions = analyticsRepository.countConversions();
            long uniqueVisitors = analyticsRepository.countUniqueVisitors();

            double conversionRate = totalVisits > 0
                    ? Math.round((totalConversions * 100.0 / totalVisits) * 10.0) / 10.0
                    : 0.0;

            return new AnalyticsStatsResponse(totalVisits, totalConversions, uniqueVisitors, conversionRate);
        } catch (Exception e) {
            logger.error("Error fetching analytics stats", e);
            return new AnalyticsStatsResponse(0, 0, 0, 0.0);
        }
    }

    @Transactional
    public void trackVisit(String page, String ipAddress, String userAgent) {
        try {
            Analytics analytics = new Analytics();
            analytics.setEventType("visit");
            analytics.setConverterType(page);
            analytics.setIpAddress(ipAddress);
            analytics.setUserAgent(userAgent);
            analytics.setTimestamp(LocalDateTime.now());

            analyticsRepository.save(analytics);
            logger.debug("Visit tracked for page: {} from IP: {}", page, ipAddress);
        } catch (Exception e) {
            logger.error("Error tracking visit", e);
        }
    }

    @Transactional
    public void trackConversion(String converterType, String ipAddress, String userAgent) {
        try {
            Analytics analytics = new Analytics();
            analytics.setEventType("conversion");
            analytics.setConverterType(converterType);
            analytics.setIpAddress(ipAddress);
            analytics.setUserAgent(userAgent);
            analytics.setTimestamp(LocalDateTime.now());

            analyticsRepository.save(analytics);
            logger.info("Conversion tracked: {} from IP: {}", converterType, ipAddress);
        } catch (Exception e) {
            logger.error("Error tracking conversion", e);
        }
    }
}