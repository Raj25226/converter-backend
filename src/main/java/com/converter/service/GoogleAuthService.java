package com.converter.service;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class GoogleAuthService {

    @Value("${google.client-id}")
    private String googleClientId;

    private GoogleIdTokenVerifier googleIdTokenVerifier;

    @PostConstruct
    public void init() {
        if (googleClientId == null || googleClientId.isBlank()) {
            log.warn("Google Client ID not configured — Google OAuth will be disabled");
            return;
        }
        googleIdTokenVerifier =
                new GoogleIdTokenVerifier.Builder(
                                new NetHttpTransport(), GsonFactory.getDefaultInstance())
                        .setAudience(Collections.singletonList(googleClientId))
                        .build();
        log.info(
                "Google OAuth initialized with client ID: {}...{}",
                googleClientId.substring(0, Math.min(10, googleClientId.length())),
                googleClientId.length() > 20
                        ? googleClientId.substring(googleClientId.length() - 8)
                        : "");
    }

    /**
     * Verifies a Google ID token and returns the payload.
     *
     * @param idTokenString the raw Google ID token from the frontend
     * @return GoogleIdToken.Payload if valid
     * @throws IllegalStateException if Google OAuth is not configured
     * @throws GeneralSecurityException if token is cryptographically invalid
     * @throws IOException on network errors
     * @throws IllegalArgumentException if token is expired or audience mismatched
     */
    public GoogleIdToken.Payload verifyToken(String idTokenString)
            throws GeneralSecurityException, IOException {

        if (googleIdTokenVerifier == null) {
            throw new IllegalStateException(
                    "Google OAuth is not configured. Set GOOGLE_CLIENT_ID environment variable.");
        }

        if (idTokenString == null || idTokenString.isBlank()) {
            throw new IllegalArgumentException("Google credential token cannot be empty");
        }

        GoogleIdToken idToken = googleIdTokenVerifier.verify(idTokenString);
        if (idToken == null) {
            throw new IllegalArgumentException(
                    "Invalid Google credential: token verification failed. "
                            + "Ensure the token is fresh and issued for this application.");
        }

        GoogleIdToken.Payload payload = idToken.getPayload();

        // Extra safety — token must not be expired
        long nowSeconds = System.currentTimeMillis() / 1000;
        if (payload.getExpirationTimeSeconds() < nowSeconds) {
            throw new IllegalArgumentException(
                    "Google credential has expired. Please sign in again.");
        }

        // Email must be verified by Google
        if (!Boolean.TRUE.equals(payload.getEmailVerified())) {
            throw new IllegalArgumentException(
                    "Google account email is not verified. Please verify your Google account first.");
        }

        log.debug("Google token verified for: {}", payload.getEmail());
        return payload;
    }

    public boolean isConfigured() {
        return googleIdTokenVerifier != null;
    }
}
