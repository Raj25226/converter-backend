package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminStatsResponse {

    private String status;
    private String message;

    // Analytics
    private long totalVisits;
    private long totalConversions;
    private long uniqueVisitors;
    private double conversionRate;

    // Users
    private long totalUsers;

    // Tool breakdown
    private List<ToolStatDto> toolStats;

    @Data
    @Builder
    public static class ToolStatDto {
        private String tool;
        private long count;
    }

    public static AdminStatsResponse error(String message) {
        return AdminStatsResponse.builder()
                .status("error")
                .message(message)
                .build();
    }
}