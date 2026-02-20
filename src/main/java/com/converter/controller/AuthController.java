package com.converter.controller;

import com.converter.dto.*;
import com.converter.entity.User;
import com.converter.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    // POST /api/auth/signup
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse> signup(@Valid @RequestBody SignupRequest req) {
        ApiResponse response = authService.signup(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    // POST /api/auth/login
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthResponse response = authService.login(req);
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/google
    @PostMapping("/google")
    public ResponseEntity<AuthResponse> googleLogin(@Valid @RequestBody GoogleAuthRequest req) {
        AuthResponse response = authService.googleAuth(req);
        return ResponseEntity.ok(response);
    }

    // POST /api/auth/resend-verification
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse> resendVerification(
            @Valid @RequestBody ResendVerificationRequest req) {
        return ResponseEntity.ok(authService.resendVerification(req));
    }

    // GET /api/auth/verify-email?token=...
    @GetMapping("/verify-email")
    public ResponseEntity<ApiResponse> verifyEmail(@RequestParam String token) {
        return ResponseEntity.ok(authService.verifyEmail(token));
    }

    // POST /api/auth/forgot-password
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req) {
        return ResponseEntity.ok(authService.forgotPassword(req));
    }

    // POST /api/auth/reset-password
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req) {
        return ResponseEntity.ok(authService.resetPassword(req));
    }

    // PUT /api/auth/profile (🔒 auth required)
    @PutMapping("/profile")
    public ResponseEntity<AuthResponse> updateProfile(
            Authentication auth,
            @Valid @RequestBody UpdateProfileRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.updateProfile(userId, req));
    }

    // POST /api/auth/change-password (🔒 auth required)
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse> changePassword(
            Authentication auth,
            @Valid @RequestBody ChangePasswordRequest req) {
        Long userId = (Long) auth.getPrincipal();
        return ResponseEntity.ok(authService.changePassword(userId, req));
    }

    // GET /api/auth/me (🔒 auth required)
    @GetMapping("/me")
    public ResponseEntity<AuthResponse> getMe(Authentication auth) {
        Long userId = (Long) auth.getPrincipal();
        User user = authService.getUserById(userId);
        return ResponseEntity.ok(AuthResponse.builder()
                .status("success")
                .user(authService.toUserDto(user))
                .build());
    }
}