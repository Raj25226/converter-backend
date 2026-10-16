package com.converter.feature;

import com.converter.service.FeatureFlagService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class FeatureFlagWebConfig implements WebMvcConfigurer {
    private final FeatureFlagService featureFlagService;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(
                        new HandlerInterceptor() {
                            @Override
                            public boolean preHandle(
                                    HttpServletRequest request,
                                    HttpServletResponse response,
                                    Object handler) {
                                if ("OPTIONS".equals(request.getMethod())) return true;
                                String path =
                                        String.valueOf(
                                                request.getAttribute(
                                                        HandlerMapping
                                                                .BEST_MATCHING_PATTERN_ATTRIBUTE));
                                FeatureFlag flag =
                                        switch (path) {
                                            case "/api/auth/login" -> FeatureFlag.LOGIN;
                                            case "/api/auth/signup" -> FeatureFlag.SIGNUP;
                                            case "/api/auth/google" -> FeatureFlag.GOOGLE_OAUTH;
                                            case "/api/auth/resend-verification",
                                                    "/api/auth/verify-email" ->
                                                    FeatureFlag.EMAIL_VERIFICATION;
                                            case "/api/auth/forgot-password",
                                                    "/api/auth/reset-password" ->
                                                    FeatureFlag.PASSWORD_RESET;
                                            case "/api/auth/profile" -> FeatureFlag.PROFILE;
                                            case "/api/auth/change-password" ->
                                                    FeatureFlag.PASSWORD_CHANGE;
                                            case "/api/analytics/stats" ->
                                                    FeatureFlag.ANALYTICS_DASHBOARD;
                                            case "/api/analytics/track-visit",
                                                    "/api/analytics/track-conversion" ->
                                                    FeatureFlag.ANALYTICS_TRACKING;
                                            default -> null;
                                        };
                                if (flag == FeatureFlag.GOOGLE_OAUTH)
                                    featureFlagService.require(FeatureFlag.LOGIN, flag);
                                else if (flag != null) featureFlagService.require(flag);
                                if (path.startsWith("/api/download/"))
                                    featureFlagService.require(FeatureFlag.DOWNLOADS);
                                if (path.startsWith("/api/user/history")
                                        && "GET".equals(request.getMethod()))
                                    featureFlagService.require(FeatureFlag.HISTORY);
                                return true;
                            }
                        })
                .addPathPatterns("/api/**");
    }
}
