package com.converter.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {

    private String status;
    private String message;
    private String token;
    private Boolean emailVerified;
    private UserDto user;

    @Data
    @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UserDto {
        private Long id;
        private String email;
        private String firstName;
        private String lastName;
        private String role;
        private String picture;
        private Boolean emailVerified;
    }

    // ─── Factory methods ────────────────────────────────────────────────────

    public static AuthResponse success(String token, UserDto user) {
        return AuthResponse.builder()
                .status("success")
                .token(token)
                .emailVerified(user.getEmailVerified())
                .user(user)
                .build();
    }

    public static AuthResponse unverified() {
        return AuthResponse.builder().status("success").emailVerified(false).build();
    }

    public static AuthResponse error(String message) {
        return AuthResponse.builder().status("error").message(message).build();
    }
}
