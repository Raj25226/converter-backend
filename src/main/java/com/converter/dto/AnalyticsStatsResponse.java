package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalyticsStatsResponse {

    private String status;
    private String message;
    private StatsData data;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StatsData {
        private long totalVisits;
        private long totalConversions;
        private long uniqueVisitors;
        private double conversionRate;
    }

    public static AnalyticsStatsResponse success(
            long visits, long conversions, long unique, double rate) {
        return AnalyticsStatsResponse.builder()
                .status("success")
                .data(new StatsData(visits, conversions, unique, rate))
                .build();
    }

    public static AnalyticsStatsResponse error(String message) {
        return AnalyticsStatsResponse.builder().status("error").message(message).build();
    }
}
