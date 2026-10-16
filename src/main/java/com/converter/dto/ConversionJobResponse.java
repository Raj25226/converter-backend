package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConversionJobResponse(String jobId, String requestId, String status, String tool, String originalFileName,
                                    Instant createdAt, Instant updatedAt, ConversionResponse result, String error) { }
