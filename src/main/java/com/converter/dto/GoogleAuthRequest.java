package com.converter.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class GoogleAuthRequest {

    @NotBlank(message = "Google credential is required")
    private String credential;
}