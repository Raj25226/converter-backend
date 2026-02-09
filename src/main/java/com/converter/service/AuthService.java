package com.converter.service;

import com.converter.dto.AuthRequest;
import com.converter.dto.AuthResponse;
import com.converter.dto.RegisterRequest;
import com.converter.entity.User;
import com.converter.repository.UserRepository;
import com.converter.util.JwtUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtUtil jwtUtil;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        try {
            // Check if user already exists
            if (userRepository.existsByEmail(request.getEmail())) {
                return AuthResponse.error("Email already registered");
            }

            // Create new user
            User user = new User();
            user.setEmail(request.getEmail());
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setFirstName(request.getFirstName());
            user.setLastName(request.getLastName());
            user.setIsActive(true);
            user.setEmailVerified(false);
            user.setRole(User.UserRole.USER);

            User savedUser = userRepository.save(user);

            // Generate token
            String token = jwtUtil.generateToken(savedUser.getEmail(), savedUser.getId());

            logger.info("User registered successfully: {}", savedUser.getEmail());

            return AuthResponse.success(
                    token,
                    savedUser.getId(),
                    savedUser.getEmail(),
                    savedUser.getFirstName(),
                    savedUser.getLastName(),
                    savedUser.getRole().name()
            );

        } catch (Exception e) {
            logger.error("Error during registration", e);
            return AuthResponse.error("Registration failed: " + e.getMessage());
        }
    }

    public AuthResponse login(AuthRequest request) {
        try {
            // Find user by email
            Optional<User> userOptional = userRepository.findByEmailAndIsActiveTrue(request.getEmail());

            if (userOptional.isEmpty()) {
                return AuthResponse.error("Invalid email or password");
            }

            User user = userOptional.get();

            // Verify password
            if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
                return AuthResponse.error("Invalid email or password");
            }

            // Generate token
            String token = jwtUtil.generateToken(user.getEmail(), user.getId());

            logger.info("User logged in successfully: {}", user.getEmail());

            return AuthResponse.success(
                    token,
                    user.getId(),
                    user.getEmail(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getRole().name()
            );

        } catch (Exception e) {
            logger.error("Error during login", e);
            return AuthResponse.error("Login failed: " + e.getMessage());
        }
    }

    public AuthResponse validateToken(String token) {
        try {
            if (jwtUtil.validateToken(token)) {
                String email = jwtUtil.getEmailFromToken(token);
                Long userId = jwtUtil.getUserIdFromToken(token);

                Optional<User> userOptional = userRepository.findById(userId);

                if (userOptional.isPresent()) {
                    User user = userOptional.get();
                    return AuthResponse.success(
                            token,
                            user.getId(),
                            user.getEmail(),
                            user.getFirstName(),
                            user.getLastName(),
                            user.getRole().name()
                    );
                }
            }
            return AuthResponse.error("Invalid token");
        } catch (Exception e) {
            logger.error("Error validating token", e);
            return AuthResponse.error("Token validation failed");
        }
    }
}