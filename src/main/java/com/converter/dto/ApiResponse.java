package com.converter.dto;

public class ApiResponse {
    private String status;
    private String message;

    // Constructors
    public ApiResponse() {}

    public ApiResponse(String status, String message) {
        this.status = status;
        this.message = message;
    }

    // Static factory methods
    public static ApiResponse success(String message) {
        return new ApiResponse("success", message);
    }

    public static ApiResponse error(String message) {
        return new ApiResponse("error", message);
    }

    // Getters and Setters
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}