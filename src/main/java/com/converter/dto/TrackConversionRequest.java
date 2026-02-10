package com.converter.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public class TrackConversionRequest {

    @NotBlank(message = "Converter type is required")
    private String converterType;

    private LocalDateTime timestamp;

    // Constructors
    public TrackConversionRequest() {
        this.timestamp = LocalDateTime.now();
    }

    public TrackConversionRequest(String converterType, LocalDateTime timestamp) {
        this.converterType = converterType;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    // Getters and Setters
    public String getConverterType() {
        return converterType;
    }

    public void setConverterType(String converterType) {
        this.converterType = converterType;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}