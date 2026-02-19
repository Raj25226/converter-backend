package com.converter.dto;

import com.converter.entity.Conversion;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversionHistoryResponse {

    private String status;
    private String message;
    private List<ConversionItem> conversions;
    private int usedToday;
    private int dailyLimit;
    private int remaining;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ConversionItem {
        private Long id;
        private String converterType;
        private String originalFileName;
        private String convertedFileName;
        private String downloadUrl;
        private Instant createdAt;
        private Instant expiresAt;
        private boolean expired;
        private Long fileSize;
    }

    public static ConversionItem fromConversion(Conversion c, String downloadBaseUrl) {
        boolean expired = c.getExpiresAt().isBefore(Instant.now());
        return ConversionItem.builder()
                .id(c.getId())
                .converterType(c.getConverterType())
                .originalFileName(c.getOriginalFileName())
                .convertedFileName(c.getConvertedFileName())
                .downloadUrl(expired ? null : downloadBaseUrl + c.getFileId())
                .createdAt(c.getCreatedAt())
                .expiresAt(c.getExpiresAt())
                .expired(expired)
                .fileSize(c.getFileSize())
                .build();
    }
}