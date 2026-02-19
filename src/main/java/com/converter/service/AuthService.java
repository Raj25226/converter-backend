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

        if (userRepository.existsByEmail(email)) {
            throw new AppException("Email already registered", HttpStatus.CONFLICT);
        }

        User user = new User(
                email,
                passwordEncoder.encode(req.getPassword()),
                req.getFirstName().trim(),
                req.getLastName().trim()
        );
        user.setEmailVerified(false);
        user = userRepository.save(user);

        // Send verification email
        String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                Instant.now().plus(24, ChronoUnit.HOURS));
        emailService.sendVerificationEmail(user.getEmail(), token);

        log.info("User registered: {}", email);
        return ApiResponse.success("Account created. Please verify your email.");
    }

    // ─── Login ───────────────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        User user = userRepository.findByEmailAndIsActiveTrue(email)
                .orElseThrow(() -> new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED));

        if (user.getPasswordHash() == null) {
            throw new AppException(
                    "This account uses Google sign-in. Please use 'Continue with Google'.",
                    HttpStatus.BAD_REQUEST);
        }

        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new AppException("Invalid email or password", HttpStatus.UNAUTHORIZED);
        }

        if (!user.isEmailVerified()) {
            return AuthResponse.unverified();
        }

        String token = jwtUtil.generateToken(user);
        log.info("User logged in: {}", email);
        return AuthResponse.success(token, toUserDto(user));
    }

    // ─── Google OAuth ────────────────────────────────────────────────────────

    @Transactional
    public AuthResponse googleAuth(GoogleAuthRequest req) {
        if (!googleAuthService.isConfigured()) {
            throw new AppException("Google OAuth is not configured on this server",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }

        GoogleIdToken.Payload payload;
        try {
            payload = googleAuthService.verifyToken(req.getCredential());
        } catch (IllegalArgumentException e) {
            throw new AppException(e.getMessage(), HttpStatus.UNAUTHORIZED);
        } catch (IllegalStateException e) {
            throw new AppException(e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        } catch (Exception e) {
            log.error("Google token verification error", e);
            throw new AppException("Failed to verify Google credential. Please try again.",
                    HttpStatus.UNAUTHORIZED);
        }

        String email = payload.getEmail().toLowerCase().trim();
        String firstName = (String) payload.get("given_name");
        String lastName  = (String) payload.get("family_name");
        String picture   = (String) payload.get("picture");

        User user = userRepository.findByEmail(email).orElse(null);

        if (user == null) {
            // Create new user
            user = new User();
            user.setEmail(email);
            user.setPasswordHash(null); // Google users don't have passwords
            user.setFirstName(firstName != null ? firstName.trim() : "User");
            user.setLastName(lastName != null ? lastName.trim() : "");
            user.setEmailVerified(true);
            user.setPicture(picture);
            user.setRole(User.Role.USER);
            user.setActive(true);
            user = userRepository.save(user);
            log.info("New Google user created: {}", email);
        } else {
            // Update existing user
            boolean changed = false;
            if (!user.isEmailVerified()) { user.setEmailVerified(true); changed = true; }
            if (firstName != null && !firstName.equals(user.getFirstName())) {
                user.setFirstName(firstName.trim()); changed = true;
            }
            if (lastName != null && !lastName.equals(user.getLastName())) {
                user.setLastName(lastName.trim()); changed = true;
            }
            if (picture != null && !picture.equals(user.getPicture())) {
                user.setPicture(picture); changed = true;
            }
            if (changed) user = userRepository.save(user);
            log.info("Existing user logged in via Google: {}", email);
        }

        if (!user.isActive()) {
            throw new AppException("Your account has been suspended. Please contact support.",
                    HttpStatus.FORBIDDEN);
        }

        String token = jwtUtil.generateToken(user);
        return AuthResponse.success(token, toUserDto(user));
    }

    // ─── Email Verification ──────────────────────────────────────────────────

    @Transactional
    public ApiResponse verifyEmail(String token) {
        EmailToken emailToken = emailTokenRepository.findByToken(token)
                .orElseThrow(() -> new AppException("Invalid or expired verification link",
                        HttpStatus.BAD_REQUEST));

        if (!emailToken.isValid() ||
                emailToken.getType() != EmailToken.TokenType.VERIFY_EMAIL) {
            throw new AppException("Invalid or expired verification link", HttpStatus.BAD_REQUEST);
        }

        User user = emailToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        emailToken.setUsed(true);
        emailTokenRepository.save(emailToken);

        log.info("Email verified for: {}", user.getEmail());
        return ApiResponse.success("Email verified successfully");
    }

    @Transactional
    public ApiResponse resendVerification(ResendVerificationRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        // Always return success to not reveal if email exists
        userRepository.findByEmail(email).ifPresent(user -> {
            if (!user.isEmailVerified()) {
                // Invalidate existing tokens
                emailTokenRepository.deleteByUserIdAndType(user.getId(),
                        EmailToken.TokenType.VERIFY_EMAIL);
                String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                        Instant.now().plus(24, ChronoUnit.HOURS));
                emailService.sendVerificationEmail(user.getEmail(), token);
            }
        });

        return ApiResponse.success("Verification email sent if account exists");
    }

    // ─── Password Reset ──────────────────────────────────────────────────────

    @Transactional
    public ApiResponse forgotPassword(ForgotPasswordRequest req) {
        String email = req.getEmail().toLowerCase().trim();

        // Always return 200 to not reveal if email exists
        userRepository.findByEmail(email).ifPresent(user -> {
            // Invalidate existing reset tokens
            emailTokenRepository.deleteByUserIdAndType(user.getId(),
                    EmailToken.TokenType.RESET_PASSWORD);
            String token = generateEmailToken(user, EmailToken.TokenType.RESET_PASSWORD,
                    Instant.now().plus(1, ChronoUnit.HOURS));
            emailService.sendPasswordResetEmail(user.getEmail(), token);
        });

        return ApiResponse.success("Reset email sent if account exists");
    }

    @Transactional
    public ApiResponse resetPassword(ResetPasswordRequest req) {
        EmailToken emailToken = emailTokenRepository.findByToken(req.getToken())
                .orElseThrow(() -> new AppException("Invalid or expired reset link", HttpStatus.BAD_REQUEST));

        if (!emailToken.isValid() ||
                emailToken.getType() != EmailToken.TokenType.RESET_PASSWORD) {
            throw new AppException("Invalid or expired reset link", HttpStatus.BAD_REQUEST);
        }

        User user = emailToken.getUser();
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        emailToken.setUsed(true);
        emailTokenRepository.save(emailToken);

        log.info("Password reset for: {}", user.getEmail());
        return ApiResponse.success("Password reset successfully");
    }

    // ─── Profile Update ──────────────────────────────────────────────────────

    @Transactional
    public AuthResponse updateProfile(Long userId, UpdateProfileRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (req.getFirstName() != null) user.setFirstName(req.getFirstName().trim());
        if (req.getLastName() != null)  user.setLastName(req.getLastName().trim());

        if (req.getEmail() != null) {
            String newEmail = req.getEmail().toLowerCase().trim();
            if (!newEmail.equals(user.getEmail())) {
                if (userRepository.existsByEmail(newEmail)) {
                    throw new AppException("Email already in use", HttpStatus.CONFLICT);
                }
                user.setEmail(newEmail);
                user.setEmailVerified(false);
                // Send new verification email
                String token = generateEmailToken(user, EmailToken.TokenType.VERIFY_EMAIL,
                        Instant.now().plus(24, ChronoUnit.HOURS));
                emailService.sendVerificationEmail(newEmail, token);
            }
        }

        user = userRepository.save(user);
        String newToken = jwtUtil.generateToken(user);
        log.info("Profile updated for user: {}", userId);

        return AuthResponse.builder()
                .status("success")
                .message("Profile updated")
                .token(newToken)
                .user(toUserDto(user))
                .build();
    }

    // ─── Change Password ─────────────────────────────────────────────────────

    @Transactional
    public ApiResponse changePassword(Long userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException("User not found", HttpStatus.NOT_FOUND));

        if (user.getPasswordHash() == null) {
            throw new AppException("Google OAuth accounts cannot change password here",
                    HttpStatus.BAD_REQUEST);
        }

        if (!passwordEncoder.matches(req.getCurrentPassword(), user.getPasswordHash())) {
            throw new AppException("Current password is incorrect", HttpStatus.BAD_REQUEST);
        }

        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);

        log.info("Password changed for user: {}", userId);
        return ApiResponse.success("Password changed successfully");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String generateEmailToken(User user, EmailToken.TokenType type, Instant expiresAt) {
        String rawToken = UUID.randomUUID().toString() + UUID.randomUUID().toString();
        EmailToken emailToken = new EmailToken(user, rawToken, type, expiresAt);
        emailTokenRepository.save(emailToken);
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