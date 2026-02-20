package com.converter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TrackConversionRequest {

    @NotBlank(message = "Converter type is required")
    private String converterType;

    private String timestamp;

    @NotBlank(message = "Session ID is required")
    private String sessionId;
}