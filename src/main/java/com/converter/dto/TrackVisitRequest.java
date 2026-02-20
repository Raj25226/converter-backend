package com.converter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class TrackVisitRequest {

    @NotBlank(message = "Page is required")
    private String page;

    private String timestamp;

    @NotBlank(message = "Session ID is required")
    private String sessionId;
}