package com.converter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Logs request outcomes without recording query strings, headers, or request bodies. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class RequestLoggingFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String previous = MDC.get("requestId");
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        response.setHeader("X-Request-ID", requestId);
        long started = System.nanoTime();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            failed = true;
            log.error("Request processing failed: method={}", request.getMethod(), e);
            throw e;
        } finally {
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            // Use the matched route template so arbitrary user-controlled paths aren't logged.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            if (failed || response.getStatus() >= 500) {
                log.error(
                        "HTTP method={} route={} status={} durationMs={}",
                        request.getMethod(),
                        route,
                        response.getStatus(),
                        elapsed);
            } else if (response.getStatus() >= 400) {
                log.warn(
                        "HTTP method={} route={} status={} durationMs={}",
                        request.getMethod(),
                        route,
                        response.getStatus(),
                        elapsed);
            } else {
                log.info(
                        "HTTP method={} route={} status={} durationMs={}",
                        request.getMethod(),
                        route,
                        response.getStatus(),
                        elapsed);
            }
            if (previous == null) MDC.remove("requestId");
            else MDC.put("requestId", previous);
        }
    }
}
