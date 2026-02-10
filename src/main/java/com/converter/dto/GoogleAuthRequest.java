package com.converter.dto;

import jakarta.validation.constraints.NotBlank;

public class GoogleAuthRequest {

    @NotBlank(message = "Credential is required")
    private String credential;

    // Constructors
    public GoogleAuthRequest() {}

    public GoogleAuthRequest(String credential) {
        this.credential = credential;
    }

    // Getters and Setters
    public String getCredential() {
        return credential;
    }

    public void setCredential(String credential) {
        this.credential = credential;
    }
}