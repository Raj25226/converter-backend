package com.converter.service;

import com.converter.dto.AuthResponse;
import com.converter.dto.GoogleAuthRequest;
import com.converter.entity.User;
import com.converter.repository.UserRepository;
import com.converter.util.JwtUtil;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
public class GoogleAuthService {

    private static final Logger logger = LoggerFactory.getLogger(GoogleAuthService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Value("${google.client-id:}")
    private String googleClientId;

    @Transactional
    public AuthResponse authenticateWithGoogle(GoogleAuthRequest request) {
        try {
            // Verify Google token
            GoogleIdToken.Payload payload = verifyGoogleToken(request.getCredential());

            if (payload == null) {
                return AuthResponse.error("Invalid Google credential");
            }

            String email = payload.getEmail();
            String firstName = (String) payload.get("given_name");
            String lastName = (String) payload.get("family_name");
            String picture = (String) payload.get("picture");

            // Check if user exists
            Optional<User> existingUser = userRepository.findByEmail(email);

            User user;
            if (existingUser.isPresent()) {
                user = existingUser.get();
                logger.info("Existing user logged in via Google: {}", email);
            } else {
                // Create new user
                user = new User();
                user.setEmail(email);
                user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString())); // Random password for OAuth users
                user.setFirstName(firstName != null ? firstName : "User");
                user.setLastName(lastName != null ? lastName : "");
                user.setIsActive(true);
                user.setEmailVerified(true); // Google accounts are pre-verified
                user.setRole(User.UserRole.USER);

                user = userRepository.save(user);
                logger.info("New user registered via Google: {}", email);
            }

            // Generate JWT token
            String token = jwtUtil.generateToken(user.getEmail(), user.getId());

            AuthResponse response = AuthResponse.success(
                    token,
                    user.getId(),
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getRole().name()
            );
            response.setMessage("Google login successful");

            return response;

        } catch (Exception e) {
            logger.error("Error during Google authentication", e);
            return AuthResponse.error("Google authentication failed: " + e.getMessage());
        }
    }

    private GoogleIdToken.Payload verifyGoogleToken(String credential) {
        try {
            // If no client ID is configured, skip verification (for development)
            if (googleClientId == null || googleClientId.isEmpty()) {
                logger.warn("Google Client ID not configured. Skipping token verification.");
                // For development: parse token without verification
                return parseTokenWithoutVerification(credential);
            }

            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    new GsonFactory())
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(credential);

            if (idToken != null) {
                return idToken.getPayload();
            }

            return null;
        } catch (Exception e) {
            logger.error("Error verifying Google token", e);
            return null;
        }
    }

    private GoogleIdToken.Payload parseTokenWithoutVerification(String credential) {
        try {
            // This is for development only - DO NOT use in production
            String[] parts = credential.split("\\.");
            if (parts.length < 2) {
                return null;
            }

            // Decode the payload (second part of JWT)
            java.util.Base64.Decoder decoder = java.util.Base64.getUrlDecoder();
            String payload = new String(decoder.decode(parts[1]));

            // Parse JSON manually or use a library
            // This is a simplified version - in production use proper verification
            logger.debug("Token payload (unverified): {}", payload);

            // Return null to force error in dev mode
            return null;
        } catch (Exception e) {
            logger.error("Error parsing token", e);
            return null;
        }
    }
}