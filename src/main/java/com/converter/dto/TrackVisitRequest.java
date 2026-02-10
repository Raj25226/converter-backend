package com.converter.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

public class TrackVisitRequest {

    @NotBlank(message = "Page is required")
    private String page;

    private LocalDateTime timestamp;

    // Constructors
    public TrackVisitRequest() {
        this.timestamp = LocalDateTime.now();
    }

    public TrackVisitRequest(String page, LocalDateTime timestamp) {
        this.page = page;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    // Getters and Setters
    public String getPage() {
        return page;
    }

    public void setPage(String page) {
        this.page = page;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}