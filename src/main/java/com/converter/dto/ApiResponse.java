package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse {

    private String status;
    private String message;

    public static ApiResponse success(String message) {
        return ApiResponse.builder()
                .status("success")
                .message(message)
                .build();
    }

    public static ApiResponse error(String message) {
        return ApiResponse.builder()
                .status("error")
                .message(message)
                .build();
    }
}