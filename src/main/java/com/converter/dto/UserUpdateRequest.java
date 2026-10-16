package com.converter.dto;

import lombok.Data;

@Data
public class UserUpdateRequest {
    private String role; // "user" | "admin"
    private Boolean isActive; // for suspending users
}
