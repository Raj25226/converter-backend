package com.converter.config;

import com.converter.dto.ApiResponse;
import com.converter.entity.User;
import com.converter.repository.UserRepository;
import com.converter.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${cors.allowed-origins}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        // Support comma-separated list of origins
        config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthFilter jwtAuthFilter,
                                           CorsConfigurationSource corsConfigurationSource) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // ── Public auth endpoints ─────────────────────────────
                        .requestMatchers(
                                "/api/auth/signup",
                                "/api/auth/login",
                                "/api/auth/google",
                                "/api/auth/resend-verification",
                                "/api/auth/verify-email",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password"
                        ).permitAll()
                        // ── Public conversion & download ─────────────────────
                        .requestMatchers("/api/convert/**").permitAll()
                        .requestMatchers("/api/download/**").permitAll()
                        // ── Public analytics ─────────────────────────────────
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/analytics/stats").hasRole("ADMIN")
                        .requestMatchers("/api/analytics/**").permitAll()
                        // ── Admin only ────────────────────────────────────────
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // ── Authenticated user endpoints ─────────────────────
                        .requestMatchers(
                                "/api/auth/me",
                                "/api/auth/profile",
                                "/api/auth/change-password",
                                "/api/user/**"
                        ).authenticated()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            log.warn("Unauthorized access to [{}]: {}", req.getRequestURI(), e.getMessage());
                            sendError(res, 401, "Unauthorized: " + e.getMessage());
                        })
                        .accessDeniedHandler((req, res, e) -> {
                            log.warn("Access denied to [{}]: {}", req.getRequestURI(), e.getMessage());
                            sendError(res, 403, "Forbidden: insufficient permissions");
                        })
                );

        return http.build();
    }

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SecurityConfig.class);

    private void sendError(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        new ObjectMapper().writeValue(res.getWriter(), ApiResponse.error(message));
    }

    // ─── JWT Auth Filter ─────────────────────────────────────────────────────

    @Component
    @RequiredArgsConstructor
    @Slf4j
    public static class JwtAuthFilter extends OncePerRequestFilter {

        private final JwtUtil jwtUtil;
        private final UserRepository userRepository;

        @Override
        protected void doFilterInternal(HttpServletRequest request,
                                        HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {

            String path = request.getRequestURI();
            String header = request.getHeader("Authorization");

            if (header == null || !header.startsWith("Bearer ")) {
                log.trace("No Bearer token on request [{}], continuing unauthenticated", path);
                chain.doFilter(request, response);
                return;
            }

            String token = header.substring(7).trim();
            try {
                if (jwtUtil.validateToken(token)) {
                    Long userId = jwtUtil.getUserIdFromToken(token);
                    String role;

                    Optional<User> userOpt = userRepository.findById(userId);
                    if (userOpt.isPresent() && userOpt.get().isActive()
                            && jwtUtil.getTokenVersion(token) == userOpt.get().getTokenVersion()) {
                        role = userOpt.get().getRole().name();
                        List<SimpleGrantedAuthority> authorities = List.of(
                                new SimpleGrantedAuthority("ROLE_" + role)
                        );
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(userId, null, authorities);
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        log.debug("Authenticated user id=[{}] role=[{}] on [{}]", userId, role, path);
                    } else if (userOpt.isEmpty()) {
                        log.warn("JWT references unknown userId=[{}], ignoring token", userId);
                    } else {
                        log.warn("User id=[{}] is suspended/inactive, denying JWT auth", userId);
                    }
                } else {
                    log.debug("Invalid JWT token on request [{}]", path);
                }
            } catch (Exception e) {
                log.warn("JWT processing error on [{}]: {}", path, e.getMessage());
            }

            chain.doFilter(request, response);
        }
    }
}