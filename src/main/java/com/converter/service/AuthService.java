package com.converter.service;

import com.converter.dto.*;
import com.converter.entity.EmailToken;
import com.converter.entity.User;
import com.converter.exception.AppException;
import com.converter.repository.EmailTokenRepository;
import com.converter.repository.UserRepository;
import com.converter.util.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final EmailTokenRepository emailTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final EmailService emailService;
    private final GoogleAuthService googleAuthService;

    // ─── Signup ──────────────────────────────────────────────────────────────

    @Transactional
    public ApiResponse signup(SignupRequest req) {
        String email = req.getEmail().toLowerCase().trim();
        log.info("Signup attempt for email=[{}]", email);

        if (userRepository.existsByEmail(email)) {
            log.warn("Signup rejected: email=[{}] already registered", email);
            throw new AppException("Email already registered", HttpStatus.CONFLICT);
        }

        User user = new User(
                email,
                passwordEncoder.encode(req.getPassword()),
                req.getFirstName().trim(),
                req.getLastName().trim()
        );
        user.setEmailVerified(false);
        user.setActive(true);
        user = userRepository.save(user);

        String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                Instant.now().plus(24, ChronoUnit.HOURS));
        emailService.sendVerificationEmail(user.getEmail(), token);

        log.info("Signup successful: userId=[{}] email=[{}]", user.getId(), email);
        return ApiResponse.success("Account created. Please check your email to verify your account.");
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest req) {
        String email = req.getEmail().toLowerCase().trim();
        log.info("Login attempt for email=[{}]", email);

        // ✅ Uses findByEmailAndActiveTrue (matches renamed field 'active' in User entity)
        User user = userRepository.findByEmailAndActiveTrue(email)
                .orElseThrow(() -> {
                    log.warn("Login failed: no active user for email=[{}]", email);
                    return new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
                });

        if (user.getPasswordHash() == null) {
            log.warn("Login failed: user=[{}] is a Google OAuth account, has no password", email);
            throw new AppException(
                    "This account uses Google sign-in. Please use 'Continue with Google'.",
                    HttpStatus.BAD_REQUEST);
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            log.warn("Login failed: wrong password for email=[{}]", email);
            throw new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (!user.isEmailVerified()) {
            log.info("Login for unverified email=[{}] — returning unverified response", email);
            return AuthResponse.unverified();
        }

        String token = jwtUtil.generateToken(user);
        log.info("Login successful: userId=[{}] email=[{}]", user.getId(), email);
        return AuthResponse.success(token, toUserDto(user));
    }

    // ─── Google OAuth ────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse googleAuth(GoogleAuthRequest req) {
        log.info("Google OAuth attempt");

        if (!googleAuthService.isConfigured()) {
            log.error("Google OAuth not configured: GOOGLE_CLIENT_ID is not set");
            throw new AppException("Google OAuth is not configured on this server",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        GoogleIdToken.Payload payload;
        try {
            payload = googleAuthService.verifyToken(req.getCredential());
        } catch (IllegalArgumentException e) {
            log.warn("Google token rejected: {}", e.getMessage());
            throw new AppException(e.getMessage(), HttpStatus.UNAUTHORIZED);
        } catch (IllegalStateException e) {
            log.error("Google auth service error: {}", e.getMessage());
            throw new AppException(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            log.error("Unexpected Google token verification error", e);
            throw new AppException("Failed to verify Google credential. Please try again.",
                    HttpStatus.UNAUTHORIZED);
        }

        String email     = payload.getEmail().toLowerCase().trim();
        String firstName = (String) payload.get("given_name");
        String lastName  = (String) payload.get("family_name");
        String picture   = (String) payload.get("picture");

        log.info("Google token verified for email=[{}]", email);

        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            user = new User();
            user.setEmail(email);
            user.setPasswordHash(null);
            user.setFirstName(firstName != null ? firstName.trim() : "User");
            user.setLastName(lastName  != null ? lastName.trim()  : "");
            user.setEmailVerified(true);
            user.setPicture(picture);
            user.setRole(User.Role.USER);
            user.setActive(true);
            user = userRepository.save(user);
            log.info("New Google user created: userId=[{}] email=[{}]", user.getId(), email);
        } else {
            boolean changed = false;
            if (!user.isEmailVerified())               { user.setEmailVerified(true); changed = true; }
            if (firstName != null && !firstName.equals(user.getFirstName())) { user.setFirstName(firstName.trim()); changed = true; }
            if (lastName  != null && !lastName.equals(user.getLastName()))   { user.setLastName(lastName.trim());   changed = true; }
            if (picture   != null && !picture.equals(user.getPicture()))      { user.setPicture(picture);            changed = true; }
            if (changed) user = userRepository.save(user);
            log.info("Existing Google user logged in: userId=[{}] email=[{}]", user.getId(), email);
        }

        if (!user.isActive()) {
            log.warn("Google login rejected: userId=[{}] is suspended", user.getId());
            throw new AppException("Your account has been suspended. Please contact support.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user);
        return AuthResponse.success(token, toUserDto(user));
    }

    // ─── Email Verification ──────────────────────────────────────────────────

    @Transactional
    public ApiResponse verifyEmail(String token) {
        log.info("Email verification attempt with token=[{}...]", token.substring(0, Math.min(8, token.length())));

        EmailToken emailToken = emailTokenRepository.findByToken(token)
                .orElseThrow(() -> {
                    log.warn("Email verification failed: token not found");
                    return new AppException("Invalid or expired verification link", HttpStatus.BAD_REQUEST);
                });

        if (!emailToken.isValid() || emailToken.getType() != EmailToken.TokenType.VERIFY_EMAIL) {
            log.warn("Email verification failed: token invalid or wrong type for userId=[{}]",
                    emailToken.getUser().getId());
            throw new AppException("Invalid or expired verification link", HttpStatus.BAD_REQUEST);
        }

        User user = emailToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        emailToken.setUsed(true);
        emailTokenRepository.save(emailToken);

        log.info("Email verified for userId=[{}] email=[{}]", user.getId(), user.getEmail());
        return ApiResponse.success("Email verified successfully. You can now log in.");
    }

    @Transactional
    public ApiResponse resendVerification(ResendVerificationRequest req) {
        String email = req.getEmail().toLowerCase().trim();
        log.info("Resend verification requested for email=[{}]", email);

        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                emailTokenRepository.deleteByUserIdAndType(user.getId(), EmailToken.TokenType.VERIFY_EMAIL);
                String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                        Instant.now().plus(24, ChronoUnit.HOURS));
                emailService.sendVerificationEmail(user.getEmail(), token);
                log.info("Verification email resent to userId=[{}] email=[{}]", user.getId(), email);
            } else {
                log.debug("Resend skipped: email=[{}] already verified", email);
            }
        });

        return ApiResponse.success("If your account exists and is unverified, a verification email has been sent.");
    }

    // ─── Password Reset ──────────────────────────────────────────────────────

    @Transactional
    public ApiResponse forgotPassword(ForgotPasswordRequest req) {
        String email = req.getEmail().toLowerCase().trim();
        log.info("Password reset requested for email=[{}]", email);

        userRepository.findByEmail(email).ifPresent(user -> {
            emailTokenRepository.deleteByUserIdAndType(user.getId(), EmailToken.TokenType.RESET_PASSWORD);
            String token = generateEmailToken(user, EmailToken.TokenType.RESET_PASSWORD,
                    Instant.now().plus(1, ChronoUnit.HOURS));
            emailService.sendPasswordResetEmail(user.getEmail(), token);
            log.info("Password reset email sent to userId=[{}] email=[{}]", user.getId(), email);
        });

        return ApiResponse.success("If that email is registered, a password reset link has been sent.");
    }

    @Transactional
    public ApiResponse resetPassword(ResetPasswordRequest req) {
        log.info("Password reset attempt with token=[{}...]",
                req.getToken().substring(0, Math.min(8, req.getToken().length())));

        EmailToken emailToken = emailTokenRepository.findByToken(req.getToken())
                .orElseThrow(() -> {
                    log.warn("Password reset failed: token not found");
                    return new AppException("Invalid or expired reset link", HttpStatus.BAD_REQUEST);
                });

        if (!emailToken.isValid() || emailToken.getType() != EmailToken.TokenType.RESET_PASSWORD) {
            log.warn("Password reset failed: token invalid or wrong type");
            throw new AppException("Invalid or expired reset link. Please request a new one.",
                    HttpStatus.BAD_REQUEST);
        }

        User user = emailToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        emailToken.setUsed(true);
        emailTokenRepository.save(emailToken);

        log.info("Password reset successful for userId=[{}] email=[{}]", user.getId(), user.getEmail());
        return ApiResponse.success("Password reset successfully. You can now log in.");
    }

    // ─── Profile Update ──────────────────────────────────────────────────────

    @Transactional
    public AuthResponse updateProfile(Long userId, UpdateProfileRequest req) {
        log.info("Profile update for userId=[{}]", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (req.getFirstName() != null && !req.getFirstName().isBlank()) {
            user.setFirstName(req.getFirstName().trim());
            log.debug("Updated firstName for userId=[{}]", userId);
        }
        if (req.getLastName() != null && !req.getLastName().isBlank()) {
            user.setLastName(req.getLastName().trim());
            log.debug("Updated lastName for userId=[{}]", userId);
        }

        if (req.getEmail() != null && !req.getEmail().isBlank()) {
            String newEmail = req.getEmail().toLowerCase().trim();
            if (!newEmail.equals(user.getEmail())) {
                if (userRepository.existsByEmail(newEmail)) {
                    log.warn("Profile update rejected: email=[{}] already in use", newEmail);
                    throw new AppException("Email already in use by another account", HttpStatus.CONFLICT);
                }
                user.setEmail(newEmail);
                user.setEmailVerified(false);
                emailTokenRepository.deleteByUserIdAndType(userId, EmailToken.TokenType.VERIFY_EMAIL);
                String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                        Instant.now().plus(24, ChronoUnit.HOURS));
                emailService.sendVerificationEmail(newEmail, token);
                log.info("Email changed for userId=[{}]: new verification email sent to [{}]", userId, newEmail);
            }
        }

        user = userRepository.save(user);
        String newToken = jwtUtil.generateToken(user);
        log.info("Profile updated successfully for userId=[{}]", userId);

        return AuthResponse.builder()
                .status("success")
                .message("Profile updated successfully")
                .token(newToken)
                .user(toUserDto(user))
                .build();
    }

    // ─── Change Password ─────────────────────────────────────────────────────

    @Transactional
    public ApiResponse changePassword(Long userId, ChangePasswordRequest req) {
        log.info("Password change attempt for userId=[{}]", userId);

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (user.getPasswordHash() == null) {
            log.warn("Password change rejected: userId=[{}] is a Google OAuth account", userId);
            throw new AppException(
                    "Google OAuth accounts don't have a password. Use Google to sign in.",
                    HttpStatus.BAD_REQUEST);
        }

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            log.warn("Password change rejected: wrong current password for userId=[{}]", userId);
            throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        if (req.getCurrentPassword().equals(req.getNewPassword())) {
            throw new AppException("New password must be different from the current password",
                    HttpStatus.BAD_REQUEST);
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        log.info("Password changed successfully for userId=[{}]", userId);
        return ApiResponse.success("Password changed successfully.");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String generateEmailToken(User user, EmailToken.TokenType type, Instant expiresAt) {
        String rawToken = UUID.randomUUID().toString().replace("-", "") +
                UUID.randomUUID().toString().replace("-", "");
        EmailToken emailToken = new EmailToken(user, rawToken, type, expiresAt);
        emailTokenRepository.save(emailToken);
        log.debug("Email token created: type=[{}] userId=[{}] expires=[{}]", type, user.getId(), expiresAt);
        return rawToken;
    }

    public AuthResponse.UserDto toUserDto(User user) {
        return AuthResponse.UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .role(user.getRole().name().toLowerCase())
                .picture(user.getPicture())
                .emailVerified(user.isEmailVerified())
                .build();
    }

    public User getUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));
    }
}