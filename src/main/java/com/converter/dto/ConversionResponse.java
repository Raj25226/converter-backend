package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@lombok.NoArgsConstructor
@lombok.AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversionResponse {

    private String status;
    private String message;
    private String downloadUrl;
    private String fileName;
    private Instant expiresAt;
    private Long fileSize;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private java.util.List<String> warnings;

    public static ConversionResponse success(String downloadUrl, String fileName,
                                             Instant expiresAt, Long fileSize) {
        return ConversionResponse.builder()
                .status("success")
                .downloadUrl(downloadUrl)
                .fileName(fileName)
                .expiresAt(expiresAt)
                .fileSize(fileSize)
                .build();
    }

    public static ConversionResponse error(String message) {
        return ConversionResponse.builder()
                .status("error")
                .message(message)
                .build();
    }
}