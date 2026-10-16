package com.converter.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @Size(min = 1, max = 100, message = "First name must be 1-100 characters")
    private String firstName;

    @Size(min = 1, max = 100, message = "Last name must be 1-100 characters")
    private String lastName;

    @Email(message = "Invalid email format")
    @Size(max = 255)
    private String email;
}
